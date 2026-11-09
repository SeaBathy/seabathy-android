package au.opendepth.app.track

import au.opendepth.app.storage.GpxImporter
import au.opendepth.app.util.GpxExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrackTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val lat0 = -19.0
    private val lng0 = 147.0

    /** [metres] north of the start. */
    private fun north(metres: Double) = lat0 + metres / 111_195.0

    /** A boat heading north at [knots], one point every [stepS] seconds. */
    private fun run(knots: Double, count: Int, stepS: Long = 10, startMillis: Long = 1_700_000_000_000L): List<TrackPoint> {
        val ms = knots / 1.943844
        return (0 until count).map { i -> TrackPoint(startMillis + i * stepS * 1000, north(ms * stepS * i), lng0) }
    }

    @Test
    fun `filter skips small moves, rough fixes and glitches`() {
        val filter = TrackFilter()
        val t = 1_000_000L
        assertNotNull(filter.accept(t, lat0, lng0, 5.0))
        // Swinging at anchor: under 10 m adds nothing.
        assertNull(filter.accept(t + 10_000, north(6.0), lng0, 5.0))
        // Too rough to trust.
        assertNull(filter.accept(t + 20_000, north(200.0), lng0, 80.0))
        // 1 km in 5 s is a GPS jump, not the boat.
        assertNull(filter.accept(t + 25_000, north(1000.0), lng0, 5.0))
        assertNotNull(filter.accept(t + 30_000, north(30.0), lng0, 5.0))
        // After a long gap, a big move is real (the phone was off).
        assertNotNull(filter.accept(t + 30_000 + 600_000, north(50_000.0), lng0, 5.0))
    }

    @Test
    fun `summary has distance, moving time and speeds`() {
        val points = run(knots = 5.0, count = 61) // 10 minutes at 5 kn
        val s = TrackMath.summarise("id", "Trip", points)
        val expectedM = 5 / 1.943844 * 600
        assertEquals(expectedM, s.distanceM, 2.0)
        assertEquals(600_000L, s.endMillis - s.startMillis)
        assertEquals(600_000L, s.movingMillis)
        assertEquals(5.0, s.averageSpeedMs * 1.943844, 0.05)
        assertEquals(5.0, s.maxSpeedMs * 1.943844, 0.05)
    }

    @Test
    fun `a stop and a GPS gap don't count as moving or distance`() {
        val first = run(knots = 6.0, count = 31)
        val last = first.last()
        // An hour sitting still (one point at the end, a few metres away), then a gap and on again.
        val parked = TrackPoint(last.timeMillis + 3_600_000, last.lat + 12 / 111_195.0, lng0)
        val second = run(knots = 6.0, count = 31, startMillis = parked.timeMillis + 30 * 60_000L)
            .map { it.copy(lat = it.lat + 0.1) }
        val s = TrackMath.summarise("id", "Trip", first + parked + second)
        // The hour at anchor stays joined; the 30-minute gap with an 11 km jump splits the track and isn't added.
        assertEquals(listOf(32, 31), TrackMath.segments(first + parked + second).map { it.size })
        val legM = 6 / 1.943844 * 300
        assertEquals(2 * legM + 12, s.distanceM, 3.0)
        // Two 5-minute legs moving; the hour at anchor is not.
        assertEquals(600_000L, s.movingMillis)
        assertEquals(6.0, s.maxSpeedMs * 1.943844, 0.05)
    }

    @Test
    fun `a single noisy jump doesn't set the top speed`() {
        val points = run(knots = 4.0, count = 30).toMutableList()
        // One fix 60 m off the line for one step: 12 kn over 10 s, but not over 30 s.
        points[15] = points[15].copy(lng = lng0 + 60 / (111_195.0 * Math.cos(Math.toRadians(lat0))))
        val top = TrackMath.maxSpeedMs(points) * 1.943844
        assertTrue("top $top", top < 8.0)
    }

    @Test
    fun `a track shorter than the top speed window still has a top speed`() {
        val points = run(knots = 5.0, count = 3) // 20 s
        val s = TrackMath.summarise("id", "Short", points)
        assertEquals(5.0, s.maxSpeedMs * 1.943844, 0.05)
        assertTrue(s.maxSpeedMs >= s.averageSpeedMs)
    }

    @Test
    fun `simplify keeps the shape and drops straight runs`() {
        val straight = run(knots = 5.0, count = 200)
        val turn = straight.last().let { end ->
            (1..200).map { i -> TrackPoint(end.timeMillis + i * 10_000L, end.lat, lng0 + i * 25 / 105_000.0) }
        }
        val simple = TrackMath.simplify(straight + turn, 2.0)
        assertEquals(3, simple.size)
        assertEquals(straight.last(), simple[1])
    }

    @Test
    fun `codec round-trips and skips a damaged last line`() {
        val points = run(knots = 3.0, count = 3)
        val text = points.joinToString("") { TrackCodec.line(it) } + "17000,-19.0"
        assertEquals(points, TrackCodec.parse(text))
    }

    @Test
    fun `store appends, finishes, renames and deletes`() {
        val store = TrackStore(temp.root)
        val points = run(knots = 5.0, count = 20)
        points.forEach { store.append("a", it) }
        val saved = store.finish("a", "Morning")!!
        assertEquals(20, saved.pointCount)
        assertEquals(points, store.points("a"))

        // A recording that never moved is thrown away.
        store.append("b", points[0])
        assertNull(store.finish("b", "Nothing"))
        assertTrue(store.points("b").isEmpty())

        // A fresh store reads the index back.
        val reopened = TrackStore(temp.root)
        assertEquals(listOf("Morning"), reopened.summaries().map { it.name })
        reopened.rename("a", "Reef run")
        assertTrue(reopened.isNameTaken("reef run"))
        assertEquals("Reef run", TrackStore(temp.root).summary("a")?.name)
        reopened.delete("a")
        assertTrue(TrackStore(temp.root).summaries().isEmpty())
    }

    @Test
    fun `GPX export reads back through the importer`() {
        val first = run(knots = 5.0, count = 5)
        // GPS back an hour later, 11 km on: a new segment.
        val second = run(knots = 5.0, count = 5, startMillis = first.last().timeMillis + 3_600_000).map { it.copy(lat = it.lat + 0.1) }
        val gpx = GpxExporter.buildTrack("Reef & back", first + second)
        assertTrue(gpx.contains("<name>Reef &amp; back</name>"))
        assertEquals(2, Regex("<trkseg>").findAll(gpx).count())
        assertTrue(gpx.contains("<time>2023-11-14T22:13:20Z</time>"))
        val track = GpxImporter.parse(gpx.byteInputStream()).tracks.single()
        assertEquals("Reef & back", track.name)
        val expected = first + second
        assertEquals(expected.map { it.timeMillis }, track.points.map { it.timeMillis })
        expected.zip(track.points).forEach { (e, a) ->
            assertEquals(e.lat, a.lat, 1e-7)
            assertEquals(e.lng, a.lng, 1e-7)
        }
    }
}
