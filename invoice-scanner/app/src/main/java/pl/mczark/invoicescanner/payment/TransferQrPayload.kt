package pl.mczark.invoicescanner.payment

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Treść kodu QR do przelewu wg rekomendacji Związku Banków Polskich (2013),
 * obsługiwana przez aplikacje mobilne większości polskich banków:
 *
 * `NIP|PL|NRB|KWOTA_W_GROSZACH(6)|ODBIORCA(20)|TYTUŁ(32)|||`
 */
object TransferQrPayload {

    private val MAX_AMOUNT = BigDecimal("9999.99")

    /** Zwraca null, gdy danych nie da się zakodować (np. inna waluta niż PLN lub zbyt duża kwota). */
    fun build(
        iban: String,
        amount: BigDecimal?,
        currency: String?,
        recipient: String,
        title: String,
        nip: String? = null,
    ): String? {
        if (!iban.startsWith("PL") || iban.length != 28) return null
        if (currency != null && currency != "PLN") return null
        if (amount != null && (amount.signum() < 0 || amount > MAX_AMOUNT)) return null

        val grosze = amount?.setScale(2, RoundingMode.HALF_UP)?.movePointRight(2)?.toInt()
        return listOf(
            nip?.filter { it.isDigit() }?.takeIf { it.length == 10 }.orEmpty(),
            "PL",
            iban.substring(2),
            grosze?.toString()?.padStart(6, '0').orEmpty(),
            sanitize(recipient, 20),
            sanitize(title, 32),
            "",
            "",
            "",
        ).joinToString("|")
    }

    private fun sanitize(value: String, maxLength: Int): String =
        value.replace('|', ' ').replace(Regex("\\s+"), " ").trim().take(maxLength).trim()
}
