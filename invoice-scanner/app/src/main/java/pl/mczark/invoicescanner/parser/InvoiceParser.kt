package pl.mczark.invoicescanner.parser

import java.math.BigDecimal
import java.time.LocalDate

/** Dane wyciągnięte z tekstu faktury (z PDF-a, OCR albo treści maila). */
data class ParsedInvoice(
    val invoiceNumber: String? = null,
    val sellerName: String? = null,
    val sellerNip: String? = null,
    /** Znormalizowany IBAN, np. PL61109010140000071219812874. */
    val accountNumber: String? = null,
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val dueDate: LocalDate? = null,
    val issueDate: LocalDate? = null,
    val splitPayment: Boolean = false,
    val alreadyPaid: Boolean = false,
    /** Czy w tekście występują słowa typowe dla faktury. */
    val looksLikeInvoice: Boolean = false,
    /**
     * Czy tekst zawiera wyrażenia, które pojawiają się na fakturach, a nie w reklamach
     * (np. "faktura nr", "do zapłaty", "termin płatności").
     */
    val strongInvoiceSignal: Boolean = false,
    /** Pewność kwoty: 3 – "do zapłaty", 2 – "razem brutto", 1 – "razem/suma", 0 – brak słowa kluczowego. */
    val amountConfidence: Int = 0,
) {
    /** Im więcej pól udało się znaleźć, tym wyższy wynik. */
    val score: Int
        get() = (if (accountNumber != null) 4 else 0) +
            (if (amount != null) 3 else 0) +
            (if (dueDate != null) 1 else 0) +
            (if (invoiceNumber != null) 1 else 0) +
            (if (sellerName != null) 1 else 0) +
            (if (looksLikeInvoice) 2 else 0)

    /** Uzupełnia brakujące pola danymi z innego źródła (np. treści maila). */
    fun mergeWith(other: ParsedInvoice): ParsedInvoice = copy(
        invoiceNumber = invoiceNumber ?: other.invoiceNumber,
        sellerName = sellerName ?: other.sellerName,
        sellerNip = sellerNip ?: other.sellerNip,
        accountNumber = accountNumber ?: other.accountNumber,
        amount = amount ?: other.amount,
        amountConfidence = if (amount == null) other.amountConfidence else amountConfidence,
        currency = if (amount == null) other.currency ?: currency else currency ?: other.currency,
        dueDate = dueDate ?: other.dueDate,
        issueDate = issueDate ?: other.issueDate,
        splitPayment = splitPayment || other.splitPayment,
        alreadyPaid = alreadyPaid || other.alreadyPaid,
        looksLikeInvoice = looksLikeInvoice || other.looksLikeInvoice,
        strongInvoiceSignal = strongInvoiceSignal || other.strongInvoiceSignal,
    )
}

/**
 * Heurystyczny parser polskich (i prostych angielskich) faktur.
 * Działa na zwykłym tekście, więc jest niezależny od Androida i łatwy do testowania.
 */
object InvoiceParser {

    private val INVOICE_KEYWORDS = listOf(
        "faktura", "faktury", "fakture", "invoice", "rachunek do zaplaty", "nota obciazeniowa",
        "do zaplaty", "termin platnosci", "proforma",
    )

    private val STRONG_INVOICE_SIGNALS = Regex(
        "faktur[ay]? (vat|nr|numer|korygujac|proforma|elektroniczn)|(nr|numer) faktury|do zaplaty|" +
            "termin (platnosci|zaplaty)|(nr|numer) (konta|rachunku)|sprzedawca|nabywca|" +
            "invoice (no|number|#)|amount due|due date"
    )

    // --- Kwoty -------------------------------------------------------------------------------

    private const val CURRENCY = "(PLN|zł|zl|EUR|€|USD|\\$|GBP|£|CHF)"

    private val AMOUNT = Regex(
        "(?<![\\d.,])(\\d{1,3}(?:[ .,']\\d{3})+|\\d+)[.,](\\d{2})(?![\\d]|[.,]\\d)(?:\\s?$CURRENCY)?",
        RegexOption.IGNORE_CASE,
    )

