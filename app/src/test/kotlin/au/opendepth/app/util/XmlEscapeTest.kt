package au.opendepth.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class XmlEscapeTest {

    @Test
    fun `the five predefined entities are escaped`() {
        assertEquals("&amp;&lt;&gt;&quot;&apos;", XmlEscape.text("&<>\"'"))
    }

    @Test
    fun `plain text is unchanged`() {
        assertEquals("Reef edge 12m", XmlEscape.text("Reef edge 12m"))
    }

    @Test
    fun `tabs and newlines survive but carriage returns are encoded`() {
        assertEquals("a\tb\nc&#13;d", XmlEscape.text("a\tb\nc\rd"))
    }

    @Test
    fun `C0 control characters are dropped`() {
        val withControls = "a" + 1.toChar() + 2.toChar() + 11.toChar() + "b"
        assertEquals("ab", XmlEscape.text(withControls))
    }

    @Test
    fun `the non characters at the end of the BMP are dropped`() {
        val withNonCharacters = "a" + 0xFFFE.toChar() + 0xFFFF.toChar() + "b"
        assertEquals("ab", XmlEscape.text(withNonCharacters))
    }

    @Test
    fun `characters above the BMP survive as surrogate pairs`() {
        val astral = "a" + 0xD800.toChar() + 0xDFFF.toChar() + "b"
        assertEquals(astral, XmlEscape.text(astral))
    }

    @Test
    fun `non ASCII text is preserved`() {
        assertEquals("Île ✛ 12°", XmlEscape.text("Île ✛ 12°"))
    }

    @Test
    fun `an empty string stays empty`() {
        assertEquals("", XmlEscape.text(""))
    }
}
