package pl.radar.spolek.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

/** Źródło danych rynkowych – implementacja sieciowa w pakiecie data, w testach – dane syntetyczne. */
interface MarketData {
    suspend fun universe(): List<Company>
    /** Dzienne świece za ok. rok, posortowane rosnąco. */
    suspend fun bars(symbol: String): List<Bar>
    /** Wskaźnik P/E (TTM); null gdy brak lub ujemne zyski. */
    suspend fun peRatios(symbols: List<String>): Map<String, Double?>
}

class ScreenerEngine(
    private val data: MarketData,
    private val cfg: ScreenerConfig,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(NY) },
    private val concurrency: Int = 6,
) {
    companion object {
        val NY: ZoneId = ZoneId.of("America/New_York")

        /** Usuwa dzisiejszą, niedomkniętą świecę, jeśli sesja w USA jeszcze trwa. */
        fun dropIncomplete(bars: List<Bar>, nowNy: ZonedDateTime): List<Bar> {
            val last = bars.lastOrNull() ?: return bars
            val today = nowNy.toLocalDate().toEpochDay()
            return if (last.epochDay == today && nowNy.toLocalTime() < LocalTime.of(16, 15)) bars.dropLast(1) else bars
        }
    }

    suspend fun screen(progress: suspend (String, Float) -> Unit = { _, _ -> }): ScreenResult = coroutineScope {
        val warnings = mutableListOf<String>()
        progress("Pobieranie składu indeksu S&P 500…", 0f)
        val universe = data.universe()
        val nowNy = now()

        // 1. Notowania wszystkich spółek
        val done = AtomicInteger(0)
        val sem = Semaphore(concurrency)
        val failed = AtomicInteger(0)
        val barsBySymbol: Map<String, List<Bar>> = universe.map { c ->
            async {
                sem.withPermit {
                    val bars = try {
                        dropIncomplete(data.bars(c.symbol), nowNy)
                    } catch (e: Exception) {
                        failed.incrementAndGet(); emptyList()
                    }
                    val d = done.incrementAndGet()
                    if (d % 10 == 0 || d == universe.size) {
                        progress("Notowania: $d / ${universe.size}", 0.05f + 0.75f * d / universe.size)
                    }
                    c.symbol to bars
                }
            }
        }.awaitAll().filter { it.second.isNotEmpty() }.toMap()
        if (failed.get() > 0) warnings += "Nie pobrano notowań dla ${failed.get()} spółek."

        val companies = universe.filter { it.symbol in barsBySymbol }
        val dates = Analysis.marketDates(barsBySymbol.values)
        val lastSession = dates.lastOrNull() ?: 0L

        // 2. Kryterium 1 – szerokość rynku w grupie
        progress("Analiza branż…", 0.82f)
        val groupOf: (Company) -> String = { if (cfg.grouping == GroupingLevel.SECTOR) it.sector else it.subIndustry }
        val groups = companies.groupBy(groupOf).filterValues { it.size >= cfg.minGroupSize }
        val breadthDates = dates.takeLast(cfg.breadthDays + 1)
        val passedGroups = groups.mapNotNull { (name, members) ->
            val b = Analysis.breadth(members.map { barsBySymbol.getValue(it.symbol) }, breadthDates, cfg)
            if (b.passed) GroupSummary(name, members.size, b.dailyRatios.min()) to members else null
        }

        // 3. Kryteria 2 i 4 – trend i odbicie
        progress("Analiza trendów i formacji…", 0.86f)
        data class Candidate(val c: Company, val group: String, val bars: List<Bar>, val trend: TrendResult, val bounce: BounceSignal)
        var trendPassed = 0
        val candidates = mutableListOf<Candidate>()
        for ((summary, members) in passedGroups) {
            for (c in members) {
                val bars = barsBySymbol.getValue(c.symbol)
                if (bars.lastOrNull()?.epochDay != lastSession) continue
                val t = Analysis.trend(bars, cfg) ?: continue
                if (!t.passed) continue
                trendPassed++
                val b = Analysis.findBounce(bars, cfg) ?: continue
                candidates += Candidate(c, summary.name, bars, t, b)
            }
        }

        // 4. Kryterium 3 – P/E vs mediana branży (pobieramy P/E tylko dla grup, które przeszły kryterium 1)
        var pePassed = 0
        var peMissing = 0
        val recs = mutableListOf<Recommendation>()
        if (candidates.isNotEmpty()) {
            progress("Pobieranie wskaźników P/E…", 0.9f)
            val neededGroups = candidates.map { it.group }.toSet()
            val peerSymbols = passedGroups.filter { it.first.name in neededGroups }
                .flatMap { it.second }.map { it.symbol }
            val pe = try {
                data.peRatios(peerSymbols)
            } catch (e: Exception) {
                warnings += "Nie udało się pobrać P/E: ${e.message}"; emptyMap()
            }
            val medians = passedGroups.filter { it.first.name in neededGroups }.associate { (s, members) ->
                s.name to Analysis.median(members.mapNotNull { pe[it.symbol] }.filter { it > 0 })
            }
            for (cand in candidates) {
                val p = pe[cand.c.symbol]?.takeIf { it > 0 }
                val med = medians[cand.group]
                if (p == null || med == null) { peMissing++; continue }
                if (p > med) continue
                pePassed++
                val bars = cand.bars
                recs += Recommendation(
                    symbol = cand.c.symbol, name = cand.c.name, group = cand.group, sector = cand.c.sector,
                    lastClose = bars.last().close, lastDate = bars.last().epochDay,
                    pe = p, groupMedianPe = med,
                    trendR2 = cand.trend.r2, trendReturn = cand.trend.periodReturn,
                    peakPrice = cand.bounce.peakPrice, peakDate = bars[cand.bounce.peakIndex].epochDay,
                    retestDate = bars[cand.bounce.retestIndex].epochDay, retestLow = cand.bounce.retestLow,
                    bouncePct = cand.bounce.bouncePct,
                    score = Analysis.score(cand.trend, cand.bounce, bars.size),
                )
            }
            if (peMissing > 0) warnings += "Brak P/E (lub ujemne zyski) dla $peMissing kandydatów – pominięto."
        }

        progress("Gotowe", 1f)
        ScreenResult(
            timestamp = System.currentTimeMillis(),
            lastSessionDate = lastSession,
            universeSize = universe.size,
            withData = companies.size,
            groupsChecked = groups.size,
            groupsPassed = passedGroups.map { it.first }.sortedByDescending { it.minRatio },
            trendPassed = trendPassed,
            bouncePassed = candidates.size,
            pePassed = pePassed,
            peMissing = peMissing,
            recommendations = recs.sortedByDescending { it.score }.take(cfg.maxResults),
            warnings = warnings,
        )
    }
}
