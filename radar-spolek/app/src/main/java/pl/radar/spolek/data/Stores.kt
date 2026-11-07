package pl.radar.spolek.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import pl.radar.spolek.domain.CriterionDiagnosis
import pl.radar.spolek.domain.Direction
import pl.radar.spolek.domain.GroupMeter
import pl.radar.spolek.domain.GroupSummary
import pl.radar.spolek.domain.HintUnit
import pl.radar.spolek.domain.MarketBreadthDay
import pl.radar.spolek.domain.ThresholdHint
import pl.radar.spolek.domain.GroupingLevel
import pl.radar.spolek.domain.Recommendation
import pl.radar.spolek.domain.ScreenResult
import pl.radar.spolek.domain.ScreenerConfig
import java.io.File

enum class UniverseMode(val label: String) {
    ALL_US("Cały rynek USA (NYSE, Nasdaq, NYSE American)"),
    SP500("Tylko S&P 500"),
}

/** Które spółki skanujemy. Domyślnie wszystkie akcje zwykłe notowane w USA. */
data class UniverseFilter(
    val mode: UniverseMode = UniverseMode.ALL_US,
    /** Minimalna kapitalizacja w mln USD (0 = bez limitu). */
    val minMarketCapM: Double = 0.0,
    /** Minimalna cena akcji w USD (0 = bez limitu). */
    val minPrice: Double = 0.0,
    /** Tylko spółki z siedzibą w USA (bez zagranicznych notowanych w USA, np. ADR). */
    val usOnly: Boolean = false,
)

data class AppSettings(
    val config: ScreenerConfig = ScreenerConfig(),
    val weeklyEnabled: Boolean = true,
    val fmpKey: String = "",
    val universe: UniverseFilter = UniverseFilter(),
)

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = ScreenerConfig()
        val p = prefs
        fun dbl(k: String, def: Double) = p.getFloat(k, def.toFloat()).toDouble()
        val cfg = ScreenerConfig(
            grouping = runCatching { GroupingLevel.valueOf(p.getString("grouping", d.grouping.name)!!) }.getOrDefault(d.grouping),
            minGroupSize = p.getInt("minGroupSize", d.minGroupSize),
            breadthDays = p.getInt("breadthDays", d.breadthDays),
            breadthThreshold = dbl("breadthThreshold", d.breadthThreshold),
            trendSessions = p.getInt("trendSessions", d.trendSessions),
            minTrendR2 = dbl("minTrendR2", d.minTrendR2),
            minTrendReturn = dbl("minTrendReturn", d.minTrendReturn),
            maxPeToMedian = dbl("maxPeToMedian", d.maxPeToMedian),
            peakLookback = p.getInt("peakLookback", d.peakLookback),
            pivotStrength = p.getInt("pivotStrength", d.pivotStrength),
            minPeakProminence = dbl("minPeakProminence", d.minPeakProminence),
            retestTolerance = dbl("retestTolerance", d.retestTolerance),
            minCorrection = dbl("minCorrection", d.minCorrection),
            minBounce = dbl("minBounce", d.minBounce),
            maxDaysSinceRetest = p.getInt("maxDaysSinceRetest", d.maxDaysSinceRetest),
            maxResults = p.getInt("maxResults", d.maxResults),
        )
        val u = UniverseFilter(
            mode = runCatching { UniverseMode.valueOf(p.getString("universeMode", UniverseMode.ALL_US.name)!!) }
                .getOrDefault(UniverseMode.ALL_US),
            minMarketCapM = dbl("minMarketCapM", 0.0),
            minPrice = dbl("minPrice", 0.0),
            usOnly = p.getBoolean("usOnly", false),
        )
        return AppSettings(cfg, p.getBoolean("weeklyEnabled", true), p.getString("fmpKey", "") ?: "", u)
    }

    fun save(s: AppSettings) {
        val c = s.config
        prefs.edit()
            .putString("grouping", c.grouping.name)
            .putInt("minGroupSize", c.minGroupSize)
            .putInt("breadthDays", c.breadthDays)
            .putFloat("breadthThreshold", c.breadthThreshold.toFloat())
            .putInt("trendSessions", c.trendSessions)
            .putFloat("minTrendR2", c.minTrendR2.toFloat())
            .putFloat("minTrendReturn", c.minTrendReturn.toFloat())
            .putFloat("maxPeToMedian", c.maxPeToMedian.toFloat())
            .putInt("peakLookback", c.peakLookback)
            .putInt("pivotStrength", c.pivotStrength)
            .putFloat("minPeakProminence", c.minPeakProminence.toFloat())
            .putFloat("retestTolerance", c.retestTolerance.toFloat())
            .putFloat("minCorrection", c.minCorrection.toFloat())
            .putFloat("minBounce", c.minBounce.toFloat())
            .putInt("maxDaysSinceRetest", c.maxDaysSinceRetest)
            .putInt("maxResults", c.maxResults)
            .putBoolean("weeklyEnabled", s.weeklyEnabled)
            .putString("fmpKey", s.fmpKey.trim())
            .putString("universeMode", s.universe.mode.name)
            .putFloat("minMarketCapM", s.universe.minMarketCapM.toFloat())
            .putFloat("minPrice", s.universe.minPrice.toFloat())
            .putBoolean("usOnly", s.universe.usOnly)
            .apply()
    }
}

