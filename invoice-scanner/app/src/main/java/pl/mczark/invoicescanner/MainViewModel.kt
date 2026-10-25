package pl.mczark.invoicescanner

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.mczark.invoicescanner.auth.AuthState
import pl.mczark.invoicescanner.background.DailyCheck
import pl.mczark.invoicescanner.data.Invoice
import pl.mczark.invoicescanner.data.PaymentStatus
import pl.mczark.invoicescanner.data.ScanProgress
import pl.mczark.invoicescanner.gmail.GmailAuthException
import java.io.IOException

data class UiState(
    val accountEmail: String? = null,
    val invoices: List<Invoice> = emptyList(),
    val scanning: Boolean = false,
    val progress: ScanProgress? = null,
    val message: String? = null,
    /** Codzienne sprawdzanie skrzynki w tle z powiadomieniem o nowych fakturach. */
    val dailyCheckEnabled: Boolean = true,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as InvoiceScannerApp
    private val repository = app.repository

    private data class ScanState(
        val scanning: Boolean = false,
        val progress: ScanProgress? = null,
        val message: String? = null,
    )

    private val scanState = MutableStateFlow(ScanState())
    private val dailyCheckEnabled = MutableStateFlow(DailyCheck.isEnabled(application))

    val uiState: StateFlow<UiState> = combine(
        repository.invoices,
        repository.accountEmail,
        scanState,
        dailyCheckEnabled,
    ) { invoices, email, scan, daily ->
        UiState(
            accountEmail = email,
            invoices = invoices.sortedWith(
                compareBy<Invoice> { it.status }
                    .thenBy(nullsLast()) { it.dueDate } // ISO yyyy-MM-dd sortuje się jak data
                    .thenByDescending { it.emailDateMillis }
            ),
            scanning = scan.scanning,
            progress = scan.progress,
            message = scan.message,
            dailyCheckEnabled = daily,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val consentRequests = Channel<PendingIntent>(Channel.BUFFERED)

    /** Ekrany zgody Google, które UI ma uruchomić. */
    val consentIntents: Flow<PendingIntent> = consentRequests.receiveAsFlow()

    fun scan() {
        if (scanState.value.scanning) return
        scanState.update { it.copy(scanning = true, progress = null, message = null) }
        viewModelScope.launch {
            try {
                when (val auth = app.authorizer.authorize()) {
                    is AuthState.NeedsConsent -> {
                        scanState.update { it.copy(scanning = false) }
                        consentRequests.send(auth.pendingIntent)
                    }
                    is AuthState.Authorized -> runScan(auth.accessToken, retryOnAuthError = true)
                }
            } catch (e: Exception) {
                finishWithError(e)
            }
        }
    }

    fun onConsentResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) {
            scanState.update { it.copy(scanning = false, message = "Bez dostępu do Gmaila nie da się wyszukać faktur") }
            return
        }
        scanState.update { it.copy(scanning = true, message = null) }
        viewModelScope.launch {
            try {
                when (val auth = app.authorizer.handleConsentResult(data)) {
                    is AuthState.Authorized -> runScan(auth.accessToken, retryOnAuthError = true)
                    is AuthState.NeedsConsent -> finishWithError(IllegalStateException("Nie udzielono zgody"))
                }
            } catch (e: Exception) {
                finishWithError(e)
            }
        }
    }

    private suspend fun runScan(token: String, retryOnAuthError: Boolean) {
        try {
            val result = app.scanner.scan(token) { progress ->
                scanState.update { it.copy(progress = progress) }
            }
            val summary = when {
                result.scanned == 0 -> "Brak nowych wiadomości do sprawdzenia"
                result.newInvoices.isEmpty() -> "Sprawdzono ${result.scanned} wiadomości – brak nowych faktur"
                else -> "Znaleziono nowe faktury: ${result.newInvoices.size}"
            }
            scanState.update { ScanState(message = summary) }
        } catch (e: GmailAuthException) {
            app.authorizer.invalidate(token)
            val auth = if (retryOnAuthError) app.authorizer.authorize() else null
            when (auth) {
                is AuthState.Authorized -> runScan(auth.accessToken, retryOnAuthError = false)
                is AuthState.NeedsConsent -> {
                    scanState.update { it.copy(scanning = false) }
                    consentRequests.send(auth.pendingIntent)
                }
                null -> finishWithError(e)
            }
        }
    }

    private fun finishWithError(e: Exception) {
        val text = when (e) {
            is IOException -> "Błąd połączenia z Gmailem: ${e.message}"
            else -> e.message ?: "Nieznany błąd"
        }
        scanState.update { ScanState(message = text) }
    }

    fun messageShown() = scanState.update { it.copy(message = null) }

    fun setStatus(invoice: Invoice, status: PaymentStatus) = viewModelScope.launch {
        repository.setStatus(invoice.messageId, status)
    }

    fun save(invoice: Invoice) = viewModelScope.launch {
        repository.updateInvoice(invoice.copy(editedByUser = true))
    }

    fun delete(invoice: Invoice) = viewModelScope.launch {
        repository.delete(invoice.messageId)
    }

    fun setDailyCheckEnabled(enabled: Boolean) {
        DailyCheck.setEnabled(app, enabled)
        dailyCheckEnabled.value = enabled
    }

    fun clearAll() = viewModelScope.launch {
        repository.clear()
    }
}
