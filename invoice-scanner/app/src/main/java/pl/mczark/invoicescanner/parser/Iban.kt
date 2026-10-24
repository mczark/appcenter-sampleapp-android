package pl.mczark.invoicescanner.parser

import java.math.BigInteger

/** Walidacja i formatowanie numerów rachunków (IBAN / polski NRB). */
object Iban {

    private val IBAN_LENGTHS = mapOf(
        "PL" to 28, "DE" to 22, "GB" to 22, "FR" to 27, "ES" to 24, "IT" to 27,
        "NL" to 18, "BE" to 16, "AT" to 20, "CZ" to 24, "SK" to 24, "LT" to 20,
        "LV" to 21, "EE" to 20, "IE" to 22, "LU" to 20, "CH" to 21, "SE" to 24,
        "DK" to 18, "NO" to 15, "FI" to 18, "PT" to 25, "HU" to 28, "RO" to 24,
    )

    /**
     * Zwraca znormalizowany IBAN (bez spacji, wielkie litery) albo null, jeśli
     * numer jest niepoprawny. 26 cyfr bez prefiksu traktowane jest jako polski NRB.
     */
    fun normalize(raw: String): String? {
        val compact = raw.filter { it.isLetterOrDigit() }.uppercase()
        val iban = if (compact.length == 26 && compact.all { it.isDigit() }) "PL$compact" else compact
        if (iban.length < 15 || iban.length > 34) return null
        val country = iban.take(2)
        if (!country.all { it in 'A'..'Z' } || !iban.substring(2, 4).all { it.isDigit() }) return null
        val expectedLength = IBAN_LENGTHS[country] ?: return null
        if (iban.length != expectedLength) return null
        return if (isChecksumValid(iban)) iban else null
    }

    fun isChecksumValid(iban: String): Boolean {
        val rearranged = iban.substring(4) + iban.substring(0, 4)
        val numeric = buildString {
            for (c in rearranged) {
                when {
                    c.isDigit() -> append(c)
                    c in 'A'..'Z' -> append(c - 'A' + 10)
                    else -> return false
                }
            }
        }
        return BigInteger(numeric).mod(BigInteger.valueOf(97)) == BigInteger.ONE
    }

    /** "PL61109010140000071219812874" -> "PL61 1090 1014 0000 0712 1981 2874" */
    fun format(iban: String): String = iban.chunked(4).joinToString(" ")

    /** Numer krajowy (dla PL: 26 cyfr NRB), tak jak wpisuje się go w polskim przelewie. */
    fun domestic(iban: String): String =
        if (iban.startsWith("PL")) iban.substring(2) else iban
}
