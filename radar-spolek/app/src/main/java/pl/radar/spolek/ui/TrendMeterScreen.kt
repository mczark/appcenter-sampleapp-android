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
    GREEN_2W("Najwięcej zielonych sesji w 2 tygodnie"),
    TODAY("Najsilniejsze w ostatniej sesji"),
    GREEN_DAYS("Najwięcej zielonych sesji w 2 miesiące"),
    NAME("Nazwa"),
}

/** Odsetek spółek rosnących w ostatniej sesji (0–1). */
private fun GroupMeter.lastShare(): Double = days.lastOrNull()?.advancingShare ?: 0.0

private fun GroupMeter.greenDays() = days.count { it.isGreen }

/** Ostatnie 2 tygodnie = 10 sesji. */
private const val TWO_WEEKS = 10
private fun GroupMeter.green2w() = days.takeLast(TWO_WEEKS).count { it.isGreen }

/** Drill-down trendomierza: osobny trendomierz dla każdej branży (lub sektora). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendMeterScreen(vm: MainViewModel, onBack: () -> Unit, onOpenGroup: (level: String, name: String) -> Unit) {
    val state by vm.home.collectAsState()
    val r = state.result
    var level by remember { mutableStateOf(MeterLevel.INDUSTRY) }
    var sort by remember { mutableStateOf(MeterSort.GREEN_2W) }
    var query by remember { mutableStateOf("") }
    val uptrendCount = remember(r, level) {
        r?.uptrendStocks.orEmpty().groupingBy { if (level == MeterLevel.SECTOR) it.sector else it.industry }.eachCount()
    }
    val listState = rememberLazyListState()

    val all = when (level) { MeterLevel.INDUSTRY -> r?.industryMeters; MeterLevel.SECTOR -> r?.sectorMeters }.orEmpty()
        .filter { it.days.isNotEmpty() }
    val shown = all
        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        .let { list ->
            when (sort) {
                MeterSort.GREEN_2W -> list.sortedWith(
                    compareByDescending<GroupMeter> { it.green2w() }.thenByDescending { it.greenDays() }.thenByDescending { it.lastShare() },
                )
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
                    val green = all.count { it.days.last().isGreen }
                    val red = all.count { it.days.last().isRed }
                    Text(
                        "Ostatnia sesja (${Fmt.day(all.first().days.last().epochDay)}): $green zielonych, $red czerwonych " +
                            "z ${all.size}. Dotknij grupy, by zobaczyć jej spółki w trendzie wzrostowym.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(shown, key = { "${level.name}:${it.name}" }) { g ->
                GroupMeterRow(g, uptrendCount[g.name] ?: 0) {
                    onOpenGroup(if (level == MeterLevel.SECTOR) "sector" else "industry", g.name)
                }
            }
        }
    }
}

@Composable
private fun GroupMeterRow(g: GroupMeter, uptrend: Int, onOpen: () -> Unit) {
    val neutral = MaterialTheme.colorScheme.outline
    val last = g.days.last()
    val recent = g.days.takeLast(TWO_WEEKS)
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Lamp(meterColor(last, neutral), diameter = 24.dp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(g.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(
                        "${g.size} spółek · w trendzie 6 mies.: $uptrend · ostatnio rosło ${Fmt.pct(last.advancingShare ?: 0.0, false)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${g.green2w()}/${recent.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = if (g.green2w() * 2 > recent.size) pl.radar.spolek.ui.theme.Gain else MaterialTheme.colorScheme.onSurface)
                    Text("zielone 2 tyg.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp))
            }
            // miniatura: 2 miesiące, ostatnie 2 tygodnie wyróżnione
            MeterChart(g.days, selected = null, height = 48.dp, showAxis = false, highlightLast = TWO_WEEKS)
            Text(
                "2 miesiące: ${g.greenDays()}/${g.days.size} sesji zielonych",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
