package pl.radar.spolek.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pl.radar.spolek.domain.Bar
import pl.radar.spolek.domain.Company
import pl.radar.spolek.domain.MarketData
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Implementacja [MarketData] z pamięcią podręczną na dysku.
 * Uniwersum: cały rynek USA (lista z wyszukiwarki Nasdaq) albo S&P 500 – zależnie od [filter].
 */
class Repository(
    context: Context,
    private val fmpKey: String = "",
    private val filter: UniverseFilter = UniverseFilter(),
) : MarketData {
    private val app = context.applicationContext
    private val yahoo = YahooClient()
    private val barsDir = File(app.cacheDir, "bars").apply { mkdirs() }
    private val universeFile = File(app.filesDir, "sp500.csv")
    private val usFile = File(app.filesDir, "us_stocks.json")
    private val peFile = File(app.cacheDir, "pe.json")

    companion object {
        const val CONSTITUENTS_URL =
            "https://raw.githubusercontent.com/datasets/s-and-p-500-companies/main/data/constituents.csv"
        private const val BARS_TTL = 6 * 3600_000L
        private const val PE_TTL = 24 * 3600_000L
        private const val UNIVERSE_TTL = 7 * 24 * 3600_000L
        private const val US_TTL = 3 * 24 * 3600_000L
        const val NASDAQ_SCREENER_URL = "https://api.nasdaq.com/api/screener/stocks?tableonly=true&download=true"
        /** Zapas: codziennie aktualizowana kopia danych z wyszukiwarki Nasdaq na GitHubie. */
        val GITHUB_TICKERS = listOf("nasdaq", "nyse", "amex").map {
            "https://raw.githubusercontent.com/rreichel3/US-Stock-Symbols/main/$it/${it}_full_tickers.json"
        }
        /** Instrumenty, które nie są akcjami zwykłymi: warranty, jednostki, prawa poboru, akcje uprzywilejowane, obligacje. */
        private val NON_COMMON = Regex(
            "\\b(warrants?|units?|rights?|preferred|preference|notes?|debentures?|subordinated|trust preferred|depositary shares? representing)\\b|\\d+(\\.\\d+)?%",
            RegexOption.IGNORE_CASE,
        )
        private const val SPARK_BATCH = 20

    }

    // ---------- Uniwersum ----------

    override suspend fun universe(): List<Company> =
        if (filter.mode == UniverseMode.SP500) sp500() else allUs()

    private suspend fun sp500(): List<Company> = withContext(Dispatchers.IO) {
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

    /** Wszystkie akcje notowane w USA z wyszukiwarki Nasdaq (zapas: kopia na GitHubie), po filtrach. */
    private suspend fun allUs(): List<Company> = withContext(Dispatchers.IO) {
        val fresh = usFile.exists() && System.currentTimeMillis() - usFile.lastModified() < US_TTL
        if (!fresh) {
            val rows = runCatching { fetchNasdaq() }.getOrNull()?.takeIf { it.length() > 3000 }
                ?: runCatching { fetchGithubTickers() }.getOrNull()?.takeIf { it.length() > 3000 }
            if (rows != null) usFile.writeText(rows.toString())
            else if (!usFile.exists()) throw IOException("Nie udało się pobrać listy spółek z rynku USA")
        }
        val rows = JSONArray(usFile.readText())
        val out = ArrayList<Company>(rows.length())
        val seen = HashSet<String>()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val symbol = r.optString("symbol").trim().replace('/', '.')
            val name = r.optString("name").trim()
            val sector = r.optString("sector").trim()
            val industry = r.optString("industry").trim()
            if (symbol.isEmpty() || '^' in symbol || ' ' in symbol || !seen.add(symbol)) continue
            if (sector.isEmpty() || industry.isEmpty()) continue // fundusze, SPAC-i bez klasyfikacji
            if (NON_COMMON.containsMatchIn(name)) continue
            if (filter.usOnly && r.optString("country") != "United States") continue
            if (filter.minMarketCapM > 0 && num(r.optString("marketCap")) < filter.minMarketCapM * 1e6) continue
            if (filter.minPrice > 0 && num(r.optString("lastsale")) < filter.minPrice) continue
            out += Company(symbol, name, sector, industry)
        }
        out
    }

    private fun num(s: String): Double = s.replace("$", "").replace(",", "").trim().toDoubleOrNull() ?: 0.0

    private suspend fun fetchNasdaq(): JSONArray {
        val body = Http.get(
            NASDAQ_SCREENER_URL,
            headers = mapOf(
                "Accept" to "application/json, text/plain, */*",
                "Origin" to "https://www.nasdaq.com",
                "Referer" to "https://www.nasdaq.com/",
            ),
        )
        return JSONObject(body).getJSONObject("data").getJSONArray("rows")
    }

    private suspend fun fetchGithubTickers(): JSONArray {
        val all = JSONArray()
        for (url in GITHUB_TICKERS) {
            val arr = JSONArray(Http.get(url))
            for (i in 0 until arr.length()) all.put(arr.getJSONObject(i))
        }
        return all
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

    /** Pełne świece z 6 miesięcy dla kandydatów (mniej danych niż rok). */
    override suspend fun ohlc(symbol: String): List<Bar> = withContext(Dispatchers.IO) {
        val f = File(barsDir, symbol.replace('/', '_') + ".6mo.json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < BARS_TTL) {
            runCatching { return@withContext decodeBars(f.readText()) }
        }
        val bars = yahoo.dailyBars(symbol, "6mo")
        if (bars.isNotEmpty()) f.writeText(encodeBars(bars))
        bars
    }

    /** Zamknięcia hurtowo po 20 symboli; gdy zapytanie zbiorcze zawiedzie – pobieranie spółka po spółce. */
    override suspend fun closes(symbols: List<String>, onProgress: suspend (Int, Int) -> Unit): Map<String, List<Bar>> =
        coroutineScope {
            val sem = Semaphore(4)
            val done = AtomicInteger(0)
            symbols.chunked(SPARK_BATCH).map { batch ->
                async {
                    sem.withPermit {
                        var got = runCatching { yahoo.sparkCloses(batch) }.getOrDefault(emptyMap())
                        if (got.size < batch.size / 2) {
                            // fallback: pojedyncze wykresy (wolniej, ale niezawodnie)
                            val m = HashMap(got)
                            for (s in batch) if (s !in m) runCatching { bars(s) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { m[s] = it }
                            got = m
                        }
                        onProgress(minOf(symbols.size, done.addAndGet(batch.size)), symbols.size)
                        got
                    }
                }
            }.awaitAll().fold(HashMap<String, List<Bar>>()) { acc, m -> acc.putAll(m); acc }
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
