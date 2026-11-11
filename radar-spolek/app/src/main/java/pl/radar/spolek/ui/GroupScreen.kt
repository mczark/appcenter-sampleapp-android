package pl.radar.spolek.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import pl.radar.spolek.domain.FundamentalScore
import pl.radar.spolek.domain.Metric
import pl.radar.spolek.domain.MetricKind
import pl.radar.spolek.domain.ScoredStock
import pl.radar.spolek.domain.TrendStock
import pl.radar.spolek.ui.theme.Gain
import pl.radar.spolek.ui.theme.Loss

private fun Metric.format(v: Double): String = when (kind) {
    MetricKind.MULTIPLE -> Fmt.num(v, if (this == Metric.PEG || this == Metric.DEBT_EQUITY) 2 else 1)
    MetricKind.PERCENT -> Fmt.pct(v, signed = this == Metric.REVENUE_GROWTH)
}

/** Kolor wartości wg pozycji w grupie: górna tercja zielona, dolna czerwona. */
@Composable
private fun percentileColor(p: Double?): Color = when {
    p == null -> MaterialTheme.colorScheme.onSurface
    p >= 2.0 / 3 -> Gain
    p <= 1.0 / 3 -> Loss
    else -> MaterialTheme.colorScheme.onSurface
}

/** Szczegóły branży lub sektora: trendomierz grupy i spółki w trendzie wzrostowym od pół roku z oceną fundamentalną. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(vm: MainViewModel, level: String, name: String, onBack: () -> Unit, onOpenStock: (String) -> Unit) {
    LaunchedEffect(level, name) { vm.loadGroup(level, name) }
    val g by vm.group.collectAsState()
    val settings by vm.settings.collectAsState()
    val cfg = settings.config
    val stocksBySymbol = remember(g.stocks) { g.stocks.associateBy { it.symbol } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(name, maxLines = 1)
                        Text(if (level == "sector") "Sektor" else "Branża", style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") } },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            g.meter?.let { m -> if (m.days.isNotEmpty()) item { GroupMeterCard(m.days, m.size) } }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Spółki w wyraźnym trendzie wzrostowym od pół roku (${g.stocks.size})", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Trend: ${cfg.trendSessions} sesji, wzrost ≥ ${Fmt.pct(cfg.minTrendReturn, false)}, R² ≥ ${Fmt.num(cfg.minTrendR2, 2)}, " +
                            "wyższe szczyty i dołki. Kolejność według łącznej oceny wskaźników fundamentalnych.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (g.stocks.isEmpty()) {
                item { Text("Żadna spółka z tej grupy nie jest obecnie w wyraźnym trendzie wzrostowym.", style = MaterialTheme.typography.bodyMedium) }
            }
            if (g.loading) item {
                Column {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(g.progress, style = MaterialTheme.typography.bodySmall)
                }
            }
            g.error?.let { e -> item { Text("⚠ $e", style = MaterialTheme.typography.bodySmall) } }
            itemsIndexed(g.scored, key = { _, s -> s.fundamentals.symbol }) { i, s ->
                stocksBySymbol[s.fundamentals.symbol]?.let { t -> StockFundamentalsCard(i + 1, t, s) { onOpenStock(t.symbol) } }
            }
            if (g.scored.isNotEmpty()) item {
                Text(
                    "Ocena 0–100 to średni percentyl wskaźników w tej grupie: dla każdego wskaźnika spółki są ustawiane " +
                        "od najlepszej (100) do najgorszej (0). Niżej = lepiej dla P/E, Fwd P/E, PEG, P/B i D/E " +
                        "(ujemne wartości, np. P/E przy stracie, liczą się jako najgorsze); wyżej = lepiej dla ROE, marży " +
                        "i wzrostu przychodów. Ocenę liczymy, gdy dostępne są co najmniej ${FundamentalScore.MIN_METRICS} wskaźniki. " +
                        "Kolor wartości: zielony – górna tercja grupy, czerwony – dolna. Dane: Yahoo Finance.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GroupMeterCard(days: List<pl.radar.spolek.domain.MarketBreadthDay>, size: Int) {
    val neutral = MaterialTheme.colorScheme.outline
    var selected by remember(days) { mutableStateOf(days.size - 1) }
    val d = days[selected.coerceIn(days.indices)]
    val green2w = days.takeLast(10).count { it.isGreen }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Lamp(meterColor(days.last(), neutral), diameter = 28.dp)
                Column(Modifier.padding(start = 10.dp)) {
                    Text("Trendomierz grupy ($size spółek)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Zielone sesje: $green2w/${minOf(10, days.size)} w 2 tyg., ${days.count { it.isGreen }}/${days.size} w 2 mies.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                "${Fmt.day(d.epochDay)}:  rosło ${Fmt.pct(d.advancingShare ?: 0.0, false)}  (▲ ${d.advancers}  ▼ ${d.decliners}  bez zmian ${d.unchanged})",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = meterColor(d, MaterialTheme.colorScheme.onSurface),
            )
            MeterChart(days, selected, onSelect = { selected = it }, height = 150.dp, highlightLast = 10)
        }
    }
}

@Composable
private fun StockFundamentalsCard(rank: Int, t: TrendStock, s: ScoredStock, onClick: () -> Unit) {
    val f = s.fundamentals
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#$rank", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(t.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Fmt.price(t.lastClose), style = MaterialTheme.typography.bodyMedium)
                    Text("6M ${Fmt.pct(t.periodReturn)} · R² ${Fmt.num(t.r2, 2)}", style = MaterialTheme.typography.labelSmall, color = Gain)
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(
                    s.score?.let { "Ocena fundamentalna: ${Fmt.num(it, 0)}/100 (z ${s.percentiles.size} wskaźników)" }
                        ?: "Za mało danych do oceny (${s.percentiles.size} wskaźników)",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider()
            Metric.entries.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { m ->
                        val v = m.get(f)
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(m.short, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                v?.let { m.format(it) } ?: "—",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = percentileColor(s.percentiles[m]),
                            )
                        }
                    }
                }
            }
        }
    }
}
