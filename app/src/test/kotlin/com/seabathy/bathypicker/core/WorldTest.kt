package com.seabathy.bathypicker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Where high detail works outside Australia, what a box gets, and the links asked for. */
class WorldTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("world").toFile().apply { deleteOnExit() }
        World.forgetCache()
    }

    private fun box(w: Double, s: Double, e: Double, n: Double) = doubleArrayOf(w, s, e, n)

    private val keyWest = box(-81.82, 24.455, -81.755, 24.52)
    private val halifax = box(-63.62, 44.55, -63.52, 44.63)
    private val bayOfIslands = box(174.08, -35.30, 174.20, -35.20)
    private val mamanuca = box(177.05, -17.80, 177.20, -17.66)
    private val glenan = box(-4.1, 47.65, -3.9, 47.78)
    private val bali = box(115.1, -8.8, 115.3, -8.6)

    // ------------------------------------------------------------------ places

    @Test
    fun `Australian waters keep the IMAS build, and only they do`() {
        for (b in listOf(box(151.2, -33.9, 151.3, -33.8), box(130.7, -12.5, 130.9, -12.3), box(145.7, -16.9, 145.9, -16.7),
            box(167.9, -29.1, 168.0, -29.0), box(105.6, -10.5, 105.7, -10.4), box(147.3, -43.1, 147.4, -43.0),
            box(115.6, -32.1, 115.8, -31.9), box(142.5, -9.45, 142.7, -9.3))) {
            assertTrue(b.joinToString(), World.inAustralia(b))
        }
        for (b in listOf(bali, keyWest, bayOfIslands, mamanuca, box(147.1, -9.6, 147.2, -9.4), box(166.4, -22.4, 166.5, -22.2),
            box(125.5, -9.0, 125.7, -8.8), box(174.7, -36.9, 174.8, -36.8))) {
            assertFalse(b.joinToString(), World.inAustralia(b))
        }
    }

    @Test
    fun `High detail is offered where any source is`() {
        for (b in listOf(keyWest, halifax, bayOfIslands, mamanuca, glenan, box(151.2, -33.9, 151.3, -33.8),
            box(-157.9, 21.2, -157.7, 21.4), box(-66.2, 18.4, -66.0, 18.5), box(144.6, 13.4, 144.8, 13.6))) {
            assertTrue(b.joinToString(), World.touches(b))
        }
        for (b in listOf(bali, box(-40.0, 30.0, -39.8, 30.2), box(-43.3, -23.0, -43.1, -22.8), box(139.0, 35.0, 139.2, 35.2),
            box(136.0, -4.0, 136.2, -3.8))) {
            assertFalse(b.joinToString(), World.touches(b))
        }
    }

    @Test
    fun `places are named from the box's middle`() {
        assertEquals("Florida", World.place(World.US, keyWest, 3.0))
        assertEquals("Pacific coast", World.place(World.US, box(-122.6, 37.7, -122.4, 37.9), 3.0))
        assertEquals("Hawaii", World.place(World.US, box(-157.9, 21.2, -157.7, 21.4), 3.0))
        assertEquals("Great Lakes", World.place(World.US, box(-87.7, 41.8, -87.5, 42.0), 3.0))
        assertEquals("Atlantic coast", World.place(World.CANADA, halifax, 3.0))
        assertEquals("Northland", World.place(World.NZ_PLACES, bayOfIslands, 2.0))
        assertEquals("Wellington", World.place(World.NZ_PLACES, box(174.7, -41.35, 174.85, -41.25), 2.0))
        assertEquals("Fiji", World.place(World.PACIFIC_PLACES, mamanuca, 1.5))
        assertEquals("Fiji", World.place(World.PACIFIC_PLACES, box(-178.9, -17.3, -178.7, -17.1), 1.5))
        assertEquals("Tonga", World.place(World.PACIFIC_PLACES, box(-175.3, -21.2, -175.1, -21.0), 1.5))
        assertEquals("New Caledonia", World.place(World.PACIFIC_PLACES, box(166.4, -22.4, 166.5, -22.2), 1.5))
        assertEquals("Vanuatu", World.place(World.PACIFIC_PLACES, box(168.2, -17.8, 168.4, -17.7), 1.5))
        assertNull(World.place(World.PACIFIC_PLACES, box(-140.0, 20.0, -139.8, 20.2), 1.5))
    }

    // ------------------------------------------------------------------ links

    @Test
    fun `the links ask for the box as each server wants it`() {
        val noaa = World.noaaExportUrl(keyWest, 2128, 2128)
        assertTrue(noaa, noaa.startsWith(World.NOAA_SERVICE + "/exportImage?bbox=-81.820000,24.455000,-81.755000,24.520000&"))
        assertTrue(noaa, "size=2128,2128" in noaa && "format=tiff" in noaa && "pixelType=F32" in noaa && "noData=-99999" in noaa)
        // The global fill is left out (LowPS under 0.002 degrees), so GMRT shows there instead.
        assertTrue(noaa, java.net.URLDecoder.decode(noaa, "UTF-8").contains("\"where\":\"LowPS<0.002\""))

        val q = World.noaaQueryUrl(keyWest)
        assertTrue(q, "/query?geometry=-81.820000,24.455000,-81.755000,24.520000" in q && "returnGeometry=true" in q)
        assertTrue(q, java.net.URLDecoder.decode(q, "UTF-8").contains("where=Category=1 AND LowPS<0.002"))

        val nonna = World.nonnaUrl(halifax, 300, 240)
        assertTrue(nonna, nonna.startsWith("https://nonna-geoserver.data.chs-shc.ca/geoserver/wcs?service=WCS&version=2.0.1&request=GetCoverage"))
        assertTrue(nonna, "coverageId=nonna__NONNA%2010%20Coverage" in nonna && "subset=Long(-63.620000,-63.520000)" in nonna)
        assertTrue(nonna, "subset=Lat(44.550000,44.630000)" in nonna && "scalesize=i(300),j(240)" in nonna)
        assertTrue(nonna, "subsettingCrs=http://www.opengis.net/def/crs/EPSG/0/4326" in nonna && "geotiff:compression=Deflate" in nonna)

        val emod = World.emodnetUrl(glenan, 0, 0)
        assertTrue(emod, emod.startsWith("https://ows.emodnet-bathymetry.eu/wcs?") && "coverageId=emodnet__mean" in emod)
        assertFalse(emod, "scalesize" in emod)

        assertEquals("https://www.gmrt.org/services/GridServer?north=24.52000&south=24.45500&east=-81.75500&west=-81.82000&layer=topo&format=geotiff&resolution=high",
            World.gmrtUrl(keyWest))
        assertEquals("https://stac.digitalearthpacific.org/collections/dep_s2_sdb/items?bbox=177.050000,-17.800000,177.200000,-17.660000&limit=50",
            World.depSearchUrl(mamanuca))
        assertEquals("https://nz-coastal.s3.ap-southeast-2.amazonaws.com/new-zealand/new-zealand/dem_1m/2193/AV29.tiff", World.linzUrl("AV29"))
    }

    @Test
    fun `big areas are asked for in overlapping pieces that cover the grid`() {
        val g = doubleArrayOf(0.0, 0.0, 0.9, 0.5, 9000.0, 5000.0)
        val pieces = World.pieces(g, 1)
        assertEquals(6, pieces.size)
        for (p in pieces) {
            assertTrue(p.cols <= World.CHUNK + 2 * World.MARGIN && p.rows <= World.CHUNK + 2 * World.MARGIN)
            // Square pixels, the grid's own size.
            assertEquals((p.box[2] - p.box[0]) / p.cols, 0.0001, 1e-12)
            assertEquals((p.box[3] - p.box[1]) / p.rows, 0.0001, 1e-12)
        }
        assertTrue(pieces.minOf { it.box[0] } < 0.0 && pieces.maxOf { it.box[2] } > 0.9)
        assertTrue(pieces.minOf { it.box[1] } < 0.0 && pieces.maxOf { it.box[3] } > 0.5)
        // Coarser requests (CHS at 10 m for a 3 m build): fewer, smaller pieces over the same ground.
        val coarse = World.pieces(g, 3)
        assertEquals(1, coarse.size)
        assertEquals((9000 + 2 * 3 * World.MARGIN) / 3, coarse[0].cols)
    }

    @Test
    fun `LINZ tiles are found from the collection by their sheet`() {
        val collection = """{"type":"Collection","links":[{"rel":"root","href":"https://x/catalog.json"},
            {"href":"./AV29.json","rel":"item"},{"href":"./AV30.json","rel":"item"},{"href":"./CH10.json","rel":"item"},
            {"href":"./odd name.json","rel":"item"}]}"""
        val codes = World.linzCodes(collection)
        assertEquals(listOf("AV29", "AV30", "CH10"), codes)
        assertEquals(listOf("AV29", "AV30"), World.linzTilesIn(codes, bayOfIslands))
        assertEquals(emptyList<String>(), World.linzTilesIn(codes, box(172.6, -43.65, 172.8, -43.5)))
    }

    // ------------------------------------------------------------------ what a box gets

    private val noaaAnswer = """{"features":[
        {"attributes":{"Name":"ncei19_n24x75_w082x00_2022v1","LowPS":3.0864197e-05,"VerticalDatum":"NAVD 88"},
         "geometry":{"rings":[[[-82.0,24.5],[-82.0,24.75],[-81.75,24.75],[-81.75,24.5],[-82.0,24.5]]]}},
        {"attributes":{"Name":"ncei13_n24x50_w082x00_2022v1","LowPS":9.2592592e-05,"VerticalDatum":"NAVD 88"},
         "geometry":{"rings":[[[-82.0,24.25],[-82.0,24.5],[-81.75,24.5],[-81.75,24.25],[-82.0,24.25]]]}}]}"""

    private fun assess(b: DoubleArray, net: FakeNet, capMb: Double = 250.0) =
        World.assess(b, capMb * 1048576, 10_000_000_000L, dir, net, null)

    @Test
    fun `a US box names the state and the detail`() {
        val a = assess(keyWest, FakeNet(mapOf(World.NOAA_SERVICE + "/query" to noaaAnswer)))
        assertTrue(a.line, a.ok)
        assertEquals("USA (Florida): NOAA, 3.4 m in parts, mostly 10 m. Up to about 18 MB at 3.4 m detail.", a.line)
        val u = a.use(World.NOAA)!!
        assertEquals(1.0, u.cover, 1e-9)
        assertEquals(setOf("NAVD 88"), u.datums)
        assertEquals(3.4, a.resM, 1e-9)
    }

    @Test
    fun `too big for the limit comes coarser and says so`() {
        val a = assess(keyWest, FakeNet(mapOf(World.NOAA_SERVICE + "/query" to noaaAnswer)), capMb = 5.0)
        assertTrue(a.ok)
        assertEquals(10.0, a.resM, 1e-9)
        assertTrue(a.line, a.line.endsWith("at 10 m detail (a smaller box comes sharper)."))
    }

    @Test
    fun `only the global fill in the US is no high detail`() {
        val a = assess(keyWest, FakeNet(mapOf(World.NOAA_SERVICE + "/query" to """{"features":[]}""")))
        assertFalse(a.ok)
        assertEquals("No high-detail depths here (NOAA has no coastal DEM for this box). Cancel and choose Standard.", a.line)
    }

    @Test
    fun `nowhere near a source is no high detail, with nothing asked`() {
        val net = FakeNet()
        val a = assess(bali, net)
        assertFalse(a.ok)
        assertEquals(World.NONE, a.line)
        assertTrue(net.asked.isEmpty())
    }

    private fun sample(share: Double): (String, File) -> Unit = { _, f ->
        FakeNet.tiff(f, 48, 48, -63.62, 44.63, 0.002, 0.0017, Float.MAX_VALUE) { x, _ -> if (x < 48 * share) -20f else null }
    }

    @Test
    fun `a Canadian box samples CHS for where it has surveys`() {
        val net = FakeNet(files = mapOf(World.NONNA_WCS to sample(0.5)))
        val a = assess(halifax, net)
        assertTrue(a.line, a.ok)
        assertEquals(0.5, a.use(World.NONNA)!!.cover, 1e-9)
        assertTrue(a.line, a.line.startsWith("Canada (Atlantic coast): CHS surveys, 10 m where surveyed; GMRT fills the rest. Up to about "))
        assertTrue(a.line, a.line.endsWith(" MB at 10 m detail."))
        assertTrue(net.asked.single().contains("scalesize=i(48),j(48)"))
        // No surveys in the sample: none.
        val none = assess(halifax, FakeNet(files = mapOf(World.NONNA_WCS to sample(0.0))))
        assertFalse(none.ok)
        assertEquals("No high-detail depths here (no CHS surveys in this box). Cancel and choose Standard.", none.line)
    }

    @Test
    fun `a server that doesn't answer is said, not taken for no data`() {
        val a = assess(halifax, FakeNet())
        assertFalse(a.ok)
        assertEquals("CHS didn't answer (HTTP 503). Check the internet connection or try again later, or cancel and choose Standard.", a.line)
    }

    @Test
    fun `where two countries meet both are used`() {
        val juanDeFuca = box(-123.3, 48.3, -123.1, 48.45)
        val net = FakeNet(
            texts = mapOf(World.NOAA_SERVICE + "/query" to """{"features":[{"attributes":{"Name":"a","LowPS":3.0864197e-05},
                "geometry":{"rings":[[[-124,48],[-124,48.4],[-123,48.4],[-123,48],[-124,48]]]}}]}"""),
            files = mapOf(World.NONNA_WCS to sample(0.3))
        )
        val a = assess(juanDeFuca, net)
        assertTrue(a.line, a.ok)
        assertEquals(listOf(World.NOAA, World.NONNA), a.uses.map { it.source })
        assertTrue(a.line, a.line.startsWith("USA (Pacific coast) and Canada (Pacific coast): NOAA, 3.4 m; CHS surveys, 10 m where surveyed; GMRT fills the rest."))
    }

    @Test
    fun `a European box is EMODnet`() {
        val net = FakeNet(files = mapOf(World.EMODNET_WCS to sample(1.0)))
        val a = assess(glenan, net)
        assertTrue(a.line, a.ok)
        assertEquals("Europe: EMODnet, about 115 m. Up to about 1 MB at 115 m detail.", a.line)
    }

    private val linzCollection = """{"links":[{"href":"./AV29.json","rel":"item"},{"href":"./AV30.json","rel":"item"}]}"""

    @Test
    fun `a New Zealand box names the region and the lidar tiles`() {
        val a = assess(bayOfIslands, FakeNet(mapOf(World.LINZ_BASE + "collection.json" to linzCollection)))
        assertTrue(a.line, a.ok)
        assertTrue(a.line, a.line.startsWith("New Zealand (Northland): LINZ lidar near shore, 1 m; GMRT fills the rest. Up to about "))
        assertEquals(listOf(World.linzUrl("AV29"), World.linzUrl("AV30")), a.use(World.LINZ)!!.files)
        // Off a coast with no lidar yet.
        val canterbury = assess(box(172.6, -43.65, 172.8, -43.5), FakeNet(mapOf(World.LINZ_BASE + "collection.json" to linzCollection)))
        assertFalse(canterbury.ok)
        assertEquals("No high-detail depths here (New Zealand's coastal lidar doesn't reach this box yet). Cancel and choose Standard.", canterbury.line)
    }

    @Test
    fun `a Pacific box uses the newest satellite depths of each tile`() {
        val host = World.DEP_HOST + "dep_s2_sdb/0-0-1/062/020/"
        val stac = """{"type":"FeatureCollection","features":[
            {"id":"old","assets":{"depth":{"href":"${host}2023/old_depth.tif"}},"properties":{"start_datetime":"2023-01-01T00:00:00Z","proj:bbox":[2952000,-2080000,3048000,-1984000]}},
            {"id":"new","assets":{"depth":{"href":"${host}2024/new_depth.tif"}},"properties":{"start_datetime":"2024-07-01T00:00:00Z","proj:bbox":[2952000,-2080000,3048000,-1984000]}},
            {"id":"elsewhere","assets":{"depth":{"href":"https://example.com/x_depth.tif"}},"properties":{}},
            {"id":"nodepth","assets":{"mean":{"href":"${host}2024/mean.tif"}},"properties":{}}]}"""
        val a = assess(mamanuca, FakeNet(mapOf(World.DEP_SEARCH to stac)))
        assertTrue(a.line, a.ok)
        assertEquals(listOf("${host}2024/new_depth.tif"), a.use(World.DEP)!!.files)
        assertTrue(a.line, a.line.startsWith("Fiji: satellite estimate of the reef shallows, 10 m; GMRT fills the rest. Up to about "))
        val empty = assess(mamanuca, FakeNet(mapOf(World.DEP_SEARCH to """{"features":[]}""")))
        assertFalse(empty.ok)
        assertEquals("No high-detail depths here (no satellite depths for this box). Cancel and choose Standard.", empty.line)
    }

    // ------------------------------------------------------------------ credits

    @Test
    fun `the credits list the sources with depths in the file, and GMRT when it filled gaps`() {
        val a = assess(keyWest, FakeNet(mapOf(World.NOAA_SERVICE + "/query" to noaaAnswer)))
        val plan = Planner.Plan()
        plan.resM = a.resM
        val prep = World.Prepared(plan, a)
        prep.uses.addAll(a.uses)
        prep.gmrtResM = 56.0

        val all = World.used(prep, mapOf(World.NOAA to 100L), 50L)
        assertEquals(listOf(World.NOAA, World.GMRT), all)
        assertEquals("Depths: NOAA NCEI coastal DEMs (public domain); Depths: GMRT, Ryan et al. 2009 (CC BY 4.0)", World.creditLine(all))
        val text = World.creditsText(prep, all, "High detail 24.49N 81.79W (0.07°).tif", "SeaBathy 1.4.0")
        assertTrue(text, text.startsWith("Sources for High detail 24.49N 81.79W (0.07°).tif\nMade with SeaBathy 1.4.0 on "))
        assertTrue(text, "On the chart: Depths: NOAA NCEI coastal DEMs (public domain); Depths: GMRT" in text)
        assertTrue(text, "ncei19_n24x75_w082x00_2022v1" in text && "NAVD 88" in text)
        assertTrue(text, "GMRT, Global Multi-Resolution Topography (Ryan et al. 2009, doi:10.1029/2008GC002332), about 56 m here." in text)
        assertTrue(text, "NOAA NCEI DEMs are US public domain" in text && "CC BY 4.0" in text)
        assertFalse(text, "Open Government Licence" in text)

        // Everything from NOAA: GMRT isn't credited.
        val noaaOnly = World.used(prep, mapOf(World.NOAA to 100L), 0L)
        assertEquals(listOf(World.NOAA), noaaOnly)
        assertFalse(World.creditsText(prep, noaaOnly, "x.tif", "SeaBathy").contains("GMRT, Global"))
        // A layer that turned out empty isn't credited either.
        assertEquals(listOf(World.GMRT), World.used(prep, mapOf(World.NOAA to 0L), 10L))
    }

    @Test
    fun `each source's credit line is the one its owner asks for`() {
        assertEquals("Contains information licensed under the Open Government Licence - Canada (CHS NONNA, not for navigation)", World.credit(World.NONNA))
        assertEquals("Depths: EMODnet Bathymetry Consortium (2024), EMODnet Digital Bathymetry (DTM 2024), CC BY 4.0", World.credit(World.EMODNET))
        assertEquals("Depths: Toitū Te Whenua Land Information New Zealand, Coastal LiDAR DEM, CC BY 4.0", World.credit(World.LINZ))
        assertEquals("Depths (estimate): Digital Earth Pacific, Pacific Community (SPC), satellite-derived bathymetry, CC BY 4.0", World.credit(World.DEP))
        assertEquals("Depths: GMRT, Ryan et al. 2009 (CC BY 4.0)", World.credit(World.GMRT))
    }

    @Test
    fun `detail is written as people say it`() {
        assertEquals("3.4", World.metres(3.43))
        assertEquals("2", World.metres(2.0))
        assertEquals("10", World.metres(10.3))
        assertEquals("115", World.metres(115.0))
    }
}
