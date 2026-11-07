package pl.radar.spolek.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import kotlin.math.pow
import kotlin.math.sin

class AnalysisTest {

    private val cfg = ScreenerConfig()

    private fun barsFromCloses(closes: List<Double>, startDay: Long = 20000): List<Bar> =
        closes.mapIndexed { i, c -> Bar(startDay + i, c, c * 1.004, c * 0.996, c, 1_000_000) }

    /** 100 sesji regularnego wzrostu, potem: szczyt ~140 → cofnięcie → wybicie → test szczytu → odbicie. */
    private fun bouncePattern(retestTo: Double = 140.5, bounceTo: Double = 143.5): List<Double> {
        val base = (0 until 110).map { i -> 100.0 * (1.30).pow(i / 109.0) * (1 + 0.004 * sin(i * 0.9)) }
        val peak = listOf(133.0, 135.0, 137.0, 140.0, 138.0, 136.0, 134.0, 133.5, 135.0, 138.0)
        val breakout = listOf(141.0, 144.0, 147.0, 148.0, 146.5, 144.0, 142.0, retestTo)
        val bounce = listOf((retestTo + bounceTo) / 2, bounceTo)
        return base + peak + breakout + bounce
    }

    @Test fun medianOddEven() {
        assertEquals(2.0, Analysis.median(listOf(3.0, 1.0, 2.0))!!, 1e-9)
        assertEquals(2.5, Analysis.median(listOf(4.0, 1.0, 2.0, 3.0))!!, 1e-9)
        assertNull(Analysis.median(emptyList()))
    }

    @Test fun bounceDetected() {
        val bars = barsFromCloses(bouncePattern())
        val b = Analysis.findBounce(bars, cfg)
        assertNotNull(b)
        assertEquals(140.0 * 1.004, b!!.peakPrice, 1e-6)
        assertEquals(bars.size - 3, b.retestIndex)
    }

    @Test fun noBounceWhenSupportBroken() {
        val bars = barsFromCloses(bouncePattern(retestTo = 131.0, bounceTo = 134.0))
        assertNull(Analysis.findBounce(bars, cfg))
    }

    @Test fun noBounceWithoutRebound() {
        val closes = bouncePattern().dropLast(2) // kończy się na dołku korekty
        assertNull(Analysis.findBounce(barsFromCloses(closes), cfg))
    }

    @Test fun noBounceWhenCorrectionStopsAboveLevel() {
        // korekta po wybiciu zatrzymuje się ok. 4,5% nad poziomem szczytu – to nie jest test szczytu
        val closes = bouncePattern().dropLast(10) +
            listOf(141.0, 144.0, 147.0, 150.0, 149.0, 148.0, 147.0, 147.5, 149.0, 150.5)
        assertNull(Analysis.findBounce(barsFromCloses(closes), cfg))
    }

    @Test fun trendPassesForSteadyUptrend() {
        val t = Analysis.trend(barsFromCloses(bouncePattern()), cfg)!!
        assertTrue("r2=${t.r2} ret=${t.periodReturn}", t.passed)
    }

    @Test fun trendFailsForSideways() {
        val closes = (0 until 130).map { 100 + 8 * sin(it / 6.0) }
        assertFalse(Analysis.trend(barsFromCloses(closes), cfg)!!.passed)
    }

    @Test fun breadthNeedsHalfEveryDay() {
        val days = 12
        val up = barsFromCloses((0 until days).map { 100.0 + it })
        val down = barsFromCloses((0 until days).map { 100.0 - it })
        val dates = (20000L until 20000L + days).toList().takeLast(cfg.breadthDays + 1)
        assertTrue(Analysis.breadth(listOf(up, up, down, down), dates, cfg).passed)
        assertFalse(Analysis.breadth(listOf(up, down, down, down), dates, cfg).passed)
        // jeden dzień spadku całej grupy psuje wynik
        val dip = barsFromCloses((0 until days).map { if (it == 8) 90.0 else 100.0 + it })
        assertFalse(Analysis.breadth(listOf(dip, dip, up, down), dates, cfg).passed)
    }

