package pl.radar.spolek.data

import org.json.JSONArray
import java.net.URLEncoder

/**
 * Opcjonalne zapasowe źródło P/E: Financial Modeling Prep (darmowy klucz na financialmodelingprep.com).
 * Używane tylko dla symboli, dla których Yahoo nie zwróciło P/E. Jedno zapytanie na spółkę.
 */
class FmpClient(private val apiKey: String) {
    suspend fun pe(symbol: String): Double? {
        val url = "https://financialmodelingprep.com/stable/ratios-ttm?symbol=" +
            URLEncoder.encode(symbol.replace('.', '-'), "UTF-8") + "&apikey=" + URLEncoder.encode(apiKey, "UTF-8")
        val body = Http.get(url, retries = 1).trim()
        if (!body.startsWith("[")) return null
        val arr = JSONArray(body)
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        for (key in listOf("priceToEarningsRatioTTM", "peRatioTTM", "priceEarningsRatioTTM")) {
            val v = o.optDouble(key, Double.NaN)
            if (!v.isNaN()) return v.takeIf { it > 0 }
        }
        return null
    }
}
