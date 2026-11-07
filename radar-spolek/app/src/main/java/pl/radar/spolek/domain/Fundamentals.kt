package pl.radar.spolek.domain

/** Wskaźniki fundamentalne spółki (null = brak danych). Wartości procentowe jako ułamki (0.15 = 15%). */
data class Fundamentals(
    val symbol: String,
    val pe: Double? = null,
    val forwardPe: Double? = null,
    val peg: Double? = null,
    val priceToBook: Double? = null,
    val roe: Double? = null,
    val profitMargin: Double? = null,
    val revenueGrowth: Double? = null,
    /** Dług / kapitał własny jako krotność (1.5 = 150%). */
    val debtToEquity: Double? = null,
)

enum class MetricKind { MULTIPLE, PERCENT }

/**
 * Wskaźnik w łącznej ocenie. [higherIsBetter] – kierunek. Dla wskaźników „im niżej, tym lepiej”
 * wartości ≤ 0 (np. P/E przy stracie, ujemny kapitał) traktujemy jako najgorsze.
 */
enum class Metric(val label: String, val short: String, val higherIsBetter: Boolean, val kind: MetricKind, val get: (Fundamentals) -> Double?) {
    PE("Cena / zysk (TTM)", "P/E", false, MetricKind.MULTIPLE, { it.pe }),
    FORWARD_PE("Cena / prognozowany zysk", "Fwd P/E", false, MetricKind.MULTIPLE, { it.forwardPe }),
    PEG("P/E do tempa wzrostu zysków", "PEG", false, MetricKind.MULTIPLE, { it.peg }),
    PB("Cena / wartość księgowa", "P/B", false, MetricKind.MULTIPLE, { it.priceToBook }),
    ROE("Rentowność kapitału", "ROE", true, MetricKind.PERCENT, { it.roe }),
    MARGIN("Marża netto", "Marża", true, MetricKind.PERCENT, { it.profitMargin }),
    REVENUE_GROWTH("Wzrost przychodów r/r", "Przychody r/r", true, MetricKind.PERCENT, { it.revenueGrowth }),
    DEBT_EQUITY("Dług / kapitał własny", "D/E", false, MetricKind.MULTIPLE, { it.debtToEquity }),
}

data class ScoredStock(
    val fundamentals: Fundamentals,
    /** Łączna ocena 0–100 (średni percentyl wskaźników); null, gdy dostępnych wskaźników jest za mało. */
    val score: Double?,
    /** Percentyl każdego dostępnego wskaźnika w grupie (1.0 = najlepszy). */
    val percentiles: Map<Metric, Double>,
)

object FundamentalScore {
    const val MIN_METRICS = 3

    /**
     * Ranking względny: dla każdego wskaźnika spółki z danymi są ustawiane od najlepszej do najgorszej
     * i dostają percentyl (najlepsza 1.0, najgorsza 0.0, remisy – średnia). Ocena = średni percentyl × 100
     * z dostępnych wskaźników (min. [MIN_METRICS]). Wynik posortowany od najlepszej oceny.
     */
    fun rank(list: List<Fundamentals>): List<ScoredStock> {
        val perc = HashMap<String, MutableMap<Metric, Double>>()
        for (m in Metric.entries) {
            val withValue = list.mapNotNull { f -> m.get(f)?.takeIf { !it.isNaN() }?.let { f.symbol to it } }
            if (withValue.isEmpty()) continue
            // klucz „im mniejszy, tym lepszy”
            val keyed = withValue.map { (s, v) ->
                val k = if (m.higherIsBetter) -v else if (v <= 0) Double.MAX_VALUE else v
                s to k
            }
            val n = keyed.size
            for ((s, k) in keyed) {
                val better = keyed.count { it.second < k }
                val ties = keyed.count { it.second == k } - 1
                val p = if (n == 1) 1.0 else 1.0 - (better + ties / 2.0) / (n - 1)
                perc.getOrPut(s) { mutableMapOf() }[m] = p
            }
        }
        return list.map { f ->
            val p = perc[f.symbol].orEmpty()
            ScoredStock(f, if (p.size >= MIN_METRICS) p.values.average() * 100 else null, p)
        }.sortedWith(compareByDescending<ScoredStock> { it.score ?: -1.0 }.thenByDescending { it.percentiles.size })
    }
}
