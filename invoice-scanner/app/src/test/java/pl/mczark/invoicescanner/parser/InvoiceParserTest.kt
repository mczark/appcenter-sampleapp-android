package pl.mczark.invoicescanner.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class InvoiceParserTest {

    @Test
    fun `parses typical Polish VAT invoice`() {
        val text = """
            Faktura VAT nr FV/2024/03/0157
            Data wystawienia: 05.03.2024
            Sprzedawca: Nabywca:
            ACME Usługi Sp. z o.o. Jan Kowalski
            ul. Długa 1, 00-001 Warszawa ul. Krótka 2, 30-001 Kraków
            NIP: 526-104-08-28
            Lp. Nazwa Ilość Netto VAT Brutto
            1 Abonament internetowy 1 100,00 23% 123,00
            Razem 100,00 23,00 123,00
            Do zapłaty: 123,00 PLN
            Słownie: sto dwadzieścia trzy złote 00/100
            Sposób płatności: przelew
            Termin płatności: 19.03.2024
            Nr konta: 61 1090 1014 0000 0712 1981 2874
        """.trimIndent()

        val result = InvoiceParser.parse(text)

        assertEquals("FV/2024/03/0157", result.invoiceNumber)
        assertEquals("ACME Usługi Sp. z o.o. Jan Kowalski", result.sellerName)
        assertEquals("5261040828", result.sellerNip)
        assertEquals("PL61109010140000071219812874", result.accountNumber)
        assertEquals(BigDecimal("123.00"), result.amount)
        assertEquals("PLN", result.currency)
        assertEquals(LocalDate.of(2024, 3, 19), result.dueDate)
        assertEquals(LocalDate.of(2024, 3, 5), result.issueDate)
        assertTrue(result.looksLikeInvoice)
        assertFalse(result.alreadyPaid)
        assertFalse(result.splitPayment)
    }

    @Test
    fun `handles text without diacritics and amount on the next line`() {
        val text = """
            FAKTURA NR 12/05/2024
            Sprzedawca
            Energia Plus S.A.
            Kwota do zaplaty
            1 234,56 zl
            Termin platnosci 2024-06-10
            Rachunek bankowy PL 61 1090 1014 0000 0712 1981 2874
            Mechanizm podzielonej płatności
        """.trimIndent()

        val result = InvoiceParser.parse(text)

        assertEquals("12/05/2024", result.invoiceNumber)
        assertEquals("Energia Plus S.A.", result.sellerName)
        assertEquals(BigDecimal("1234.56"), result.amount)
        assertEquals("PLN", result.currency)
        assertEquals(LocalDate.of(2024, 6, 10), result.dueDate)
        assertEquals("PL61109010140000071219812874", result.accountNumber)
        assertTrue(result.splitPayment)
    }

    @Test
    fun `computes due date from payment term in days`() {
        val text = """
            Faktura 2024/77
            Data wystawienia 10.01.2024
            Termin płatności: 14 dni
            Data sprzedaży 09.01.2024
            Do zapłaty 50,00 PLN
        """.trimIndent()

        assertEquals(LocalDate.of(2024, 1, 24), InvoiceParser.parse(text).dueDate)
    }

    @Test
    fun `detects already paid invoice`() {
        val paidByStatus = InvoiceParser.parse("Faktura 1/2024\nStatus: opłacona\nRazem brutto 99,99 zł")
        assertTrue(paidByStatus.alreadyPaid)

        val paidByRemaining = InvoiceParser.parse("Faktura 2/2024\nZapłacono: 99,99 zł\nPozostało do zapłaty: 0,00 zł")
        assertTrue(paidByRemaining.alreadyPaid)
        assertNull(paidByRemaining.amount)
    }

    @Test
    fun `parses English invoice with euro`() {
        val text = """
            Invoice number: INV-2024-0042
            Invoice date: 2024-02-01
            Due date: 2024-02-15
            IBAN: DE89 3704 0044 0532 0130 00
            Subtotal 1,000.00
            Total due 1,190.00 EUR
        """.trimIndent()

        val result = InvoiceParser.parse(text)

        assertEquals("INV-2024-0042", result.invoiceNumber)
        assertEquals(BigDecimal("1190.00"), result.amount)
        assertEquals("EUR", result.currency)
        assertEquals("DE89370400440532013000", result.accountNumber)
        assertEquals(LocalDate.of(2024, 2, 15), result.dueDate)
    }

    @Test
    fun `marks account number with invalid checksum as unverified and ignores dates as amounts`() {
        val text = """
            Faktura 5/2024 z dnia 12.03.2024
            Konto: 11 1111 1111 1111 1111 1111 1111
            Do zapłaty: 10,50 PLN
        """.trimIndent()

        val result = InvoiceParser.parse(text)

        assertEquals("PL11111111111111111111111111", result.accountNumber)
        assertFalse(result.accountVerified)
        assertEquals(BigDecimal("10.50"), result.amount)
    }

    @Test
    fun `prefers account number placed next to bank keyword`() {
        val text = """
            Faktura 9/2024
            Ref: 27 1140 2004 0000 3002 0135 5387
            Wpłaty prosimy kierować na rachunek:
            61 1090 1014 0000 0712 1981 2874
            Do zapłaty 10,00 zł
        """.trimIndent()

        assertEquals("PL61109010140000071219812874", InvoiceParser.parse(text).accountNumber)
    }

    @Test
    fun `finds account number split across lines`() {
        val text = """
            Faktura VAT 15/10/2026
            Numer rachunku: 61 1090 1014 0000
            0712 1981 2874
            Do zapłaty: 99,00 zł
        """.trimIndent()

        assertEquals("PL61109010140000071219812874", InvoiceParser.parse(text).accountNumber)
    }

    @Test
    fun `finds account number with spaces between every digit`() {
        val text = """
            Faktura 3/2026
            Konto bankowe:
            6 1 1 0 9 0 1 0 1 4 0 0 0 0 0 7 1 2 1 9 8 1 2 8 7 4
        """.trimIndent()

        assertEquals("PL61109010140000071219812874", InvoiceParser.parse(text).accountNumber)
    }

    @Test
    fun `repairs typical OCR mistakes in account number`() {
        val text = """
            Faktura 4/2026
            Nr konta: 61 1O9O 1014 OOOO 0712 l981 2874
        """.trimIndent()

        assertEquals("PL61109010140000071219812874", InvoiceParser.parse(text).accountNumber)
    }

    @Test
    fun `finds account number preceded by other numbers in the same line`() {
        val text = """
            Faktura 5/2026
            Rachunek bankowy (mBank) 2026 61109010140000071219812874
        """.trimIndent()

        assertEquals("PL61109010140000071219812874", InvoiceParser.parse(text).accountNumber)
    }

    @Test
    fun `marketing email has no strong invoice signal`() {
        val text = """
            Nowości tygodnia w Empiku!
            Książka "Przykład" teraz 39,99 zł
            Zestaw klocków 199,99 zł
            Zamówienia powyżej 49 zł z darmową dostawą. Fakturę pobierzesz w swoim koncie.
        """.trimIndent()

        val result = InvoiceParser.parse(text)

        assertTrue(result.looksLikeInvoice)
        assertFalse(result.strongInvoiceSignal)
        assertEquals(0, result.amountConfidence)
        assertNull(result.accountNumber)
    }

    @Test
    fun `real invoice has strong signal and confident amount`() {
        val result = InvoiceParser.parse("Faktura VAT nr 1/2026\nDo zapłaty: 10,00 zł")

        assertTrue(result.strongInvoiceSignal)
        assertEquals(3, result.amountConfidence)
    }

    // Układ zeskanowanej faktury, w którym OCR rozdziela kolumnę etykiet i kolumnę wartości.
    private fun scannedInvoice(account: String) = """
        Sprzedawca:
        KDKSYSTEM KSIĘŻAK KRZYSZTOF
        Adres:
        NIP:
        Rachunek:
        Telefon:
        PROMIENNA 10 A 05-270 MARKI
        NIP: 5341363580 Regon: 140994064 BDO: 4
        $account PKO BP SA ODDZIAŁ 2 W PRUSZKOWIE
        MARKI, data wystawienia: 24.10.2026
        Nabywca:
        Faktura FA 94/10/26
        Razem: 1 538,00 353,74 1 891,74
        Należność ogółem: 1 891,74 zł słownie: jeden tysiąc osiemset dziewięćdziesiąt jeden złotych
        Pozostaje do zapłaty: 1 891,74 zł.
        Płatność: przelew
        Termin zapłaty do: 31.10.2026
    """.trimIndent()

    @Test
    fun `parses scanned invoice with labels separated from values`() {
        val result = InvoiceParser.parse(scannedInvoice("42102010550000910201464734"))

        assertEquals("PL42102010550000910201464734", result.accountNumber)
        assertTrue(result.accountVerified)
        assertEquals("FA 94/10/26", result.invoiceNumber)
        assertEquals("KDKSYSTEM KSIĘŻAK KRZYSZTOF", result.sellerName)
        assertEquals("5341363580", result.sellerNip)
        assertEquals(BigDecimal("1891.74"), result.amount)
        assertEquals(LocalDate.of(2026, 10, 31), result.dueDate)
        assertEquals(LocalDate.of(2026, 10, 24), result.issueDate)
        assertTrue(result.strongInvoiceSignal)
        assertFalse(result.marketing)
    }

    @Test
    fun `repairs OCR letters in account number far from its label`() {
        val result = InvoiceParser.parse(scannedInvoice("421O2O1O55OOOO91O2O1464734"))

        assertEquals("PL42102010550000910201464734", result.accountNumber)
        assertTrue(result.accountVerified)
    }

    @Test
    fun `keeps account number with OCR digit error as unverified`() {
        val result = InvoiceParser.parse(scannedInvoice("42102010550000910201464784"))

        assertEquals("PL42102010550000910201464784", result.accountNumber)
        assertFalse(result.accountVerified)
    }

    @Test
    fun `verified account from another source wins over unverified one`() {
        val ocr = ParsedInvoice(accountNumber = "PL42102010550000910201464784", accountVerified = false)
        val body = ParsedInvoice(accountNumber = "PL42102010550000910201464734")

        val merged = ocr.mergeWith(body)

        assertEquals("PL42102010550000910201464734", merged.accountNumber)
        assertTrue(merged.accountVerified)
    }

    @Test
    fun `detects Empik newsletter with consumer credit disclaimer`() {
        val text = """
            Jeśli newsletter nie wyświetla się poprawnie, użyj tego linku.
            Regulamin usług "Empik Premium" znajdziesz tutaj.
            Akcja "Empik Premium taniej przy zakupach za min. 50 zł w salonie, w tym książki" jest skierowana do klientów.
            Dla kredytu na zakup towarów i usług z karencją: Rzeczywista Roczna Stopa Oprocentowania (RRSO) 0%;
            cena towaru 2000 zł; całkowita kwota kredytu 2000 zł; całkowita kwota do zapłaty 2000 zł;
            całkowity koszt kredytu 0 zł; wysokość 20 równych rat miesięcznych 100 zł.
            Niniejsza propozycja nie jest ofertą w rozumieniu art. 66 Kodeksu Cywilnego.
            Nie chcę otrzymywać newslettera.
            Empik S.A. z siedzibą w Warszawie, NIP: 5260207427, kapitał zakładowy 97 028 362,00 zł, opłacony w całości.
        """.trimIndent()

        val result = InvoiceParser.parse(text)

        assertTrue(result.marketing)
        assertFalse(result.strongInvoiceSignal)
        assertNull(result.accountNumber)
    }

    @Test
    fun `order confirmation without the word invoice is not an invoice and is already paid`() {
        val text = """
            Szczegóły zamówienia ZN411806/26
            Grzebienie dolnego kosza pasuje do zmywarki Siemens SN66N080EU/18
            632-UK-0167
            Towar gotowy do wysyłki.
            1 szt. 183,00 zł
            InPost Paczkomat 24/7 (przedpłata) 13,99 zł
            Zapłacono: 196,99 zł
            Twoje zakupy realizowane z InPostPay
        """.trimIndent()

        val result = InvoiceParser.parse(text)

        assertFalse(result.looksLikeInvoice)
        assertTrue(result.alreadyPaid)
    }

    @Test
    fun `recognises the word invoice in other languages`() {
        listOf("Rechnung Nr. 4711", "Factura 12/2026", "Facture n° 15", "Fattura 3", "Fakturę wysłaliśmy", "INVOICE #9")
            .forEach { assertTrue(it, InvoiceParser.looksLikeInvoice(it)) }
        listOf("Potwierdzenie zamówienia", "Numer rachunku: 61 1090", "Do zapłaty 10 zł")
            .forEach { assertFalse(it, InvoiceParser.looksLikeInvoice(it)) }
    }

    @Test
    fun `converts html email to text`() {
        val html = "<html><head><style>p{}</style></head><body><p>Faktura nr&nbsp;A/1/2024</p>" +
            "<table><tr><td>Do zap&#322;aty:</td><td>15,00 z&#322;</td></tr></table></body></html>"
        val result = InvoiceParser.parse(TextUtils.htmlToText(html))

        assertEquals("A/1/2024", result.invoiceNumber)
        assertEquals(BigDecimal("15.00"), result.amount)
        assertEquals("PLN", result.currency)
    }

    @Test
    fun `validates NIP checksum`() {
        assertTrue(InvoiceParser.isNipValid("5261040828"))
        assertFalse(InvoiceParser.isNipValid("5261040829"))
    }

    @Test
    fun `merges data from several sources`() {
        val fromPdf = ParsedInvoice(amount = BigDecimal("10.00"), currency = "PLN", looksLikeInvoice = true)
        val fromBody = ParsedInvoice(accountNumber = "PL61109010140000071219812874", amount = BigDecimal("99.00"))

        val merged = fromPdf.mergeWith(fromBody)

        assertEquals(BigDecimal("10.00"), merged.amount)
        assertEquals("PL61109010140000071219812874", merged.accountNumber)
    }
}
