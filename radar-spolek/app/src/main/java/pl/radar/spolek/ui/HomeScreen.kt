package pl.radar.spolek.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import pl.radar.spolek.domain.CriterionDiagnosis
import pl.radar.spolek.domain.HintUnit
import pl.radar.spolek.domain.Recommendation
import pl.radar.spolek.domain.ThresholdHint
import pl.radar.spolek.ui.theme.Loss
import pl.radar.spolek.domain.ScreenResult
import pl.radar.spolek.ui.theme.Gain

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val state by vm.home.collectAsState()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Radar Spółek") },
                actions = {
                    IconButton(onClick = { searching = !searching }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Szukaj spółki")
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Ustawienia") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (searching) item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '.' || c == '-' } },
                    label = { Text("Ticker, np. AAPL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onOpen(query) }),
                    trailingIcon = {
                        IconButton(onClick = { if (query.isNotBlank()) onOpen(query) }) { Icon(Icons.Filled.Search, "Pokaż") }
                    },
                )
            }
            item { RunCard(state, vm::runNow) }
            state.result?.let { r ->
                item { FunnelCard(r) }
                if (r.recommendations.isEmpty()) item {
                    EmptyCard(r) { h ->
                        vm.applyHint(h)
                        Toast.makeText(ctx, "Ustawiono: ${h.label}. Uruchom skanowanie ponownie.", Toast.LENGTH_LONG).show()
                    }
                }
                else {
                    item {
                        Text("Rekomendacje (${r.recommendations.size})", style = MaterialTheme.typography.titleMedium)
                    }
                    items(r.recommendations, key = { it.symbol }) { RecommendationCard(it) { onOpen(it.symbol) } }
                }
            }
            item {
                Text(
                    "Aplikacja jest narzędziem do filtrowania spółek według zdefiniowanych reguł technicznych i " +
                        "wskaźnikowych. Nie stanowi rekomendacji w rozumieniu przepisów prawa ani porady inwestycyjnej. " +
                        "Dane pochodzą z nieoficjalnych źródeł i mogą być opóźnione lub niekompletne.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RunCard(state: HomeState, onRun: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val r = state.result
            Text(
                if (r == null) "Brak wyników – uruchom pierwsze skanowanie."
                else "Ostatnie skanowanie: ${Fmt.dateTime(r.timestamp)}\nDane do sesji: ${Fmt.day(r.lastSessionDate)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.running) {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Text(state.progressMsg, style = MaterialTheme.typography.bodySmall)
            } else {
                Button(onClick = onRun, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Skanuj teraz")
                }
                Text(
                    "Automatyczne skanowanie: co sobotę rano (do zmiany w ustawieniach). Skan całego rynku USA trwa kilka minut – najlepiej przez Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.error?.let { Text("Błąd: $it", color = MaterialTheme.colorScheme.error) }
            r?.warnings?.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun FunnelCard(r: ScreenResult) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Lejek kryteriów", style = MaterialTheme.typography.titleSmall)
            FunnelRow("Spółki z notowaniami", "${r.withData} / ${r.universeSize}")
            FunnelRow("Branże w trendzie 10 dni", "${r.groupsPassed.size} / ${r.groupsChecked}")
            FunnelRow("…spółki w trendzie 6 mies.", "${r.trendPassed}")
            FunnelRow("…z odbiciem od szczytu", "${r.bouncePassed}")
            FunnelRow("…z P/E ≤ mediana branży", "${r.pePassed}")
        }
    }
}

@Composable
private fun FunnelRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmptyCard(r: ScreenResult, onApply: (ThresholdHint) -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Brak spółek spełniających wszystkie kryteria", style = MaterialTheme.typography.titleSmall)
            Text(
                "Poniżej status każdego kryterium. Przy każdym progu podana jest wartość, przy której pojawiłaby się " +
                    "co najmniej jedna spółka, jeśli zmienisz tylko ten jeden parametr.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (r.diagnosis.isEmpty()) {
                Text("Uruchom skanowanie ponownie, aby zobaczyć diagnozę progów.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    r.diagnosis.forEach { d -> CriterionCard(d, r, onApply) }
}

@Composable
private fun CriterionCard(d: CriterionDiagnosis, r: ScreenResult, onApply: (ThresholdHint) -> Unit) {
    val ok = d.passedAlone > 0
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ok) "✓" else "✗",
                    color = if (ok) Gain else Loss,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text("${d.number}. ${d.title}", style = MaterialTheme.typography.titleSmall)
            }
            val alone = when {
                d.number == 1 && ok -> "Spełnia ${d.passedAlone} z ${d.outOf} spółek (${r.groupsPassed.size} branż)."
                d.number == 3 -> if (d.outOf == 0) "Brak danych P/E do oceny." else "Spełnia ${d.passedAlone} z ${d.outOf} spółek z pobranym P/E."
                ok -> "Spełnia ${d.passedAlone} z ${d.outOf} spółek (niezależnie od pozostałych kryteriów)."
                else -> "Nie spełnia żadna z ${d.outOf} spółek."
            }
            Text(alone, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (d.blockedOnlyByThis > 0) "Blokuje ${d.blockedOnlyByThis} spółek, które spełniają pozostałe trzy kryteria."
                else "Żadna spółka nie odpada wyłącznie na tym kryterium.",
                style = MaterialTheme.typography.bodySmall,
                color = if (d.blockedOnlyByThis > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            d.note?.let { Text("⚠ $it", style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            d.hints.forEach { h -> HintRow(h, onApply) }
        }
    }
}

private fun ThresholdHint.fmt(v: Double): String = when (unit) {
    HintUnit.PERCENT -> Fmt.num(rounded(v) * 100, 1) + "%"
    HintUnit.DECIMAL -> Fmt.num(rounded(v), 2)
    HintUnit.COUNT -> rounded(v).toLong().toString()
}

@Composable
private fun HintRow(h: ThresholdHint, onApply: (ThresholdHint) -> Unit) {
    val req = h.required
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(h.label, style = MaterialTheme.typography.bodyMedium)
            val currentTxt = when (h.unit) {
                HintUnit.PERCENT -> Fmt.num(h.current * 100, 1) + "%"
                HintUnit.DECIMAL -> Fmt.num(h.current, 2)
                HintUnit.COUNT -> h.current.toLong().toString()
            }
            if (req != null) {
                Text(
                    "obecnie $currentTxt → wystarczy ${h.fmt(req)}" + (h.example?.let { " (np. $it)" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Text(
                    "obecnie $currentTxt – sama zmiana tego progu nie da wyniku",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                h.aloneRequired?.let {
                    Text(
                        "kryterium spełniłaby choć jedna spółka przy ${h.fmt(it)}" + (h.aloneExample?.let { s -> " (np. $s)" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (req != null) {
            TextButton(onClick = { onApply(h) }) { Text("Ustaw") }
        }
    }
}

@Composable
private fun RecommendationCard(r: Recommendation, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.symbol, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(r.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Fmt.price(r.lastClose), style = MaterialTheme.typography.titleMedium)
                    Text("6M ${Fmt.pct(r.trendReturn)}", color = Gain, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(r.group, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("P/E", Fmt.num(r.pe))
                Stat("Mediana P/E", Fmt.num(r.groupMedianPe))
                Stat("Odbicie", Fmt.pct(r.bouncePct))
                Stat("R² trendu", Fmt.num(r.trendR2, 2))
            }
        }
    }
}

@Composable
fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
