package pl.radar.spolek.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pl.radar.spolek.domain.MarketBreadthDay
import pl.radar.spolek.ui.theme.Gain
import pl.radar.spolek.ui.theme.Loss
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

internal fun meterColor(d: MarketBreadthDay, neutral: Color) = when {
    d.isGreen -> Gain
    d.isRed -> Loss
    else -> neutral
}

/**
 * Trendomierz: odsetek spółek rosnących w kolejnych sesjach z ostatnich 2 miesięcy.
 * Słupek zielony, gdy wzrosło co najmniej 50% spółek, czerwony – gdy mniej niż 50%.
 */
@Composable
fun TrendMeterCard(days: List<MarketBreadthDay>, universeLabel: String, onOpenGroups: (() -> Unit)? = null) {
    if (days.isEmpty()) return
    val neutral = MaterialTheme.colorScheme.outline
    var selected by remember(days) { mutableStateOf(days.size - 1) }
    val d = days[selected.coerceIn(days.indices)]
    val last = days.last()
    val greens = days.count { it.isGreen }
    val reds = days.count { it.isRed }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.then(if (onOpenGroups != null) Modifier.clickable(onClick = onOpenGroups) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Lamp(meterColor(last, neutral))
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("Trendomierz", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            last.isGreen -> "Zielony – w ostatniej sesji wzrosło ${Fmt.pct(last.advancingShare ?: 0.0, false)} spółek (≥ 50%)"
                            last.isRed -> "Czerwony – w ostatniej sesji wzrosło tylko ${Fmt.pct(last.advancingShare ?: 0.0, false)} spółek (< 50%)"
                            else -> "Brak danych z ostatniej sesji"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (onOpenGroups != null) Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            }

            Text(
                "${Fmt.day(d.epochDay)}:  rosło ${Fmt.pct(d.advancingShare ?: 0.0, signed = false)}" +
                    "   (▲ ${Fmt.volume(d.advancers.toLong())}  ▼ ${Fmt.volume(d.decliners.toLong())}  bez zmian ${Fmt.volume(d.unchanged.toLong())})",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = meterColor(d, MaterialTheme.colorScheme.onSurface),
            )
            Text(
                "Odsetek liczony od ${Fmt.volume(d.total.toLong())} spółek z notowaniem tego dnia.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            MeterChart(days, selected, onSelect = { selected = it })

            Text(
                "Ostatnie 2 miesiące: $greens sesji zielonych, $reds czerwonych. Słupek = % spółek rosnących " +
                    "($universeLabel); linia przerywana = 50%. Dotknij słupka, by zobaczyć szczegóły dnia.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onOpenGroups != null) {
                TextButton(onClick = onOpenGroups, modifier = Modifier.align(Alignment.End)) {
                    Text("Trendomierze branż ›")
                }
            }
        }
    }
}

@Composable
internal fun Lamp(color: Color, diameter: Dp = 34.dp) {
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent)), radius = size.minDimension / 2)
            drawCircle(color, radius = size.minDimension / 3.4f)
            drawCircle(Color.White.copy(alpha = 0.45f), radius = size.minDimension / 12f,
                center = Offset(size.width * 0.42f, size.height * 0.40f))
        }
    }
}

/**
 * Wykres słupkowy trendomierza. Bez [onSelect] – wersja miniaturowa (bez dotyku);
 * [showAxis] = false ukrywa podpisy osi.
 */
@Composable
internal fun MeterChart(
    days: List<MarketBreadthDay>,
    selected: Int?,
    onSelect: ((Int) -> Unit)? = null,
    height: Dp = 180.dp,
    showAxis: Boolean = true,
    /** Liczba ostatnich sesji tła wyróżnionego (0 = bez wyróżnienia). */
    highlightLast: Int = 0,
) {
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    val neutral = MaterialTheme.colorScheme.outline
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelW = if (showAxis) 44.dp else 0.dp
    fun idx(x: Float, w: Float) = ((x / w) * days.size).toInt().coerceIn(0, days.size - 1)

    val touch = if (onSelect == null) Modifier else Modifier
        .pointerInput(days) {
            detectTapGestures { o -> val w = size.width - labelW.toPx(); if (o.x <= w) onSelect(idx(o.x, w)) }
        }
        .pointerInput(days) {
            detectDragGestures { ch, _ ->
                val w = size.width - labelW.toPx()
                onSelect(idx(ch.position.x.coerceIn(0f, w - 1f), w))
            }
        }

    Canvas(Modifier.fillMaxWidth().height(height).then(touch)) {
        val lw = labelW.toPx()
        val bottom = if (showAxis) 18.dp.toPx() else 0f
        val w = size.width - lw
        val h = size.height - bottom
        // oś Y: 0–100% spółek rosnących
        fun y(share: Double) = (h - share * (h - 4f)).toFloat()
        val step = w / days.size
        val text = Paint().apply { color = axis.toArgb(); textSize = 10.dp.toPx(); isAntiAlias = true }

        for (v in listOf(1.0, 0.5, 0.0)) {
            if (!showAxis && v != 0.5) continue
            val yy = y(v)
            if (v == 0.5) {
                drawLine(axis, Offset(0f, yy), Offset(w, yy), strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
            } else {
                drawLine(grid, Offset(0f, yy), Offset(w, yy), strokeWidth = 1f)
            }
            if (showAxis) drawContext.canvas.nativeCanvas.drawText("${(v * 100).toInt()}%", w + 6.dp.toPx(), yy + 4.dp.toPx(), text)
        }
        // etykiety miesięcy
        var prevMonth = -1
        if (showAxis) days.forEachIndexed { i, d ->
            val date = LocalDate.ofEpochDay(d.epochDay)
            if (date.monthValue != prevMonth) {
                if (prevMonth != -1) drawLine(grid, Offset(step * i, 0f), Offset(step * i, h), strokeWidth = 1f)
                drawContext.canvas.nativeCanvas.drawText(
                    date.month.getDisplayName(TextStyle.SHORT, Locale("pl")), step * i + 3.dp.toPx(), h + 14.dp.toPx(), text,
                )
                prevMonth = date.monthValue
            }
        }
        if (highlightLast > 0) {
            val from = (days.size - highlightLast).coerceAtLeast(0)
            drawRect(highlight, Offset(step * from, 0f), Size(step * (days.size - from), h))
        }
        // słupki
        val barW = max(2f, step * 0.7f)
        days.forEachIndexed { i, d ->
            val c = meterColor(d, neutral)
            val x = step * i + (step - barW) / 2
            val share = d.advancingShare ?: return@forEachIndexed
            val top = y(share); val bot = y(0.0)
            drawRect(c.copy(alpha = if (selected == null || i == selected) 1f else 0.8f), Offset(x, top), Size(barW, max(2f, bot - top)))
        }
        // zaznaczenie
        if (selected != null) {
            val sx = step * selected + step / 2
            drawRect(axis.copy(alpha = 0.6f), Offset(sx - step / 2, 0f), Size(step, h), style = Stroke(width = 1.5f))
        }
    }
}
