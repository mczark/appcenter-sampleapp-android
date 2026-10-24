package pl.mczark.invoicescanner.data

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalDate

enum class PaymentStatus { TO_PAY, PAID }

/** Faktura znaleziona w skrzynce wraz z danymi do przelewu. */
@Serializable
data class Invoice(
    val messageId: String,
    val emailSubject: String,
    val emailFrom: String,
    val emailDateMillis: Long,
    /** Skąd pochodzą dane, np. "faktura.pdf" albo "treść wiadomości". */
    val source: String,
    val recipientName: String,
    val recipientNip: String? = null,
    /** Znormalizowany IBAN. */
    val accountNumber: String? = null,
    /** Kwota w postaci tekstowej BigDecimal, np. "123.45". */
    val amount: String? = null,
    val currency: String = "PLN",
    val invoiceNumber: String? = null,
    val transferTitle: String,
    /** Data w formacie ISO (yyyy-MM-dd). */
    val dueDate: String? = null,
    val issueDate: String? = null,
    val splitPayment: Boolean = false,
    val status: PaymentStatus = PaymentStatus.TO_PAY,
    val editedByUser: Boolean = false,
    /** Tekst, z którego odczytano dane (do podglądu i zgłaszania błędów odczytu). */
    val sourceText: String? = null,
    /** Wersja reguł analizy, którymi przetworzono wiadomość; starsze są analizowane ponownie. */
    val analysisVersion: Int = 1,
) {
    val amountValue: BigDecimal? get() = amount?.toBigDecimalOrNull()
    val dueDateValue: LocalDate? get() = dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    val isOverdue: Boolean
        get() = status == PaymentStatus.TO_PAY && dueDateValue?.isBefore(LocalDate.now()) == true

    /** Komplet danych potrzebnych do wykonania przelewu. */
    val isComplete: Boolean get() = accountNumber != null && amount != null
}
