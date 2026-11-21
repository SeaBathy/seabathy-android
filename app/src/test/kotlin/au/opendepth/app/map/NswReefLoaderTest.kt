package au.opendepth.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.UnknownHostException

class NswReefLoaderTest {

    @get:Rule val temp = TemporaryFolder()

    private fun page(id: Int) =
        """{"features":[{"type":"Feature","id":$id,"geometry":{"type":"Point","coordinates":[151.3,-33.8]},"properties":{}}]}"""

    private val tiles = (0 until 6).map { NswReefTiles.Tile(3767 + it, 2457) }

    /** A fetch that answers each tile with one reef, counting the calls, failing as [fail] says. */
    private class Fetcher(var fail: (String) -> Exception? = { null }) {
        var calls = 0
        fun fetch(url: String): String {
            calls++
            fail(url)?.let { throw it }
            val west = Regex("geometry=([0-9.]+)").find(url)!!.groupValues[1]
            return """{"features":[{"type":"Feature","id":${west.hashCode()},"geometry":{"type":"Point","coordinates":[151.3,-33.8]},"properties":{}}]}"""
        }
    }

    private fun loader(fetcher: Fetcher, clock: () -> Long = { 1_000_000L }) =
        NswReefLoader(NswReefStore(temp.root) { fetcher.fetch(it) }, clock)

    @Test
    fun `kept tiles are shown at once, then the fetched ones every few and at the end`() {
        val fetcher = Fetcher()
        val loader = loader(fetcher)
        val shows = mutableListOf<Set<String>>()
        loader.load(tiles, current = { true }, show = { shows += it.keys.toSet() })
        assertEquals(6, fetcher.calls)
        // Nothing kept to start with: after the first four, then after the last two.
        assertEquals(listOf(4, 6), shows.map { it.size })
    }

    @Test
    fun `the same set isn't given to the map again`() {
        val fetcher = Fetcher()
        val loader = loader(fetcher)
        var last: Set<String> = emptySet()
        loader.load(tiles, current = { true }, show = { last = it.keys.toSet() })
        loader.shown = last // as the map takes it
        var shows = 0
        repeat(3) { loader.load(tiles, current = { true }, show = { shows++ }) }
        assertEquals(0, shows)
        assertEquals(6, fetcher.calls)
        // A smaller view is a different set: given once.
        loader.load(tiles.take(2), current = { true }, show = { shows++; loader.shown = it.keys.toSet() })
        loader.load(tiles.take(2), current = { true }, show = { shows++ })
        assertEquals(1, shows)
        // Cleared: shown again from scratch.
        loader.forget()
        loader.load(tiles.take(2), current = { true }, show = { shows++ })
        assertEquals(2, shows)
    }

    @Test
    fun `offline, the first failure ends the look and isn't held against the tile`() {
        val fetcher = Fetcher(fail = { UnknownHostException("no internet") })
        val loader = loader(fetcher)
        loader.load(tiles, current = { true }, show = { })
        assertEquals(1, fetcher.calls)
        // Straight after, nothing is tried; a little later, or once the internet is back, it is.
        loader.load(tiles, current = { true }, show = { })
        assertEquals(1, fetcher.calls)
        fetcher.fail = { null }
        loader.online()
        var shown = 0
        loader.load(tiles, current = { true }, show = { shown = it.size })
        assertEquals(7, fetcher.calls)
        assertEquals(6, shown)
    }

    @Test
    fun `after the offline pause, tiles are tried again`() {
        var now = 1_000_000L
        val fetcher = Fetcher(fail = { java.net.ConnectException("no route") })
        val loader = loader(fetcher) { now }
        loader.load(tiles, current = { true }, show = { })
        fetcher.fail = { null }
        now += NswReefLoader.OFFLINE_PAUSE_MS + 1
        loader.load(tiles, current = { true }, show = { })
        assertEquals(7, fetcher.calls)
    }

    @Test
    fun `a tile the service fails is skipped for a while, the rest still come`() {
        var now = 1_000_000L
        val bad = tiles[2]
        val fetcher = Fetcher(fail = { url -> if (url.contains("geometry=" + String.format(java.util.Locale.US, "%.6f", bad.west))) IOException("HTTP 500") else null })
        val loader = loader(fetcher) { now }
        var shown = 0
        loader.load(tiles, current = { true }, show = { shown = it.size })
        assertEquals(6, fetcher.calls)
        assertEquals(5, shown)
        loader.load(tiles, current = { true }, show = { })
        assertEquals(6, fetcher.calls)
        now += NswReefLoader.RETRY_MS + 1
        loader.load(tiles, current = { true }, show = { })
        assertEquals(7, fetcher.calls)
    }

    @Test
    fun `a newer look, or switching off, stops the fetching`() {
        val fetcher = Fetcher()
        val loader = loader(fetcher)
        loader.load(tiles, current = { fetcher.calls < 2 }, show = { })
        assertEquals(2, fetcher.calls)
        loader.load(tiles, current = { false }, show = { throw AssertionError("shown after it was stopped") })
        assertEquals(2, fetcher.calls)
    }

    @Test
    fun `what is kept stays under its cap, the tiles looked at longest ago going first`() {
        val store = NswReefStore(temp.root, maxBytes = 1) { page(1) }
        store.download(tiles[0])
        store.download(tiles[1])
        // A cap smaller than one tile keeps none; a big one keeps all.
        assertEquals(0L, store.keptBytes())
        val roomy = NswReefStore(temp.newFolder("roomy"), maxBytes = 10_000_000) { page(1) }
        tiles.forEach { roomy.download(it) }
        assertTrue(roomy.keptBytes() > 0)
        val oneTile = roomy.keptBytes() / tiles.size
        val f = temp.root.resolve("roomy")
        // The oldest file goes when the cap only fits five.
        f.listFiles()!!.sortedBy { it.name }.forEachIndexed { i, file -> file.setLastModified(1_000_000L + i * 1000L) }
        val five = NswReefStore(f, maxBytes = oneTile * 5 + oneTile / 2) { page(1) }
        five.prune()
        assertEquals(5, f.listFiles()!!.size)
    }

    @Test
    fun `clearing removes every kept tile`() {
        val store = NswReefStore(temp.root) { page(1) }
        tiles.take(3).forEach { store.download(it) }
        assertTrue(store.keptBytes() > 0)
        assertTrue(store.clear() > 0)
        assertEquals(0L, store.keptBytes())
        assertNull(store.cached(tiles[0]))
    }

    @Test
    fun `what counts as offline`() {
        assertTrue(NswReefLoader.isOffline(UnknownHostException()))
        assertTrue(NswReefLoader.isOffline(java.net.ConnectException()))
        assertTrue(NswReefLoader.isOffline(java.net.NoRouteToHostException()))
        assertTrue(NswReefLoader.isOffline(java.net.SocketTimeoutException()))
        assertTrue(!NswReefLoader.isOffline(IOException("the reef service answered 500")))
    }
}
