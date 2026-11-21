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

/** Which tides a spot gets (official or the model), and how the official ones are downloaded and kept. */
class OfficialTidesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val index = MsqStationIndex.parse(MsqFixtures.INDEX)
    private val station = index.byId("060010A")!!
    private val url2026 = station.years.getValue(2026)
    private val url2027 = station.years.getValue(2027)
    private val ckan = "https://www.data.qld.gov.au/api/3/action/package_show?id=${station.dataset}"

    /** What the fake data.qld.gov.au serves; anything else is a 404. */
    private val served = mutableMapOf<String, String>()
    private val asked = mutableListOf<String>()
    private var offline = false

    private fun fetch(url: String): String {
        asked += url
        if (offline) throw IOException("No internet connection")
        return served[url] ?: throw OfficialTides.NotFound(url)
    }

    private fun tides(dir: File = folder.root) = OfficialTides(dir, index, ::fetch)

    private fun aest(y: Int, mo: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, mo, d, h, 0).toInstant(ZoneOffset.ofHours(10)).toEpochMilli()

    private fun year(y: Int, lat: String = "-21 17") = MsqFixtures.csv(MsqFixtures.yearRows(y), lat = lat)

    private val november = aest(2026, 11, 22)

    @Test
    fun `near a station with its predictions saved, the tides are official`() {
        served[url2026] = year(2026)
        served[url2027] = year(2027)
        val t = tides()
        assertEquals(station, t.choose("place", -21.27, 149.31))
        // Nothing saved yet: the model stays in use.
        assertNull(t.forecast("place", november))
        assertTrue(t.needsDownload(station, november))
        t.download(station, november)
        val f = t.forecast("place", november)!!
        assertEquals(station, f.station)
        assertEquals(-21.27, f.requestLat, 1e-9)
        assertEquals(station.lat, f.modelLat, 1e-9)
        assertTrue(f.storedBytes > 50_000)
        // Saved through next year.
        assertEquals(2027, OfficialTides.yearAt(f.curve.endMillis!!))
        assertFalse(t.needsDownload(station, november))
        assertEquals(setOf(2026, 2027), t.savedYears(station))
        // Places near the station show them as saved offline to the end of 2027.
        assertEquals(aest(2028, 1, 1, 0) - 60_000L, t.savedUntilAt(-21.29, 149.29, november))
        assertNull(t.savedUntilAt(-21.28, 149.54, november))
    }

    @Test
    fun `away from the stations, the model`() {
        val t = tides()
        assertNull(t.choose("place", -21.28, 149.54))
        assertNull(t.forecast("place", november))
        assertNull(t.forecastAt(-21.28, 149.54, november))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `moving away from a station goes back to the model`() {
        served[url2026] = year(2026)
        val t = tides()
        t.choose("follow", -21.27, 149.31)
        t.download(station, aest(2026, 6, 1))
        assertNotNull(t.forecast("follow", aest(2026, 6, 1)))
        t.choose("follow", -21.28, 149.54)
        assertNull(t.forecast("follow", aest(2026, 6, 1)))
    }

    @Test
    fun `saved predictions work offline in a new session`() {
        served[url2026] = year(2026)
        served[url2027] = year(2027)
        tides().download(station, november)
        offline = true
        val later = tides()
        later.choose("place", -21.27, 149.31)
        assertNotNull(later.forecast("place", aest(2027, 3, 1)))
        // And for a route's tide window, without choosing them.
        assertEquals(station, later.forecastAt(-21.29, 149.29, november)?.station)
    }

    @Test
    fun `without a connection and nothing saved, the model fills in`() {
        offline = true
        val t = tides()
        t.choose("place", -21.27, 149.31)
        try {
            t.download(station, november)
            fail("expected the download to fail")
        } catch (expected: IOException) {
        }
        assertNull(t.forecast("place", november))
        assertTrue(t.needsDownload(station, november))
    }

    @Test
    fun `next year's not out yet is looked for again in a few days`() {
        served[url2026] = year(2026)
        val t = tides()
        // 2027 answers 404 and the dataset's record doesn't list it.
        served[ckan] = """{"result": {"resources": [{"url": "$url2026"}]}}"""
        t.download(station, november)
        assertEquals(setOf(2026), t.savedYears(station))
        assertFalse(t.needsDownload(station, november + 3_600_000L))
        assertTrue(t.needsDownload(station, november + 3_600_000L, ignoreWait = true))
        assertTrue(t.needsDownload(station, november + 4 * 86_400_000L))
        // Before September, next year's isn't looked for at all.
        assertFalse(t.needsDownload(station, aest(2026, 5, 1)))
    }

    @Test
    fun `a failed connection doesn't hold back next year's for days`() {
        served[url2026] = year(2026)
        val t = tides()
        t.download(station, aest(2026, 6, 1))
        offline = true
        try {
            t.download(station, november)
            fail("expected the download to fail")
        } catch (expected: IOException) {
        }
        // Not noted as looked for: tried again as soon as the connection is back.
        assertTrue(t.needsDownload(station, november + 3_600_000L))
        offline = false
        served[ckan] = """{"result": {"resources": []}}"""
        t.download(station, november)
        // Now it's known not to be out: looked for again in a few days.
        assertFalse(t.needsDownload(station, november + 3_600_000L))
    }

    @Test
    fun `a year that isn't all there is refused, and a saved one is replaced`() {
        val firstHalf = MsqFixtures.yearRows(2026).filter { it.local.monthValue <= 6 }
        served[url2026] = MsqFixtures.csv(firstHalf)
        val t = tides()
        try {
            t.download(station, aest(2026, 3, 1))
            fail("expected the short file to be refused")
        } catch (expected: IOException) {
        }
        assertTrue(t.needsDownload(station, aest(2026, 3, 1)))
        // One saved by an older version is dropped when read, and downloaded again.
        File(folder.root, "060010A_2026.csv").writeText(MsqFixtures.csv(firstHalf))
        val later = tides()
        assertEquals(emptySet<Int>(), later.savedYears(station))
        assertFalse(File(folder.root, "060010A_2026.csv").exists())
        served[url2026] = year(2026)
        later.download(station, aest(2026, 3, 1))
        assertEquals(setOf(2026), later.savedYears(station))
    }

    @Test
    fun `heights are from a mean sea level that stays put as years are added`() {
        served[url2026] = year(2026)
        served[url2027] = year(2027)
        val t = tides()
        t.choose("place", -21.27, 149.31)
        t.download(station, aest(2026, 6, 1))
        val june = t.forecast("place", aest(2026, 6, 1))!!.lowestTideM!!
        t.download(station, november)
        val withNext = t.forecast("place", november)!!.lowestTideM!!
        // The mean of this calendar year's curve, whichever years are saved.
        assertEquals(june, withNext, 1e-12)
        val y2026 = MsqCsv.parse(year(2026)).extremes
        assertEquals(-MsqCurve.curveMean(y2026), withNext, 1e-12)
        // A station with its mean sea level known uses that.
        val mackay = index.byId("100826")!!
        assertEquals(2.9, mackay.mslAboveLatM!!, 0.0)
    }

    @Test
    fun `a GPS fix doesn't go round again when the saved tides don't cover now`() {
        // On 1 January before the year's first tide (or 31 December after the last), with
        // nothing to download: the model fills in, without another official round.
        assertEquals(UpdateAction.USE_MODEL, OfficialTides.actionOnUpdate(chosenHere = true, covers = false, downloadDue = false, force = false))
        assertEquals(UpdateAction.NOTHING, OfficialTides.actionOnUpdate(chosenHere = true, covers = true, downloadDue = false, force = false))
        assertEquals(UpdateAction.REFRESH, OfficialTides.actionOnUpdate(chosenHere = true, covers = true, downloadDue = true, force = false))
        assertEquals(UpdateAction.REFRESH, OfficialTides.actionOnUpdate(chosenHere = false, covers = false, downloadDue = false, force = false))
        assertEquals(UpdateAction.REFRESH, OfficialTides.actionOnUpdate(chosenHere = true, covers = false, downloadDue = false, force = true))
    }

    @Test
    fun `only data qld's own addresses, read with a limit`() {
        assertTrue(OfficialTides.isDataQld("https://www.data.qld.gov.au/ckan-opendata-attachments-prod/resources/x/q060010a_2026_hilo.csv?ETag=1"))
        assertFalse(OfficialTides.isDataQld("http://www.data.qld.gov.au/dataset/x.csv"))
        assertFalse(OfficialTides.isDataQld("https://www.data.qld.gov.au.example.com/x.csv"))
        assertFalse(OfficialTides.isDataQld("https://user@www.data.qld.gov.au/x.csv"))
        assertFalse(OfficialTides.isDataQld("https://tmr.qld.gov.au/x.csv"))
        assertFalse(OfficialTides.isDataQld("not a url"))
        val small = ByteArray(60_000) { 'a'.code.toByte() }
        assertEquals(60_000, OfficialTides.readCapped(small.inputStream(), 100_000).size)
        try {
            OfficialTides.readCapped(ByteArray(200_000).inputStream(), 100_000)
            fail("expected the big reply to be refused")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun `a year not in the bundled list is found on data qld`() {
        val url2028 = "https://www.data.qld.gov.au/dataset/a/resource/d/download/q060010a_half-tide-tug-harbour_2028_hilo.csv"
        served[ckan] = """{"result": {"resources": [
            {"url": "http://tmr.qld.gov.au/elsewhere/q060010a_2028_hilo.csv"},
            {"url": "$url2028"}]}}"""
        served[url2028] = year(2028)
        val t = tides()
        t.download(station, aest(2028, 2, 1))
        assertEquals(setOf(2028), t.savedYears(station))
        // Only data.qld.gov.au's own files are fetched.
        assertFalse(asked.any { it.contains("tmr.qld.gov.au") })
    }

    @Test
    fun `this year's must come, or it fails`() {
        served[ckan] = """{"result": {"resources": []}}"""
        try {
            tides().download(station, aest(2028, 2, 1))
            fail("expected NotPublished")
        } catch (expected: OfficialTides.NotPublished) {
        }
    }

    @Test
    fun `a file for another place is refused`() {
        served[url2026] = year(2026, lat = "-19 14")
        try {
            tides().download(station, aest(2026, 6, 1))
            fail("expected the file to be refused")
        } catch (expected: IOException) {
        }
        assertTrue(folder.root.listFiles { f -> f.name.endsWith(".csv") }.orEmpty().isEmpty())
    }

    @Test
    fun `old years are dropped`() {
        served[url2026] = year(2026)
        served[url2027] = year(2027)
        val t = tides()
        t.download(station, november)
        val y2028 = "https://www.data.qld.gov.au/dataset/a/resource/e/download/q060010a_x_2028_hilo.csv"
        served[ckan] = """{"result": {"resources": [{"url": "$y2028"}]}}"""
        served[y2028] = year(2028)
        t.download(station, aest(2028, 1, 10))
        // Last year's is kept for the days just gone; the year before goes.
        assertEquals(setOf(2027, 2028), t.savedYears(station))
        assertFalse(File(folder.root, "060010A_2026.csv").exists())
    }
}
