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

        val ids = api.listMessageIds(buildQuery(daysBack), MAX_MESSAGES)
            .filterNot(repository::isKnown)

        var found = 0
        ids.forEachIndexed { index, id ->
            onProgress(ScanProgress(index, ids.size, found))
            try {
                val invoice = analyze(api, api.message(id))
                if (invoice != null) {
                    repository.addInvoice(invoice)
                    found++
                } else {
                    repository.markSkipped(id)
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

        val sources = mutableListOf<Pair<String, ParsedInvoice>>()
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
                sources += filename.ifBlank { "załącznik" } to InvoiceParser.parse(text)
            }
        }
        sources += "treść wiadomości" to InvoiceParser.parse("$subject\n$bodyText")

        val (bestSource, best) = sources.maxBy { it.second.score }
        val merged = sources.fold(best) { acc, (_, parsed) -> acc.mergeWith(parsed) }

        val looksLikeInvoice = merged.looksLikeInvoice || InvoiceParser.looksLikeInvoice(subject)
        if (!looksLikeInvoice || (merged.accountNumber == null && merged.amount == null)) return null

        val recipient = merged.sellerName ?: senderName(from)
        val title = merged.invoiceNumber?.let { "Faktura $it" } ?: subject.take(60).ifBlank { "Płatność" }

        return Invoice(
            messageId = message.id,
            emailSubject = subject,
            emailFrom = from,
            emailDateMillis = message.internalDate?.toLongOrNull() ?: System.currentTimeMillis(),
            source = bestSource,
            recipientName = recipient,
            recipientNip = merged.sellerNip,
            accountNumber = merged.accountNumber,
            amount = merged.amount?.toPlainString(),
            currency = merged.currency ?: "PLN",
            invoiceNumber = merged.invoiceNumber,
            transferTitle = title,
            dueDate = merged.dueDate?.toString(),
            issueDate = merged.issueDate?.toString(),
            splitPayment = merged.splitPayment,
            status = if (merged.alreadyPaid) PaymentStatus.PAID else PaymentStatus.TO_PAY,
        )
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
        private const val MAX_MESSAGES = 200
        private const val MIN_PLAIN_TEXT_CHARS = 200
        private const val MIN_IMAGE_BYTES = 30_000
        private const val MAX_ATTACHMENT_BYTES = 15_000_000

        fun buildQuery(daysBack: Int): String =
            "newer_than:${daysBack}d -in:sent -in:drafts -in:chats " +
                "(faktura OR faktury OR fakturę OR fv OR invoice OR rachunek OR \"do zapłaty\" " +
                "OR \"termin płatności\" OR \"e-faktura\" OR proforma)"
    }
}