    @Test fun trendMeterCountsAdvancersAndDecliners() {
        val a = barsFromCloses(listOf(10.0, 11.0, 12.0, 12.0))
        val b = barsFromCloses(listOf(10.0, 9.0, 13.0, 11.0))
        val c = barsFromCloses(listOf(10.0, 9.5, 9.0, 8.0))
        val m = Analysis.marketBreadth(listOf(a, b, c), (20000L..20003L).toList())
        assertEquals(3, m.size)
        assertEquals(MarketBreadthDay(20001, 1, 2, 0), m[0]) // czerwony
        assertEquals(MarketBreadthDay(20002, 2, 1, 0), m[1]) // zielony
        assertEquals(MarketBreadthDay(20003, 0, 2, 1), m[2])
        assertTrue(m[1].net > 0 && m[0].net < 0)
    }

    @Test fun trendMeterCoversTwoMonths() = runBlocking {
        val r = ScreenerEngine(sampleMarket(), cfg, now = future).screen()
        val first = java.time.LocalDate.ofEpochDay(r.trendMeter.first().epochDay)
        val last = java.time.LocalDate.ofEpochDay(r.trendMeter.last().epochDay)
        assertEquals(r.lastSessionDate, r.trendMeter.last().epochDay)
        assertFalse(first.isBefore(last.minusMonths(2)))
        assertTrue(first.minusDays(1).isBefore(last.minusMonths(2)))
        assertTrue(r.trendMeter.all { it.advancers + it.decliners + it.unchanged == 7 })
    }

    @Test fun dropIncompleteBar() {
        val now = ZonedDateTime.of(2026, 11, 6, 11, 0, 0, 0, ScreenerEngine.NY)
        val today = now.toLocalDate().toEpochDay()
        val bars = barsFromCloses(listOf(1.0, 2.0), startDay = today - 1)
        assertEquals(1, ScreenerEngine.dropIncomplete(bars, now).size)
        assertEquals(2, ScreenerEngine.dropIncomplete(bars, now.withHour(17)).size)
    }

    @Test fun engineEndToEnd() = runBlocking {
        val result = ScreenerEngine(sampleMarket(), cfg, now = future).screen()
        assertEquals(listOf("Tech"), result.groupsPassed.map { it.name })
        // AAA: P/E 15 ≤ mediana(15,40,20,25)=22.5 → OK; BBB: 40 > 22.5 → odrzucona
        assertEquals(listOf("AAA"), result.recommendations.map { it.symbol })
        assertEquals(22.5, result.recommendations[0].groupMedianPe, 1e-9)
        assertTrue(result.diagnosis.isEmpty())
    }

    private val future = { ZonedDateTime.now(ScreenerEngine.NY).plusYears(5) }

    private fun hint(r: ScreenResult, key: String) = r.diagnosis.flatMap { it.hints }.first { it.key == key }

    @Test fun diagnosisBreadthThreshold() = runBlocking {
        // przy progu 60% branża Tech (min. 50% rosnących) odpada → brak wyników
        val r = ScreenerEngine(sampleMarket(), cfg.copy(breadthThreshold = 0.6), now = future).screen()
        assertTrue(r.recommendations.isEmpty())
        val c1 = r.diagnosis.first { it.number == 1 }
        assertEquals(0, c1.passedAlone)
        assertEquals(2, c1.blockedOnlyByThis) // AAA (Tech) i EEE (Oil) spełniają 2, 3 i 4
        val h = hint(r, "breadthThreshold")
        assertEquals(0.5, h.required!!, 1e-9)
        assertEquals("AAA", h.example)
        // liczba sesji: przy 60% Tech spełnia warunek tylko w 2 ostatnich sesjach
        assertEquals(2.0, hint(r, "breadthDays").required!!, 1e-9)
        // zmiana progów trendu czy odbicia nic nie da – blokuje branża
        assertNull(hint(r, "minTrendR2").required)
        assertNull(hint(r, "minBounce").required)
        // po zastosowaniu podpowiedzi pojawia się rekomendacja
        val fixed = cfg.copy(breadthThreshold = 0.6).withParam(h.key, h.rounded(h.required!!))
        assertEquals(listOf("AAA"), ScreenerEngine(sampleMarket(), fixed, now = future).screen().recommendations.map { it.symbol })
    }

