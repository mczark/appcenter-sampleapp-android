package pl.radar.spolek.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import pl.radar.spolek.domain.Bar
import pl.radar.spolek.ui.theme.Gain
import pl.radar.spolek.ui.theme.Loss
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

data class ChartLevel(val price: Double, val color: Color, val label: String)
data class ChartMarker(val index: Int, val price: Double, val color: Color)

/**
 * Wykres świecowy (dzienny) narysowany na Canvas.
 * @param bars świece do narysowania (okno wykresu)
 * @param sma wartości SMA50 dopasowane indeksami do [bars] (null = brak)
 * @param selected zaznaczony indeks (krzyż), sterowany dotykiem
 */
@Composable
fun CandleChart(
    bars: List<Bar>,
    sma: List<Double?>,
    levels: List<ChartLevel>,
    markers: List<ChartMarker>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (bars.isEmpty()) return
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val smaColor = MaterialTheme.colorScheme.primary
    val axisWidthPx = 54.dp
    val bottomPx = 22.dp

    fun indexAt(x: Float, chartWidth: Float): Int =
        ((x / chartWidth) * bars.size).toInt().coerceIn(0, bars.size - 1)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(300.dp)
            .pointerInput(bars) {
                detectTapGestures { off ->
                    val w = size.width - axisWidthPx.toPx()
                    onSelect(if (off.x > w) null else indexAt(off.x, w))
                }
            }
            .pointerInput(bars) {
                detectDragGestures(onDragEnd = { }) { change, _ ->
                    val w = size.width - axisWidthPx.toPx()
                    onSelect(indexAt(change.position.x.coerceIn(0f, w - 1f), w))
                }
            },
    ) {
        val axisW = axisWidthPx.toPx()
        val bottom = bottomPx.toPx()
        val w = size.width - axisW
        val h = size.height - bottom
        var lo = bars.minOf { it.low }
        var hi = bars.maxOf { it.high }
        levels.forEach { lo = min(lo, it.price); hi = max(hi, it.price) }
        val padY = (hi - lo) * 0.06
        lo -= padY; hi += padY
        fun y(p: Double) = (h * (1 - (p - lo) / (hi - lo))).toFloat()
        val step = w / bars.size
        fun x(i: Int) = step * i + step / 2

        val textPaint = Paint().apply {
            color = axisColor.toArgb(); textSize = 10.dp.toPx(); isAntiAlias = true
        }

        // siatka i oś cen
        val ticks = 5
        for (t in 0..ticks) {
            val p = lo + (hi - lo) * t / ticks
            val yy = y(p)
            drawLine(gridColor, Offset(0f, yy), Offset(w, yy), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(Fmt.num(p, 2), w + 6.dp.toPx(), yy + 4.dp.toPx(), textPaint)
        }
        // etykiety miesięcy
        var prevMonth = -1
        bars.forEachIndexed { i, b ->
            val d = LocalDate.ofEpochDay(b.epochDay)
            if (d.monthValue != prevMonth) {
                if (prevMonth != -1) {
                    drawLine(gridColor, Offset(x(i), 0f), Offset(x(i), h), strokeWidth = 1f)
                    drawContext.canvas.nativeCanvas.drawText(
                        d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale("pl")),
                        x(i) + 3.dp.toPx(), h + 15.dp.toPx(), textPaint,
                    )
                }
                prevMonth = d.monthValue
            }
        }
        // poziomy (np. szczyt)
        levels.forEach { lv ->
            val yy = y(lv.price)
            drawLine(lv.color, Offset(0f, yy), Offset(w, yy), strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
            val lp = Paint(textPaint).apply { color = lv.color.toArgb() }
            drawContext.canvas.nativeCanvas.drawText(lv.label, 4.dp.toPx(), yy - 4.dp.toPx(), lp)
        }
        // świece
        val bodyW = max(1f, step * 0.65f)
        bars.forEachIndexed { i, b ->
            val c = if (b.close >= b.open) Gain else Loss
            drawLine(c, Offset(x(i), y(b.high)), Offset(x(i), y(b.low)), strokeWidth = max(1f, step * 0.12f))
            val top = y(max(b.open, b.close)); val bot = y(min(b.open, b.close))
            drawRect(c, Offset(x(i) - bodyW / 2, top), Size(bodyW, max(1f, bot - top)))
        }
        // SMA50
        val path = Path()
        var started = false
        sma.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
        }
        if (started) drawPath(path, smaColor.copy(alpha = 0.8f), style = Stroke(width = 2.5f))
        // znaczniki (np. dołek testu)
        markers.forEach { m ->
            if (m.index in bars.indices) {
                drawCircle(m.color, radius = 6.dp.toPx(), center = Offset(x(m.index), y(m.price)), style = Stroke(width = 3f))
            }
        }
        // krzyż
        selected?.takeIf { it in bars.indices }?.let { i ->
            val cross = axisColor.copy(alpha = 0.7f)
            drawLine(cross, Offset(x(i), 0f), Offset(x(i), h), strokeWidth = 1.5f)
            val yy = y(bars[i].close)
            drawLine(cross, Offset(0f, yy), Offset(w, yy), strokeWidth = 1.5f)
        }
    }
}
