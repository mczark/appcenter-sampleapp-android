package pl.mczark.invoicescanner.ui

import pl.mczark.invoicescanner.data.Invoice
import pl.mczark.invoicescanner.parser.Iban
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val POLISH = Locale.forLanguageTag("pl-PL")
private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy")

fun formatAmount(amount: BigDecimal?, currency: String): String {
    amount ?: return "—"
    val format = NumberFormat.getNumberInstance(POLISH).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    return "${format.format(amount)} $currency"
}

fun formatDate(date: LocalDate?): String = date?.format(DATE_FORMAT) ?: "—"

fun formatAccount(iban: String?): String = iban?.let(Iban::format) ?: "—"

/** Tekst do skopiowania / udostępnienia z kompletem danych do przelewu. */
fun Invoice.transferText(): String = buildString {
    appendLine("Odbiorca: $recipientName")
    accountNumber?.let { appendLine("Numer rachunku: ${Iban.format(it)}") }
    appendLine("Kwota: ${formatAmount(amountValue, currency)}")
    appendLine("Tytuł: $transferTitle")
    dueDateValue?.let { appendLine("Termin płatności: ${formatDate(it)}") }
    if (splitPayment) appendLine("Płatność w mechanizmie podzielonej płatności (MPP)")
}.trimEnd()
