package pl.radar.spolek.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** W którą stronę trzeba przesunąć próg, żeby go złagodzić. */
enum class Direction { LOWER, RAISE, NEAREST }

enum class HintUnit { PERCENT, DECIMAL, COUNT }

/**
 * Próg jednego parametru. [required] to wartość, przy której (przy niezmienionych pozostałych parametrach)
 * pojawiłaby się co najmniej jedna rekomendacja; null = sama zmiana tego parametru nie wystarczy.
 * [aloneRequired] to wartość, przy której to kryterium spełniłaby choć jedna spółka, bez względu na
 * pozostałe kryteria (liczona tylko, gdy kryterium nie spełnia żadna spółka).
 */
data class ThresholdHint(
    val key: String,
    val label: String,
    val unit: HintUnit,
    val direction: Direction,
    val current: Double,
    val required: Double?,
    val example: String?,
    val aloneRequired: Double? = null,
    val aloneExample: String? = null,
) {
    /** Wartość zaokrąglona tak, by po wpisaniu jej w ustawienia warunek na pewno był spełniony. */
    fun rounded(v: Double): Double {
        val scale = when (unit) { HintUnit.PERCENT -> 1000.0; HintUnit.DECIMAL -> 100.0; HintUnit.COUNT -> 1.0 }
        return when (direction) {
            Direction.LOWER -> floor(v * scale + 1e-6) / scale
            Direction.RAISE -> ceil(v * scale - 1e-6) / scale
            Direction.NEAREST -> Math.round(v * scale) / scale
        }
    }
}

data class CriterionDiagnosis(
    val number: Int,
    val title: String,
    /** Liczba spółek spełniających to kryterium niezależnie od pozostałych. */
    val passedAlone: Int,
    val outOf: Int,
    /** Liczba spółek, które spełniają trzy pozostałe kryteria i odpadają tylko na tym. */
    val blockedOnlyByThis: Int,
    val hints: List<ThresholdHint>,
    val note: String? = null,
)

/** Wynik oceny pojedynczej spółki (z aktualną sesją). */
class StockEval(
    val company: Company,
    val group: String,
    val bars: List<Bar>,
    val trend: TrendResult?,
    val bounce: BounceSignal?,
)

/** Statystyki szerokości rynku w grupie: odsetki rosnących spółek w kolejnych sesjach (od najstarszej). */
class GroupStat(val name: String, val size: Int, val ratios: List<Double?>) {
    fun minRatio(days: Int): Double? {
        if (ratios.size < days) return null
        val last = ratios.takeLast(days)
        if (last.any { it == null }) return null
        return last.minOf { it!! }
    }

    /** Liczba ostatnich kolejnych sesji, w których odsetek rosnących ≥ próg. */
    fun streak(threshold: Double): Int {
        var n = 0
        for (i in ratios.indices.reversed()) {
            val r = ratios[i] ?: break
            if (r < threshold) break
            n++
        }
        return n
    }

    fun passes(cfg: ScreenerConfig) = size >= cfg.minGroupSize && streak(cfg.breadthThreshold) >= cfg.breadthDays
}

/**
 * Diagnoza „co by było, gdyby”: dla każdego progu osobno szukamy najmniejszej zmiany, przy której choć jedna
 * spółka spełniłaby wszystkie cztery kryteria, przy niezmienionych pozostałych parametrach.
 */
object Diagnostics {

