package com.seabathy.bathypicker.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * The app's extra layers on top of the BathyPicker catalogue: IMAS's marine park mosaics and the
 * AusSeabed warehouse coverages (assets/bathypicker/extra_surveys.json), with small made-up lists.
 */
class ExtraSurveysTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("extras").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun catalogue(vararg entries: String) =
        """{"schema":"bathypicker-surveys/v1","built":"2026-11-22","surveys":[${entries.joinToString(",")}]}"""

    private val park = """{"id":"imas-amp-test","name":"Test marine park mosaic (IMAS)","kind":"park","year":2026,"res_m":2.0,
        "files":[{"url":"${Surveys.PARK_HOST}Test.tif","bytes":1000000,"crs":"EPSG:4326","bbox":[150.0,-30.0,151.0,-29.0],
        "cells":[[150.0,-30.0,150.2,-29.8],[150.8,-29.2,151.0,-29.0]],"tiled":true,"overviews":6,"empty_tile_bytes":1044,
        "empty_tile_for":{"compression":8,"predictor":2,"tile":[512,512],"size":[33353,27275]}}],
        "bbox":[150.0,-30.0,151.0,-29.0],"sign":1,"shift_m":0.0,"updated":"2026-07-28","licence":"CC BY 4.0",
        "credit":"Flukes, E. & Monk, J. (2026).","note":"Mixed sources."}"""

    private val deepPark = """{"id":"imas-amp-deep","name":"Deep marine park mosaic (IMAS)","kind":"park","year":2026,"res_m":15.0,"imas_m":64,
        "files":[{"url":"${Surveys.PARK_HOST}Deep.tif","bytes":1000,"crs":"EPSG:4326","bbox":[152.0,-30.0,153.0,-29.0]}],
        "bbox":[152.0,-30.0,153.0,-29.0],"updated":"2026-07-28"}"""

    private val wcs = """{"id":"wcs-test","name":"Test bay 2018","kind":"wcs","coverage":"ausseabed__Test_Bay_Bathymetry__2m_2018_OV",
        "year":2018,"res_m":2.0,"files":[{"bbox":[144.7,-38.4,144.8,-38.3],"cells":[[144.7,-38.4,144.75,-38.35]]}],
        "bbox":[144.7,-38.4,144.8,-38.3],"stated_datum":"EGM2008","sign":1,"shift_m":0.0,"updated":"2026-03-25","licence":"CC BY 4.0"}"""

    private val survey = """{"id":"s3-survey","name":"Some survey","year":2018,"res_m":0.9,
        "files":[{"url":"${Surveys.HOST}L3/Some/file.tif","bytes":500,"crs":"EPSG:4326","bbox":[150.0,-30.0,150.5,-29.5]}],
        "bbox":[150.0,-30.0,150.5,-29.5],"updated":"2020-01-01"}"""

    @Test
    fun `park and warehouse entries are read with their kind`() {
        val s = Surveys.parse(catalogue(park, deepPark, wcs), "test")!!
        assertEquals(3, s.list.size)
        val p = s.list.first { it.id == "imas-amp-test" }
        assertEquals(Surveys.PARK, p.kind)
        assertEquals(0.0, p.imasM, 0.0)
        assertEquals(1044L, p.files[0].emptyTile)
        assertEquals(listOf(8, 2, 512, 512, 33353, 27275), p.files[0].emptyFor.toList())
        assertEquals(6, p.files[0].overviews)
        assertEquals(2, p.files[0].cells.size)
        assertNotNull(p.files[0].proj)
        assertEquals(64.0, s.list.first { it.id == "imas-amp-deep" }.imasM, 0.0)
        val w = s.list.first { it.id == "wcs-test" }
        assertEquals(Surveys.WCS, w.kind)
        assertEquals("ausseabed__Test_Bay_Bathymetry__2m_2018_OV", w.coverage)
        assertEquals(Warehouse.pseudoUrl(w.coverage), w.files[0].url)
        assertEquals("EPSG:4326", w.files[0].crs)
        // An ordinary survey stays as it was.
        val o = Surveys.parse(catalogue(survey), "test")!!.list[0]
        assertEquals(Surveys.SURVEY, o.kind)
        assertNull(o.coverage)
        assertEquals(-1, o.files[0].overviews)
        assertNull(o.files[0].emptyFor)
    }

    @Test
    fun `entries pointing anywhere else are left out`() {
        val wrongHost = park.replace(Surveys.PARK_HOST, "https://example.com/bathy/")
        val parkOnS3 = survey.replace("\"name\"", "\"kind\":\"park\",\"name\"")
        val badCoverage = wcs.replace("ausseabed__Test_Bay_Bathymetry__2m_2018_OV", "ausseabed__x&request=GetMap")
        val otherService = wcs.replace("ausseabed__Test_Bay", "other__Test_Bay")
        val unknownKind = park.replace("\"kind\":\"park\"", "\"kind\":\"wms\"")
        val s = Surveys.parse(catalogue(wrongHost, parkOnS3, badCoverage, otherService, unknownKind, survey), "test")!!
        assertEquals(listOf("s3-survey"), s.list.map { it.id })
    }

    @Test
    fun `extras are added to whichever catalogue is used, once`() {
        val base = Surveys.parse(catalogue(survey), "online")!!
        val extra = Surveys.parse(catalogue(park, wcs, survey.replace("Some survey", "Copy")), "bundled extras")!!
        val all = Surveys.merge(base, extra)
        assertEquals(listOf("s3-survey", "imas-amp-test", "wcs-test"), all.list.map { it.id })
        assertEquals("Some survey", all.list[0].name)
        assertEquals("2026-11-22", all.built)
        assertEquals("online", all.origin)
        assertTrue(Surveys.merge(base, null) === base)
        assertEquals(2, Surveys.merge(null, Surveys.parse(catalogue(park, wcs), "x")).list.size)
    }

    @Test
    fun `a park is in a box only where it has data cells`() {
        val s = Surveys.parse(catalogue(park), "test")!!
        assertEquals(1, s.inBox(doubleArrayOf(150.1, -29.9, 150.3, -29.7)).size)
        assertEquals(1, s.inBox(doubleArrayOf(150.9, -29.1, 151.2, -28.9)).size)
        // Inside the park's bounding box, but between its cells.
        assertEquals(0, s.inBox(doubleArrayOf(150.4, -29.6, 150.6, -29.4)).size)
    }

    @Test
    fun `finest first, so a park sits between finer and coarser surveys`() {
        val coarse = survey.replace("s3-survey", "coarse").replace("\"res_m\":0.9", "\"res_m\":2.6")
        val l = Surveys.parse(catalogue(park, coarse, survey), "test")!!.list.toMutableList()
        Surveys.defaultOrder(l)
        assertEquals(listOf("s3-survey", "imas-amp-test", "coarse"), l.map { it.id })
    }

    @Test
    fun `deep parks are measured against the coarser IMAS in deep water`() {
        val s = Surveys.parse(catalogue(park, deepPark, survey.replace("\"res_m\":0.9", "\"res_m\":12")), "test")!!.list
        val p = s.first { it.id == "imas-amp-test" }
        val deep = s.first { it.id == "imas-amp-deep" }
        val twelve = s.first { it.id == "s3-survey" }
        assertFalse(Surveys.coarser(p, 10.0))
        assertTrue(Surveys.coarser(twelve, 10.0))
        assertFalse(Surveys.coarser(deep, 10.0))      // 15 m against IMAS's 64 m
        deep.imasM = 10.0
        assertTrue(Surveys.coarser(deep, 10.0))
        assertFalse(Surveys.coarser(twelve, null))
    }

    @Test
    fun `the line says what lies on IMAS`() {
        val s = Surveys.parse(catalogue(park, deepPark, wcs, survey), "test")!!.list
        val p = s.first { it.id == "imas-amp-test" }
        val deep = s.first { it.id == "imas-amp-deep" }
        val w = s.first { it.id == "wcs-test" }
        val o = s.first { it.id == "s3-survey" }
        assertEquals("", Surveys.onTop(emptyList()))
        assertEquals("the Test marine park mosaic on top", Surveys.onTop(listOf(p)))
        assertEquals("the Test marine park mosaic and 2 multibeam surveys on top", Surveys.onTop(listOf(o, p, w)))
        assertEquals("2 marine park mosaics and 1 multibeam survey on top", Surveys.onTop(listOf(p, deep, w)))
        assertEquals("1 multibeam survey on top", Surveys.onTop(listOf(w)))
    }

    /** A tiled float GeoTIFF (EPSG:4326), 2 x 1 tiles of 256 pixels: depths in the west tile only. */
    private fun twoTiles(): File {
        val f = File(dir, "two.tif")
        RandomAccessFile(f, "rw").use { raf ->
            val t = TiffWriter.TILE
            val tw = TiffWriter(raf.channel, raf.channel, 2 * t, t, doubleArrayOf(150.0, 0.001, 0.0, -29.0, 0.0, -0.001), 4326, -9999f)
            val rnd = java.util.Random(1)
            tw.writeTile(0, 0, 0, FloatArray(t * t) { -20f - rnd.nextFloat() * 5f })
            tw.writeTile(0, 1, 0, FloatArray(t * t) { -9999f })
            tw.finish()
        }
        return f
    }

    @Test
    fun `tile sizes alone tell where a park has depths`() {
        val f = twoTiles()
        val t = RemoteTiff.open(f.toURI().toString(), null)
        val counts = t.tileRow(t.levels[0], 0, null)[1]
        val empty = counts[1]
        assertTrue("an empty tile is small: ${counts.toList()}", empty in 1..2000 && counts[0] > empty * 3)
        val L = t.levels[0]
        val measuredOn = intArrayOf(L.compression, L.predictor, L.tileW, L.tileH, L.width, L.height)
        assertTrue(Surveys.hasData(t, empty, measuredOn, doubleArrayOf(150.05, -29.2, 150.1, -29.1), null))
        assertFalse(Surveys.hasData(t, empty, measuredOn, doubleArrayOf(150.3, -29.2, 150.4, -29.1), null))
        assertFalse(Surveys.hasData(t, empty, measuredOn, doubleArrayOf(151.0, -29.2, 151.1, -29.1), null))   // off the file
        assertTrue(Surveys.hasData(t, 0, measuredOn, doubleArrayOf(150.3, -29.2, 150.4, -29.1), null))     // not known: assume so
    }

    @Test
    fun `a stored empty-tile size is only trusted on the file it was measured on`() {
        val t = RemoteTiff.open(twoTiles().toURI().toString(), null)
        val L = t.levels[0]
        val empty = t.tileRow(L, 0, null)[1][1]
        val emptyBox = doubleArrayOf(150.3, -29.2, 150.4, -29.1)
        val same = intArrayOf(L.compression, L.predictor, L.tileW, L.tileH, L.width, L.height)
        assertFalse(Surveys.hasData(t, empty, same, emptyBox, null))
        // Republished with other compression, predictor, tiles or size, or never recorded: assume it has depths.
        for (i in same.indices) {
            val other = same.clone().also { it[i] += 1 }
            assertTrue("changed field $i", Surveys.hasData(t, empty, other, emptyBox, null))
        }
        assertTrue(Surveys.hasData(t, empty, null, emptyBox, null))
    }

    @Test
    fun `download estimate follows the overviews a build reads`() {
        // An output at 15 m reads a 2 m file's 8 m overview, or the file itself when it has none.
        assertEquals(8.0, Planner.readResM(2.0, 15.0, -1), 1e-9)
        assertEquals(8.0, Planner.readResM(2.0, 15.0, 6), 1e-9)
        assertEquals(4.0, Planner.readResM(2.0, 15.0, 1), 1e-9)
        assertEquals(2.0, Planner.readResM(2.0, 15.0, 0), 1e-9)
        assertEquals(2.0, Planner.readResM(2.0, 1.0, 3), 1e-9)
        assertEquals(10.0, Planner.readResM(10.0, 16.0, -1), 1e-9)
        assertEquals(4.0, Planner.tileRound(512.0, 512.0), 1e-9)
        val s = Surveys.parse(catalogue(park), "test")!!.list
        val box = doubleArrayOf(150.0, -30.0, 150.2, -29.8)          // exactly one of its two equal cells
        val atOwn = Planner.downloadBytes(s, box, 2.0, null, 0.0)
        val coarser = Planner.downloadBytes(s, box, 8.0, null, 0.0)
        assertTrue("$atOwn", atOwn > 500_000 && atOwn < 600_000)     // half the file, plus whole tiles at the edges
        assertTrue("$coarser vs $atOwn", coarser < atOwn / 10)
        assertTrue(Planner.downloadBytes(s, box, 2.0, 10.0, 0.0) > atOwn)
    }

    @Test
    fun `the bundled extras all read, credit their source and stay apart from the catalogue`() {
        val assets = File("src/main/assets/bathypicker")
        val text = File(assets, "extra_surveys.json").readText()
        val extra = Surveys.parse(text, "bundled extras")!!
        val raw = Regex("\"id\":\"").findAll(text).count()
        assertEquals("every entry is well formed", raw, extra.list.size)
        val parks = extra.list.filter { it.kind == Surveys.PARK }
        val coverages = extra.list.filter { it.kind == Surveys.WCS }
        assertTrue(parks.size >= 40)
        assertTrue(coverages.isNotEmpty())
        assertEquals(extra.list.size, parks.size + coverages.size)
        for (s in extra.list) {
            assertEquals(s.id, "CC BY 4.0", s.licence)
            assertTrue(s.id, s.credit.isNotBlank())
            assertEquals(s.id, 1, s.sign)
            assertEquals(s.id, 0.0, s.shiftM, 0.0)
            assertTrue(s.id, s.resM < 70)
        }
        for (p in parks) {
            assertTrue(p.id, p.credit.startsWith("Flukes, E. & Monk, J. (2026). Bathymetry of the Australian Marine Parks"))
            assertTrue(p.id, p.files.single().url.startsWith(Surveys.PARK_HOST))
            assertTrue(p.id, p.files.single().emptyTile > 0)
            val on = p.files.single().emptyFor
            assertTrue(p.id, on != null && on[0] == 8 && on[2] == 512 && on[3] == 512 && on[4] > 0 && on[5] > 0)
            // Finer than the 10 m shelf IMAS, or a deep-water park measured against the coarser IMAS there.
            assertFalse(p.id, Surveys.coarser(p, 10.0))
        }
        val catalogue = Surveys.parse(File(assets, "surveys.json").readText(), "bundled")!!
        val ids = catalogue.list.map { it.id }.toSet()
        assertTrue(extra.list.none { it.id in ids })
        assertEquals(extra.list.size, extra.list.map { it.id }.toSet().size)
        assertEquals(catalogue.list.size + extra.list.size, Surveys.merge(catalogue, extra).list.size)
    }
}
