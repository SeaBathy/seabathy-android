package au.opendepth.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsMarkerTest {

    private fun good(speedMps: Float, bearing: Float = 90f) = GpsMarker.Way(true, bearing, true, speedMps)

    /** A network position, or a fix that dropped its course: no course, no speed. */
    private val courseless = GpsMarker.Way(false, 0f, false, 0f)

    /** Runs fixes one second apart from [start], returning the arrow after each. */
    private fun run(vararg ways: GpsMarker.Way?, start: GpsMarker.Arrow = GpsMarker.Arrow.NONE, stepMs: Long = 1_000L): List<GpsMarker.Arrow> {
        var arrow = start
        return ways.mapIndexed { i, way -> GpsMarker.arrow(arrow, way, (i + 1) * stepMs).also { arrow = it } }
    }

    @Test
    fun `speeding up through the band, the arrow comes on only above the upper speed`() {
        val arrows = run(good(0.2f), good(0.35f), good(0.45f), good(0.5f), good(0.55f), good(0.7f))
        assertEquals(listOf(null, null, null, null, 90f, 90f), arrows.map { it.course })
    }

    @Test
    fun `slowing down through the band, the arrow stays on until below the lower speed`() {
        val arrows = run(good(1.0f), good(0.45f), good(0.35f), good(0.3f), good(0.29f), good(0.1f))
        assertEquals(listOf(90f, 90f, 90f, 90f, null, null), arrows.map { it.course })
    }

    @Test
    fun `hovering at 0_4 m a second keeps the arrow as it was`() {
        // Come down from speed: on, and it stays on, following the course.
        val fromAbove = run(good(1f, 80f), good(0.4f, 81f), good(0.4f, 85f), good(0.4f, 79f))
        assertEquals(listOf(80f, 81f, 85f, 79f), fromAbove.map { it.course })
        // Come up from stopped: off, and it stays off.
        val fromBelow = run(good(0f), good(0.4f), good(0.4f), good(0.4f))
        assertTrue(fromBelow.all { it.course == null })
    }

    @Test
    fun `a fix with no course between good ones keeps the last course`() {
        val arrows = run(good(2f, 45f), courseless, good(2f, 47f))
        assertEquals(listOf(45f, 45f, 47f), arrows.map { it.course })
        // The hold is counted from the fix that gave the course.
        assertEquals(1_000L, arrows[1].courseAtMs)
    }

    @Test
    fun `courseless fixes keep the course for a few seconds, then the arrow goes`() {
        val start = GpsMarker.Arrow(45f, courseAtMs = 0L)
        assertEquals(45f, GpsMarker.arrow(start, courseless, GpsMarker.COURSE_HOLD_MS).course)
        assertNull(GpsMarker.arrow(start, courseless, GpsMarker.COURSE_HOLD_MS + 1).course)
        // Nothing comes back from a courseless fix when there was no arrow.
        assertNull(GpsMarker.arrow(GpsMarker.Arrow.NONE, courseless, 1_000L).course)
        // A fix with a speed but no course is just as courseless.
        val speedOnly = GpsMarker.Way(false, 0f, true, 1.5f)
        assertEquals(45f, GpsMarker.arrow(start, speedOnly, 2_000L).course)
    }

    @Test
    fun `a fix saying the boat has stopped takes the arrow away at once, course or not`() {
        val start = GpsMarker.Arrow(45f, courseAtMs = 0L)
        assertNull(GpsMarker.arrow(start, GpsMarker.Way(false, 0f, true, 0f), 1_000L).course)
        assertNull(GpsMarker.arrow(start, good(0.1f), 1_000L).course)
    }

    @Test
    fun `GPS switched off, the arrow goes at once`() {
        val arrows = run(good(3f), null, courseless)
        assertEquals(listOf(90f, null, null), arrows.map { it.course })
    }

    @Test
    fun `a moving boat with a course gets an arrow along it`() {
        assertEquals(47f, GpsMarker.course(hasBearing = true, bearing = 47f, hasSpeed = true, speedMps = 3f)!!, 1e-4f)
        assertEquals(0f, GpsMarker.course(true, 0f, true, 0.6f)!!, 1e-4f)
    }

    @Test
    fun `a stopped or slow boat gets none`() {
        assertNull(GpsMarker.course(true, 47f, true, 0f))
        assertNull(GpsMarker.course(true, 47f, true, 0.3f))
        // At the threshold itself it is not yet moving.
        assertNull(GpsMarker.course(true, 47f, true, GpsMarker.MOVING_MPS))
    }

    @Test
    fun `a fix with no course or no speed gets none`() {
        assertNull(GpsMarker.course(hasBearing = false, bearing = 47f, hasSpeed = true, speedMps = 5f))
        assertNull(GpsMarker.course(hasBearing = true, bearing = 47f, hasSpeed = false, speedMps = 5f))
        assertNull(GpsMarker.course(true, Float.NaN, true, 5f))
        assertNull(GpsMarker.course(true, 47f, true, Float.NaN))
    }

    @Test
    fun `the course is kept from 0 up to 360`() {
        assertEquals(0f, GpsMarker.course(true, 360f, true, 5f)!!, 1e-4f)
        assertEquals(350f, GpsMarker.course(true, -10f, true, 5f)!!, 1e-4f)
        assertEquals(10f, GpsMarker.course(true, 370f, true, 5f)!!, 1e-4f)
    }

    @Test
    fun `the arrow is drawn again when it comes or goes`() {
        assertTrue(GpsMarker.courseChanged(shown = null, now = 90f))
        // The boat stopped: the arrow has to go even though the boat has not moved.
        assertTrue(GpsMarker.courseChanged(shown = 90f, now = null))
        assertFalse(GpsMarker.courseChanged(shown = null, now = null))
    }

    @Test
    fun `the arrow follows a turn, but not every wobble`() {
        assertFalse(GpsMarker.courseChanged(shown = 90f, now = 90f))
        assertFalse(GpsMarker.courseChanged(shown = 90f, now = 92f))
        assertTrue(GpsMarker.courseChanged(shown = 90f, now = 93f))
        assertTrue(GpsMarker.courseChanged(shown = 90f, now = 86f))
        assertTrue(GpsMarker.courseChanged(shown = 90f, now = 270f))
    }

    @Test
    fun `a turn through north is measured the short way round`() {
        assertFalse(GpsMarker.courseChanged(shown = 359f, now = 1f))
        assertTrue(GpsMarker.courseChanged(shown = 358f, now = 2f))
        assertTrue(GpsMarker.courseChanged(shown = 2f, now = 358f))
    }

    @Test
    fun `the marker carries its course only when it has an arrow`() {
        assertEquals("{\"r0\":0.000127765}", GpsMarker.properties(0.000127765, course = null))
        assertEquals("{\"r0\":0.000127765,\"course\":123.5}", GpsMarker.properties(0.000127765, course = 123.46f))
        assertEquals("{\"r0\":0.000000000,\"course\":0.0}", GpsMarker.properties(0.0, course = 0f))
    }

    @Test
    fun `the marker's properties are always valid JSON numbers`() {
        // An accuracy that is not a number would break the whole marker.
        assertEquals("{\"r0\":0.000000000}", GpsMarker.properties(Double.NaN, null))
        assertEquals("{\"r0\":0.000000000}", GpsMarker.properties(Double.POSITIVE_INFINITY, null))
        val previous = java.util.Locale.getDefault()
        try {
            // A decimal comma would not be JSON.
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("{\"r0\":0.500000000,\"course\":45.5}", GpsMarker.properties(0.5, 45.5f))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
