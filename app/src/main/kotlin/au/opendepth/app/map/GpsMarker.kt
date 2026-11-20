package au.opendepth.app.map

import java.util.Locale
import kotlin.math.abs

/**
 * What the boat's marker on the chart shows besides where it is: how wide
 * its accuracy circle is, and whether an arrow points along its course.
 * Only the working out, so it is tested without a map or a GPS.
 */
object GpsMarker {

    /**
     * Faster than this, in metres a second (about one knot), the arrow comes
     * on: slower, a GPS course means little.
     */
    const val MOVING_MPS = 0.5f

    /**
     * Slower than this, once the arrow is on, it goes. Between this and
     * [MOVING_MPS] it stays as it was, so drifting or trolling near one knot
     * doesn't make it blink.
     */
    const val STOPPED_MPS = 0.3f

    /**
     * How long a fix with no course or no speed (a network position landing
     * between GPS fixes, say) keeps the last course, in milliseconds, counted
     * from the last fix that gave one.
     */
    const val COURSE_HOLD_MS = 5_000L

    /** The arrow is drawn again once the course is this many degrees from the one shown. */
    const val COURSE_STEP_DEGREES = 3f

    /** The feature property the arrow turns by: the course over ground, degrees true. Left out when there is no arrow. */
    const val COURSE = "course"

    /** What a fix says of the boat's way: its course and speed, each only when [hasBearing] / [hasSpeed]. */
    data class Way(val hasBearing: Boolean, val bearing: Float, val hasSpeed: Boolean, val speedMps: Float)

    /**
     * The arrow as shown: the [course] it points along (degrees true, 0 up to
     * 360), or null for none, and [courseAtMs], when a fix last gave that
     * course, on the clock passed to [arrow].
     */
    data class Arrow(val course: Float?, val courseAtMs: Long = 0L) {
        companion object {
            val NONE = Arrow(null)
        }
    }

    /**
     * The arrow after a fix with [way] at [nowMs], from the arrow shown before
     * ([previous]). A null [way] is the GPS switched off: the arrow goes at once.
     *
     * The arrow comes on above [MOVING_MPS] and goes below [STOPPED_MPS];
     * in between it stays on (following the course) or off, as it was. A fix
     * with no course or no speed keeps the last course until [COURSE_HOLD_MS]
     * after the fix that gave it, then the arrow goes; one whose speed says
     * the boat is slower than [STOPPED_MPS] takes it away at once.
     */
    fun arrow(previous: Arrow, way: Way?, nowMs: Long): Arrow {
        if (way == null) return Arrow.NONE
        val speedKnown = way.hasSpeed && way.speedMps.isFinite()
        if (speedKnown && way.speedMps < STOPPED_MPS) return Arrow.NONE
        val bearing = if (way.hasBearing && way.bearing.isFinite()) ((way.bearing % 360f) + 360f) % 360f else null
        if (bearing == null || !speedKnown) {
            val held = previous.course != null && nowMs - previous.courseAtMs in 0..COURSE_HOLD_MS
            return if (held) previous else Arrow.NONE
        }
        val on = previous.course != null || way.speedMps > MOVING_MPS
        return if (on) Arrow(bearing, nowMs) else Arrow.NONE
    }

    /**
     * The course a fix alone gives the arrow, with none shown before: degrees
     * true from 0 up to 360, or null for no arrow (the fix has no course, has
     * no speed, or the boat is no faster than [MOVING_MPS]).
     */
    fun course(hasBearing: Boolean, bearing: Float, hasSpeed: Boolean, speedMps: Float): Float? =
        arrow(Arrow.NONE, Way(hasBearing, bearing, hasSpeed, speedMps), 0L).course

    /**
     * Whether the arrow on the chart has to be drawn again: it comes or
     * goes, or the course has turned [COURSE_STEP_DEGREES] or more from the
     * one [shown] (going the short way round through north).
     */
    fun courseChanged(shown: Float?, now: Float?): Boolean {
        if (shown == null || now == null) return shown != now
        val apart = abs(now - shown) % 360f
        return minOf(apart, 360f - apart) >= COURSE_STEP_DEGREES
    }

    /**
     * The marker's properties as JSON: "r0", the accuracy's radius in screen
     * pixels at zoom 0 (see [BaseMapLayers]), and [COURSE] when there is an arrow.
     */
    fun properties(accuracyRadiusZoom0: Double, course: Float?): String {
        val radius = String.format(Locale.US, "%.9f", if (accuracyRadiusZoom0.isFinite()) accuracyRadiusZoom0 else 0.0)
        return if (course == null) {
            "{\"r0\":$radius}"
        } else {
            "{\"r0\":$radius,\"$COURSE\":${String.format(Locale.US, "%.1f", course)}}"
        }
    }
}
