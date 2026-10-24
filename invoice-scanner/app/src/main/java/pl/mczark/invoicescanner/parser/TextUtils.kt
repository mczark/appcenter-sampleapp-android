package pl.mczark.invoicescanner.parser

import java.util.Locale

object TextUtils {

    private val POLISH_FOLD = mapOf(
        'ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l', 'ń' to 'n',
        'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z',
    )

    /**
     * Małe litery bez polskich znaków diakrytycznych. Zachowuje długość tekstu,
     * dzięki czemu indeksy z wersji "złożonej" pasują do oryginału.
     */
    fun fold(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        if (lower.length != text.length) {
            return text.map { c -> c.lowercaseChar().let { POLISH_FOLD[it] ?: it } }.joinToString("")
        }
        return String(CharArray(lower.length) { i -> POLISH_FOLD[lower[i]] ?: lower[i] })
    }

    /** Ujednolica spacje i końce linii; usuwa puste linie. */
    fun normalizeLines(text: String): List<String> =
        text.replace(' ', ' ')
            .replace(' ', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { it.replace(Regex("[ \\t]+"), " ").trim() }
            .filter { it.isNotEmpty() }

    private val BLOCK_TAGS = Regex(
        "(?i)<\\s*(br|/p|/div|/tr|/li|/h[1-6]|/table|p|div|tr|li|h[1-6])\\b[^>]*>"
    )
    private val CELL_TAGS = Regex("(?i)<\\s*/?\\s*t[dh]\\b[^>]*>")
    private val DROP_BLOCKS = Regex("(?is)<\\s*(style|script|head)\\b.*?<\\s*/\\s*\\1\\s*>")
    private val ANY_TAG = Regex("<[^>]+>")
    private val NUMERIC_ENTITY = Regex("&#(x?)([0-9a-fA-F]+);")

    /** Prosta konwersja HTML -> tekst, wystarczająca do wyszukiwania danych faktury. */
    fun htmlToText(html: String): String {
        var text = html.replace(DROP_BLOCKS, " ")
        text = text.replace(BLOCK_TAGS, "\n")
        text = text.replace(CELL_TAGS, " ")
        text = text.replace(ANY_TAG, "")
        text = text.replace(NUMERIC_ENTITY) { m ->
            val radix = if (m.groupValues[1].isEmpty()) 10 else 16
            m.groupValues[2].toIntOrNull(radix)?.let { String(Character.toChars(it)) } ?: m.value
        }
        return text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&oacute;", "ó")
            .replace("&Oacute;", "Ó")
    }
}
