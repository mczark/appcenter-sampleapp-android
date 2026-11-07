package pl.radar.spolek.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import pl.radar.spolek.domain.GroupSummary
import pl.radar.spolek.domain.GroupingLevel
import pl.radar.spolek.domain.Recommendation
import pl.radar.spolek.domain.ScreenResult
import pl.radar.spolek.domain.ScreenerConfig
import java.io.File

data class AppSettings(
    val config: ScreenerConfig = ScreenerConfig(),
    val weeklyEnabled: Boolean = true,
    val fmpKey: String = "",
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
            peakLookback = p.getInt("peakLookback", d.peakLookback),
            pivotStrength = p.getInt("pivotStrength", d.pivotStrength),
            minPeakProminence = dbl("minPeakProminence", d.minPeakProminence),
            retestTolerance = dbl("retestTolerance", d.retestTolerance),
            minCorrection = dbl("minCorrection", d.minCorrection),
            minBounce = dbl("minBounce", d.minBounce),
            maxDaysSinceRetest = p.getInt("maxDaysSinceRetest", d.maxDaysSinceRetest),
            maxResults = p.getInt("maxResults", d.maxResults),
        )
        return AppSettings(cfg, p.getBoolean("weeklyEnabled", true), p.getString("fmpKey", "") ?: "")
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
        )
    }.getOrNull()
}