    /** Słowa kluczowe dla kwoty – im wyższy priorytet, tym pewniejsza kwota do zapłaty. */
    private val AMOUNT_KEYWORDS = listOf(
        3 to Regex("pozostalo do zaplaty|pozostaje do zaplaty|kwota do zaplaty|razem do zaplaty|suma do zaplaty|do zaplaty|naleznosc|amount due|balance due|total due|to pay"),
        2 to Regex("razem brutto|wartosc brutto|suma brutto|kwota brutto|ogolem|grand total|total"),
        1 to Regex("brutto|razem|suma"),
    )

    private val IGNORED_AMOUNT_LINES = Regex("slownie|in words|zaplacono|wplacono|paid:")

    // --- Rachunek ----------------------------------------------------------------------------

    private val ACCOUNT_CANDIDATE = Regex(
        "(?<![A-Z0-9])(?:[A-Z]{2}[ ]?)?\\d{2}(?:[ -]?[A-Z0-9]{4}){2,7}(?:[ -]?[A-Z0-9]{1,3})?(?![A-Z0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val ACCOUNT_KEYWORDS = Regex("konto|konta|rachunek|rachunku|iban|nrb|przelew|wplat|bank|account")

    /**
     * Luźny zapis numeru konta: dowolne odstępy między cyframi, przeniesienie do kolejnej linii
     * i typowe pomyłki OCR (O zamiast 0, l zamiast 1 itd.).
     */
    private val LOOSE_ACCOUNT_RUN = Regex("[0-9OoDQIl|SB][0-9OoDQIl|SB \\-]{25,90}")
    private val OCR_DIGITS = mapOf(
        'O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0',
        'I' to '1', 'l' to '1', '|' to '1',
        'S' to '5', 'B' to '8',
    )

    // --- Daty --------------------------------------------------------------------------------

    private val DATE_DMY = Regex("(?<!\\d)(\\d{1,2})[.\\-/](\\d{1,2})[.\\-/](\\d{4})(?!\\d)")
    private val DATE_YMD = Regex("(?<!\\d)(\\d{4})[.\\-/](\\d{1,2})[.\\-/](\\d{1,2})(?!\\d)")
    private val DUE_KEYWORDS = Regex(
        "termin platnosci|termin zaplaty|termin platn|platne do|platny do|zaplac do|zaplata do|platnosc do|" +
            "data platnosci|termin wplaty|wplac do|due date|payment due|pay by"
    )
    private val ISSUE_KEYWORDS = Regex("data wystawienia|wystawion[ao]|data faktury|issue date|date of issue|invoice date")
    private val DUE_IN_DAYS = Regex("(\\d{1,3})\\s*(dni|days)")

    // --- Numer faktury -----------------------------------------------------------------------

    private const val NUMBER_TOKEN = "([A-Z0-9][A-Z0-9/\\-_.]*\\d[A-Z0-9/\\-_.]*)"
    private val INVOICE_NUMBER_PATTERNS = listOf(
        Regex("(?:nr|numer)\\s+(?:faktury|dokumentu|rachunku)\\s*[:.]?\\s*$NUMBER_TOKEN", RegexOption.IGNORE_CASE),
        Regex(
            "\\b(?:faktura|fv|invoice|proforma)(?:\\s+(?:vat|korygujaca|korygująca|elektroniczna))?" +
                "(?:\\s+(?:nr|numer|no\\.?|number|#))?\\s*[:.#]?\\s*$NUMBER_TOKEN",
            RegexOption.IGNORE_CASE,
        ),
    )

    // --- Sprzedawca --------------------------------------------------------------------------

    private val SELLER_KEYWORDS = Regex("^(sprzedawca|sprzedajacy|wystawca|dostawca|uslugodawca|seller|supplier|odbiorca platnosci)\\b")
    private val COLUMN_SPLIT = Regex("(?i)\\b(nabywca|odbiorca|kupujacy|kupujący|platnik|płatnik|buyer|customer)\\b")
    private val NIP = Regex(
        "(?i)NIP\\s*[:.]?\\s*(?:PL)?\\s*(\\d{3}[- ]?\\d{3}[- ]?\\d{2}[- ]?\\d{2}|\\d{3}[- ]?\\d{2}[- ]?\\d{2}[- ]?\\d{3}|\\d{10})"
    )

    // --- Flagi -------------------------------------------------------------------------------

    private val SPLIT_PAYMENT = Regex("mechanizm podzielonej platnosci|split payment|\\bmpp\\b")
    private val PAID = Regex(
        "faktura (jest )?(oplacona|zaplacona)|status\\s*:?\\s*(oplacona|zaplacona|paid)|" +
            "zaplacono (w calosci|karta|kartą|przelewem|online|gotowka)|oplacono (w calosci|karta|online)|" +
            "nie wymaga (zaplaty|platnosci)|paid in full|payment received|platnosc zostala pobrana|" +
            "kwota zostanie pobrana|zostanie pobrana z karty|polecenie zaplaty"
    )

    fun parse(text: String): ParsedInvoice {
        val lines = TextUtils.normalizeLines(text)
        val folded = lines.map(TextUtils::fold)
        val foldedAll = folded.joinToString("\n")

        val issueDate = findDate(lines, folded, ISSUE_KEYWORDS)
        val amount = findAmount(lines, folded)
        val dueDate = findDate(lines, folded, DUE_KEYWORDS) ?: findDueInDays(folded, issueDate)

        val paidByRemainingZero = amount?.let { it.priority == 3 && it.value.signum() == 0 } == true

        return ParsedInvoice(
            invoiceNumber = findInvoiceNumber(lines),
            sellerName = findSellerName(lines, folded),
            sellerNip = findSellerNip(lines, folded),
            accountNumber = findAccount(lines, folded),
            amount = amount?.value?.takeIf { it.signum() > 0 },
            currency = amount?.currency ?: findDocumentCurrency(foldedAll),
            dueDate = dueDate,
            issueDate = issueDate,
            splitPayment = SPLIT_PAYMENT.containsMatchIn(foldedAll),
            alreadyPaid = paidByRemainingZero || PAID.containsMatchIn(foldedAll),
            looksLikeInvoice = INVOICE_KEYWORDS.any { foldedAll.contains(it) },
            strongInvoiceSignal = STRONG_INVOICE_SIGNALS.containsMatchIn(foldedAll),
            amountConfidence = amount?.priority ?: 0,
        )
    }

    fun looksLikeInvoice(text: String): Boolean {
        val folded = TextUtils.fold(text)
        return INVOICE_KEYWORDS.any { folded.contains(it) }
    }

    // ------------------------------------------------------------------------------------------

    internal data class AmountMatch(val value: BigDecimal, val currency: String?, val priority: Int)

    internal fun parseAmounts(line: String): List<Pair<BigDecimal, String?>> =
        AMOUNT.findAll(line).mapNotNull { m ->
            val integerPart = m.groupValues[1].filter { it.isDigit() }
            val value = "$integerPart.${m.groupValues[2]}".toBigDecimalOrNull() ?: return@mapNotNull null
            value to m.groupValues[3].takeIf { it.isNotEmpty() }?.let(::normalizeCurrency)
        }.toList()

    private fun findAmount(lines: List<String>, folded: List<String>): AmountMatch? {
        for ((priority, keyword) in AMOUNT_KEYWORDS) {
            for (i in lines.indices) {
                val match = keyword.find(folded[i]) ?: continue
                if (IGNORED_AMOUNT_LINES.containsMatchIn(folded[i]) && priority < 3) continue
                // Kwota zwykle stoi za słowem kluczowym w tej samej linii albo w jednej z kolejnych.
                val sameLine = parseAmounts(lines[i].substring(match.range.first))
                val candidates = sameLine.ifEmpty {
                    (i + 1..minOf(i + 2, lines.lastIndex))
                        .asSequence()
                        .filterNot { IGNORED_AMOUNT_LINES.containsMatchIn(folded[it]) }
                        .map { parseAmounts(lines[it]) }
                        .firstOrNull { it.isNotEmpty() }
                        .orEmpty()
                }
                if (candidates.isEmpty()) continue
                // "Do zapłaty: X" – pierwsza kwota za słowem kluczowym. W wierszu podsumowania
                // tabeli (netto, VAT, brutto) kwota brutto jest największa.
                val best = if (priority == 3) candidates.first() else candidates.maxBy { it.first }
                return AmountMatch(best.first, best.second, priority)
            }
        }
        // Brak słów kluczowych – bierzemy największą kwotę opatrzoną walutą.
        return lines.flatMap(::parseAmounts)
            .filter { it.second != null }
            .maxByOrNull { it.first }
            ?.let { AmountMatch(it.first, it.second, 0) }
    }

    private fun normalizeCurrency(raw: String): String = when (TextUtils.fold(raw)) {
        "zl", "pln" -> "PLN"
        "€", "eur" -> "EUR"
        "$", "usd" -> "USD"
        "£", "gbp" -> "GBP"
        "chf" -> "CHF"
        else -> raw.uppercase()
    }

    private fun findDocumentCurrency(folded: String): String? = when {
        Regex("\\bpln\\b|\\bzl\\b").containsMatchIn(folded) -> "PLN"
        Regex("\\beur\\b|€").containsMatchIn(folded) -> "EUR"
        Regex("\\busd\\b").containsMatchIn(folded) -> "USD"
        else -> null
    }

    private fun findAccount(lines: List<String>, folded: List<String>): String? {
        data class Candidate(val iban: String, val lineIndex: Int, val nearKeyword: Boolean)

        val candidates = lines.flatMapIndexed { index, line ->
            ACCOUNT_CANDIDATE.findAll(line).mapNotNull { m ->
                val iban = normalizeAccount(m.value) ?: return@mapNotNull null
                val near = (maxOf(0, index - 2)..index).any { ACCOUNT_KEYWORDS.containsMatchIn(folded[it]) }
                Candidate(iban, index, near)
            }.toList()
        }
        return (candidates.firstOrNull { it.nearKeyword } ?: candidates.firstOrNull())?.iban
            ?: findLooseAccount(lines, folded)
    }

    /**
     * Druga próba tylko w okolicy słów "konto", "rachunek" itp.: numer rozbity spacjami co cyfrę,
     * przeniesiony do kolejnej linii albo przekłamany przez OCR. Fałszywe trafienia odsiewa suma
     * kontrolna IBAN.
     */
    private fun findLooseAccount(lines: List<String>, folded: List<String>): String? {
        for (i in lines.indices) {
            if (!ACCOUNT_KEYWORDS.containsMatchIn(folded[i])) continue
            val region = (i..minOf(i + 2, lines.lastIndex)).joinToString(" ") { lines[it] }
            for (run in LOOSE_ACCOUNT_RUN.findAll(region)) {
                if (run.value.count { it.isDigit() } < 16) continue
                val tokens = run.value.split(' ', '-').filter { it.isNotEmpty() }
                    .map { token -> token.map { OCR_DIGITS[it] ?: it }.joinToString("") }
                // Numer może zaczynać się od dowolnego "słowa" ciągu – przed nim bywają inne liczby.
                for (start in tokens.indices) {
                    val digits = tokens.drop(start).joinToString("")
                    if (digits.length < 26) break
                    Iban.normalize(digits.take(26))?.let { return it }
                }
            }
        }
        return null
    }

    /** Dopasowanie bywa zachłanne (np. doklejony rok po numerze), więc próbujemy też samego prefiksu. */
    private fun normalizeAccount(raw: String): String? {
        Iban.normalize(raw)?.let { return it }
        val compact = raw.filter { it.isLetterOrDigit() }.uppercase()
        return when {
            compact.length > 26 && compact.all { it.isDigit() } -> Iban.normalize(compact.take(26))
            compact.startsWith("PL") && compact.length > 28 -> Iban.normalize(compact.take(28))
            else -> null
        }
    }

    private fun findDate(lines: List<String>, folded: List<String>, keywords: Regex): LocalDate? {
        for (i in lines.indices) {
            val match = keywords.find(folded[i]) ?: continue
            val rest = lines[i].substring(match.range.first)
            parseDate(rest)?.let { return it }
            // "Termin płatności: 14 dni" – data wyliczana jest osobno, nie szukamy jej niżej.
            if (DUE_IN_DAYS.containsMatchIn(folded[i].substring(match.range.first))) continue
            for (j in i + 1..minOf(i + 2, lines.lastIndex)) {
                parseDate(lines[j])?.let { return it }
            }
        }
        return null
    }

    internal fun parseDate(text: String): LocalDate? {
        val dmy = DATE_DMY.find(text)
        val ymd = DATE_YMD.find(text)
        val first = listOfNotNull(dmy, ymd).minByOrNull { it.range.first } ?: return null
        return runCatching {
            val (a, b, c) = first.destructured
            if (first === ymd) LocalDate.of(a.toInt(), b.toInt(), c.toInt())
            else LocalDate.of(c.toInt(), b.toInt(), a.toInt())
        }.getOrNull()
    }

    private fun findDueInDays(folded: List<String>, issueDate: LocalDate?): LocalDate? {
        issueDate ?: return null
        for (line in folded) {
            val keyword = DUE_KEYWORDS.find(line) ?: continue
            val days = DUE_IN_DAYS.find(line, keyword.range.first)?.groupValues?.get(1)?.toLongOrNull()
            if (days != null) return issueDate.plusDays(days)
        }
        return null
    }

    private fun findInvoiceNumber(lines: List<String>): String? {
        for (pattern in INVOICE_NUMBER_PATTERNS) {
            for (line in lines) {
                for (m in pattern.findAll(line)) {
                    val number = m.groupValues[1].trimEnd('.', '-', '/', '_')
                    if (number.length < 2) continue
                    return number
                }
            }
        }
        return null
    }

    private fun findSellerName(lines: List<String>, folded: List<String>): String? {
        for (i in lines.indices) {
            val match = SELLER_KEYWORDS.find(folded[i]) ?: continue
            val rest = lines[i].substring(match.range.last + 1).trimStart(':', ' ', '-')
            val sameLine = cleanSellerName(rest)
            if (sameLine != null) return sameLine
            for (j in i + 1..minOf(i + 2, lines.lastIndex)) {
                cleanSellerName(lines[j])?.let { return it }
            }
        }
        return null
    }

    private fun cleanSellerName(raw: String): String? {
        val cut = COLUMN_SPLIT.find(raw)?.let { raw.substring(0, it.range.first) } ?: raw
        val name = cut.trim().trim(':', '-', ',', ' ')
        if (name.length < 3) return null
        val folded = TextUtils.fold(name)
        // Linie z samymi danymi identyfikacyjnymi/adresem nie są nazwą.
        if (folded.startsWith("nip") || folded.startsWith("regon") || folded.startsWith("ul.")) return null
        if (name.count { it.isLetter() } < 3) return null
        return name.take(120)
    }

    private fun findSellerNip(lines: List<String>, folded: List<String>): String? {
        val sellerLine = folded.indexOfFirst { SELLER_KEYWORDS.containsMatchIn(it) }
        val ordered = if (sellerLine >= 0) lines.drop(sellerLine) + lines.take(sellerLine) else lines
        for (line in ordered) {
            for (m in NIP.findAll(line)) {
                val digits = m.groupValues[1].filter { it.isDigit() }
                if (isNipValid(digits)) return digits
            }
        }
        return null
    }

    fun isNipValid(nip: String): Boolean {
        if (nip.length != 10 || !nip.all { it.isDigit() }) return false
        val weights = intArrayOf(6, 5, 7, 2, 3, 4, 5, 6, 7)
        val sum = weights.indices.sumOf { weights[it] * (nip[it] - '0') }
        val check = sum % 11
        return check != 10 && check == nip[9] - '0'
    }
}
