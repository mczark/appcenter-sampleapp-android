package pl.radar.spolek.domain

/** Jedna świeca dzienna. [epochDay] = liczba dni od 1970-01-01 (data sesji w Nowym Jorku). */
data class Bar(
    val epochDay: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)

/**
 * Spółka z uniwersum wraz z klasyfikacją: dla S&P 500 – sektor i podbranża GICS,
 * dla całego rynku USA – sektor i branża według Nasdaq.
 */
data class Company(
    val symbol: String,
    val name: String,
    val sector: String,
    val subIndustry: String,
)

enum class GroupingLevel(val label: String) {
    SECTOR("Sektor (11–12 grup)"),
    SUB_INDUSTRY("Branża (~150 grup; dla S&P 500 podbranża GICS)"),
}

/**
 * Wszystkie progi kryteriów. Wartości domyślne to sformalizowana wersja kryteriów:
 *  1. branża w trendzie wzrostowym od 10 dni – w każdej z 10 ostatnich sesji ≥50% spółek branży zamknęło się wyżej,
 *  2. spółka w jednoznacznym trendzie wzrostowym od pół roku (126 sesji),
 *  3. P/E ≤ mediana P/E branży,
 *  4. odbicie po korekcie do poziomu ostatniego wyraźnego szczytu z ostatniego miesiąca.
 */
data class ScreenerConfig(
    val grouping: GroupingLevel = GroupingLevel.SUB_INDUSTRY,
    val minGroupSize: Int = 3,
    // Kryterium 1
    val breadthDays: Int = 10,
    val breadthThreshold: Double = 0.5,
    // Kryterium 2
    val trendSessions: Int = 126,
    val minTrendR2: Double = 0.70,
    val minTrendReturn: Double = 0.10,
    // Kryterium 3: P/E ≤ maxPeToMedian × mediana branży (1.0 = dokładnie mediana)
    val maxPeToMedian: Double = 1.0,
    // Kryterium 4
    val peakLookback: Int = 22,
    val pivotStrength: Int = 3,
    val minPeakProminence: Double = 0.03,
    val retestTolerance: Double = 0.025,
    val minCorrection: Double = 0.02,
    val minBounce: Double = 0.015,
    val maxDaysSinceRetest: Int = 7,
    // Wynik
    val maxResults: Int = 10,
)

data class TrendResult(
    val passed: Boolean,
    val r2: Double,
    /** Nachylenie regresji log(cena) przeliczone na roczną stopę wzrostu. */
    val annualizedSlope: Double,
    val periodReturn: Double,
    val higherHigh: Boolean,
    val higherLow: Boolean,
)

data class BounceSignal(
    val peakIndex: Int,
    val peakPrice: Double,
    val breakoutIndex: Int,
    val topIndex: Int,
    val retestIndex: Int,
    val retestLow: Double,
    val bouncePct: Double,
)

data class BreadthResult(
    val passed: Boolean,
    /** Odsetek rosnących spółek w kolejnych sesjach (od najstarszej). */
    val dailyRatios: List<Double>,
)

data class Recommendation(
    val symbol: String,
    val name: String,
    val group: String,
    val sector: String,
    val lastClose: Double,
    val lastDate: Long,
    val pe: Double,
    val groupMedianPe: Double,
    val trendR2: Double,
    val trendReturn: Double,
    val peakPrice: Double,
    val peakDate: Long,
    val retestDate: Long,
    val retestLow: Double,
    val bouncePct: Double,
    val score: Double,
)

/** Trendomierz – jedna sesja: ile spółek wzrosło, spadło i się nie zmieniło względem poprzedniego zamknięcia. */
data class MarketBreadthDay(
    val epochDay: Long,
    val advancers: Int,
    val decliners: Int,
    val unchanged: Int,
) {
    val net: Int get() = advancers - decliners
}

/** Spółka w wyraźnym trendzie wzrostowym (kryterium 2) – do listy w szczegółach branży. */
data class TrendStock(
    val symbol: String,
    val name: String,
    val industry: String,
    val sector: String,
    val lastClose: Double,
    val r2: Double,
    val periodReturn: Double,
)

/** Trendomierz jednej grupy (branży lub sektora). */
data class GroupMeter(
    val name: String,
    val size: Int,
    val days: List<MarketBreadthDay>,
)

data class GroupSummary(
    val name: String,
    val size: Int,
    val minRatio: Double,
)

data class ScreenResult(
    val timestamp: Long,
    val lastSessionDate: Long,
    val universeSize: Int,
    val withData: Int,
    val groupsChecked: Int,
    val groupsPassed: List<GroupSummary>,
    val trendPassed: Int,
    val bouncePassed: Int,
    val pePassed: Int,
    val peMissing: Int,
    val recommendations: List<Recommendation>,
    val warnings: List<String>,
    /** Diagnoza kryteriów – liczona, gdy brak rekomendacji. */
    val diagnosis: List<CriterionDiagnosis> = emptyList(),
    /** Trendomierz rynku za ostatnie 2 miesiące (od najstarszej sesji). */
    val trendMeter: List<MarketBreadthDay> = emptyList(),
    /** Trendomierze branż i sektorów (te same sesje co [trendMeter]). */
    val industryMeters: List<GroupMeter> = emptyList(),
    val sectorMeters: List<GroupMeter> = emptyList(),
    /** Spółki w wyraźnym trendzie wzrostowym od pół roku (kryterium 2), z całego skanu. */
    val uptrendStocks: List<TrendStock> = emptyList(),
)
