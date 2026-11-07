package pl.radar.spolek.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import pl.radar.spolek.domain.Bar
import java.io.IOException
import java.net.URLEncoder

/**
 * Klient nieoficjalnego API Yahoo Finance.
 *  • notowania dzienne: /v8/finance/chart (bez tokenu),
 *  • P/E: /v7/finance/quote (wymaga ciasteczka i tokenu „crumb”).
 */
class YahooClient {
    private val crumbMutex = Mutex()
    @Volatile private var crumb: String? = null

    /** Symbole z kropką (BRK.B) Yahoo zapisuje z myślnikiem (BRK-B). */
    fun toYahoo(symbol: String) = symbol.replace('.', '-')

    suspend fun dailyBars(symbol: String, range: String = "1y"): List<Bar> {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/${enc(toYahoo(symbol))}" +
            "?range=$range&interval=1d&includePrePost=false&events=div%2Csplit"
        val root = JSONObject(Http.get(url)).getJSONObject("chart")
        val res = root.optJSONArray("result")?.optJSONObject(0)
            ?: throw IOException("Brak danych dla $symbol")
        val ts = res.optJSONArray("timestamp") ?: return emptyList()
        val offset = res.optJSONObject("meta")?.optLong("gmtoffset", -18000L) ?: -18000L
        val q = res.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val o = q.getJSONArray("open"); val h = q.getJSONArray("high")
        val l = q.getJSONArray("low"); val c = q.getJSONArray("close")
        val v = q.optJSONArray("volume")
        val out = ArrayList<Bar>(ts.length())
        for (i in 0 until ts.length()) {
            if (o.isNull(i) || h.isNull(i) || l.isNull(i) || c.isNull(i)) continue
            val day = Math.floorDiv(ts.getLong(i) + offset, 86_400L)
            val bar = Bar(day, o.getDouble(i), h.getDouble(i), l.getDouble(i), c.getDouble(i),
                if (v == null || v.isNull(i)) 0L else v.getLong(i))
            // Yahoo bywa, że dubluje ostatnią świecę – zostawiamy nowszą
            if (out.isNotEmpty() && out.last().epochDay == day) out[out.size - 1] = bar else out.add(bar)
        }
        return out
    }

    private suspend fun ensureCrumb(): String = crumbMutex.withLock {
        crumb?.let { return@withLock it }
        // Ustawia ciasteczko A3 (strona zwraca 404 – to normalne)
        runCatching { Http.get("https://fc.yahoo.com", allowError = true, retries = 0) }
        val c = Http.get("https://query2.finance.yahoo.com/v1/test/getcrumb").trim()
        if (c.isEmpty() || c.length > 32 || c.contains('<') || c.contains(' ')) {
            throw IOException("Yahoo nie wydało tokenu (crumb)")
        }
        crumb = c
        c
    }

    /** P/E (TTM) w paczkach po 40 symboli. Gdy brak trailingPE, liczymy cena / EPS TTM (tylko dla EPS > 0). */
    suspend fun trailingPE(symbols: List<String>): Map<String, Double?> {
        val out = HashMap<String, Double?>()
        val back = symbols.associateBy { toYahoo(it) }
        for (batch in symbols.chunked(40)) {
            val json = quoteBatch(batch.joinToString(",") { toYahoo(it) })
            val arr = json.optJSONObject("quoteResponse")?.optJSONArray("result") ?: continue
            for (i in 0 until arr.length()) {
                val q = arr.getJSONObject(i)
                val sym = back[q.optString("symbol")] ?: continue
                var pe = q.optDouble("trailingPE", Double.NaN)
                if (pe.isNaN()) {
                    val price = q.optDouble("regularMarketPrice", Double.NaN)
                    val eps = q.optDouble("epsTrailingTwelveMonths", Double.NaN)
                    if (!price.isNaN() && !eps.isNaN() && eps > 0) pe = price / eps
                }
                out[sym] = if (pe.isNaN() || pe <= 0) null else pe
            }
        }
        return out
    }

    private suspend fun quoteBatch(joined: String, retry: Boolean = true): JSONObject {
        val c = ensureCrumb()
        val url = "https://query2.finance.yahoo.com/v7/finance/quote?symbols=${enc(joined)}" +
            "&fields=trailingPE,epsTrailingTwelveMonths,regularMarketPrice&crumb=${enc(c)}"
        return try {
            JSONObject(Http.get(url))
        } catch (e: HttpException) {
            if (retry && (e.code == 401 || e.code == 403)) {
                crumb = null
                quoteBatch(joined, retry = false)
            } else throw e
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
