package pl.mczark.invoicescanner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.mczark.invoicescanner.UiState
import pl.mczark.invoicescanner.data.Invoice
import pl.mczark.invoicescanner.data.PaymentStatus
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoiceListScreen(
    state: UiState,
    onScan: () -> Unit,
    onOpen: (Invoice) -> Unit,
    onClearAll: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Faktury do zapłaty")
                        state.accountEmail?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                actions = {
                    if (state.accountEmail != null) {
                        IconButton(onClick = onScan, enabled = !state.scanning) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Skanuj skrzynkę")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Więcej")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Wyczyść zapisane dane") },
                                    onClick = {
                                        menuOpen = false
                                        confirmClear = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.scanning) ScanProgressBar(state)

            if (state.accountEmail == null && state.invoices.isEmpty()) {
                ConnectGmail(scanning = state.scanning, onConnect = onScan)
                return@Column
            }

            val toPay = state.invoices.filter { it.status == PaymentStatus.TO_PAY }
            val paid = state.invoices.filter { it.status == PaymentStatus.PAID }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Do zapłaty (${toPay.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Opłacone (${paid.size})") })
            }

            val shown = if (tab == 0) toPay else paid
            if (shown.isEmpty()) {
                EmptyList(if (tab == 0) "Brak faktur do zapłaty" else "Brak opłaconych faktur")
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (tab == 0) item { TotalCard(toPay) }
                    items(shown, key = { it.messageId }) { invoice ->
                        InvoiceRow(invoice, onClick = { onOpen(invoice) })
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Wyczyścić dane?") },
            text = {
                Text(
                    "Usunie to zapisane faktury z telefonu. Dostęp aplikacji do Gmaila możesz cofnąć " +
                        "w ustawieniach konta Google (Bezpieczeństwo → Aplikacje innych firm)."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearAll()
                }) { Text("Wyczyść") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Anuluj") } },
        )
    }
}

@Composable
private fun ScanProgressBar(state: UiState) {
    val progress = state.progress
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (progress == null || progress.total == 0) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Łączenie z Gmailem…", style = MaterialTheme.typography.bodySmall)
        } else {
            LinearProgressIndicator(
                progress = { progress.processed.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Analizuję wiadomości: ${progress.processed}/${progress.total}, znalezione faktury: ${progress.found}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ConnectGmail(scanning: Boolean, onConnect: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Mail, contentDescription = null, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text("Znajdź faktury w swoim Gmailu", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Aplikacja przeszuka wiadomości z ostatnich 90 dni, odczyta załączone faktury (PDF i skany) " +
                "i pokaże dane potrzebne do przelewu. Dostęp jest tylko do odczytu, a dane zostają w telefonie.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onConnect, enabled = !scanning) { Text("Połącz z Gmail") }
    }
}

@Composable
private fun EmptyList(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.AutoMirrored.Outlined.ReceiptLong, contentDescription = null, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(8.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun TotalCard(invoices: List<Invoice>) {
    val totals = invoices
        .filter { it.amountValue != null }
        .groupBy { it.currency }
        .mapValues { (_, list) -> list.fold(BigDecimal.ZERO) { sum, inv -> sum + inv.amountValue!! } }
    if (totals.isEmpty()) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Łącznie do zapłaty", style = MaterialTheme.typography.labelLarge)
            totals.forEach { (currency, sum) ->
                Text(formatAmount(sum, currency), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun InvoiceRow(invoice: Invoice, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    invoice.recipientName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    formatAmount(invoice.amountValue, invoice.currency),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                invoice.transferTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dueColor = if (invoice.isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    when {
                        invoice.dueDateValue == null -> "Brak terminu płatności"
                        invoice.isOverdue -> "Po terminie: ${formatDate(invoice.dueDateValue)}"
                        else -> "Termin: ${formatDate(invoice.dueDateValue)}"
                    },
                    color = dueColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (!invoice.isComplete) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        when {
                            invoice.accountNumber == null -> " brak nr konta"
                            !invoice.accountVerified -> " sprawdź nr konta"
                            else -> " brak kwoty"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}
