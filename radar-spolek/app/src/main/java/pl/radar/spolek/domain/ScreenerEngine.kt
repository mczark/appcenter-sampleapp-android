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
        /** Ile sesji wstecz liczymy szerokość rynku (na potrzeby diagnozy progu liczby sesji). */
        private const val BREADTH_WINDOW = 60

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
        val failed = AtomicInteger(0)
        val sem = Semaphore(concurrency)
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

        // 2. Szerokość rynku we wszystkich grupach (kryterium 1)
        progress("Analiza branż…", 0.82f)
        val groupOf: (Company) -> String = { if (cfg.grouping == GroupingLevel.SECTOR) it.sector else it.subIndustry }
        val membersByGroup = companies.groupBy(groupOf)
        val window = dates.takeLast(maxOf(cfg.breadthDays, BREADTH_WINDOW) + 1)
        val groupStats = membersByGroup.mapValues { (name, members) ->
            GroupStat(name, members.size, Analysis.breadthRatios(members.map { barsBySymbol.getValue(it.symbol) }, window))
        }
        val c1Groups = groupStats.values.filter { it.passes(cfg) }.map { it.name }.toSet()

        // 3. Trend i odbicie dla każdej spółki z aktualną sesją (kryteria 2 i 4)
        progress("Analiza trendów i formacji…", 0.86f)
        val evals = companies.mapNotNull { c ->
            val bars = barsBySymbol.getValue(c.symbol)
            if (bars.lastOrNull()?.epochDay != lastSession) null
            else StockEval(c, groupOf(c), bars, Analysis.trend(bars, cfg), Analysis.findBounce(bars, cfg))
        }
        fun c1(s: StockEval) = s.group in c1Groups
        fun c2(s: StockEval) = s.trend?.passed == true
        fun c4(s: StockEval) = s.bounce != null

        // 4. P/E (kryterium 3) – pobierane tylko dla potrzebnych branż
        val pe = HashMap<String, Double?>()
        val fetchedGroups = HashSet<String>()
        suspend fun fetchPe(groupNames: Set<String>) {
            val todo = groupNames - fetchedGroups
            if (todo.isEmpty()) return
            fetchedGroups += todo
            val symbols = todo.flatMap { membersByGroup[it].orEmpty() }.map { it.symbol }.filter { it !in pe }
            if (symbols.isEmpty()) return
            try {
                pe.putAll(data.peRatios(symbols))
            } catch (e: Exception) {
                val msg = "Nie udało się pobrać P/E: ${e.message}"
                if (msg !in warnings) warnings += msg
            }
        }
        fun medians(): Map<String, Double?> = fetchedGroups.associateWith { g ->
            Analysis.median(membersByGroup[g].orEmpty().mapNotNull { pe[it.symbol] }.filter { it > 0 })
        }

        val funnel = evals.filter { c1(it) && c2(it) && c4(it) }
        if (funnel.isNotEmpty()) {
            progress("Pobieranie wskaźników P/E…", 0.9f)
            fetchPe(funnel.map { it.group }.toSet())
        }
        var med = medians()
        var peMissing = 0
        val recs = mutableListOf<Recommendation>()
        for (s in funnel) {
            val p = pe[s.company.symbol]?.takeIf { it > 0 }
            val m = med[s.group]
            if (p == null || m == null) { peMissing++; continue }
            if (p > m * cfg.maxPeToMedian) continue
            val t = s.trend!!; val b = s.bounce!!; val bars = s.bars
            recs += Recommendation(
                symbol = s.company.symbol, name = s.company.name, group = s.group, sector = s.company.sector,
                lastClose = bars.last().close, lastDate = bars.last().epochDay,
                pe = p, groupMedianPe = m,
                trendR2 = t.r2, trendReturn = t.periodReturn,
                peakPrice = b.peakPrice, peakDate = bars[b.peakIndex].epochDay,
                retestDate = bars[b.retestIndex].epochDay, retestLow = b.retestLow,
                bouncePct = b.bouncePct,
                score = Analysis.score(t, b, bars.size),
            )
        }
        if (peMissing > 0) warnings += "Brak P/E (lub ujemne zyski) dla $peMissing kandydatów – pominięto."

        // 5. Diagnoza, gdy brak wyników
        var diagnosis = emptyList<CriterionDiagnosis>()
        if (recs.isEmpty()) {
            progress("Brak spółek – analiza progów…", 0.93f)
            // P/E potrzebne do sprawdzenia każdego progu z osobna: branże z kryterium 1 mające spółkę
            // z trendem lub odbiciem oraz branże ze spółką spełniającą jednocześnie kryteria 2 i 4.
            fetchPe(evals.filter { (c1(it) && (c2(it) || c4(it))) || (c2(it) && c4(it)) }.map { it.group }.toSet())
            med = medians()
            diagnosis = Diagnostics.compute(cfg, evals, groupStats, pe, med)
        }

        progress("Gotowe", 1f)
        ScreenResult(
            timestamp = System.currentTimeMillis(),
            lastSessionDate = lastSession,
            universeSize = universe.size,
            withData = companies.size,
            groupsChecked = groupStats.values.count { it.size >= cfg.minGroupSize },
            groupsPassed = c1Groups.map { g ->
                val st = groupStats.getValue(g)
                GroupSummary(g, st.size, st.minRatio(cfg.breadthDays) ?: 0.0)
            }.sortedByDescending { it.minRatio },
            trendPassed = evals.count { c1(it) && c2(it) },
            bouncePassed = funnel.size,
            pePassed = recs.size,
            peMissing = peMissing,
            recommendations = recs.sortedByDescending { it.score }.take(cfg.maxResults),
            warnings = warnings,
            diagnosis = diagnosis,
        )
    }
}
