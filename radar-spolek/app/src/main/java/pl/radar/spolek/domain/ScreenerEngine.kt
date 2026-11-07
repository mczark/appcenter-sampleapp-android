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

    /**
     * Zamknięcia (ok. rok) dla wielu spółek naraz – etap 1 skanu. Świece mogą mieć open = high = low = close.
     * Domyślnie pobiera pełne świece po kolei.
     */
    suspend fun closes(symbols: List<String>, onProgress: suspend (Int, Int) -> Unit): Map<String, List<Bar>> {
        val out = LinkedHashMap<String, List<Bar>>()
        symbols.forEachIndexed { i, s ->
            runCatching { bars(s) }.getOrNull()?.let { out[s] = it }
            onProgress(i + 1, symbols.size)
        }
        return out
    }

    /** Pełne świece OHLC z ostatnich miesięcy – etap 2 skanu (formacja odbicia). */
    suspend fun ohlc(symbol: String): List<Bar> = bars(symbol)
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
        progress("Pobieranie listy spółek…", 0f)
        val universe = data.universe()
        val nowNy = now()

        // 1. Zamknięcia wszystkich spółek (hurtowo)
        progress("Notowania: 0 / ${universe.size}", 0.03f)
        val raw = data.closes(universe.map { it.symbol }) { d, total ->
            progress("Notowania: $d / $total", 0.03f + 0.6f * d / maxOf(1, total))
        }
        val barsBySymbol: Map<String, List<Bar>> = raw.mapValues { dropIncomplete(it.value, nowNy) }.filterValues { it.isNotEmpty() }
        val failed = universe.size - barsBySymbol.size
        if (failed > 0) warnings += "Brak notowań dla $failed spółek."

        val companies = universe.filter { it.symbol in barsBySymbol }
        val dates = Analysis.marketDates(barsBySymbol.values)
        val lastSession = dates.lastOrNull() ?: 0L

        // Trendomierz: wzrosty vs spadki wszystkich skanowanych spółek z ostatnich 2 miesięcy
        val meterFrom = java.time.LocalDate.ofEpochDay(lastSession).minusMonths(2).toEpochDay()
        val meterDates = dates.filter { it >= meterFrom }.let { d ->
            // jedna sesja wcześniej jako punkt odniesienia dla pierwszego dnia
            val prev = dates.lastOrNull { it < meterFrom }
            if (prev != null) listOf(prev) + d else d
        }
        val trendMeter = Analysis.marketBreadth(barsBySymbol.values, meterDates)
        fun groupMeters(key: (Company) -> String): List<GroupMeter> =
            companies.groupBy(key).filterKeys { it.isNotBlank() }.map { (name, members) ->
                GroupMeter(name, members.size, Analysis.marketBreadth(members.map { barsBySymbol.getValue(it.symbol) }, meterDates))
            }.sortedBy { it.name }
        val industryMeters = groupMeters { it.subIndustry }
        val sectorMeters = groupMeters { it.sector }

        // 2. Szerokość rynku we wszystkich grupach (kryterium 1)
        progress("Analiza branż…", 0.64f)
        val groupOf: (Company) -> String = { if (cfg.grouping == GroupingLevel.SECTOR) it.sector else it.subIndustry }
        val membersByGroup = companies.groupBy(groupOf)
        val window = dates.takeLast(maxOf(cfg.breadthDays, BREADTH_WINDOW) + 1)
        val groupStats = membersByGroup.mapValues { (name, members) ->
            GroupStat(name, members.size, Analysis.breadthRatios(members.map { barsBySymbol.getValue(it.symbol) }, window))
        }
        val c1Groups = groupStats.values.filter { it.passes(cfg) }.map { it.name }.toSet()

        // 3. Trend dla każdej spółki z aktualną sesją (kryterium 2)
        progress("Analiza trendów…", 0.66f)
        val trendEvals = companies.mapNotNull { c ->
            val bars = barsBySymbol.getValue(c.symbol)
            if (bars.lastOrNull()?.epochDay != lastSession) null
            else StockEval(c, groupOf(c), bars, Analysis.trend(bars, cfg), ohlc = false)
        }

        // 4. Pełne świece i formacja odbicia (kryterium 4) – tylko dla spółek z branż w trendzie lub w trendzie wzrostowym
        val needOhlc = trendEvals.filter { it.group in c1Groups || it.trend?.passed == true }
        val ohlcDone = AtomicInteger(0)
        val sem = Semaphore(concurrency)
        val ohlcBySymbol: Map<String, List<Bar>> = needOhlc.map { e ->
            async {
                sem.withPermit {
                    val b = try { dropIncomplete(data.ohlc(e.company.symbol), nowNy) } catch (ex: Exception) { emptyList() }
                    val d = ohlcDone.incrementAndGet()
                    if (d % 10 == 0 || d == needOhlc.size) {
                        progress("Świece kandydatów: $d / ${needOhlc.size}", 0.66f + 0.22f * d / needOhlc.size)
                    }
                    e.company.symbol to b
                }
            }
        }.awaitAll().filter { it.second.isNotEmpty() }.toMap()
        val evals = trendEvals.map { e ->
            val o = ohlcBySymbol[e.company.symbol]
            if (o == null || o.last().epochDay != lastSession) e
            else StockEval(e.company, e.group, e.trendBars, e.trend, o, Analysis.findBounce(o, cfg), ohlc = true)
        }
        val ohlcMissing = needOhlc.size - evals.count { it.ohlc }
        if (ohlcMissing > 0) warnings += "Nie pobrano pełnych świec dla $ohlcMissing kandydatów."

        fun c1(s: StockEval) = s.group in c1Groups
        fun c2(s: StockEval) = s.trend?.passed == true
        fun c4(s: StockEval) = s.bounce != null

        // 5. P/E (kryterium 3) – pobierane tylko dla potrzebnych branż
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

        // 6. Diagnoza, gdy brak wyników
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
            trendMeter = trendMeter,
            industryMeters = industryMeters,
            sectorMeters = sectorMeters,
        )
    }
}
