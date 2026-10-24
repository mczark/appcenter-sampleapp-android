package pl.mczark.invoicescanner.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.mczark.invoicescanner.data.Invoice
import pl.mczark.invoicescanner.data.PaymentStatus
import pl.mczark.invoicescanner.parser.Iban
import pl.mczark.invoicescanner.payment.QrCode
import pl.mczark.invoicescanner.payment.TransferQrPayload
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoiceDetailScreen(
    invoice: Invoice,
    onBack: () -> Unit,
    onSetStatus: (PaymentStatus) -> Unit,
    onSave: (Invoice) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun copy(label: String, value: String) {
        clipboard.setText(AnnotatedString(value))
        scope.launch { snackbar.showSnackbar("Skopiowano: $label") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dane do przelewu") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                    }
                },
                actions = {
                    IconButton(onClick = { editing = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Popraw dane")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Usuń")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(invoice)

            Card {
                Column(Modifier.padding(vertical = 8.dp)) {
                    CopyRow("Odbiorca", invoice.recipientName) { copy("odbiorca", it) }
                    HorizontalDivider()
                    CopyRow(
                        "Numer rachunku",
                        formatAccount(invoice.accountNumber),
                        copyValue = invoice.accountNumber?.let(Iban::domestic),
                        monospace = true,
                    ) { copy("numer rachunku", it) }
                    HorizontalDivider()
                    CopyRow(
                        "Kwota",
                        formatAmount(invoice.amountValue, invoice.currency),
                        copyValue = invoice.amountValue?.toPlainString()?.replace('.', ','),
                    ) { copy("kwota", it) }
                    HorizontalDivider()
                    CopyRow("Tytuł przelewu", invoice.transferTitle) { copy("tytuł", it) }
                    invoice.recipientNip?.let { nip ->
                        HorizontalDivider()
                        CopyRow("NIP odbiorcy", nip) { copy("NIP", it) }
                    }
                }
            }

            if (invoice.splitPayment) {
                Notice(
                    "Faktura oznaczona jako płatna w mechanizmie podzielonej płatności (MPP). " +
                        "W banku wybierz przelew MPP i podaj kwotę VAT z faktury."
                )
            }
            if (!invoice.isComplete) {
                Notice("Nie udało się odczytać wszystkich danych. Uzupełnij je przyciskiem edycji (ołówek).")
            }

            TransferQr(invoice)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { copy("dane do przelewu", invoice.transferText()) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Kopiuj wszystko")
                }
                OutlinedButton(onClick = { share(context, invoice) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Udostępnij")
                }
            }

            if (invoice.status == PaymentStatus.TO_PAY) {
                Button(onClick = { onSetStatus(PaymentStatus.PAID) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Oznacz jako opłaconą")
                }
            } else {
                OutlinedButton(onClick = { onSetStatus(PaymentStatus.TO_PAY) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Przywróć do zapłaty")
                }
            }

            EmailDetails(invoice, onOpenGmail = { openInGmail(context, invoice.messageId) })
        }
    }

    if (editing) {
        EditInvoiceDialog(
            invoice = invoice,
            onDismiss = { editing = false },
            onSave = {
                editing = false
                onSave(it)
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Usunąć fakturę z listy?") },
            text = { Text("Wiadomość nie zostanie ponownie dodana przy kolejnym skanowaniu.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Usuń") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Anuluj") } },
        )
    }
}

@Composable
private fun Header(invoice: Invoice) {
    Column {
        Text(
            formatAmount(invoice.amountValue, invoice.currency),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val due = invoice.dueDateValue
            Text(
                if (due == null) "Brak terminu płatności" else "Termin płatności: ${formatDate(due)}",
                color = if (invoice.isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        when {
                            invoice.status == PaymentStatus.PAID -> "Opłacona"
                            invoice.isOverdue -> "Po terminie"
                            else -> "Do zapłaty"
                        }
                    )
                },
            )
        }
    }
}

@Composable
private fun CopyRow(
    label: String,
    value: String,
    copyValue: String? = value,
    monospace: Boolean = false,
    onCopy: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (monospace) FontFamily.Monospace else null,
            )
        }
        if (copyValue != null) {
            IconButton(onClick = { onCopy(copyValue) }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Kopiuj: $label")
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TransferQr(invoice: Invoice) {
    val payload = remember(invoice) {
        invoice.accountNumber?.let {
            TransferQrPayload.build(
                iban = it,
                amount = invoice.amountValue,
                currency = invoice.currency,
                recipient = invoice.recipientName,
                title = invoice.transferTitle,
                nip = invoice.recipientNip,
            )
        }
    } ?: return
    val bitmap = remember(payload) { QrCode.encode(payload).asImageBitmap() }

    Card {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Kod QR do przelewu", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Image(bitmap, contentDescription = "Kod QR z danymi przelewu", modifier = Modifier.size(220.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                "Zeskanuj w aplikacji banku (opcja „Płatność kodem QR” / „Skanuj fakturę”).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun EmailDetails(invoice: Invoice, onOpenGmail: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Źródło", style = MaterialTheme.typography.titleMedium)
            invoice.invoiceNumber?.let { Text("Numer faktury: $it") }
            invoice.issueDate?.let { Text("Data wystawienia: ${formatDate(LocalDate.parse(it))}") }
            Text("Od: ${invoice.emailFrom}")
            Text("Temat: ${invoice.emailSubject}")
            Text("Dane odczytane z: ${invoice.source}")
            if (invoice.editedByUser) Text("Dane poprawione ręcznie", color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onOpenGmail) { Text("Otwórz wiadomość w Gmailu") }
        }
    }
}

@Composable
private fun EditInvoiceDialog(invoice: Invoice, onDismiss: () -> Unit, onSave: (Invoice) -> Unit) {
    var recipient by remember { mutableStateOf(invoice.recipientName) }
    var account by remember { mutableStateOf(invoice.accountNumber?.let(Iban::format).orEmpty()) }
    var amount by remember { mutableStateOf(invoice.amount?.replace('.', ',').orEmpty()) }
    var currency by remember { mutableStateOf(invoice.currency) }
    var title by remember { mutableStateOf(invoice.transferTitle) }
    var due by remember { mutableStateOf(invoice.dueDateValue?.let(::formatDate).orEmpty()) }

    val normalizedAccount = account.takeIf { it.isNotBlank() }?.let(Iban::normalize)
    val accountError = account.isNotBlank() && normalizedAccount == null
    val parsedAmount = amount.replace(" ", "").replace(',', '.').toBigDecimalOrNull()
    val amountError = amount.isNotBlank() && (parsedAmount == null || parsedAmount.signum() < 0)
    val parsedDue = due.takeIf { it.isNotBlank() }?.let {
        runCatching { LocalDate.parse(it.trim(), DateTimeFormatter.ofPattern("dd.MM.yyyy")) }.getOrNull()
    }
    val dueError = due.isNotBlank() && parsedDue == null
    val valid = recipient.isNotBlank() && title.isNotBlank() && !accountError && !amountError && !dueError

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Popraw dane przelewu") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(recipient, { recipient = it }, label = { Text("Odbiorca") })
                OutlinedTextField(
                    account, { account = it },
                    label = { Text("Numer rachunku") },
                    isError = accountError,
                    supportingText = if (accountError) {
                        { Text("Niepoprawny numer rachunku") }
                    } else null,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        amount, { amount = it },
                        label = { Text("Kwota") },
                        isError = amountError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        currency, { currency = it.uppercase().take(3) },
                        label = { Text("Waluta") },
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(title, { title = it }, label = { Text("Tytuł przelewu") })
                OutlinedTextField(
                    due, { due = it },
                    label = { Text("Termin płatności (dd.mm.rrrr)") },
                    isError = dueError,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        invoice.copy(
                            recipientName = recipient.trim(),
                            accountNumber = normalizedAccount,
                            amount = parsedAmount?.setScale(2, java.math.RoundingMode.HALF_UP)?.toPlainString(),
                            currency = currency.ifBlank { "PLN" },
                            transferTitle = title.trim(),
                            dueDate = parsedDue?.toString(),
                        )
                    )
                },
            ) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

private fun share(context: Context, invoice: Invoice) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, invoice.transferTitle)
        putExtra(Intent.EXTRA_TEXT, invoice.transferText())
    }
    context.startActivity(Intent.createChooser(intent, "Udostępnij dane przelewu"))
}

private fun openInGmail(context: Context, messageId: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com/mail/u/0/#all/$messageId"))
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Brak przeglądarki i aplikacji Gmail – nic nie robimy.
    }
}