/** Zapis ostatniego wyniku skanowania jako JSON. */
class ResultStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "last_result.json")

    fun save(r: ScreenResult) {
        val o = JSONObject()
            .put("timestamp", r.timestamp)
            .put("lastSessionDate", r.lastSessionDate)
            .put("universeSize", r.universeSize)
            .put("withData", r.withData)
            .put("groupsChecked", r.groupsChecked)
            .put("trendPassed", r.trendPassed)
            .put("bouncePassed", r.bouncePassed)
            .put("pePassed", r.pePassed)
            .put("peMissing", r.peMissing)
            .put("warnings", JSONArray(r.warnings))
            .put("groupsPassed", JSONArray().apply {
                r.groupsPassed.forEach { put(JSONObject().put("name", it.name).put("size", it.size).put("minRatio", it.minRatio)) }
            })
            .put("recommendations", JSONArray().apply {
                r.recommendations.forEach { x ->
                    put(JSONObject()
                        .put("symbol", x.symbol).put("name", x.name).put("group", x.group).put("sector", x.sector)
                        .put("lastClose", x.lastClose).put("lastDate", x.lastDate)
                        .put("pe", x.pe).put("groupMedianPe", x.groupMedianPe)
                        .put("trendR2", x.trendR2).put("trendReturn", x.trendReturn)
                        .put("peakPrice", x.peakPrice).put("peakDate", x.peakDate)
                        .put("retestDate", x.retestDate).put("retestLow", x.retestLow)
                        .put("bouncePct", x.bouncePct).put("score", x.score))
                }
            })
            .put("diagnosis", JSONArray().apply { r.diagnosis.forEach { put(encodeDiagnosis(it)) } })
            .put("trendMeter", encodeDays(r.trendMeter))
            .put("industryMeters", encodeGroups(r.industryMeters))
            .put("sectorMeters", encodeGroups(r.sectorMeters))
        file.writeText(o.toString())
    }

    fun load(): ScreenResult? = runCatching {
        val o = JSONObject(file.readText())
        val g = o.getJSONArray("groupsPassed")
        val rs = o.getJSONArray("recommendations")
        val w = o.getJSONArray("warnings")
        ScreenResult(
            timestamp = o.getLong("timestamp"),
            lastSessionDate = o.getLong("lastSessionDate"),
            universeSize = o.getInt("universeSize"),
            withData = o.getInt("withData"),
            groupsChecked = o.getInt("groupsChecked"),
            groupsPassed = (0 until g.length()).map {
                val x = g.getJSONObject(it); GroupSummary(x.getString("name"), x.getInt("size"), x.getDouble("minRatio"))
            },
            trendPassed = o.getInt("trendPassed"),
            bouncePassed = o.getInt("bouncePassed"),
            pePassed = o.getInt("pePassed"),
            peMissing = o.getInt("peMissing"),
            recommendations = (0 until rs.length()).map {
                val x = rs.getJSONObject(it)
                Recommendation(
                    x.getString("symbol"), x.getString("name"), x.getString("group"), x.getString("sector"),
                    x.getDouble("lastClose"), x.getLong("lastDate"), x.getDouble("pe"), x.getDouble("groupMedianPe"),
                    x.getDouble("trendR2"), x.getDouble("trendReturn"), x.getDouble("peakPrice"), x.getLong("peakDate"),
                    x.getLong("retestDate"), x.getDouble("retestLow"), x.getDouble("bouncePct"), x.getDouble("score"),
                )
            },
            warnings = (0 until w.length()).map { w.getString(it) },
            diagnosis = o.optJSONArray("diagnosis")?.let { a -> (0 until a.length()).map { decodeDiagnosis(a.getJSONObject(it)) } }
                ?: emptyList(),
            trendMeter = o.optJSONArray("trendMeter")?.let { decodeDays(it) } ?: emptyList(),
            industryMeters = o.optJSONArray("industryMeters")?.let { decodeGroups(it) } ?: emptyList(),
            sectorMeters = o.optJSONArray("sectorMeters")?.let { decodeGroups(it) } ?: emptyList(),
        )
    }.getOrNull()

    private fun encodeDays(days: List<MarketBreadthDay>) = JSONArray().apply {
        days.forEach { put(JSONArray().put(it.epochDay).put(it.advancers).put(it.decliners).put(it.unchanged)) }
    }

    private fun decodeDays(a: JSONArray): List<MarketBreadthDay> = (0 until a.length()).map {
        val d = a.getJSONArray(it)
        MarketBreadthDay(d.getLong(0), d.getInt(1), d.getInt(2), d.getInt(3))
    }

    private fun encodeGroups(groups: List<GroupMeter>) = JSONArray().apply {
        groups.forEach { put(JSONObject().put("name", it.name).put("size", it.size).put("days", encodeDays(it.days))) }
    }

    private fun decodeGroups(a: JSONArray): List<GroupMeter> = (0 until a.length()).map {
        val g = a.getJSONObject(it)
        GroupMeter(g.getString("name"), g.getInt("size"), decodeDays(g.getJSONArray("days")))
    }

    private fun JSONObject.putOpt2(key: String, v: Any?): JSONObject = put(key, v ?: JSONObject.NULL)
    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (isNull(key) || !has(key)) null else getDouble(key)
    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key) || !has(key)) null else getString(key)

    private fun encodeDiagnosis(d: CriterionDiagnosis): JSONObject = JSONObject()
        .put("number", d.number).put("title", d.title)
        .put("passedAlone", d.passedAlone).put("outOf", d.outOf).put("blockedOnlyByThis", d.blockedOnlyByThis)
        .putOpt2("note", d.note)
        .put("hints", JSONArray().apply {
            d.hints.forEach { h ->
                put(JSONObject()
                    .put("key", h.key).put("label", h.label).put("unit", h.unit.name).put("direction", h.direction.name)
                    .put("current", h.current)
                    .putOpt2("required", h.required).putOpt2("example", h.example)
                    .putOpt2("aloneRequired", h.aloneRequired).putOpt2("aloneExample", h.aloneExample))
            }
        })

    private fun decodeDiagnosis(o: JSONObject): CriterionDiagnosis {
        val hs = o.getJSONArray("hints")
        return CriterionDiagnosis(
            number = o.getInt("number"), title = o.getString("title"),
            passedAlone = o.getInt("passedAlone"), outOf = o.getInt("outOf"),
            blockedOnlyByThis = o.getInt("blockedOnlyByThis"),
            hints = (0 until hs.length()).map {
                val h = hs.getJSONObject(it)
                ThresholdHint(
                    key = h.getString("key"), label = h.getString("label"),
                    unit = HintUnit.valueOf(h.getString("unit")), direction = Direction.valueOf(h.getString("direction")),
                    current = h.getDouble("current"),
                    required = h.optDoubleOrNull("required"), example = h.optStringOrNull("example"),
                    aloneRequired = h.optDoubleOrNull("aloneRequired"), aloneExample = h.optStringOrNull("aloneExample"),
                )
            },
            note = o.optStringOrNull("note"),
        )
    }
}
