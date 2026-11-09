package au.opendepth.app.util

/**
 * Minimal XML 1.0 text escaping.
 *
 * Characters that are illegal in XML 1.0 documents (the C0 control codes other
 * than tab, newline and carriage return, plus U+FFFE and U+FFFF) are dropped
 * rather than escaped, because no numeric character reference can make them
 * valid.
 */
object XmlEscape {

    fun text(value: String): String {
        val out = StringBuilder(value.length + ESCAPE_HEADROOM)
        for (character in value) {
            when (character) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                '\r' -> out.append("&#13;")
                else -> if (isLegalXmlCharacter(character)) out.append(character)
            }
        }
        return out.toString()
    }

    /**
     * Surrogates are allowed through so characters above the BMP survive: a
     * Kotlin string holds them as a UTF-16 pair, and dropping either half
     * would mangle the text. Unpaired surrogates cannot arrive from text input.
     */
    private fun isLegalXmlCharacter(character: Char): Boolean {
        val code = character.code
        return character == '\t' ||
            character == '\n' ||
            code in 0x20..0xFFFD
    }

    private const val ESCAPE_HEADROOM = 16
}
