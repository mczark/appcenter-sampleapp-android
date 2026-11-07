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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.radar.spolek.domain.GroupMeter

private enum class MeterLevel(val label: String) { INDUSTRY("Branże"), SECTOR("Sektory") }

private enum class MeterSort(val label: String) {
    TODAY("Najsilniejsze w ostatniej sesji"),
    GREEN_DAYS("Najwięcej zielonych sesji"),
    NAME("Nazwa"),
}

/** Udział rosnących wśród spółek, które zmieniły cenę, w ostatniej sesji (0–1). */
private fun GroupMeter.lastShare(): Double {
    val d = days.lastOrNull() ?: return 0.5
    val moved = d.advancers + d.decliners
    return if (moved == 0) 0.5 else d.advancers.toDouble() / moved
}

private fun GroupMeter.greenDays() = days.count { it.net > 0 }

/** Drill-down trendomierza: osobny trendomierz dla każdej branży (lub sektora). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendMeterScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.home.collectAsState()
    val r = state.result
    var level by remember { mutableStateOf(MeterLevel.INDUSTRY) }
    var sort by remember { mutableStateOf(MeterSort.TODAY) }
    var query by remember { mutableStateOf("") }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()

    val all = when (level) { MeterLevel.INDUSTRY -> r?.industryMeters; MeterLevel.SECTOR -> r?.sectorMeters }.orEmpty()
        .filter { it.days.isNotEmpty() }
    val shown = all
        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        .let { list ->
            when (sort) {
                MeterSort.TODAY -> list.sortedWith(compareByDescending<GroupMeter> { it.lastShare() }.thenByDescending { it.size })
                MeterSort.GREEN_DAYS -> list.sortedWith(compareByDescending<GroupMeter> { it.greenDays() }.thenByDescending { it.lastShare() })
                MeterSort.NAME -> list.sortedBy { it.name.lowercase() }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trendomierze ${level.label.lowercase()}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") } },
            )
        },
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (all.isEmpty()) {
                item {
                    Text(
                        "Brak danych – uruchom skanowanie w nowej wersji aplikacji, aby policzyć trendomierze branż.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                return@LazyColumn
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MeterLevel.entries.forEach { l ->
                            FilterChip(selected = level == l, onClick = { level = l }, label = { Text(l.label) })
                        }
                    }
                    MeterSort.entries.forEach { s ->
                        FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) })
                    }
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text("Szukaj ${if (level == MeterLevel.INDUSTRY) "branży" else "sektora"}") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    val green = all.count { it.days.last().net > 0 }
                    val red = all.count { it.days.last().net < 0 }
                    Text(
                        "Ostatnia sesja (${Fmt.day(all.first().days.last().epochDay)}): $green zielonych, $red czerwonych " +
                            "z ${all.size}. Dotknij, by rozwinąć wykres.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(shown, key = { "${level.name}:${it.name}" }) { g ->
                GroupMeterRow(g, expanded[g.name] == true) { expanded[g.name] = expanded[g.name] != true }
            }
        }
    }
}

@Composable
private fun GroupMeterRow(g: GroupMeter, isExpanded: Boolean, onToggle: () -> Unit) {
    val neutral = MaterialTheme.colorScheme.outline
    val last = g.days.last()
    var selected by remember(g) { mutableStateOf(g.days.size - 1) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Lamp(meterColor(last, neutral), diameter = 24.dp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(g.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(
                        "${g.size} spółek · ostatnio ▲ ${last.advancers} ▼ ${last.decliners}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${g.greenDays()}/${g.days.size}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("zielonych", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!isExpanded) {
                MeterChart(g.days, selected = null, height = 48.dp, showAxis = false)
            } else {
                val d = g.days[selected.coerceIn(g.days.indices)]
                Text(
                    "${Fmt.day(d.epochDay)}:  ▲ ${d.advancers}   ▼ ${d.decliners}   bez zmian ${d.unchanged}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = meterColor(d, MaterialTheme.colorScheme.onSurface),
                )
                MeterChart(g.days, selected, onSelect = { selected = it }, height = 160.dp)
                Text(
                    "Słupek = spółki rosnące − spadające w tej grupie. Dotknij nagłówka, by zwinąć.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
