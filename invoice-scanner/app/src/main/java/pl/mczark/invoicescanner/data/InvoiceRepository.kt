package pl.mczark.invoicescanner.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Przechowuje znalezione faktury lokalnie (plik JSON w pamięci aplikacji). */
class InvoiceRepository(context: Context) {

    @Serializable
    private data class Store(
        val invoices: List<Invoice> = emptyList(),
        /** Wiadomości już przeanalizowane, w których nie było faktury. */
        val skippedMessageIds: Set<String> = emptySet(),
        /** Faktury usunięte przez użytkownika – nie wracają przy kolejnych skanowaniach. */
        val deletedMessageIds: Set<String> = emptySet(),
        val accountEmail: String? = null,
        /** Wersja reguł analizy, której dotyczy [skippedMessageIds]. */
        val analysisVersion: Int = 1,
        /**
         * Statusy ustawione ręcznie ("opłacona" / "do zapłaty"). Przechowywane osobno od wyników
         * analizy, więc przetrwają ponowną analizę po aktualizacji aplikacji, także gdy faktura
         * na chwilę zniknie z listy.
         */
        val statusOverrides: Map<String, PaymentStatus>? = null,
    )

    private val file = File(context.filesDir, "invoices.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val mutex = Mutex()
    private val store = MutableStateFlow(load())

    private val _invoices = MutableStateFlow(store.value.invoices)
    val invoices: StateFlow<List<Invoice>> = _invoices.asStateFlow()

    private val _accountEmail = MutableStateFlow(store.value.accountEmail)
    val accountEmail: StateFlow<String?> = _accountEmail.asStateFlow()

    /** Czy wiadomość była już przeanalizowana bieżącą wersją reguł (albo jej wynik poprawił użytkownik). */
    fun isKnown(messageId: String, analysisVersion: Int): Boolean {
        val s = store.value
        if (messageId in s.deletedMessageIds) return true
        val invoice = s.invoices.firstOrNull { it.messageId == messageId }
        if (invoice != null) return invoice.editedByUser || invoice.analysisVersion >= analysisVersion
        return messageId in s.skippedMessageIds && s.analysisVersion >= analysisVersion
    }

    fun staleInvoiceIds(analysisVersion: Int): List<String> =
        store.value.invoices.filter { !it.editedByUser && it.analysisVersion < analysisVersion }.map { it.messageId }

    fun invoice(messageId: String): Invoice? = store.value.invoices.firstOrNull { it.messageId == messageId }

    /** Po zmianie reguł analizy wiadomości odrzucone wcześniej zostaną sprawdzone jeszcze raz. */
    suspend fun startAnalysisVersion(analysisVersion: Int) = update { s ->
        if (s.analysisVersion >= analysisVersion) s
        else s.copy(skippedMessageIds = emptySet(), analysisVersion = analysisVersion)
    }

    /** Wiadomość, która po ponownej analizie okazała się nie być fakturą. */
    suspend fun removeAndSkip(messageId: String) = update { s ->
        s.copy(
            invoices = s.invoices.filterNot { it.messageId == messageId },
            skippedMessageIds = s.skippedMessageIds + messageId,
        )
    }

    suspend fun addInvoice(invoice: Invoice) = update { s ->
        val status = s.statusOverrides.orEmpty()[invoice.messageId] ?: invoice.status
        s.copy(invoices = s.invoices.filterNot { it.messageId == invoice.messageId } + invoice.copy(status = status))
    }

    /** Status ustawiony przez użytkownika – ma pierwszeństwo przed wynikiem analizy. */
    suspend fun setStatus(messageId: String, status: PaymentStatus) = update { s ->
        s.copy(
            invoices = s.invoices.map { if (it.messageId == messageId) it.copy(status = status) else it },
            statusOverrides = s.statusOverrides.orEmpty() + (messageId to status),
        )
    }

    suspend fun markSkipped(messageId: String) = update { s ->
        s.copy(skippedMessageIds = s.skippedMessageIds + messageId)
    }

    suspend fun updateInvoice(invoice: Invoice) = update { s ->
        s.copy(invoices = s.invoices.map { if (it.messageId == invoice.messageId) invoice else it })
    }

    suspend fun delete(messageId: String) = update { s ->
        s.copy(
            invoices = s.invoices.filterNot { it.messageId == messageId },
            deletedMessageIds = s.deletedMessageIds + messageId,
        )
    }

    suspend fun setAccountEmail(email: String) = update { s ->
        // Zmiana konta – dane poprzedniej skrzynki nie mają sensu.
        if (s.accountEmail != null && s.accountEmail != email) Store(accountEmail = email, statusOverrides = emptyMap())
        else s.copy(accountEmail = email)
    }

    suspend fun clear() = update { Store(statusOverrides = emptyMap()) }

    private suspend fun update(transform: (Store) -> Store) = mutex.withLock {
        val updated = transform(store.value)
        store.value = updated
        _invoices.value = updated.invoices
        _accountEmail.value = updated.accountEmail
        withContext(Dispatchers.IO) {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(Store.serializer(), updated))
            tmp.renameTo(file)
        }
    }

    private fun load(): Store {
        if (!file.exists()) return Store(statusOverrides = emptyMap())
        val loaded = try {
            json.decodeFromString(Store.serializer(), file.readText())
        } catch (e: Exception) {
            // Nie nadpisujemy nieczytelnych danych pustą listą – zostaje kopia do odzyskania.
            Log.e(TAG, "Nie udało się odczytać zapisanych faktur", e)
            file.renameTo(File(file.parentFile, "invoices-unreadable-${System.currentTimeMillis()}.json"))
            return Store(statusOverrides = emptyMap())
        }
        // Dane sprzed wersji z osobnymi statusami: obecne oznaczenia "opłacona" traktujemy jak ręczne,
        // żeby ponowna analiza ich nie cofnęła.
        return if (loaded.statusOverrides != null) loaded else loaded.copy(
            statusOverrides = loaded.invoices
                .filter { it.status == PaymentStatus.PAID }
                .associate { it.messageId to it.status },
        )
    }

    private companion object {
        const val TAG = "InvoiceRepository"
    }
}
