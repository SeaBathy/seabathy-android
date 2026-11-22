package au.opendepth.app.tides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneOffset

class MsqTidesTest {

    private val index = MsqStationIndex.parse(MsqFixtures.INDEX)

    private fun aest(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.ofHours(10)).toEpochMilli()

    // Station index ----------------------------------------------------------------

    @Test
    fun `nearest station within reach is chosen`() {
        assertEquals("060010A", index.nearest(-21.27, 149.31)?.id)
        // Between the two: the nearer one.
        assertEquals("100826", index.nearest(-21.14, 149.24)?.id)
    }

    @Test
    fun `nothing beyond a station's reach`() {
        // About 25 km east of Half Tide, out of everyone's reach: the model.
        assertNull(index.nearest(-21.28, 149.54))
        // 10 km from the river gauge: inside the usual 15 km but outside its 8 km.
        assertNull(index.nearest(-20.86, 149.35))
        assertEquals("999001", index.nearest(-20.92, 149.35)?.id)
    }

    @Test
    fun `waters of their own use no station`() {
        val near = MsqStationIndex(index.stations + MsqStation("X", "Close by", -20.95, 149.52, 15_000.0, "x", emptyMap()), index.separate)
        assertEquals("X", near.nearest(-20.95, 149.49)?.id)
        assertNull(near.nearest(-20.95, 149.53))
    }

    @Test
    fun `bundled index lists Queensland's stations`() {
        val file = listOf(File("src/main/assets/${MsqStationIndex.ASSET}"), File("app/src/main/assets/${MsqStationIndex.ASSET}")).first { it.isFile }
        val bundled = MsqStationIndex.parse(file.readText())
        assertEquals(77, bundled.stations.size)
        assertEquals(bundled.stations.size, bundled.stations.map { it.id }.toSet().size)
        bundled.stations.forEach { s ->
            assertTrue(s.name, s.lat in -29.0..-9.0 && s.lng in 138.0..154.0)
            assertTrue(s.name, s.years.isNotEmpty() && s.years.values.all { it.startsWith("https://www.data.qld.gov.au/") })
            assertTrue(s.name, s.reachM in 5_000.0..20_000.0)
            // Mean sea level above LAT, from a year of MSQ's 10-minute predictions.
            assertTrue(s.name, s.mslAboveLatM != null && s.mslAboveLatM!! in 0.3..6.0)
        }
        assertEquals(2.023, bundled.byId("100447")!!.mslAboveLatM!!, 0.001)
        assertEquals("Pumicestone Passage", bundled.separateAt(-26.82, 153.12)?.name)
        // Airlie Beach: Shute Harbour's gauge, about 7 km away.
        assertEquals("Shute Harbour", bundled.nearest(-20.268, 148.718)?.name)
        // In the Broadwater by the Seaway.
        assertEquals("Gold Coast Seaway", bundled.nearest(-27.94, 153.41)?.name)
        // New Farm, up the Brisbane River.
        assertEquals("Port Office Brisbane River", bundled.nearest(-27.468, 153.05)?.name)
        // Pumicestone Passage has tides of its own.
        assertNull(bundled.nearest(-26.82, 153.12))
        // Sydney: no Queensland station.
        assertNull(bundled.nearest(-33.86, 151.21))
    }

    // CSV --------------------------------------------------------------------------

