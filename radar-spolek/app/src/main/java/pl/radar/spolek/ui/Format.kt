package pl.radar.spolek.ui

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Fmt {
    private val pl = Locale("pl", "PL")
    private val dayFmt = DateTimeFormatter.ofPattern("d MMM yyyy", pl)
    private val shortFmt = DateTimeFormatter.ofPattern("d.MM", pl)
    private val dateTimeFmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", pl)

    fun price(v: Double): String = "$" + String.format(pl, "%,.2f", v)
    fun num(v: Double, digits: Int = 1): String = String.format(pl, "%.${digits}f", v)
    fun pct(v: Double, signed: Boolean = true): String =
        (if (signed && v > 0) "+" else "") + String.format(pl, "%.1f%%", v * 100)
    fun day(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dayFmt)
    fun shortDay(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(shortFmt)
    fun dateTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dateTimeFmt)
    fun volume(v: Long): String = NumberFormat.getIntegerInstance(pl).format(v)
}
