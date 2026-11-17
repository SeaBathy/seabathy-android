package au.opendepth.app.download

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The offline downloader against a real (local) tile server: it keeps a steady pace, pauses
 * when the server says slow down, and stops knocking when the server keeps refusing.
 */
class PoliteDownloadTest {
    private lateinit var server: ServerSocket
    private val requests = AtomicInteger()
    private val slowDownsLeft = AtomicInteger()
    private val tile = ByteArray(200) { it.toByte() }

    /** A tiny tile server: /refuse/ answers 403, /slow/ answers 429 a set number of times, /ok/ serves a tile. */
    @Before
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        val path = reader.readLine()?.split(" ")?.getOrNull(1).orEmpty()
                        while (reader.readLine()?.isNotEmpty() == true) { /* headers */ }
                        requests.incrementAndGet()
                        val out = s.getOutputStream()
                        val nl = "" + '\r' + '\n'
                        fun head(status: String, extra: String = "", length: Int = 0) =
                            out.write(("HTTP/1.1 " + status + nl + extra + "Content-Length: " + length + nl + "Connection: close" + nl + nl).toByteArray())
                        when {
                            path.startsWith("/refuse/") -> head("403 Forbidden")
                            path.startsWith("/slow/") && slowDownsLeft.getAndDecrement() > 0 -> head("429 Too Many Requests", "Retry-After: 0" + nl)
                            else -> {
                                head("200 OK", "Content-Type: image/png" + nl, tile.size)
                                out.write(tile)
                            }
                        }
                        out.flush()
                    }
                }
            }
        }
    }

    @After
    fun stop() = server.close()

    private fun template(path: String) = "http://127.0.0.1:${server.localPort}/$path/{z}/{x}/{y}.png"

    @Test
    fun aServerThatKeepsRefusingIsLeftAlone() {
        val client = TileFetchClient()
        repeat(20) { i -> assertNull(client.fetchTile(template("refuse"), 10, i, 1)) }
        assertEquals(TileFetchClient.BLOCKED, client.refusal)
        // It stopped asking after the eighth refusal instead of trying all twenty tiles.
        assertEquals(8, requests.get())
    }

    @Test
    fun slowDownIsObeyedThenTheTileComes() {
        slowDownsLeft.set(2)
        val client = TileFetchClient()
        val started = System.currentTimeMillis()
        val bytes = client.fetchTile(template("slow"), 10, 1, 1)
        val took = System.currentTimeMillis() - started
        assertNotNull(bytes)
        assertNull(client.refusal)
        assertEquals(3, requests.get())
        // Two pauses of at least two seconds each.
        assertTrue("paused only $took ms", took >= 4_000)
    }

    @Test
    fun aSteadyPaceOfAboutTwentyTilesASecond() {
        val client = TileFetchClient()
        val started = System.currentTimeMillis()
        repeat(41) { i -> assertNotNull(client.fetchTile(template("ok"), 10, i, 1)) }
        val took = System.currentTimeMillis() - started
        // 41 tiles at 20 a second take at least two seconds.
        assertTrue("41 tiles in $took ms", took >= 1_900)
    }

    @Test
    fun resetStartsAfresh() {
        val client = TileFetchClient()
        repeat(8) { i -> client.fetchTile(template("refuse"), 10, i, 1) }
        assertNotNull(client.refusal)
        client.resetPoliteness()
        assertNull(client.refusal)
        assertNotNull(client.fetchTile(template("ok"), 10, 1, 1))
    }
}