    fun compute(
        cfg: ScreenerConfig,
        evals: List<StockEval>,
        groups: Map<String, GroupStat>,
        pe: Map<String, Double?>,
        medians: Map<String, Double?>,
    ): List<CriterionDiagnosis> {
        fun peOf(s: StockEval) = pe[s.company.symbol]?.takeIf { it > 0 }
        fun medOf(s: StockEval) = medians[s.group]
        fun peKnown(s: StockEval) = peOf(s) != null && medOf(s) != null
        fun c1(s: StockEval) = groups[s.group]?.passes(cfg) == true
        fun c2(s: StockEval) = s.trend?.passed == true
        fun c3(s: StockEval) = peKnown(s) && peOf(s)!! <= medOf(s)!! * cfg.maxPeToMedian
        fun c4(s: StockEval) = s.bounce != null

        val r1 = evals.filter { !c1(it) && c2(it) && c3(it) && c4(it) }
        val r2 = evals.filter { c1(it) && !c2(it) && c3(it) && c4(it) }
        val r3 = evals.filter { c1(it) && c2(it) && !c3(it) && c4(it) && peKnown(it) }
        val r4 = evals.filter { c1(it) && c2(it) && c3(it) && !c4(it) }
        val n1 = evals.count { c1(it) }
        val n2 = evals.count { c2(it) }
        val n3 = evals.count { c3(it) }
        val n4 = evals.count { c4(it) }
        val peKnownCount = evals.count { peKnown(it) }

        fun hint(
            key: String, label: String, unit: HintUnit, dir: Direction, current: Double,
            rescue: List<StockEval>, alone: List<StockEval>?, req: (StockEval) -> Double?,
        ): ThresholdHint {
            fun best(set: List<StockEval>): Pair<Double, String>? {
                val vals = set.mapNotNull { s -> req(s)?.let { it to s.company.symbol } }
                return when (dir) {
                    Direction.LOWER -> vals.maxByOrNull { it.first }
                    Direction.RAISE -> vals.minByOrNull { it.first }
                    Direction.NEAREST -> vals.minByOrNull { abs(it.first - current) }
                }
            }
            val b = best(rescue)
            val a = alone?.let { best(it) }
            return ThresholdHint(key, label, unit, dir, current, b?.first, b?.second, a?.first, a?.second)
        }

        // ---------- Kryterium 1: branża ----------
        val g = { s: StockEval -> groups[s.group] }
        val a1 = if (n1 == 0) evals else null
        val hints1 = listOf(
            hint("breadthThreshold", "Min. % rosnących spółek każdego dnia", HintUnit.PERCENT, Direction.LOWER,
                cfg.breadthThreshold, r1, a1) { s ->
                g(s)?.takeIf { it.size >= cfg.minGroupSize }?.minRatio(cfg.breadthDays)
            },
            hint("breadthDays", "Liczba ostatnich sesji", HintUnit.COUNT, Direction.LOWER,
                cfg.breadthDays.toDouble(), r1, a1) { s ->
                g(s)?.takeIf { it.size >= cfg.minGroupSize }?.streak(cfg.breadthThreshold)?.takeIf { it >= 1 }?.toDouble()
            },
            hint("minGroupSize", "Min. liczba spółek w branży", HintUnit.COUNT, Direction.LOWER,
                cfg.minGroupSize.toDouble(), r1, a1) { s ->
                g(s)?.takeIf { it.size < cfg.minGroupSize && it.streak(cfg.breadthThreshold) >= cfg.breadthDays }
                    ?.size?.toDouble()
            },
        )

        // ---------- Kryterium 2: trend ----------
        val a2 = if (n2 == 0) evals else null
        fun baseOk(t: TrendResult) = t.annualizedSlope > 0 && t.higherHigh && t.higherLow
        val hints2 = listOf(
            hint("minTrendR2", "Min. R² regresji", HintUnit.DECIMAL, Direction.LOWER, cfg.minTrendR2, r2, a2) { s ->
                s.trend?.takeIf { baseOk(it) && it.periodReturn >= cfg.minTrendReturn && it.r2 < cfg.minTrendR2 }?.r2
            },
            hint("minTrendReturn", "Min. wzrost w okresie", HintUnit.PERCENT, Direction.LOWER, cfg.minTrendReturn, r2, a2) { s ->
                s.trend?.takeIf { baseOk(it) && it.r2 >= cfg.minTrendR2 && it.periodReturn < cfg.minTrendReturn }?.periodReturn
            },
            hint("trendSessions", "Długość trendu (sesje)", HintUnit.COUNT, Direction.NEAREST,
                cfg.trendSessions.toDouble(), r2, a2) { s -> nearestTrendLength(s.bars, cfg)?.toDouble() },
        )

        // ---------- Kryterium 3: P/E ----------
        val a3 = if (n3 == 0) evals.filter { peKnown(it) } else null
        val hints3 = listOf(
            hint("maxPeToMedian", "Maks. P/E jako % mediany branży", HintUnit.PERCENT, Direction.RAISE,
                cfg.maxPeToMedian, r3, a3) { s -> if (peKnown(s)) peOf(s)!! / medOf(s)!! else null },
        )
        val missingPe = evals.count { c1(it) && c2(it) && c4(it) && !peKnown(it) }

        // ---------- Kryterium 4: odbicie (przeszukiwanie siatki wartości) ----------
        val a4 = if (n4 == 0) evals else null
        fun grid(key: String, label: String, unit: HintUnit, dir: Direction, current: Double,
                 values: List<Double>, apply: (ScreenerConfig, Double) -> ScreenerConfig) =
            hint(key, label, unit, dir, current, r4, a4) { s ->
                values.firstOrNull { v -> Analysis.findBounce(s.bars, apply(cfg, v)) != null }
            }
        fun down(cur: Double, step: Double) = generateSequence(cur - step) { it - step }.takeWhile { it > -1e-9 }
            .map { maxOf(0.0, it) }.toList()
        fun up(cur: Double, step: Double, max: Double) = generateSequence(cur + step) { it + step }.takeWhile { it <= max + 1e-9 }.toList()
        val hints4 = listOf(
            grid("peakLookback", "Szczyt nie starszy niż (sesje)", HintUnit.COUNT, Direction.RAISE, cfg.peakLookback.toDouble(),
                ((cfg.peakLookback + 1)..120).map { it.toDouble() }) { c, v -> c.copy(peakLookback = v.toInt()) },
            grid("pivotStrength", "Siła szczytu (sesje z każdej strony)", HintUnit.COUNT, Direction.LOWER, cfg.pivotStrength.toDouble(),
                ((cfg.pivotStrength - 1) downTo 1).map { it.toDouble() }) { c, v -> c.copy(pivotStrength = v.toInt()) },
            grid("minPeakProminence", "Min. cofnięcie po szczycie", HintUnit.PERCENT, Direction.LOWER, cfg.minPeakProminence,
                down(cfg.minPeakProminence, 0.001)) { c, v -> c.copy(minPeakProminence = v) },
            grid("retestTolerance", "Tolerancja testu poziomu szczytu", HintUnit.PERCENT, Direction.RAISE, cfg.retestTolerance,
                up(cfg.retestTolerance, 0.001, 0.15)) { c, v -> c.copy(retestTolerance = v) },
            grid("minCorrection", "Min. głębokość korekty", HintUnit.PERCENT, Direction.LOWER, cfg.minCorrection,
                down(cfg.minCorrection, 0.001)) { c, v -> c.copy(minCorrection = v) },
            grid("minBounce", "Min. odbicie od dołka", HintUnit.PERCENT, Direction.LOWER, cfg.minBounce,
                down(cfg.minBounce, 0.001)) { c, v -> c.copy(minBounce = v) },
            grid("maxDaysSinceRetest", "Dołek korekty nie starszy niż (sesje)", HintUnit.COUNT, Direction.RAISE,
                cfg.maxDaysSinceRetest.toDouble(), ((cfg.maxDaysSinceRetest + 1)..60).map { it.toDouble() }) { c, v ->
                c.copy(maxDaysSinceRetest = v.toInt())
            },
        )

        val total = evals.size
        return listOf(
            CriterionDiagnosis(1, "Branża w trendzie wzrostowym (${cfg.breadthDays} sesji)", n1, total, r1.size, hints1),
            CriterionDiagnosis(2, "Spółka w trendzie wzrostowym (${cfg.trendSessions} sesji)", n2, total, r2.size, hints2),
            CriterionDiagnosis(3, "P/E nie wyższe niż mediana branży", n3, peKnownCount, r3.size, hints3,
                note = if (missingPe > 0) "Brak P/E dla $missingPe spółek spełniających pozostałe kryteria." else null),
            CriterionDiagnosis(4, "Odbicie po teście ostatniego szczytu", n4, total, r4.size, hints4),
        )
    }

