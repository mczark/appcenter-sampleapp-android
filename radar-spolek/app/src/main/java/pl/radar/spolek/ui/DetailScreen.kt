package pl.radar.spolek.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.radar.spolek.domain.Analysis
import pl.radar.spolek.ui.theme.Gain
import pl.radar.spolek.ui.theme.Loss

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: MainViewModel, symbol: String, onBack: () -> Unit) {
    LaunchedEffect(symbol) { vm.loadDetail(symbol) }
    val s by vm.detail.collectAsState()
    var selected by remember(symbol) { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(s.symbol.ifEmpty { symbol })
                        if (s.name.isNotEmpty()) Text(s.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") }
                },
            )
        },
    ) { pad ->
        when {
            s.loading -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            s.error != null -> Box(Modifier.fillMaxSize().padding(pad).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(s.error ?: "", color = MaterialTheme.colorScheme.error)
            }
            else -> {
                val all = s.bars
                val window = all.drop(s.chartStart)
                val smaAll = remember(all) { Analysis.sma(all.map { it.close }, 50) }
                val sma = smaAll.drop(s.chartStart)
                val last = all.last()
                val prev = all.getOrNull(all.size - 2)
                val change = if (prev != null) last.close / prev.close - 1 else 0.0
                val rec = s.recommendation
                val b = s.bounce

                val levels = buildList {
                    val peak = rec?.peakPrice ?: b?.peakPrice
                    if (peak != null) add(ChartLevel(peak, Color(0xFFE08A00), "szczyt ${Fmt.num(peak, 2)}"))
                }
                val markers = buildList {
                    if (b != null) {
                        val idx = b.retestIndex - s.chartStart
                        add(ChartMarker(idx, b.retestLow, Color(0xFF7A3FE0)))
                    }
                }

                Column(
                    Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(Fmt.price(last.close), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "  ${Fmt.pct(change)}  (${Fmt.day(last.epochDay)})",
                            color = if (change >= 0) Gain else Loss,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Stat("P/E (TTM)", s.pe?.let { Fmt.num(it) } ?: "—")
                            Stat("Mediana P/E branży", rec?.let { Fmt.num(it.groupMedianPe) } ?: "—")
                            Stat("Zmiana 6M", s.trend?.let { Fmt.pct(it.periodReturn) } ?: "—")
                        }
                    }

                    // informacja o wskazanej świecy
                    val sel = selected?.let { window.getOrNull(it) }
                    Text(
                        if (sel == null) "Wykres dzienny – ostatnie 6 miesięcy. Dotknij lub przeciągnij, by zobaczyć notowania dnia."
                        else "${Fmt.day(sel.epochDay)}  O ${Fmt.num(sel.open, 2)}  H ${Fmt.num(sel.high, 2)}  " +
                            "L ${Fmt.num(sel.low, 2)}  C ${Fmt.num(sel.close, 2)}  Vol ${Fmt.volume(sel.volume)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    CandleChart(window, sma, levels, markers, selected, onSelect = { selected = it })
                    Text(
                        "Linia ciągła: SMA50 · przerywana: poziom szczytu · okrąg: dołek korekty (test szczytu)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Kryteria", style = MaterialTheme.typography.titleSmall)
                            if (rec != null) {
                                Check(true, "Branża w trendzie 10 dni", rec.group)
                            } else {
                                Check(null, "Branża w trendzie 10 dni", "oceniane tylko podczas skanowania")
                            }
                            val t = s.trend
                            Check(
                                t?.passed, "Trend wzrostowy 6 mies.",
                                t?.let {
                                    "R² ${Fmt.num(it.r2, 2)}, zmiana ${Fmt.pct(it.periodReturn)}, " +
                                        (if (it.higherHigh && it.higherLow) "wyższe szczyty i dołki" else "brak wyższych szczytów/dołków")
                                } ?: "za mało danych",
                            )
                            Check(
                                b != null, "Odbicie po teście szczytu",
                                b?.let { "szczyt ${Fmt.num(it.peakPrice, 2)} z ${Fmt.day(all[it.peakIndex].epochDay)}, " +
                                    "dołek ${Fmt.num(it.retestLow, 2)} (${Fmt.day(all[it.retestIndex].epochDay)}), odbicie ${Fmt.pct(it.bouncePct)}" }
                                    ?: "nie wykryto formacji w ostatnim miesiącu",
                            )
                            if (rec != null) {
                                Check(rec.pe <= rec.groupMedianPe, "P/E ≤ mediana branży", "${Fmt.num(rec.pe)} vs ${Fmt.num(rec.groupMedianPe)}")
                            } else {
                                Check(null, "P/E ≤ mediana branży", "mediana liczona podczas skanowania")
                            }
                        }
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun Check(ok: Boolean?, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            when (ok) { true -> "✓"; false -> "✗"; null -> "–" },
            color = when (ok) { true -> Gain; false -> Loss; null -> MaterialTheme.colorScheme.onSurfaceVariant },
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
