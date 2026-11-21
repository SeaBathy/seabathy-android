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
 * A marine park mosaic lying mostly in deep water (imas_m) is laid under IMAS's zones finer than
 * it: on a reef the 10 m IMAS wins, in deep water the park beats IMAS's coarse zones. Made-up
 * zones and park, offline.
 */
class DeepParkLayeringTest {

    private lateinit var dir: File
    private lateinit var pool: ExecutorService

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("deeppark").toFile()
        pool = Executors.newFixedThreadPool(4)
    }

    @After
    fun tearDown() {
        pool.shutdownNow()
        dir.deleteRecursively()
    }

    private val box = doubleArrayOf(150.000, -30.010, 150.015, -30.000)
    private val split = 150.0075   // the 10 m zone (a reef) has depths west of here only

    /** One-tile IMAS-like zone in Australian Albers, pxM metres a pixel, value(lon) or null for NoData. */
    private fun zone(name: String, pxM: Double, value: (Double) -> Float?): File {
        val f = File(dir, name)
        var x0 = Double.MAX_VALUE
        var y0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE
        var y1 = -Double.MAX_VALUE
        for (lon in doubleArrayOf(box[0], box[2])) for (lat in doubleArrayOf(box[1], box[3])) {
            val xy = Albers.forward(lon, lat)
            x0 = minOf(x0, xy[0]); x1 = maxOf(x1, xy[0]); y0 = minOf(y0, xy[1]); y1 = maxOf(y1, xy[1])
        }
        x0 -= 300; y0 -= 300; x1 += 300; y1 += 300
        val w = Math.ceil((x1 - x0) / pxM).toInt()
        val h = Math.ceil((y1 - y0) / pxM).toInt()
        val t = TiffWriter.TILE
        require(w <= t && h <= t)
        RandomAccessFile(f, "rw").use { raf ->
            val tw = TiffWriter(raf.channel, raf.channel, w, h, doubleArrayOf(x0, pxM, 0.0, y1, 0.0, -pxM), 9473, 3.4e38f)
            tw.writeTile(0, 0, 0, FloatArray(t * t) { i ->
                val x = i % t
                val y = i / t
                if (x >= w || y >= h) 3.4e38f
                else value(Albers.inverse(x0 + (x + 0.5) * pxM, y1 - (y + 0.5) * pxM)[0]) ?: 3.4e38f
            })
            tw.finish()
        }
        return f
    }

    private fun plan(imasM: Double): Planner.Plan {
        val coarse = zone("coarse.tif", 64.0) { -500f }
        val fine = zone("fine.tif", 10.0) { lon -> if (lon < split) -20f else null }
        val parkFile = File(dir, "park.tif")
        val r = 0.000135
        FakeNet.tiff(parkFile, Math.ceil((box[2] - box[0] + 0.006) / r).toInt(), Math.ceil((box[3] - box[1] + 0.006) / r).toInt(),
            box[0] - 0.003, box[3] + 0.003, r, r, 3.4e38f) { _, _ -> -300f }
        val json = """{"schema":"bathypicker-surveys/v1","built":"x","surveys":[{"id":"imas-amp-deep","name":"Deep marine park mosaic (IMAS)",
            "kind":"park","year":2026,"res_m":15,"imas_m":$imasM,"files":[{"url":"${Surveys.PARK_HOST}Deep.tif","crs":"EPSG:4326",
            "bbox":[149.9,-30.1,150.1,-29.9]}],"bbox":[149.9,-30.1,150.1,-29.9],"licence":"CC BY 4.0"}]}"""
        val listed = Surveys.parse(json, "test")!!.list.single()
        val sf = Surveys.SFile()
        sf.url = parkFile.toURI().toString()
        sf.crs = "EPSG:4326"
        sf.proj = Proj.parse(sf.crs)
        sf.bbox = listed.files[0].bbox
        sf.cells = listed.files[0].cells
        val park = listed.withFiles(listOf(sf))

        val plan = Planner.Plan()
        plan.p = Planner.parse(box, null, "wgs84", "auto", "auto")
        plan.crs = "wgs84"
        plan.resM = 10.0
        plan.finestM = 10.0
        plan.imasResM = 10.0
        plan.filename = "t.tif"
        plan.blend = true
        for ((z, file, px) in listOf(Triple(Catalog.ZONES[5], coarse, 64.0), Triple(Catalog.ZONES[0], fine, 10.0))) {
            val info = Catalog.Info()
            info.tiff = RemoteTiff.open(file.toURI().toString(), null)
            info.pxM = px
            plan.order.add(z)
            plan.orderInfo.add(info)
        }
        plan.chosen.add(park)
        plan.surveyTiffs[sf.url] = RemoteTiff.open(sf.url, null)
        plan.bandM[park.id] = 30.0
        return plan
    }

    private fun build(plan: Planner.Plan): (Double, Double) -> Float {
        val job = Job().also { assertTrue(it.start()) }
        val out = File(dir, "out.tif")
        val b = Builder(plan, job, TileSource(pool, 64L shl 20), pool)
        b.workDir = dir
        RandomAccessFile(out, "rw").use { raf -> b.run(raf.channel, raf.channel) }
        val t = RemoteTiff.open(out.toURI().toString(), null)
        val L = t.levels[0]
        return { lon, lat ->
            val x = ((lon - t.gt[0]) / t.gt[1]).toInt()
            val y = ((lat - t.gt[3]) / t.gt[5]).toInt()
            val idx = t.tileRow(L, y / L.tileH, null)
            val tx = x / L.tileW
            val raw = Http.range(t.url, idx[0][tx], idx[1][tx].toInt(), null)
            t.decode(L, raw, 0, raw.size)[(y % L.tileH) * L.tileW + (x % L.tileW)]
        }
    }

    @Test
    fun `a deep-water park goes under the finer IMAS and over the coarser`() {
        val at = build(plan(64.0))
        assertEquals(-20f, at(split - 0.004, -30.005), 1e-3f)     // the reef: IMAS's 10 m zone, not the 15 m park
        assertEquals(-300f, at(split + 0.004, -30.005), 1e-3f)    // deep water: the park, not IMAS's 64 m zone
        assertEquals(-20f, at(split - 0.0005, -30.005), 1e-3f)    // IMAS's own right to the edge of its 10 m zone,
        val edge = at(split - 0.00005, -30.005)                    // with only its last pixels faded into the park
        assertTrue("edge $edge", edge <= -20f && edge > -300f)
    }

    @Test
    fun `a park finer than IMAS stays on top of it`() {
        val at = build(plan(0.0))
        assertEquals(-300f, at(split - 0.004, -30.005), 1e-3f)
        assertEquals(-300f, at(split + 0.004, -30.005), 1e-3f)
    }

    @Test
    fun `the sources file says where a deep-water park is used`() {
        val text = Credits.text(plan(64.0), "t.tif", "test")
        assertTrue(text, "Laid under the IMAS composite's zones finer than it" in text)
        assertTrue(Credits.text(plan(0.0), "t.tif", "test").let { "Laid under" !in it })
    }
}