    @Test
    fun `reads highs and lows in Queensland time above LAT`() {
        val rows = listOf(
            MsqFixtures.Row(LocalDateTime.of(2026, 1, 1, 2, 41), -1, 0.94),
            MsqFixtures.Row(LocalDateTime.of(2026, 1, 1, 9, 4), 1, 6.47),
            MsqFixtures.Row(LocalDateTime.of(2026, 1, 1, 15, 41), -1, 1.28)
        )
        val text = MsqFixtures.csv(rows) + "garbage , line\r\n31/12/2026 , 25:99 ,  1 ,   5.000\r\n02/01/2026 , 03:34 ,  9 ,   0.800\r\n"
        val p = MsqCsv.parse(text)
        assertEquals("060010A", p.stationId)
        assertEquals("HALF TIDE TUG HARBOUR", p.stationName)
        assertEquals(-21.2833, p.lat, 1e-3)
        assertEquals(149.3, p.lng, 1e-3)
        assertEquals(3, p.extremes.size)
        // 02:41 AEST on 1 January is 16:41 UTC on 31 December.
        assertEquals(java.time.Instant.parse("2025-12-31T16:41:00Z").toEpochMilli(), p.extremes[0].timeMillis)
        assertEquals(0.94, p.extremes[0].heightM, 1e-9)
        assertEquals(false, p.extremes[0].isHigh)
        assertEquals(true, p.extremes[1].isHigh)
        assertEquals(6.47, p.extremes[1].heightM, 1e-9)
    }

    @Test
    fun `refuses files not to chart datum`() {
        val rows = MsqFixtures.yearRows(2026).take(10)
        expectIOException { MsqCsv.parse(MsqFixtures.csv(rows, datum = "MEAN SEA LEVEL")) }
        expectIOException { MsqCsv.parse(MsqFixtures.csv(rows, zone = "")) }
        expectIOException { MsqCsv.parse(MsqFixtures.csv(rows, dataClass = "INTERVAL")) }
        expectIOException { MsqCsv.parse("<html>not a tide file</html>") }
    }

    @Test
    fun `files cut short are refused`() {
        val rows = MsqFixtures.yearRows(2026)
        val text = MsqFixtures.csv(rows)
        assertEquals(rows.size, MsqCsv.parse(text).extremes.size)
        // Fewer readings than the header says (a download cut off).
        expectIOException { MsqCsv.parse(MsqFixtures.csv(rows.take(1000), validReadings = rows.size)) }
        // The header's last reading isn't there.
        expectIOException { MsqCsv.parse(MsqFixtures.csv(rows.take(1000), validReadings = 1000, lastReading = "31 12 2026 23:44")) }
        // Cut off mid-row: the last line doesn't parse, so the count falls short.
        expectIOException { MsqCsv.parse(text.substring(0, text.length - 12)) }
    }

    @Test
    fun `time zones`() {
        assertEquals(ZoneOffset.ofHours(10), MsqCsv.zone("1000E"))
        assertEquals(ZoneOffset.ofHoursMinutes(9, 30), MsqCsv.zone("0930E"))
        assertEquals(ZoneOffset.ofHours(-5), MsqCsv.zone("0500W"))
        assertNull(MsqCsv.zone("AEST"))
    }

    // Curve and datum --------------------------------------------------------------

    @Test
    fun `curve passes through the official highs and lows`() {
        val p = MsqCsv.parse(MsqFixtures.csv(MsqFixtures.yearRows(2026)))
        val now = aest(2026, 6, 1, 12, 0)
        val built = MsqCurve.build(p.extremes, now)!!
        val mean = built.meanAboveLatM
        assertEquals(3.0, mean, 0.1)
        p.extremes.take(200).forEach { e ->
            assertEquals(e.heightM, built.curve.heightAt(e.timeMillis)!! + mean, 1e-9)
        }
        // Halfway in time between a low and a high, half the height (cosine).
        val (a, b) = p.extremes[10] to p.extremes[11]
        assertEquals((a.heightM + b.heightM) / 2, built.curve.heightAt((a.timeMillis + b.timeMillis) / 2)!! + mean, 0.01)
        // A quarter of the way: (1 - cos 45°) / 2 of the rise.
        val quarter = a.timeMillis + (b.timeMillis - a.timeMillis) / 4
        assertEquals(MsqCurve.between(a.heightM, b.heightM, 0.25), built.curve.heightAt(quarter)!! + mean, 0.005)
        // Highs and lows listed are exactly the published ones.
        val day = built.curve.extremes(aest(2026, 6, 1, 0, 0), aest(2026, 6, 2, 0, 0))
        val published = p.extremes.filter { it.timeMillis in aest(2026, 6, 1, 0, 0)..aest(2026, 6, 2, 0, 0) }
        assertEquals(published.map { it.timeMillis }, day.map { it.timeMillis })
        published.zip(day).forEach { (p, d) -> assertEquals(p.heightM, d.heightM + mean, 1e-9) }
    }

