package com.seabathy.bathypicker.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A whole overseas build offline: fake servers make the GMRT and NOAA files for the box, and the
 * engine lays NOAA over GMRT into one GeoTIFF, which is read back.
 */
class WorldBuildTest {

    private lateinit var dir: File
    private lateinit var pool: ExecutorService

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("worldbuild").toFile()
        pool = Executors.newFixedThreadPool(4)
    }

    @After
    fun tearDown() {
        pool.shutdownNow()
        dir.deleteRecursively()
    }

    /** NOAA has a 10 m DEM over the west half of the box (west of 81.79 W) and nothing east of it. */
    private val split = -81.79
    private val bbox = doubleArrayOf(-81.80, 24.46, -81.78, 24.48)

    private val net = FakeNet(
        texts = mapOf(World.NOAA_SERVICE + "/query" to """{"features":[{"attributes":{"Name":"west half","LowPS":9.2592592e-05,
            "VerticalDatum":"NAVD 88"},"geometry":{"rings":[[[-81.9,24.4],[-81.9,24.6],[$split,24.6],[$split,24.4],[-81.9,24.4]]]}}]}"""),
        files = mapOf(
            World.GMRT_SERVER to { url, f ->
                val n = FakeNet.param(url, "north")[0]
                val s = FakeNet.param(url, "south")[0]
                val e = FakeNet.param(url, "east")[0]
                val w = FakeNet.param(url, "west")[0]
                val res = 0.0005
                FakeNet.tiff(f, Math.ceil((e - w) / res).toInt(), Math.ceil((n - s) / res).toInt(), w, n, res, res, Float.NaN) { _, _ -> -50f }
            },
            World.NOAA_SERVICE + "/exportImage" to { url, f ->
                val b = FakeNet.param(url, "bbox")
                val size = FakeNet.param(url, "size")
                val cols = size[0].toInt()
                val rows = size[1].toInt()
                val rx = (b[2] - b[0]) / cols
                val ry = (b[3] - b[1]) / rows
                FakeNet.tiff(f, cols, rows, b[0], b[3], rx, ry, -99999f) { x, _ -> if (b[0] + (x + 0.5) * rx < split) -5f else null }
            }
        )
    )

    @Test
    fun `NOAA over GMRT, read back`() {
        val a = World.assess(bbox, 250.0 * 1048576, 10_000_000_000L, dir, net, null)
        assertTrue(a.line, a.ok)
        assertEquals(10.3, a.resM, 1e-9)
        val job = Job()
        assertTrue(job.start())
        val src = TileSource(pool, 64L shl 20)
        val prep = World.prepare(a, dir, net, pool, job, src.bytesDownloaded)
        assertEquals(1, prep.plan.bases.size)
        assertEquals(listOf(World.NOAA), prep.plan.chosen.map { it.id })
        assertEquals(200.0, prep.plan.bandM[World.NOAA]!!, 1e-9)
        assertEquals(0.0005 * Catalog.M_PER_DEG, prep.gmrtResM, 1e-6)
        assertTrue(src.bytesDownloaded.get() > 0)

        val out = File(dir, "out.tif")
        val builder = Builder(prep.plan, job, src, pool)
        builder.workDir = dir
        val result = RandomAccessFile(out, "rw").use { raf -> builder.run(raf.channel, raf.channel) }
        assertEquals(100.0, result["valid_percent"] as Double, 1e-9)

        // Half the pixels came from NOAA, the rest from GMRT alone.
        val total = (builder.width.toLong() * builder.height)
        val noaa = builder.layerPixels[World.NOAA]!!
        assertTrue("$noaa of $total", noaa > total * 0.45 && noaa < total * 0.55)
        assertEquals(total - noaa, builder.basePixels)
        val used = World.used(prep, builder.layerPixels, builder.basePixels)
        assertEquals(listOf(World.NOAA, World.GMRT), used)

        // Read it back: NOAA's -5 m well inside its half, GMRT's -50 m east of it, a ramp between.
        val t = RemoteTiff.open(out.toURI().toString(), null)
        assertEquals(4326, t.epsg)
        val L = t.levels[0]
        fun at(lon: Double, lat: Double): Float {
            val x = ((lon - t.gt[0]) / t.gt[1]).toInt()
            val y = ((lat - t.gt[3]) / t.gt[5]).toInt()
            val tx = x / L.tileW
            val ty = y / L.tileH
            val idx = t.tileRow(L, ty, null)
            val raw = Http.range(t.url, idx[0][tx], idx[1][tx].toInt(), null)
            return t.decode(L, raw, 0, raw.size)[(y - ty * L.tileH) * L.tileW + (x - tx * L.tileW)]
        }
        assertEquals(-5f, at(-81.797, 24.47), 1e-4f)
        assertEquals(-5f, at(-81.7995, 24.4605), 1e-4f)
        assertEquals(-50f, at(-81.785, 24.47), 1e-4f)
        val edge = at(-81.7905, 24.47)
        assertTrue("blended edge $edge", edge < -5f && edge > -50f)

        val text = World.creditsText(prep, used, out.name, "SeaBathy test")
        assertTrue(text, "NOAA NCEI coastal DEMs, 10 m." in text && "west half" in text && "about 56 m here" in text)
        prep.cleanup()
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("world-") })
    }
}
