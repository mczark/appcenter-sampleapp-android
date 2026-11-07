package pl.radar.spolek.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FundamentalScoreTest {

    @Test fun ranksByCombinationOfMetrics() {
        val cheapProfitable = Fundamentals("A", pe = 10.0, priceToBook = 1.5, roe = 0.25, profitMargin = 0.20, debtToEquity = 0.3)
        val middle = Fundamentals("B", pe = 20.0, priceToBook = 3.0, roe = 0.15, profitMargin = 0.10, debtToEquity = 1.0)
        val expensiveLossy = Fundamentals("C", pe = -5.0, priceToBook = 8.0, roe = -0.10, profitMargin = -0.05, debtToEquity = 3.0)
        val r = FundamentalScore.rank(listOf(middle, expensiveLossy, cheapProfitable))
        assertEquals(listOf("A", "B", "C"), r.map { it.fundamentals.symbol })
        assertEquals(100.0, r[0].score!!, 1e-9)
        assertEquals(50.0, r[1].score!!, 1e-9)
        assertEquals(0.0, r[2].score!!, 1e-9) // ujemne P/E = najgorsze
    }

    @Test fun tooFewMetricsGivesNoScoreAndSortsLast() {
        val full = Fundamentals("A", pe = 10.0, priceToBook = 2.0, roe = 0.1)
        val sparse = Fundamentals("B", pe = 5.0)
        val r = FundamentalScore.rank(listOf(sparse, full))
        assertEquals("A", r[0].fundamentals.symbol)
        assertNull(r[1].score)
        assertEquals(1.0, r[1].percentiles[Metric.PE]!!, 1e-9) // B ma lepsze P/E
    }

    @Test fun tiesShareAveragePercentile() {
        val r = FundamentalScore.rank(listOf(
            Fundamentals("A", roe = 0.2, profitMargin = 0.1, revenueGrowth = 0.1),
            Fundamentals("B", roe = 0.2, profitMargin = 0.1, revenueGrowth = 0.1),
            Fundamentals("C", roe = 0.1, profitMargin = 0.05, revenueGrowth = 0.0),
        ))
        assertEquals(0.75, r.first { it.fundamentals.symbol == "A" }.percentiles[Metric.ROE]!!, 1e-9)
        assertTrue(r.last().fundamentals.symbol == "C")
    }
}
