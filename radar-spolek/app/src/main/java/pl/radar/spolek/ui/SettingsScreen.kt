package pl.radar.spolek.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import pl.radar.spolek.data.AppSettings
import pl.radar.spolek.domain.GroupingLevel
import pl.radar.spolek.domain.ScreenerConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val initial = remember { vm.settings.value }
    var weekly by remember { mutableStateOf(initial.weeklyEnabled) }
    var fmpKey by remember { mutableStateOf(initial.fmpKey) }
    var grouping by remember { mutableStateOf(initial.config.grouping) }
    // wartości liczbowe edytujemy jako tekst; procenty pokazujemy w %
    val f = remember { mutableStateOf(fieldsFrom(initial.config)) }
    var fields by f

    fun update(key: String, v: String) { fields = fields + (key to v) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ustawienia") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Skanowanie co tydzień", style = MaterialTheme.typography.titleSmall)
                    Text("Sobota ok. 9:00, z powiadomieniem o wynikach", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = weekly, onCheckedChange = { weekly = it })
            }
            HorizontalDivider()

            Section("1. Branża w trendzie wzrostowym")
            Text("Poziom grupowania branż", style = MaterialTheme.typography.bodyMedium)
            GroupingLevel.entries.forEach { g ->
                FilterChip(selected = grouping == g, onClick = { grouping = g }, label = { Text(g.label) })
            }
            NumField("Min. liczba spółek w branży", fields, "minGroupSize", ::update)
            NumField("Liczba ostatnich sesji", fields, "breadthDays", ::update)
            NumField("Min. % rosnących spółek każdego dnia", fields, "breadthThreshold", ::update)

            Section("2. Trend spółki")
            NumField("Długość trendu (sesje; 126 ≈ 6 mies.)", fields, "trendSessions", ::update)
            NumField("Min. R² regresji (0–1)", fields, "minTrendR2", ::update)
            NumField("Min. wzrost w okresie (%)", fields, "minTrendReturn", ::update)

            Section("3. Wycena")
            NumField("Maks. P/E jako % mediany branży (100 = mediana)", fields, "maxPeToMedian", ::update)

            Section("4. Odbicie od szczytu")
            NumField("Szczyt nie starszy niż (sesje; 22 ≈ 1 mies.)", fields, "peakLookback", ::update)
            NumField("Siła szczytu (sesje z każdej strony)", fields, "pivotStrength", ::update)
            NumField("Min. cofnięcie po szczycie, by był wyraźny (%)", fields, "minPeakProminence", ::update)
            NumField("Tolerancja testu poziomu szczytu (±%)", fields, "retestTolerance", ::update)
            NumField("Min. głębokość korekty (%)", fields, "minCorrection", ::update)
            NumField("Min. odbicie od dołka (%)", fields, "minBounce", ::update)
            NumField("Dołek korekty nie starszy niż (sesje)", fields, "maxDaysSinceRetest", ::update)

            Section("Wyniki i dane")
            NumField("Maks. liczba spółek", fields, "maxResults", ::update)
            OutlinedTextField(
                value = fmpKey, onValueChange = { fmpKey = it.trim() },
                label = { Text("Klucz API Financial Modeling Prep (opcjonalny)") },
                supportingText = { Text("Zapasowe źródło P/E, gdy Yahoo nie zwróci danych") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    fields = fieldsFrom(ScreenerConfig()); grouping = ScreenerConfig().grouping
                }) { Text("Przywróć domyślne") }
                Button(onClick = {
                    val cfg = configFrom(fields, grouping)
                    vm.saveSettings(AppSettings(cfg, weekly, fmpKey))
                    onBack()
                }) { Text("Zapisz") }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun NumField(label: String, fields: Map<String, String>, key: String, onChange: (String, String) -> Unit) {
    val v = fields[key].orEmpty()
    OutlinedTextField(
        value = v,
        onValueChange = { onChange(key, it.replace(',', '.').filter { c -> c.isDigit() || c == '.' }) },
        label = { Text(label) },
        singleLine = true,
        isError = v.toDoubleOrNull() == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

private val percentKeys = setOf("breadthThreshold", "minTrendReturn", "maxPeToMedian", "minPeakProminence", "retestTolerance", "minCorrection", "minBounce")

private fun trimNum(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else "%.2f".format(java.util.Locale.US, d).trimEnd('0').trimEnd('.')

private fun fieldsFrom(c: ScreenerConfig): Map<String, String> {
    fun p(d: Double) = trimNum(d * 100)
    return mapOf(
        "minGroupSize" to c.minGroupSize.toString(),
        "breadthDays" to c.breadthDays.toString(),
        "breadthThreshold" to p(c.breadthThreshold),
        "trendSessions" to c.trendSessions.toString(),
        "minTrendR2" to trimNum(c.minTrendR2),
        "minTrendReturn" to p(c.minTrendReturn),
        "maxPeToMedian" to p(c.maxPeToMedian),
        "peakLookback" to c.peakLookback.toString(),
        "pivotStrength" to c.pivotStrength.toString(),
        "minPeakProminence" to p(c.minPeakProminence),
        "retestTolerance" to p(c.retestTolerance),
        "minCorrection" to p(c.minCorrection),
        "minBounce" to p(c.minBounce),
        "maxDaysSinceRetest" to c.maxDaysSinceRetest.toString(),
        "maxResults" to c.maxResults.toString(),
    )
}

private fun configFrom(m: Map<String, String>, grouping: GroupingLevel): ScreenerConfig {
    val d = ScreenerConfig()
    fun dbl(k: String, def: Double): Double {
        val v = m[k]?.toDoubleOrNull() ?: return def
        return if (k in percentKeys) v / 100.0 else v
    }
    fun int(k: String, def: Int, min: Int, max: Int) = (m[k]?.toDoubleOrNull()?.toInt() ?: def).coerceIn(min, max)
    return ScreenerConfig(
        grouping = grouping,
        minGroupSize = int("minGroupSize", d.minGroupSize, 1, 100),
        breadthDays = int("breadthDays", d.breadthDays, 1, 60),
        breadthThreshold = dbl("breadthThreshold", d.breadthThreshold).coerceIn(0.0, 1.0),
        trendSessions = int("trendSessions", d.trendSessions, 40, 240),
        minTrendR2 = dbl("minTrendR2", d.minTrendR2).coerceIn(0.0, 1.0),
        minTrendReturn = dbl("minTrendReturn", d.minTrendReturn),
        maxPeToMedian = dbl("maxPeToMedian", d.maxPeToMedian).coerceIn(0.1, 10.0),
        peakLookback = int("peakLookback", d.peakLookback, 5, 120),
        pivotStrength = int("pivotStrength", d.pivotStrength, 1, 10),
        minPeakProminence = dbl("minPeakProminence", d.minPeakProminence),
        retestTolerance = dbl("retestTolerance", d.retestTolerance),
        minCorrection = dbl("minCorrection", d.minCorrection),
        minBounce = dbl("minBounce", d.minBounce),
        maxDaysSinceRetest = int("maxDaysSinceRetest", d.maxDaysSinceRetest, 1, 60),
        maxResults = int("maxResults", d.maxResults, 1, 50),
    )
}
