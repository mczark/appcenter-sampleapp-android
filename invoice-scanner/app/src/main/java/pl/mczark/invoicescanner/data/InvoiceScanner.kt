package pl.mczark.invoicescanner.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import pl.mczark.invoicescanner.extract.AttachmentTextExtractor
import pl.mczark.invoicescanner.gmail.GmailApi
import pl.mczark.invoicescanner.gmail.GmailAuthException
import pl.mczark.invoicescanner.gmail.GmailMessage
import pl.mczark.invoicescanner.parser.InvoiceParser
import pl.mczark.invoicescanner.parser.ParsedInvoice
import pl.mczark.invoicescanner.parser.TextUtils

data class ScanProgress(val processed: Int, val total: Int, val found: Int)

data class ScanResult(val scanned: Int, val newInvoices: Int)

/**
 * Przeszukuje skrzynkę Gmail: wybiera wiadomości wyglądające na faktury, czyta treść
 * oraz załączniki (PDF / zdjęcia) i zapisuje dane do przelewu w [InvoiceRepository].
 */
class InvoiceScanner(
    private val repository: InvoiceRepository,
    private val extractor: AttachmentTextExtractor,
) {

    suspend fun scan(
        accessToken: String,
        daysBack: Int = DEFAULT_DAYS_BACK,
        onProgress: (ScanProgress) -> Unit = {},
    ): ScanResult {
        val api = GmailApi(accessToken)
        repository.setAccountEmail(api.profile().emailAddress)
        repository.startAnalysisVersion(ANALYSIS_VERSION)

        // Zapisane faktury przeanalizowane starszymi regułami sprawdzamy ponownie, nawet jeśli
        // nowe zapytanie już ich nie zwraca (np. reklama z kategorii "Oferty").
        val ids = (api.listMessageIds(buildQuery(daysBack), MAX_MESSAGES) + repository.staleInvoiceIds(ANALYSIS_VERSION))
            .distinct()
            .filterNot { repository.isKnown(it, ANALYSIS_VERSION) }

        var found = 0
        ids.forEachIndexed { index, id ->
            onProgress(ScanProgress(index, ids.size, found))
            try {
                val previous = repository.invoice(id)
                val invoice = analyze(api, api.message(id))
                when {
                    invoice != null -> {
                        // Ponowna analiza nie może cofnąć oznaczenia "opłacona".
                        val status = if (previous?.status == PaymentStatus.PAID) PaymentStatus.PAID else invoice.status
                        repository.addInvoice(invoice.copy(status = status))
                        if (previous == null) found++
                    }
                    previous != null -> repository.removeAndSkip(id)
                    else -> repository.markSkipped(id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GmailAuthException) {
                throw e
            } catch (e: Exception) {
                // Jedna nietypowa wiadomość nie powinna przerywać całego skanowania.
                Log.w(TAG, "Nie udało się przeanalizować wiadomości $id", e)
            }
        }
        onProgress(ScanProgress(ids.size, ids.size, found))
        return ScanResult(scanned = ids.size, newInvoices = found)
    }

    private suspend fun analyze(api: GmailApi, message: GmailMessage): Invoice? {
        val subject = message.header("Subject").orEmpty()
        val from = message.header("From").orEmpty()
        val parts = message.allParts()

        val plainText = parts.firstOrNull { it.mimeType == "text/plain" && it.body?.data != null }
            ?.let { decode(it.body?.data) }
            .orEmpty()
        val htmlText = parts.firstOrNull { it.mimeType == "text/html" && it.body?.data != null }
            ?.let { TextUtils.htmlToText(decode(it.body?.data)) }
            .orEmpty()
        // Część text/plain bywa tylko zaślepką ("otwórz wersję HTML"), wtedy bierzemy HTML.
        val bodyText = when {
            plainText.length >= MIN_PLAIN_TEXT_CHARS || htmlText.isBlank() -> plainText
            else -> htmlText
        }.ifBlank { message.snippet.orEmpty() }

        val sources = mutableListOf<Source>()
        for (part in parts) {
            val body = part.body ?: continue
            val attachmentId = body.attachmentId ?: continue
            val filename = part.filename.orEmpty()
            val kind = AttachmentTextExtractor.kindOf(part.mimeType, filename)
            if (kind == AttachmentTextExtractor.Kind.OTHER) continue
            // Logotypy i stopki w mailach to też obrazki – pomijamy małe pliki graficzne.
            if (kind == AttachmentTextExtractor.Kind.IMAGE && body.size < MIN_IMAGE_BYTES) continue
            if (body.size > MAX_ATTACHMENT_BYTES) continue

            val bytes = api.attachment(message.id, attachmentId)
            val text = extractor.extract(bytes, part.mimeType, filename)
            if (text.isNotBlank()) {
                sources += Source(filename.ifBlank { "załącznik" }, text, InvoiceParser.parse(text), isAttachment = true)
            }
        }
        val messageText = "$subject\n$bodyText"
        sources += Source("treść wiadomości", messageText, InvoiceParser.parse(messageText), isAttachment = false)

        val best = sources.maxBy { it.parsed.score }
        val merged = sources.fold(best.parsed) { acc, source -> acc.mergeWith(source.parsed) }
        if (!isPayableInvoice(message, subject, sources, merged)) return null

        val recipient = merged.sellerName ?: senderName(from)
        val title = merged.invoiceNumber?.let { "Faktura $it" } ?: subject.take(60).ifBlank { "Płatność" }

        return Invoice(
            messageId = message.id,
            emailSubject = subject,
            emailFrom = from,
            emailDateMillis = message.internalDate?.toLongOrNull() ?: System.currentTimeMillis(),
            source = best.name,
            recipientName = recipient,
            recipientNip = merged.sellerNip,
            accountNumber = merged.accountNumber,
            accountVerified = merged.accountVerified,
            amount = merged.amount?.toPlainString(),
            currency = merged.currency ?: "PLN",
            invoiceNumber = merged.invoiceNumber,
            transferTitle = title,
            dueDate = merged.dueDate?.toString(),
            issueDate = merged.issueDate?.toString(),
            splitPayment = merged.splitPayment,
            status = if (merged.alreadyPaid) PaymentStatus.PAID else PaymentStatus.TO_PAY,
            sourceText = best.text.take(MAX_SOURCE_TEXT_CHARS),
            analysisVersion = ANALYSIS_VERSION,
        )
    }

    private class Source(val name: String, val text: String, val parsed: ParsedInvoice, val isAttachment: Boolean)

    /**
     * Odróżnia faktury od reklam i newsletterów, które też zawierają słowo "faktura" i ceny:
     * - musi być wyraźny sygnał faktury (załącznik-faktura, "faktura nr", "do zapłaty", temat maila),
     * - musi być numer konta albo kwota przy słowie "do zapłaty" / "razem brutto",
     * - newsletter (nagłówek List-Unsubscribe, stopka "wypisz się", reklama kredytu z RRSO) bez faktury
     *   w załączniku i bez numeru konta odpada.
     */
    private fun isPayableInvoice(
        message: GmailMessage,
        subject: String,
        sources: List<Source>,
        merged: ParsedInvoice,
    ): Boolean {
        val invoiceAttachment = sources.any { it.isAttachment && it.parsed.looksLikeInvoice }
        val strongSignal = invoiceAttachment || merged.strongInvoiceSignal || InvoiceParser.looksLikeInvoice(subject)
        val confidentAmount = merged.amountConfidence >= 2 ||
            (merged.amountConfidence == 1 && merged.invoiceNumber != null)
        val payable = merged.accountNumber != null || (merged.amount != null && confidentAmount)
        val newsletter = (message.header("List-Unsubscribe") != null || merged.marketing) &&
            !invoiceAttachment && merged.accountNumber == null
        return strongSignal && payable && !newsletter
    }

    private fun decode(data: String?): String =
        data?.let { String(GmailApi.decodeBase64Url(it), Charsets.UTF_8) }.orEmpty()

    /** "Firma X <faktury@x.pl>" -> "Firma X" */
    private fun senderName(from: String): String {
        val name = from.substringBefore('<').trim().trim('"')
        return name.ifBlank { from.substringAfter('<').substringBefore('>').trim() }
    }

    companion object {
        private const val TAG = "InvoiceScanner"
        const val DEFAULT_DAYS_BACK = 90
        /** Zwiększ po zmianie reguł analizy – zapisane wyniki zostaną przeliczone przy skanowaniu. */
        const val ANALYSIS_VERSION = 3
        private const val MAX_SOURCE_TEXT_CHARS = 30_000
        private const val MAX_MESSAGES = 200
        private const val MIN_PLAIN_TEXT_CHARS = 200
        private const val MIN_IMAGE_BYTES = 30_000
        private const val MAX_ATTACHMENT_BYTES = 15_000_000

        fun buildQuery(daysBack: Int): String =
            "newer_than:${daysBack}d -in:sent -in:drafts -in:chats -category:promotions -category:social " +
                "(faktura OR faktury OR fakturę OR fv OR invoice OR rachunek OR \"do zapłaty\" " +
                "OR \"termin płatności\" OR \"e-faktura\" OR proforma)"
    }
}