    @Test fun diagnosisPe() = runBlocking {
        val r = ScreenerEngine(sampleMarket(), cfg.copy(maxPeToMedian = 0.5), now = future).screen()
        assertTrue(r.recommendations.isEmpty())
        val h = hint(r, "maxPeToMedian")
        assertEquals(15.0 / 22.5, h.required!!, 1e-9)
        assertEquals(0.667, h.rounded(h.required!!), 1e-9)
        assertEquals(2, r.diagnosis.first { it.number == 3 }.blockedOnlyByThis) // AAA i BBB
    }

    @Test fun diagnosisBounce() = runBlocking {
        // minimalne odbicie 5% – AAA odbiła ok. 2,5% od dołka
        val r = ScreenerEngine(sampleMarket(), cfg.copy(minBounce = 0.05), now = future).screen()
        assertTrue(r.recommendations.isEmpty())
        val h = hint(r, "minBounce")
        assertNotNull(h.required)
        val fixed = cfg.copy(minBounce = 0.05).withParam(h.key, h.rounded(h.required!!))
        assertEquals(listOf("AAA"), ScreenerEngine(sampleMarket(), fixed, now = future).screen().recommendations.map { it.symbol })
    }

    @Test fun twoStageFetchOnlyCandidates() = runBlocking {
        val base = sampleMarket()
        val ohlcCalls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val staged = object : MarketData by base {
            // etap 1: same zamknięcia (bez high/low), jak z hurtowego endpointu
            override suspend fun closes(symbols: List<String>, onProgress: suspend (Int, Int) -> Unit) =
                symbols.associateWith { s -> base.bars(s).map { it.copy(open = it.close, high = it.close, low = it.close) } }
            override suspend fun ohlc(symbol: String): List<Bar> { ohlcCalls += symbol; return base.bars(symbol) }
        }
        val r = ScreenerEngine(staged, cfg, now = future).screen()
        assertEquals(listOf("AAA"), r.recommendations.map { it.symbol })
        // Oil nie jest w trendzie, a FFF/GGG nie mają trendu → bez pełnych świec
        assertFalse("FFF" in ohlcCalls || "GGG" in ohlcCalls)
        assertTrue("EEE" in ohlcCalls) // trend wzrostowy → sprawdzamy formację
    }

    private fun sampleMarket(): MarketData {
        val good = bouncePattern()
        val n = good.size
        // grupa „Tech”: 4 spółki w trendzie z formacją; ostatnie 10 dni rosną (wzorzec ma 2 spadki → nadpisujemy grupę tak, by większość rosła)
        val rising = (0 until n).map { 50.0 * 1.002.pow(it) }
        val companies = listOf(
            Company("AAA", "Alpha", "IT", "Tech"),
            Company("BBB", "Beta", "IT", "Tech"),
            Company("CCC", "Gamma", "IT", "Tech"),
            Company("DDD", "Delta", "IT", "Tech"),
            Company("EEE", "Eps", "Energy", "Oil"),
            Company("FFF", "Phi", "Energy", "Oil"),
            Company("GGG", "Gam", "Energy", "Oil"),
        )
        val series = mapOf(
            "AAA" to good, "BBB" to good, "CCC" to rising, "DDD" to rising,
            "EEE" to good, "FFF" to (0 until n).map { 80.0 - it * 0.1 }, "GGG" to (0 until n).map { 80.0 - it * 0.1 },
        )
        val pe = mapOf("AAA" to 15.0, "BBB" to 40.0, "CCC" to 20.0, "DDD" to 25.0, "EEE" to 5.0, "FFF" to 9.0, "GGG" to 9.0)
        return object : MarketData {
            override suspend fun universe() = companies
            override suspend fun bars(symbol: String) = barsFromCloses(series.getValue(symbol))
            override suspend fun peRatios(symbols: List<String>) = symbols.associateWith { pe[it] }
        }
    }
}
