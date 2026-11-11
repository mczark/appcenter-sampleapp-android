package pl.radar.spolek.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.radar.spolek.domain.Analysis
import pl.radar.spolek.domain.Bar
import pl.radar.spolek.ui.theme.Gain
import pl.radar.spolek.ui.theme.Loss
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private enum class IndexRange(val label: String, val sessions: Int) {
    M1("1M", 21), M3("3M", 63), M6("6M", 126), Y1("1R", 260),
}

/** Karta z wykresem S&P 500 / Russell 2000: przełącznik indeksu, zakres, zmiana i SMA50. */
@Composable
fun IndexChartCard(vm: MainViewModel) {
    val all by vm.indices.collectAsState()
    var index by rememberSaveable { mutableStateOf(MarketIndex.SP500) }
    var range by rememberSaveable { mutableStateOf(IndexRange.M3) }
    val st = all.getValue(index)
    val bars = st.bars
    val shown = bars.takeLast(range.sessions)
    val smaAll = remember(bars) { Analysis.sma(bars.map { it.close }, 50) }
    val sma = smaAll.takeLast(shown.size)
    var selected by remember(index, range, bars) { mutableStateOf<Int?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MarketIndex.entries.forEach { i ->
                        FilterChip(selected = index == i, onClick = { index = i }, label = { Text(i.label) })
                    }
                }
                if (st.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { vm.loadIndices(force = true) }) { Icon(Icons.Filled.Refresh, "Odśwież") }
            }

            if (shown.size >= 2) {
                val last = shown.last()
                val prev = bars.getOrNull(bars.size - 2)
                val day = if (prev != null) last.close / prev.close - 1 else 0.0
                val period = last.close / shown.first().close - 1
                val sel = selected?.let { shown.getOrNull(it) }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Fmt.num(sel?.close ?: last.close, 2), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (sel != null) "  ${Fmt.day(sel.epochDay)}  (${Fmt.pct(sel.close / shown.first().close - 1)} od początku zakresu)"
                        else "  ${Fmt.pct(day)} dziś · ${Fmt.pct(period)} w ${range.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if ((sel?.close ?: last.close) >= shown.first().close) Gain else Loss,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
                LineChart(shown, sma, selected) { selected = it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IndexRange.entries.forEach { r ->
                            FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) })
                        }
                    }
                }
                Text(
                    "Notowania dzienne z Yahoo Finance (do ok. 15 min opóźnienia) · ostatnia sesja ${Fmt.day(last.epochDay)}. " +
                        "Linia przerywana: średnia 50-sesyjna.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            st.loading -> "Pobieranie notowań ${index.label}…"
                            st.error != null -> "Nie udało się pobrać notowań: ${st.error}"
                            else -> "Brak danych"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun LineChart(bars: List<Bar>, sma: List<Double?>, selected: Int?, onSelect: (Int?) -> Unit) {
    val up = bars.last().close >= bars.first().close
    val lineColor = if (up) Gain else Loss
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val smaColor = MaterialTheme.colorScheme.primary
    val labelW = 52.dp
    fun idx(x: Float, w: Float) = ((x / w) * (bars.size - 1) + 0.5f).toInt().coerceIn(0, bars.size - 1)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .pointerInput(bars) {
                detectTapGestures { o ->
                    val w = size.width - labelW.toPx()
                    onSelect(if (o.x > w) null else idx(o.x, w))
                }
            }
            .pointerInput(bars) {
                detectDragGestures(onDragEnd = { onSelect(null) }) { ch, _ ->
                    val w = size.width - labelW.toPx()
                    onSelect(idx(ch.position.x.coerceIn(0f, w), w))
                }
            },
    ) {
        val w = size.width - labelW.toPx()
        val h = size.height - 18.dp.toPx()
        var lo = bars.minOf { it.close }; var hi = bars.maxOf { it.close }
        sma.filterNotNull().forEach { lo = minOf(lo, it); hi = maxOf(hi, it) }
        val pad = (hi - lo) * 0.08 + 1e-9
        lo -= pad; hi += pad
        fun x(i: Int) = if (bars.size == 1) w / 2 else w * i / (bars.size - 1)
        fun y(v: Double) = (h * (1 - (v - lo) / (hi - lo))).toFloat()
        val text = Paint().apply { color = axis.toArgb(); textSize = 10.dp.toPx(); isAntiAlias = true }

        for (t in 0..4) {
            val v = lo + (hi - lo) * t / 4
            drawLine(grid, Offset(0f, y(v)), Offset(w, y(v)), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(Fmt.num(v, 0), w + 6.dp.toPx(), y(v) + 4.dp.toPx(), text)
        }
        var prevMonth = -1
        bars.forEachIndexed { i, b ->
            val d = LocalDate.ofEpochDay(b.epochDay)
            if (d.monthValue != prevMonth) {
                if (prevMonth != -1) {
                    drawLine(grid, Offset(x(i), 0f), Offset(x(i), h), strokeWidth = 1f)
                    drawContext.canvas.nativeCanvas.drawText(
                        d.month.getDisplayName(TextStyle.SHORT, Locale("pl")), x(i) + 3.dp.toPx(), h + 14.dp.toPx(), text,
                    )
                }
                prevMonth = d.monthValue
            }
        }
        // wypełnienie pod linią
        val line = Path()
        bars.forEachIndexed { i, b -> if (i == 0) line.moveTo(x(i), y(b.close)) else line.lineTo(x(i), y(b.close)) }
        val area = Path().apply { addPath(line); lineTo(x(bars.size - 1), h); lineTo(x(0), h); close() }
        drawPath(area, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.25f), Color.Transparent), startY = 0f, endY = h))
        drawPath(line, lineColor, style = Stroke(width = 2.5f))
        // SMA50
        val sp = Path(); var started = false
        sma.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            if (!started) { sp.moveTo(x(i), y(v)); started = true } else sp.lineTo(x(i), y(v))
        }
        if (started) drawPath(sp, smaColor.copy(alpha = 0.7f), style = Stroke(width = 1.8f,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
        // zaznaczenie
        selected?.takeIf { it in bars.indices }?.let { i ->
            drawLine(axis.copy(alpha = 0.7f), Offset(x(i), 0f), Offset(x(i), h), strokeWidth = 1.5f)
            drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(x(i), y(bars[i].close)))
        }
    }
}
