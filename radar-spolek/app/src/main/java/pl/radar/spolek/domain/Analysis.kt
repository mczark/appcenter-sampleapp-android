package pl.radar.spolek.domain

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** Czyste funkcje analityczne – bez zależności od Androida, testowane jednostkowo. */
object Analysis {

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }

    fun sma(values: List<Double>, period: Int): List<Double?> {
        val out = ArrayList<Double?>(values.size)
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= period) sum -= values[i - period]
            out.add(if (i >= period - 1) sum / period else null)
        }
        return out
    }

    data class Regression(val slope: Double, val intercept: Double, val r2: Double)

    /** Regresja liniowa ln(cena) względem numeru sesji. */
    fun logRegression(closes: List<Double>): Regression {
        val n = closes.size
        require(n >= 2)
        val ys = closes.map { ln(it) }
        val mx = (n - 1) / 2.0
        val my = ys.average()
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (i in 0 until n) {
            val dx = i - mx; val dy = ys[i] - my
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        }
        val slope = sxy / sxx
        val r2 = if (syy == 0.0) 0.0 else (sxy * sxy) / (sxx * syy)
        return Regression(slope, my - slope * mx, r2)
    }

    /**
     * Kryterium 2 – „jednoznaczny trend wzrostowy od pół roku”. Wszystkie warunki muszą być spełnione:
     *  • nachylenie regresji ln(ceny) z ostatnich N sesji > 0 i R² ≥ minTrendR2 (wzrost regularny, nie skokowy),
     *  • zmiana ceny w okresie ≥ minTrendReturn,
     *  • wyższe szczyty i wyższe dołki: max i min zamknięć z drugiej połowy okresu > odpowiednio z pierwszej.
     */
    fun trend(bars: List<Bar>, cfg: ScreenerConfig): TrendResult? {
        if (bars.size < cfg.trendSessions) return null
        val closes = bars.takeLast(cfg.trendSessions).map { it.close }
        if (closes.any { it <= 0.0 }) return null
        val reg = logRegression(closes)
        val ret = closes.last() / closes.first() - 1.0
        val half = closes.size / 2
        val first = closes.subList(0, half)
        val second = closes.subList(half, closes.size)
        val hh = second.max() > first.max()
        val hl = second.min() > first.min()
        val passed = reg.slope > 0 && reg.r2 >= cfg.minTrendR2 && ret >= cfg.minTrendReturn && hh && hl
        return TrendResult(passed, reg.r2, exp(reg.slope * 252) - 1.0, ret, hh, hl)
    }

    private fun isPivotHigh(bars: List<Bar>, i: Int, k: Int): Boolean {
        val h = bars[i].high
        for (j in i - k until i) if (bars[j].high >= h) return false
        for (j in i + 1..i + k) if (bars[j].high > h) return false
        return true
    }

    /**
     * Kryterium 4 – odbicie po korekcie do poziomu ostatniego wyraźnego szczytu z ostatniego miesiąca.
     * Szukamy (od najnowszego) szczytu P w ostatnich [ScreenerConfig.peakLookback] sesjach, dla którego:
     *  1. P jest lokalnym maksimum (najwyższy high w oknie ±pivotStrength sesji),
     *  2. P jest „wyraźny”: przed wybiciem cena cofnęła się od niego o ≥ minPeakProminence,
     *  3. cena wybiła się ponad P (zamknięcie powyżej high P) i zrobiła nowy szczyt T,
     *  4. od T nastąpiła korekta (≥ minCorrection), której dołek R leży w strefie P ± retestTolerance,
     *     a żadne zamknięcie po T nie spadło poniżej dolnej granicy strefy (poziom obroniony),
     *  5. po R nastąpiło odbicie: ostatnie zamknięcie ≥ low(R)·(1+minBounce) i > zamknięcie z dnia R,
     *     a R wypadło nie dawniej niż maxDaysSinceRetest sesji temu.
     */
    fun findBounce(bars: List<Bar>, cfg: ScreenerConfig): BounceSignal? {
        val n = bars.size
        val k = cfg.pivotStrength
        val last = n - 1
        if (n < k * 2 + 5) return null
        val start = max(k, n - cfg.peakLookback)
        for (i in (last - k) downTo start) {
            if (!isPivotHigh(bars, i, k)) continue
            val ph = bars[i].high
            val j = (i + 1..last).firstOrNull { bars[it].close > ph } ?: continue
            if (j == i + 1) continue
            val dip = (i + 1 until j).minOf { bars[it].low }
            if (dip > ph * (1 - cfg.minPeakProminence)) continue
            val t = (j..last).maxBy { bars[it].high }
            if (t >= last - 1) continue
            val r = (t + 1..last).minBy { bars[it].low }
            if (r == last) continue
            val rLow = bars[r].low
            val lower = ph * (1 - cfg.retestTolerance)
            val upper = ph * (1 + cfg.retestTolerance)
            if (rLow < lower || rLow > upper) continue
            if ((t + 1..last).any { bars[it].close < lower }) continue
            if ((bars[t].high - rLow) / bars[t].high < cfg.minCorrection) continue
            if (last - r > cfg.maxDaysSinceRetest) continue
            val lc = bars[last].close
            if (lc < rLow * (1 + cfg.minBounce) || lc <= bars[r].close) continue
            return BounceSignal(i, ph, j, t, r, rLow, lc / rLow - 1.0)
        }
        return null
    }

    /**
     * Wspólny kalendarz sesji: daty, w których notowania ma co najmniej połowa spółek
     * (odporne na pojedyncze braki danych). Zwraca posortowane rosnąco epochDay.
     */
    fun marketDates(series: Collection<List<Bar>>): List<Long> {
        val counts = HashMap<Long, Int>()
        for (s in series) for (b in s) counts.merge(b.epochDay, 1, Int::plus)
        val maxCount = counts.values.maxOrNull() ?: return emptyList()
        return counts.filterValues { it * 2 >= maxCount }.keys.sorted()
    }

    /**
     * Kryterium 1 – w każdej z ostatnich [ScreenerConfig.breadthDays] sesji co najmniej
     * [ScreenerConfig.breadthThreshold] spółek z grupy zamknęło się wyżej niż sesję wcześniej.
     * [dates] – ostatnie breadthDays+1 dat kalendarza rynku.
     */
    fun breadth(members: List<List<Bar>>, dates: List<Long>, cfg: ScreenerConfig): BreadthResult {
        val maps = members.map { s -> s.associate { it.epochDay to it.close } }
        val ratios = ArrayList<Double>()
        var ok = dates.size == cfg.breadthDays + 1
        for (d in 1 until dates.size) {
            var up = 0; var total = 0
            for (m in maps) {
                val c = m[dates[d]] ?: continue
                val p = m[dates[d - 1]] ?: continue
                total++
                if (c > p) up++
            }
            val ratio = if (total == 0) 0.0 else up.toDouble() / total
            ratios.add(ratio)
            if (total == 0 || ratio < cfg.breadthThreshold) ok = false
        }
        return BreadthResult(ok, ratios)
    }

    /** Ranking: siła i regularność trendu, premia za świeże odbicie. */
    fun score(trend: TrendResult, bounce: BounceSignal, barsCount: Int): Double {
        val freshness = 1.0 / (1 + (barsCount - 1 - bounce.retestIndex))
        return trend.r2 * ln(1 + trend.periodReturn) * (1 + freshness)
    }
}
