package pl.radar.spolek.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pl.radar.spolek.domain.Bar
import pl.radar.spolek.domain.Company
import pl.radar.spolek.domain.MarketData
import java.io.File
import java.io.IOException

/** Implementacja [MarketData] z pamięcią podręczną na dysku. */
class Repository(context: Context, private val fmpKey: String = "") : MarketData {
    private val app = context.applicationContext
    private val yahoo = YahooClient()
    private val barsDir = File(app.cacheDir, "bars").apply { mkdirs() }
    private val universeFile = File(app.filesDir, "sp500.csv")
    private val peFile = File(app.cacheDir, "pe.json")

    companion object {
        const val CONSTITUENTS_URL =
            "https://raw.githubusercontent.com/datasets/s-and-p-500-companies/main/data/constituents.csv"
        private const val BARS_TTL = 6 * 3600_000L
        private const val PE_TTL = 24 * 3600_000L
        private const val UNIVERSE_TTL = 7 * 24 * 3600_000L
    }

    // ---------- Uniwersum ----------

    override suspend fun universe(): List<Company> = withContext(Dispatchers.IO) {
        val fresh = universeFile.exists() && System.currentTimeMillis() - universeFile.lastModified() < UNIVERSE_TTL
        if (!fresh) {
            try {
                val csv = Http.get(CONSTITUENTS_URL)
                if (parseConstituents(csv).size > 400) universeFile.writeText(csv)
            } catch (e: IOException) {
                if (!universeFile.exists()) throw IOException("Nie udało się pobrać składu S&P 500: ${e.message}")
            }
        }
        parseConstituents(universeFile.readText())
    }

    suspend fun company(symbol: String): Company? =
        runCatching { universe().firstOrNull { it.symbol.equals(symbol, ignoreCase = true) } }.getOrNull()

    /** Kolumny: Symbol, Security, GICS Sector, GICS Sub-Industry, ... */
    private fun parseConstituents(csv: String): List<Company> {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()
        val header = splitCsv(lines[0]).map { it.trim().lowercase() }
        val iSym = header.indexOf("symbol").takeIf { it >= 0 } ?: 0
        val iName = header.indexOfFirst { it == "security" || it == "name" }.takeIf { it >= 0 } ?: 1
        val iSector = header.indexOfFirst { it.contains("sector") }.takeIf { it >= 0 } ?: 2
        val iSub = header.indexOfFirst { it.contains("sub-industry") || it.contains("sub industry") }.takeIf { it >= 0 } ?: 3
        return lines.drop(1).mapNotNull { line ->
            val f = splitCsv(line)
            if (f.size <= maxOf(iSym, iName, iSector, iSub)) null
            else Company(f[iSym].trim(), f[iName].trim(), f[iSector].trim(), f[iSub].trim())
        }
    }

    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    // ---------- Notowania ----------

    override suspend fun bars(symbol: String): List<Bar> = withContext(Dispatchers.IO) {
        val f = File(barsDir, symbol.replace('/', '_') + ".json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < BARS_TTL) {
            runCatching { return@withContext decodeBars(f.readText()) }
        }
        try {
            val bars = yahoo.dailyBars(symbol)
            if (bars.isNotEmpty()) f.writeText(encodeBars(bars))
            bars
        } catch (e: IOException) {
            // brak sieci – użyj starszej kopii, jeśli jest
            if (f.exists()) decodeBars(f.readText()) else throw e
        }
    }

    private fun encodeBars(bars: List<Bar>): String {
        val arr = JSONArray()
        for (b in bars) arr.put(JSONArray().put(b.epochDay).put(b.open).put(b.high).put(b.low).put(b.close).put(b.volume))
        return arr.toString()
    }

    private fun decodeBars(s: String): List<Bar> {
        val arr = JSONArray(s)
        return (0 until arr.length()).map {
            val a = arr.getJSONArray(it)
            Bar(a.getLong(0), a.getDouble(1), a.getDouble(2), a.getDouble(3), a.getDouble(4), a.getLong(5))
        }
    }

    // ---------- P/E ----------

    override suspend fun peRatios(symbols: List<String>): Map<String, Double?> = withContext(Dispatchers.IO) {
        val cache = loadPeCache()
        val now = System.currentTimeMillis()
        val out = HashMap<String, Double?>()
        val missing = mutableListOf<String>()
        for (s in symbols) {
            val e = cache.optJSONObject(s)
            if (e != null && now - e.optLong("t") < PE_TTL) {
                out[s] = if (e.isNull("pe")) null else e.getDouble("pe")
            } else missing += s
        }
        if (missing.isNotEmpty()) {
            var yahooError: Exception? = null
            val fetched = try { yahoo.trailingPE(missing) } catch (e: Exception) { yahooError = e; emptyMap() }
            val stillMissing = missing.filter { fetched[it] == null }
            val fmpFetched = HashMap<String, Double?>()
            if (fmpKey.isNotBlank()) {
                for (s in stillMissing) fmpFetched[s] = runCatching { FmpClient(fmpKey).pe(s) }.getOrNull()
            }
            if (yahooError != null && fmpKey.isBlank()) throw IOException(
                "${yahooError.message}. Dodaj klucz Financial Modeling Prep w ustawieniach jako źródło zapasowe."
            )
            for (s in missing) {
                val v = fetched[s] ?: fmpFetched[s]
                out[s] = v
                // zapisujemy w cache tylko odpowiedzi, które faktycznie przyszły
                if (s in fetched || s in fmpFetched) {
                    cache.put(s, JSONObject().put("t", now).put("pe", v ?: JSONObject.NULL))
                }
            }
            runCatching { peFile.writeText(cache.toString()) }
        }
        out
    }

    private fun loadPeCache(): JSONObject =
        runCatching { JSONObject(peFile.readText()) }.getOrDefault(JSONObject())
}
