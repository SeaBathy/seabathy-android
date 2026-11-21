package au.opendepth.app.tides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * MSQ's 10-minute predictions: reading them, keeping them small, the curve drawn from
 * them, and the stations that download them (where half cosines between highs and lows
 * fit badly).
 */
class MsqIntervalTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val index = MsqStationIndex.parse(MsqFixtures.INDEX)
    private val strait = index.byId("777001")!!

    private fun aest(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.ofHours(10)).toEpochMilli()

    private fun hiloCsv(year: Int, lat: String = "-20 24") =
        MsqFixtures.csv(MsqFixtures.extremesOf(MsqFixtures.intervalRows(year)), id = "777001", name = "STRAIT ISLAND", lat = lat, lng = " 148 54")

    // Reading -----------------------------------------------------------------------------

    @Test
    fun `reads a year of 10-minute predictions`() {
        val rows = MsqFixtures.intervalRows(2026)
        val year = MsqCsv.parseInterval(MsqFixtures.intervalCsv(rows))
        assertEquals(52_560, year.heightsM.size)
        assertEquals(aest(2026, 1, 1, 0, 0), year.startMillis)
        assertEquals(600_000L, year.stepMillis)
        assertEquals(aest(2026, 12, 31, 23, 50), year.endMillis)
        assertEquals("777001", year.stationId)
        assertEquals(-20.4, year.lat, 1e-9)
        assertEquals(rows[1234].height, year.heightsM[1234].toDouble(), 1e-5)
        assertTrue(OfficialTides.coversYear(year, 2026))
        assertFalse(OfficialTides.coversYear(year, 2027))
    }

    @Test
    fun `10-minute files with a gap, cut short or to another datum are refused`() {
        val rows = MsqFixtures.intervalRows(2026)
        expectIOException { MsqCsv.parseInterval(MsqFixtures.intervalCsv(rows.filterIndexed { i, _ -> i != 5000 })) }
        expectIOException { MsqCsv.parseInterval(MsqFixtures.intervalCsv(rows.take(20_000), validReadings = rows.size)) }
        expectIOException { MsqCsv.parseInterval(MsqFixtures.intervalCsv(rows.take(20_000), validReadings = 20_000, lastReading = "31 12 2026 23:50")) }
        expectIOException { MsqCsv.parseInterval(MsqFixtures.intervalCsv(rows, datum = "MEAN SEA LEVEL")) }
        expectIOException { MsqCsv.parseInterval(MsqFixtures.intervalCsv(rows, dataClass = "HILO")) }
        // Half a year reads, but isn't a whole year.
        val half = MsqCsv.parseInterval(MsqFixtures.intervalCsv(rows.take(26_000)))
        assertFalse(OfficialTides.coversYear(half, 2026))
    }

    @Test
    fun `a saved year reads back to the millimetre in a fraction of the size`() {
        val year = MsqCsv.parseInterval(MsqFixtures.intervalCsv(MsqFixtures.intervalRows(2026)))
        val file = folder.newFile("y.i10")
        file.outputStream().use { year.write(it) }
        assertTrue("${file.length()} bytes", file.length() < 110_000)
        val back = file.inputStream().use { MsqIntervalYear.read(it) }
        assertEquals(year.startMillis, back.startMillis)
        assertEquals(year.stepMillis, back.stepMillis)
        assertEquals(year.heightsM.size, back.heightsM.size)
        year.heightsM.indices.forEach { assertEquals(year.heightsM[it], back.heightsM[it], 0.0006f) }
        expectIOException { "not a tide file".byteInputStream().use { MsqIntervalYear.read(it) } }
    }

    // Curve ---------------------------------------------------------------------------------

    @Test
    fun `the curve follows the 10-minute predictions and lists the published highs and lows`() {
        val y26 = MsqCsv.parseInterval(MsqFixtures.intervalCsv(MsqFixtures.intervalRows(2026)))
        val y27 = MsqCsv.parseInterval(MsqFixtures.intervalCsv(MsqFixtures.intervalRows(2027)))
        val published = MsqCsv.parse(hiloCsv(2026)).extremes + MsqCsv.parse(hiloCsv(2027)).extremes
        val built = MsqCurve.buildInterval(listOf(y27, y26), published, aest(2026, 12, 31), 2.0)!!
        assertTrue(built.fromInterval)
        // One curve across the new year.
        assertNotNull(built.curve.heightAt(aest(2027, 1, 1, 0, 5)))
        // Exactly the readings at their times, and smooth between them.
        val k = 30_000
        assertEquals(y26.heightsM[k].toDouble(), built.curve.heightAt(y26.startMillis + k * y26.stepMillis)!! + 2.0, 1e-5)
        val t = LocalDateTime.of(2026, 6, 1, 7, 3)
        assertEquals(MsqFixtures.mixedHeight(t), built.curve.heightAt(t.toInstant(ZoneOffset.ofHours(10)).toEpochMilli())!! + 2.0, 0.002)
        // The list is the published one.
        val from = aest(2026, 6, 1, 0)
        val day = built.curve.extremes(from, from + 86_400_000L)
        val expected = published.filter { it.timeMillis in from..from + 86_400_000L }
        assertEquals(expected.map { it.timeMillis }, day.map { it.timeMillis })
        expected.zip(day).forEach { (p, d) -> assertEquals(p.heightM, d.heightM + 2.0, 1e-9) }
    }

    @Test
    fun `without published highs and lows they are found on the curve`() {
        val y26 = MsqCsv.parseInterval(MsqFixtures.intervalCsv(MsqFixtures.intervalRows(2026)))
        val built = MsqCurve.buildInterval(listOf(y26), null, aest(2026, 6, 1), 2.0)!!
        val from = aest(2026, 6, 1, 0)
        val found = built.curve.extremes(from, from + 86_400_000L)
        val published = MsqCsv.parse(hiloCsv(2026)).extremes.filter { it.timeMillis in from + 600_000L..from + 86_400_000L - 600_000L }
        assertEquals(published.size, found.size)
        published.zip(found).forEach { (p, f) ->
            assertEquals(p.isHigh, f.isHigh)
            assertTrue(abs(p.timeMillis - f.timeMillis) <= 10 * 60_000L)
            assertEquals(p.heightM, f.heightM + 2.0, 0.005)
        }
    }

    @Test
    fun `years that don't follow on make separate curves`() {
        val y26 = MsqCsv.parseInterval(MsqFixtures.intervalCsv(MsqFixtures.intervalRows(2026)))
        val y28 = MsqCsv.parseInterval(MsqFixtures.intervalCsv(MsqFixtures.intervalRows(2028)))
        val built = MsqCurve.buildInterval(listOf(y26, y28), null, aest(2028, 3, 1), 2.0)!!
        assertEquals(y28.startMillis, built.curve.startMillis)
        assertNull(MsqCurve.buildInterval(listOf(y26, y28), null, aest(2027, 3, 1), 2.0))
    }

    @Test
    fun `bundled index draws the poorly fitting stations from 10-minute predictions`() {
        val file = listOf(File("src/main/assets/${MsqStationIndex.ASSET}"), File("app/src/main/assets/${MsqStationIndex.ASSET}")).first { it.isFile }
        val bundled = MsqStationIndex.parse(file.readText())
        val tenMinute = bundled.stations.filter { it.useInterval }
        assertEquals(60, tenMinute.size)
        for (name in listOf("Thursday Island", "Karumba", "Weipa", "Boigu Island", "Townsville")) {
            assertTrue(name, bundled.stations.first { it.name == name }.useInterval)
        }
        tenMinute.forEach { s ->
            assertTrue(s.name, s.intervalDataset!!.endsWith("-tide-gauge-predicted-interval-data"))
            assertTrue(s.name, s.intervalYears.isNotEmpty() && s.intervalYears.values.all { OfficialTides.isDataQld(it) && it.contains("10min") })
        }
        assertFalse(bundled.stations.first { it.name == "Gold Coast Seaway" }.useInterval)
    }

    // Downloading and keeping them ---------------------------------------------------------

    private val served = mutableMapOf<String, String>()
    private val failing = mutableSetOf<String>()
    private val asked = mutableListOf<String>()

    private fun fetch(url: String): String {
        asked += url
        if (url in failing) throw IOException("No internet connection")
        return served[url] ?: throw OfficialTides.NotFound(url)
    }

    private fun tides() = OfficialTides(folder.root, index, ::fetch)

    private fun serve(year: Int) {
        served[strait.years.getValue(year)] = hiloCsv(year)
        served[strait.intervalYears.getValue(year)] = MsqFixtures.intervalCsv(MsqFixtures.intervalRows(year))
    }

    @Test
    fun `a poorly fitting station downloads and keeps its 10-minute predictions`() {
        serve(2026)
        val t = tides()
        t.choose("place", -20.41, 148.91)
        t.download(strait, aest(2026, 6, 1))
        assertTrue(strait.intervalYears.getValue(2026) in asked)
        val f = t.forecast("place", aest(2026, 6, 1))!!
        assertTrue(f.tenMinute)
        val y26 = MsqCsv.parseInterval(served.getValue(strait.intervalYears.getValue(2026)))
        // Heights from the station's mean sea level (2.0 m), through the readings.
        assertEquals(-2.0, f.lowestTideM!!, 0.0)
        assertEquals(y26.heightsM[22_000].toDouble(), f.curve.heightAt(y26.startMillis + 22_000 * y26.stepMillis)!! + 2.0, 0.0006)
        // Kept small: the high/low CSV and about 105 KB of readings, not the 10-minute CSV's size.
        assertTrue(File(folder.root, "777001_2026.csv").isFile)
        assertTrue(File(folder.root, "777001_2026.i10").isFile)
        assertTrue("${f.storedBytes}", f.storedBytes in 100_000L..200_000L)
        assertTrue(served.getValue(strait.intervalYears.getValue(2026)).length > 1_000_000)
        assertFalse(t.needsDownload(strait, aest(2026, 6, 1)))
        // Offline in a new session.
        failing += served.keys
        val later = tides()
        later.choose("place", -20.41, 148.91)
        assertTrue(later.forecast("place", aest(2026, 7, 1))!!.tenMinute)
    }

    @Test
    fun `without the 10-minute file the highs and lows do, and it is looked for again later`() {
        served[strait.years.getValue(2026)] = hiloCsv(2026)
        served["https://www.data.qld.gov.au/api/3/action/package_show?id=${strait.intervalDataset}"] = """{"result": {"resources": []}}"""
        val t = tides()
        t.choose("place", -20.41, 148.91)
        t.download(strait, aest(2026, 6, 1))
        val f = t.forecast("place", aest(2026, 6, 1))!!
        assertFalse(f.tenMinute)
        assertNotNull(f.station)
        assertFalse(t.needsDownload(strait, aest(2026, 6, 1, 13)))
        assertTrue(t.needsDownload(strait, aest(2026, 6, 1, 19)))
        // Once it's out, the curve comes from it.
        serve(2026)
        t.download(strait, aest(2026, 6, 1, 19))
        assertTrue(t.forecast("place", aest(2026, 6, 1, 19))!!.tenMinute)
    }

    @Test
    fun `a failed connection for the 10-minute file is reported, keeping the highs and lows`() {
        serve(2026)
        failing += strait.intervalYears.getValue(2026)
        val t = tides()
        t.choose("place", -20.41, 148.91)
        try {
            t.download(strait, aest(2026, 6, 1))
            fail("expected the download to fail")
        } catch (expected: IOException) {
        }
        assertNotNull(t.forecast("place", aest(2026, 6, 1)))
        // Tried again as soon as the caller's retry allows.
        assertTrue(t.needsDownload(strait, aest(2026, 6, 1)))
    }

    @Test
    fun `a 10-minute file for another place or part of the year is refused`() {
        served[strait.years.getValue(2026)] = hiloCsv(2026)
        served[strait.intervalYears.getValue(2026)] = MsqFixtures.intervalCsv(MsqFixtures.intervalRows(2026), lat = "-10 35")
        val t = tides()
        expectIOException { t.download(strait, aest(2026, 6, 1)) }
        served[strait.intervalYears.getValue(2026)] = MsqFixtures.intervalCsv(MsqFixtures.intervalRows(2026).take(30_000))
        expectIOException { t.download(strait, aest(2026, 6, 1)) }
        assertFalse(File(folder.root, "777001_2026.i10").exists())
    }

    @Test
    fun `old 10-minute years are dropped too`() {
        serve(2026)
        serve(2027)
        val t = tides()
        t.download(strait, aest(2026, 11, 22))
        assertTrue(File(folder.root, "777001_2027.i10").isFile)
        val ckanHilo = "https://www.data.qld.gov.au/api/3/action/package_show?id=${strait.dataset}"
        val ckanTen = "https://www.data.qld.gov.au/api/3/action/package_show?id=${strait.intervalDataset}"
        val h28 = "https://www.data.qld.gov.au/dataset/s/resource/h28/download/q777001_strait-island_2028_hilo.csv"
        val i28 = "https://www.data.qld.gov.au/dataset/i/resource/i28/download/p777001_strait-island_2028_10min.csv"
        served[ckanHilo] = """{"result": {"resources": [{"url": "$h28"}]}}"""
        served[ckanTen] = """{"result": {"resources": [{"url": "$i28"}]}}"""
        served[h28] = hiloCsv(2028)
        served[i28] = MsqFixtures.intervalCsv(MsqFixtures.intervalRows(2028))
        t.download(strait, aest(2028, 1, 10))
        assertEquals(setOf(2027, 2028), t.savedYears(strait))
        assertFalse(File(folder.root, "777001_2026.i10").exists())
        assertFalse(File(folder.root, "777001_2026.csv").exists())
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            fail("expected an IOException")
        } catch (expected: IOException) {
        }
    }
}
