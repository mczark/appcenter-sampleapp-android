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

/** Spółka z uniwersum (S&P 500) wraz z klasyfikacją GICS. */
data class Company(
    val symbol: String,
    val name: String,
    val sector: String,
    val subIndustry: String,
)

enum class GroupingLevel(val label: String) {
    SECTOR("Sektor GICS (11 grup)"),
    SUB_INDUSTRY("Podbranża GICS (~120 grup)"),
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
)