    /** Najbliższa obecnej długość okresu trendu (40–240 sesji), przy której trend byłby spełniony. */
    fun nearestTrendLength(bars: List<Bar>, cfg: ScreenerConfig): Int? {
        if (Analysis.trend(bars, cfg)?.passed == true) return null
        val maxLen = minOf(240, bars.size)
        for (d in 1..240) {
            for (len in intArrayOf(cfg.trendSessions - d, cfg.trendSessions + d)) {
                if (len < 40 || len > maxLen) continue
                if (Analysis.trend(bars, cfg.copy(trendSessions = len))?.passed == true) return len
            }
        }
        return null
    }
}

/** Ustawia jeden parametr konfiguracji po kluczu (używane przez przycisk „Ustaw”). */
fun ScreenerConfig.withParam(key: String, v: Double): ScreenerConfig = when (key) {
    "breadthThreshold" -> copy(breadthThreshold = v)
    "breadthDays" -> copy(breadthDays = v.toInt())
    "minGroupSize" -> copy(minGroupSize = v.toInt())
    "minTrendR2" -> copy(minTrendR2 = v)
    "minTrendReturn" -> copy(minTrendReturn = v)
    "trendSessions" -> copy(trendSessions = v.toInt())
    "maxPeToMedian" -> copy(maxPeToMedian = v)
    "peakLookback" -> copy(peakLookback = v.toInt())
    "pivotStrength" -> copy(pivotStrength = v.toInt())
    "minPeakProminence" -> copy(minPeakProminence = v)
    "retestTolerance" -> copy(retestTolerance = v)
    "minCorrection" -> copy(minCorrection = v)
    "minBounce" -> copy(minBounce = v)
    "maxDaysSinceRetest" -> copy(maxDaysSinceRetest = v.toInt())
    else -> this
}
