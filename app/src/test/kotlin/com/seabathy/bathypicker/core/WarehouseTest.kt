package com.seabathy.bathypicker.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Surveys cut from the AusSeabed warehouse (GeoServer WCS) for a box: the requests, the pieces,
 * and a whole build offline, with a fake server answering with small made-up GeoTIFFs.
 */
class WarehouseTest {

    private lateinit var dir: File
    private lateinit var pool: ExecutorService

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("warehouse").toFile()
        pool = Executors.newFixedThreadPool(4)
    }

    @After
    fun tearDown() {
        pool.shutdownNow()
        dir.deleteRecursively()
    }

    private val coverage = "ausseabed__Test_Bay_Bathymetry__2m_2018_OV"

    private fun survey(res: Double, vararg cells: DoubleArray): Surveys.Survey {
        val cellsJson = cells.joinToString(",") { c -> c.joinToString(",", "[", "]") }
        val json = """{"schema":"bathypicker-surveys/v1","built":"x","surveys":[{"id":"wcs-test","name":"Test bay 2018","kind":"wcs",
            "coverage":"$coverage","year":2018,"res_m":$res,"files":[{"bbox":[144.0,-39.0,145.0,-38.0],"cells":[$cellsJson]}],
            "bbox":[144.0,-39.0,145.0,-38.0],"stated_datum":"EGM2008","updated":"2026-03-25","licence":"CC BY 4.0",
            "credit":"Deakin University Marine Mapping"}]}"""
        return Surveys.parse(json, "test")!!.list.single()
    }

    @Test
    fun `a GetCoverage for the box in lat-long, scaled only when coarser`() {
        val b = doubleArrayOf(144.7, -38.36, 144.74, -38.33)
        val full = Warehouse.url(coverage, b, 1.0)
        assertTrue(full, full.startsWith(Warehouse.WCS_URL + "?service=WCS&version=2.0.1&request=GetCoverage&coverageId=$coverage&format=image/tiff"))
        assertTrue(full, "&subset=Long(144.700000,144.740000)&subset=Lat(-38.360000,-38.330000)" in full)
        assertTrue(full, "&subsettingCrs=http://www.opengis.net/def/crs/EPSG/0/4326&outputCrs=http://www.opengis.net/def/crs/EPSG/0/4326" in full)
        assertTrue(full, "geotiff:compression=Deflate" in full && "geotiff:tiling=true" in full && "OGC/1/nearest-neighbor" in full)
        assertFalse(full, "scalefactor" in full)
        assertTrue(Warehouse.url(coverage, b, 0.4).contains("&scalefactor=0.400000"))
    }

    @Test
    fun `asks at the build's detail, never finer than the survey's own`() {
        val s = survey(2.0, doubleArrayOf(144.7, -38.4, 144.75, -38.35))
        assertEquals(5.0, Warehouse.requestResM(s, 5.0), 0.0)
        assertEquals(0.4, Warehouse.scaleFactor(s, 5.0), 1e-12)
        assertEquals(2.0, Warehouse.requestResM(s, 1.0), 0.0)
        assertEquals(1.0, Warehouse.scaleFactor(s, 1.0), 0.0)
    }

    @Test
    fun `only the part of the box with the survey's data is asked for`() {
        val s = survey(2.0, doubleArrayOf(144.7, -38.4, 144.75, -38.35), doubleArrayOf(144.9, -38.2, 145.0, -38.1))
        val box = doubleArrayOf(144.72, -38.36, 144.8, -38.3)
        val a = Warehouse.area(s, box, 0.001)!!
        assertEquals(144.719, a[0], 1e-9)       // the box edge, plus the margin
        assertEquals(-38.361, a[1], 1e-9)
        assertEquals(144.751, a[2], 1e-9)       // the cell's edge, plus the margin
        assertEquals(-38.349, a[3], 1e-9)
        assertNull(Warehouse.area(s, doubleArrayOf(144.8, -38.3, 144.85, -38.25), 0.001))
    }

    @Test
    fun `big areas go in overlapping pieces of the survey's own pixels`() {
        val area = doubleArrayOf(144.6, -38.4, 144.7, -38.35)   // about 8.7 x 5.6 km
        val ps = Warehouse.pieces(area, 2.0, 5.0)             // 2 m survey: 4370 x 2780 of its pixels
        assertEquals(6, ps.size)
        assertEquals(area[0], ps.minOf { it[0] }, 1e-12)
        assertEquals(area[1], ps.minOf { it[1] }, 1e-12)
        assertEquals(area[2], ps.maxOf { it[2] }, 1e-12)
        assertEquals(area[3], ps.maxOf { it[3] }, 1e-12)
        val pad = Warehouse.MARGIN_PX * 5.0 / Catalog.M_PER_DEG
        for (p in ps) {
            val m = Warehouse.metres(p)
            assertTrue(m[0] / 2.0 <= Warehouse.CHUNK + 20 && m[1] / 2.0 <= Warehouse.CHUNK + 20)
        }
        // Neighbours overlap by the margin.
        val first = ps[0]
        val second = ps[1]
        assertTrue(second[0] < first[2])
        assertEquals(pad, first[3] - (area[1] + (area[3] - area[1]) / 2), 1e-12)
        assertEquals(1, Warehouse.pieces(doubleArrayOf(144.6, -38.4, 144.61, -38.39), 2.0, 2.0).size)
    }

    @Test
    fun `a box holding too much of a survey leaves it to a smaller box`() {
        val s = survey(0.5, doubleArrayOf(144.0, -39.0, 145.0, -38.0))
        assertTrue(Warehouse.tooBig(s, doubleArrayOf(144.2, -38.6, 144.5, -38.3)))
        assertFalse(Warehouse.tooBig(s, doubleArrayOf(144.2, -38.32, 144.23, -38.3)))
        val park = survey(0.5, doubleArrayOf(144.0, -39.0, 145.0, -38.0)).also { it.kind = Surveys.PARK }
        assertFalse(Warehouse.tooBig(park, doubleArrayOf(144.2, -38.6, 144.5, -38.3)))
        // What it takes once saved: the area at the asked detail, 4 bytes a pixel at most.
        val small = survey(2.0, doubleArrayOf(144.0, -39.0, 145.0, -38.0))
        val b = doubleArrayOf(144.7, -38.36, 144.74, -38.33)
        val m = Warehouse.metres(b)
        val bytes = Warehouse.savedBytes(listOf(small), b, 5.0)
        assertEquals(m[0] / 5 * (m[1] / 5) * 4, bytes, bytes * 0.02)
        assertEquals(0.0, Warehouse.savedBytes(listOf(park), b, 5.0), 0.0)
    }

    // ------------------------------------------------------------------ fetching and building

    private val box = doubleArrayOf(144.70, -38.36, 144.74, -38.33)
    private val split = 144.72
    private val coarse = 0.0002   // the fake server's pixels, degrees
    private val missed = "HTTP 404 from fake (InvalidSubsetting: Empty intersection after subsetting)"

    private fun subset(url: String, axis: String): List<Double> {
        val m = Regex("subset=$axis\\(([-0-9.]+),([-0-9.]+)\\)").find(url) ?: error("no $axis in $url")
        return listOf(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
    }

    /** The warehouse: -5 m west of the split (or what [value] says), nothing east of it. */
    private fun server(value: (Double) -> Float? = { lon -> if (lon < split) -5f else null }, answer: (String) -> Unit = {}) =
        FakeNet(files = mapOf(Warehouse.WCS_URL to { url, f ->
            answer(url)
            val (w, e) = subset(url, "Long")
            val (s, n) = subset(url, "Lat")
            val cols = Math.ceil((e - w) / coarse).toInt()
            val rows = Math.ceil((n - s) / coarse).toInt()
            FakeNet.tiff(f, cols, rows, w, n, coarse, coarse, 3.4028235e38f) { x, _ -> value(w + (x + 0.5) * coarse) }
        }))

    private fun plan(s: Surveys.Survey, resM: Double): Planner.Plan {
        val plan = Planner.Plan()
        plan.p = Planner.parse(box, null, false, "wgs84", "auto", "auto", "default", null, null, true)
        plan.crs = "wgs84"
        plan.resM = resM
        plan.finestM = resM
        plan.filename = "test.tif"
        plan.capBytes = 3.0 * Planner.GB
        plan.chosen.add(s)
        plan.bandM[s.id] = 30.0
        return plan
    }

    private fun started() = Job().also { assertTrue(it.start()) }

    @Suppress("UNCHECKED_CAST")
    private fun log(job: Job) = (job.snapshot()["log"] as List<String>).joinToString("\n")

    private fun leftInDir() = dir.listFiles()!!.filter { it.name.startsWith("wcs-") }

    @Before
    fun noWait() {
        Warehouse.retryWaitMs = 0
    }

    @Test
    fun `a coverage is cut for the box and read like any survey`() {
        val net = server()
        val s = survey(2.0, doubleArrayOf(144.6, -38.4, 144.75, -38.35))
        val plan = plan(s, 10.0)
        val job = started()
        val written = mutableListOf<File>()
        val temp = Warehouse.fetch(plan, dir, net, job, null, written)
        assertTrue(temp === written)
        assertEquals(1, temp.size)
        assertTrue(temp[0].exists())
        val asked = net.asked.single()
        assertTrue(asked, "scalefactor=0.200000" in asked)       // a 2 m survey for a 10 m build
        val got = plan.chosen.single()
        assertEquals("wcs-test", got.id)
        assertEquals(Surveys.WCS, got.kind)
        val f = got.files.single()
        assertTrue(f.url, f.url.startsWith("file:"))
        assertNotNull(f.proj)
        assertNotNull(plan.surveyTiffs[f.url])
        assertTrue(plan.notes.isEmpty())
        // The sources file names the warehouse, not the file in the cache.
        val text = Credits.text(plan, "test.tif", "SeaBathy test")
        assertTrue(text, Warehouse.sourceLine(got) in text && "Deakin University Marine Mapping" in text && "file:" !in text)
    }

    @Test
    fun `only the server's empty intersection means nothing is there`() {
        assertTrue(Warehouse.missed(IOException(missed)))
        assertTrue(Warehouse.missed(IOException("warehouse.ausseabed.gov.au sent no depths for this area (InvalidSubsetting: Empty intersection after subsetting).")))
        assertFalse(Warehouse.missed(IOException("HTTP 404 from warehouse.ausseabed.gov.au")))
        assertFalse(Warehouse.missed(IOException("HTTP 404 from warehouse.ausseabed.gov.au (NoSuchCoverage: Could not find coverage)")))
        assertFalse(Warehouse.missed(IOException("warehouse.ausseabed.gov.au sent no depths for this area.")))   // a portal's page
        // How World.HTTP words the server's reply.
        val report = """<?xml version="1.0" encoding="UTF-8"?><ows:ExceptionReport version="2.0.0">
            <ows:Exception exceptionCode="InvalidSubsetting" locator="">
            <ows:ExceptionText>Empty intersection after subsetting</ows:ExceptionText></ows:Exception></ows:ExceptionReport>"""
        assertEquals("InvalidSubsetting: Empty intersection after subsetting", World.reason(report))
        assertEquals("", World.reason("<html><body>Please sign in to the hotel wifi</body></html>"))
        assertEquals("bad box", World.reason("""{"message": "bad box"}"""))
    }

    @Test
    fun `pieces outside the survey are skipped, and a survey with none is left out with a note`() {
        val s = survey(2.0, doubleArrayOf(144.6, -38.4, 144.75, -38.35))
        val plan = plan(s, 2.0)
        val job = started()
        val net = server { throw IOException(missed) }
        Warehouse.fetch(plan, dir, net, job, null, null)
        assertTrue(plan.chosen.isEmpty())
        assertTrue(plan.bandM.isEmpty())
        assertEquals(1, net.asked.size)                       // not tried again: the box missed it
        assertTrue(plan.notes.joinToString(), "has no depths of it in this box" in plan.notes.joinToString())
        assertTrue(log(job), "has no depths of it in this box" in log(job))
        assertTrue(leftInDir().isEmpty())
    }

    @Test
    fun `any other failure is tried again, then the whole survey is left out with a note`() {
        for (failure in listOf("HTTP 404 from fake", "HTTP 500 from fake", "warehouse.ausseabed.gov.au sent no depths for this area.")) {
            val s = survey(2.0, doubleArrayOf(144.6, -38.4, 144.75, -38.35))
            val other = survey(2.0, doubleArrayOf(144.6, -38.4, 144.75, -38.35)).also { it.kind = Surveys.PARK; it.id = "park" }
            val plan = plan(s, 2.0)
            plan.chosen.add(0, other)
            val job = started()
            val net = server { throw IOException(failure) }
            Warehouse.fetch(plan, dir, net, job, null, null)
            assertEquals(failure, listOf("park"), plan.chosen.map { it.id })
            assertEquals(failure, 2, net.asked.size)
            assertTrue(plan.notes.joinToString(), "Test bay 2018 was left out: the AusSeabed warehouse didn't send it ($failure)" in plan.notes.joinToString())
            assertTrue(leftInDir().isEmpty())
        }
    }

    @Test
    fun `a piece that fails once is tried again`() {
        var tries = 0
        val s = survey(2.0, doubleArrayOf(144.6, -38.4, 144.75, -38.35))
        val plan = plan(s, 10.0)
        Warehouse.fetch(plan, dir, server { if (tries++ == 0) throw IOException("HTTP 502 from fake") }, started(), null, null)
        assertEquals(2, tries)
        assertEquals(Surveys.WCS, plan.chosen.single().kind)
        assertTrue(plan.chosen.single().files.single().url.startsWith("file:"))
        assertTrue(plan.notes.isEmpty())
    }

    @Test
    fun `a left-out survey's finished pieces are deleted at once`() {
        // A 0.5 m survey: the box takes 2 x 2 pieces; the last one fails, after the others came.
        val s = survey(0.5, doubleArrayOf(144.6, -38.4, 144.75, -38.3))
        val plan = plan(s, 0.5)
        plan.p = Planner.parse(doubleArrayOf(144.70, -38.34, 144.715, -38.33), null, false, "wgs84", "auto", "auto", "default", null, null, true)
        val net = server(answer = { url -> if (subset(url, "Long")[0] > 144.705 && subset(url, "Lat")[0] > -38.336) throw IOException("HTTP 500 from fake") })
        val written = mutableListOf<File>()
        Warehouse.fetch(plan, dir, net, started(), null, written)
        assertEquals(4, written.size)
        assertTrue(plan.chosen.isEmpty())
        assertEquals(5, net.asked.size)                          // four pieces, the failed one twice
        assertTrue(written.none { it.exists() })
        assertTrue(leftInDir().isEmpty())
    }

    @Test
    fun `cancelling deletes everything fetched so far`() {
        // Two surveys: the first comes, then the build is cancelled while the second downloads.
        val first = survey(2.0, doubleArrayOf(144.6, -38.4, 144.75, -38.35))
        val second = survey(2.0, doubleArrayOf(144.6, -38.4, 144.75, -38.35)).also { it.id = "second"; it.coverage = "ausseabed__Second_OV" }
        val plan = plan(first, 10.0)
        plan.chosen.add(second)
        val job = started()
        val net = server(answer = { url -> if ("Second" in url) job.requestCancel() })
        val written = mutableListOf<File>()
        try {
            Warehouse.fetch(plan, dir, net, job, null, written)
            fail("not cancelled")
        } catch (e: InterruptedIOException) {
            // expected
        }
        assertEquals(2, written.size)
        assertTrue(written.none { it.exists() })
        assertTrue(leftInDir().isEmpty())
    }

    @Test
    fun `an unexpected error deletes everything fetched so far`() {
        val first = survey(2.0, doubleArrayOf(144.6, -38.4, 144.75, -38.35))
        val broken = survey(2.0, doubleArrayOf(144.6, -38.4, 144.75, -38.35)).also { it.id = "broken"; it.files = null }
        val plan = plan(first, 10.0)
        plan.chosen.add(broken)
        val written = mutableListOf<File>()
        try {
            Warehouse.fetch(plan, dir, server(), started(), null, written)
            fail("no error")
        } catch (e: NullPointerException) {
            // expected
        }
        assertEquals(1, written.size)
        assertTrue(written.none { it.exists() })
    }

    @Test
    fun `nothing to fetch when no coverage is chosen`() {
        val s = survey(2.0, doubleArrayOf(144.6, -38.4, 144.75, -38.35)).also { it.kind = Surveys.PARK }
        val plan = plan(s, 2.0)
        val net = server()
        assertTrue(Warehouse.fetch(plan, dir, net, started(), null, null).isEmpty())
        assertTrue(net.asked.isEmpty())
        assertTrue(plan.chosen.single() === s)
    }

    @Test
    fun `when the finest survey is left out the detail is chosen again`() {
        val wcs = survey(2.0, doubleArrayOf(144.6, -38.4, 144.75, -38.35))
        val park = survey(8.0, doubleArrayOf(144.6, -38.4, 144.75, -38.35)).also { it.kind = Surveys.PARK; it.id = "park" }
        val plan = plan(wcs, 2.0)
        plan.chosen.add(park)
        plan.bandM["park"] = 30.0
        plan.blend = true
        Warehouse.fetch(plan, dir, server { throw IOException("HTTP 500 from fake") }, started(), null, null)
        assertEquals(listOf("park"), plan.chosen.map { it.id })
        assertEquals(8.0, plan.resM, 0.0)
        assertEquals(8.0, plan.finestM, 0.0)
        val px = Planner.outputPixels(box, 8.0, "wgs84")
        assertEquals(px[0] * px[1] * 4, plan.estBytes)
        assertFalse(plan.blend)                                  // no IMAS under it in this plan
        val notes = plan.notes.joinToString(" ")
        assertTrue(notes, "built at 8 m, the finest left, instead of 2 m" in notes)
        assertTrue(Credits.text(plan, "t.tif", "test").contains("Notes:\n  Test bay 2018 was left out"))
        // A detail the user asked for stays as it is.
        val asked = plan(survey(2.0, doubleArrayOf(144.6, -38.4, 144.75, -38.35)), 2.0)
        asked.p = Planner.parse(box, null, false, "wgs84", "2", "auto", "default", null, null, true)
        asked.chosen.add(park)
        Warehouse.fetch(asked, dir, server { throw IOException("HTTP 500 from fake") }, started(), null, null)
        assertEquals(2.0, asked.resM, 0.0)
    }

    private fun buildWithBelow(s: Surveys.Survey, net: FakeNet, resM: Double = 20.0): Pair<Builder, Map<String, Any?>> {
        // Below it, a layer at -50 m everywhere (as IMAS is in a real build).
        val baseFile = File(dir, "base.tif")
        val r = 0.0005
        FakeNet.tiff(baseFile, Math.ceil((box[2] - box[0] + 0.02) / r).toInt(), Math.ceil((box[3] - box[1] + 0.02) / r).toInt(),
            box[0] - 0.01, box[3] + 0.01, r, r, Float.NaN) { _, _ -> -50f }
        val plan = plan(s, resM)
        plan.blend = true
        plan.countLayers = true
        plan.bandM[s.id] = 200.0
        val base = Planner.Base()
        base.tiff = RemoteTiff.open(baseFile.toURI().toString(), null)
        base.proj = Proj.parse("EPSG:4326")
        base.label = "below"
        plan.bases.add(base)
        plan.imasResM = r * Catalog.M_PER_DEG
        val job = started()
        val src = TileSource(pool, 64L shl 20)
        Warehouse.fetch(plan, dir, net, job, src.bytesDownloaded, null)
        assertTrue(src.bytesDownloaded.get() > 0)
        val out = File(dir, "out.tif")
        val builder = Builder(plan, job, src, pool)
        builder.workDir = dir
        val result = RandomAccessFile(out, "rw").use { raf -> builder.run(raf.channel, raf.channel) }
        return builder to result
    }

    private fun valueAt(file: File, lon: Double, lat: Double): Float {
        val t = RemoteTiff.open(file.toURI().toString(), null)
        val L = t.levels[0]
        val x = ((lon - t.gt[0]) / t.gt[1]).toInt()
        val y = ((lat - t.gt[3]) / t.gt[5]).toInt()
        val tx = x / L.tileW
        val ty = y / L.tileH
        val idx = t.tileRow(L, ty, null)
        val raw = Http.range(t.url, idx[0][tx], idx[1][tx].toInt(), null)
        return t.decode(L, raw, 0, raw.size)[(y - ty * L.tileH) * L.tileW + (x - tx * L.tileW)]
    }

    @Test
    fun `the coverage is laid over the layer below, edges blended`() {
        val s = survey(2.0, doubleArrayOf(144.6, -38.4, 144.75, -38.3))
        val (builder, result) = buildWithBelow(s, server())
        assertEquals(100.0, result["valid_percent"] as Double, 1e-9)
        val n = builder.layerPixels["wcs-test"]!!
        val total = builder.width.toLong() * builder.height
        assertTrue("$n of $total", n > total * 0.4 && n < total * 0.6)
        val out = File(dir, "out.tif")
        assertEquals(-5f, valueAt(out, 144.705, -38.345), 1e-3f)
        assertEquals(-50f, valueAt(out, 144.735, -38.345), 1e-3f)
        val edge = valueAt(out, split - 0.0006, -38.345)   // about 50 m in from the survey's edge, inside its 200 m band
        assertTrue("blended edge $edge", edge < -5f && edge > -50f)
    }

    @Test
    fun `fill values blended into a survey's edge are not used as depths`() {
        // A server that resampled the NoData value into the survey's edge pixels, plus a stray fill value.
        val s = survey(2.0, doubleArrayOf(144.6, -38.4, 144.75, -38.3))
        val net = server(value = { lon ->
            when {
                lon < split - 0.002 -> -5f
                lon < split - 0.001 -> -20000f
                lon < split -> 1.7e38f
                else -> null
            }
        })
        val (_, result) = buildWithBelow(s, net)
        val max = result["max"] as Double
        val min = result["min"] as Double
        assertTrue("max $max", max <= -5.0)
        assertTrue("min $min", min >= -50.0)
        val out = File(dir, "out.tif")
        assertEquals(-50f, valueAt(out, split - 0.0005, -38.345), 1e-3f)
    }

    @Test
    fun `the request asks for nearest neighbour, so edges aren't blended with NoData`() {
        val u = Warehouse.url(coverage, box, 0.5)
        assertTrue(u, "interpolation=http://www.opengis.net/def/interpolation/OGC/1/nearest-neighbor" in u)
        assertFalse(u, "linear" in u)
    }

    @Test
    fun `values that can't be depths count as no data`() {
        val f = File(dir, "odd.tif")
        FakeNet.tiff(f, 4, 1, 144.7, -38.3, 0.001, 0.001, -9999f) { x, _ -> floatArrayOf(-10f, 1.7e38f, -9999f, -3e33f)[x] }
        val t = RemoteTiff.open(f.toURI().toString(), null)
        assertFalse(t.isNodata(-10f))
        assertTrue(t.isNodata(1.7e38f))
        assertTrue(t.isNodata(-9999f))
        assertTrue(t.isNodata(-3e33f))
        assertTrue(t.isNodata(Float.NaN))
        assertFalse(t.isNodata(-11000f))
    }
}