    @Test
    fun `curve stops at a gap in the data`() {
        val rows = MsqFixtures.yearRows(2026)
        val gapped = rows.filterNot { it.local.monthValue == 3 && it.local.dayOfMonth in 10..14 }
        val extremes = MsqCsv.parse(MsqFixtures.csv(gapped)).extremes
        assertNull(MsqCurve.build(extremes, aest(2026, 3, 12, 0, 0)))
        val after = MsqCurve.build(extremes, aest(2026, 6, 1, 0, 0))!!
        assertNull(after.curve.heightAt(aest(2026, 3, 1, 0, 0)))
        assertNotNull(after.curve.heightAt(aest(2026, 3, 20, 0, 0)))
    }

    @Test
    fun `curve breaks where two highs come together`() {
        val rows = MsqFixtures.yearRows(2026)
        // A low missing in March: two highs side by side.
        val missing = rows.indexOfFirst { it.local.monthValue == 3 && it.ind == -1 }
        val extremes = MsqCsv.parse(MsqFixtures.csv(rows.filterIndexed { i, _ -> i != missing })).extremes
        val firstHigh = extremes.indexOfFirst { it.isHigh && extremes.getOrNull(extremes.indexOf(it) + 1)?.isHigh == true }
        val between = (extremes[firstHigh].timeMillis + extremes[firstHigh + 1].timeMillis) / 2
        assertNull(MsqCurve.build(extremes, between))
        val later = MsqCurve.build(extremes, aest(2026, 6, 1, 0, 0))!!
        assertNull(later.curve.heightAt(between))
    }

    @Test
    fun `a given mean sea level sets the heights`() {
        val p = MsqCsv.parse(MsqFixtures.csv(MsqFixtures.yearRows(2026)))
        val built = MsqCurve.build(p.extremes, aest(2026, 6, 1, 12, 0), meanAboveLatM = 2.9)!!
        assertEquals(2.9, built.meanAboveLatM, 0.0)
        val e = p.extremes[500]
        assertEquals(e.heightM - 2.9, built.curve.heightAt(e.timeMillis)!!, 1e-9)
        // Without one, the mean of the curve.
        assertEquals(MsqCurve.curveMean(p.extremes), MsqCurve.build(p.extremes, aest(2026, 6, 1, 12, 0))!!.meanAboveLatM, 1e-9)
    }

    @Test
    fun `an official forecast holds only where its own station applies`() {
        val half = index.byId("060010A")!!
        val built = MsqCurve.build(MsqCsv.parse(MsqFixtures.csv(MsqFixtures.yearRows(2026))).extremes, aest(2026, 6, 1, 12, 0))!!
        val official = TideForecast(-21.27, 149.31, half.lat, half.lng, 0, built.curve, lowestTideM = -built.meanAboveLatM, station = half)
        val model = TideForecast(-21.27, 149.31, -21.3, 149.4, 0, built.curve, lowestTideM = -3.0)
        // Near its own gauge.
        assertTrue(official.holdsAt(-21.29, 149.29, index.nearest(-21.29, 149.29)))
        // 20 km away by Mackay's gauge: Mackay's tides apply there, not Half Tide's.
        assertFalse(official.holdsAt(-21.12, 149.22, index.nearest(-21.12, 149.22)))
        // Beyond every station, or in water with tides of its own.
        assertFalse(official.holdsAt(-21.28, 149.54, index.nearest(-21.28, 149.54)))
        assertFalse(official.holdsAt(-20.95, 149.55, index.nearest(-20.95, 149.55)))
        // Modelled tides say so, and hold anywhere within 50 km.
        assertTrue(model.holdsAt(-21.12, 149.22, index.nearest(-21.12, 149.22)))
        assertTrue(model.holdsAt(-20.95, 149.55, null))
        assertFalse(model.holdsAt(-20.5, 149.31, null))
    }

