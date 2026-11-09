package au.opendepth.app.download

import org.junit.Assert.assertEquals
import org.junit.Test

class RedactUrlTest {

    @Test
    fun `query strings, where API keys live, are dropped`() {
        assertEquals(
            "https://tiles.example.com/3/2/1.png?…",
            redactUrl("https://tiles.example.com/3/2/1.png?key=SECRET&style=x")
        )
    }

    @Test
    fun `user-info credentials are dropped`() {
        assertEquals("http://host.example/a/1.png", redactUrl("http://user:pass@host.example/a/1.png"))
    }

    @Test
    fun `plain urls are unchanged`() {
        assertEquals("https://tile.openstreetmap.org/1/2/3.png", redactUrl("https://tile.openstreetmap.org/1/2/3.png"))
    }
}
