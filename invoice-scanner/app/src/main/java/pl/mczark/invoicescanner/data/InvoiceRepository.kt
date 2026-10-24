package pl.mczark.invoicescanner.data

import android.content.Context
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
        val accountEmail: String? = null,
    )

    private val file = File(context.filesDir, "invoices.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val mutex = Mutex()
    private val store = MutableStateFlow(load())

    private val _invoices = MutableStateFlow(store.value.invoices)
    val invoices: StateFlow<List<Invoice>> = _invoices.asStateFlow()

    private val _accountEmail = MutableStateFlow(store.value.accountEmail)
    val accountEmail: StateFlow<String?> = _accountEmail.asStateFlow()

    fun isKnown(messageId: String): Boolean =
        store.value.invoices.any { it.messageId == messageId } || messageId in store.value.skippedMessageIds

    suspend fun addInvoice(invoice: Invoice) = update { s ->
        s.copy(invoices = s.invoices.filterNot { it.messageId == invoice.messageId } + invoice)
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
            skippedMessageIds = s.skippedMessageIds + messageId,
        )
    }

    suspend fun setAccountEmail(email: String) = update { s ->
        // Zmiana konta – dane poprzedniej skrzynki nie mają sensu.
        if (s.accountEmail != null && s.accountEmail != email) Store(accountEmail = email)
        else s.copy(accountEmail = email)
    }

    suspend fun clear() = update { Store() }

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

    private fun load(): Store = runCatching {
        if (file.exists()) json.decodeFromString(Store.serializer(), file.readText()) else Store()
    }.getOrDefault(Store())
}