    @Test
    fun `an official forecast's tide is added to depths out to 50 km`() {
        val half = index.byId("060010A")!!
        val built = MsqCurve.build(MsqCsv.parse(MsqFixtures.csv(MsqFixtures.yearRows(2026))).extremes, aest(2026, 6, 1, 12, 0))!!
        val official = TideForecast(-21.27, 149.31, half.lat, half.lng, 0, built.curve, lowestTideM = -built.meanAboveLatM, station = half)
        // Beyond every station's reach, by another station, and in water with tides of its own:
        // still the nearest tides there are (1.4.2 and 1.4.3 added none there).
        assertTrue(official.addsTideAt(-21.28, 149.54))
        assertTrue(official.addsTideAt(-21.12, 149.22))
        assertTrue(official.addsTideAt(-20.95, 149.55))
        // Beyond 50 km: another place's tides, so none.
        assertFalse(official.addsTideAt(-20.5, 149.31))
        // The tide window stays strict: it says when it has no tides there.
        assertFalse(official.holdsAt(-21.28, 149.54, index.nearest(-21.28, 149.54)))
    }

    @Test
    fun `a manual height keeps the typed chart datum`() {
        val half = index.byId("060010A")!!
        val built = MsqCurve.build(MsqCsv.parse(MsqFixtures.csv(MsqFixtures.yearRows(2026))).extremes, aest(2026, 6, 1, 12, 0))!!
        val official = TideForecast(-21.27, 149.31, half.lat, half.lng, 0, built.curve, lowestTideM = -3.0, station = half)
        val manual = TideSettings(mode = TideMode.MANUAL, datum = TideDatum.MEAN_SEA_LEVEL, chartDatumAuto = false, chartDatumBelowMslM = 1.0, manualHeightM = 2.5)
        // 2.5 m above LAT is 1.5 m above mean sea level by the typed 1.0 m, not the official 3.0 m.
        assertEquals(1.5, manual.heightAboveDatum(official, 0)!!, 1e-9)
        assertEquals(1.0, manual.chartDatumBelowMsl(official)!!, 1e-9)
        // With Forecast, the official datum.
        assertEquals(3.0, manual.copy(mode = TideMode.MODEL).chartDatumBelowMsl(official)!!, 1e-9)
    }

    @Test
    fun `official heights are shown above LAT exactly whatever the settings`() {
        val p = MsqCsv.parse(MsqFixtures.csv(MsqFixtures.yearRows(2026)))
        val now = aest(2026, 6, 1, 12, 0)
        val built = MsqCurve.build(p.extremes, now)!!
        val station = index.byId("060010A")!!
        val forecast = TideForecast(-21.28, 149.3, station.lat, station.lng, now, built.curve, lowestTideM = -built.meanAboveLatM, station = station)
        val high = p.extremes.first { it.isHigh && it.timeMillis > now }
        val msl = TideSettings(datum = TideDatum.MEAN_SEA_LEVEL)
        val cd = TideSettings(datum = TideDatum.CHART_DATUM)
        // Typed chart datum is for the model: official predictions keep their own.
        val typed = TideSettings(datum = TideDatum.CHART_DATUM, chartDatumAuto = false, chartDatumBelowMslM = 1.0)
        for (s in listOf(msl, cd, typed)) {
            assertEquals(high.heightM, s.shownHeight(forecast, high.timeMillis)!!, 1e-9)
            assertTrue(s.showsChartDatum(forecast))
        }
        // Chart-datum surveys get the tide as published; mean-sea-level ones, from the mean.
        assertEquals(high.heightM, cd.heightAboveDatum(forecast, high.timeMillis)!!, 1e-9)
        assertEquals(high.heightM, typed.heightAboveDatum(forecast, high.timeMillis)!!, 1e-9)
        assertEquals(high.heightM - built.meanAboveLatM, msl.heightAboveDatum(forecast, high.timeMillis)!!, 1e-9)
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            fail("expected an IOException")
        } catch (expected: IOException) {
        }
    }
}
