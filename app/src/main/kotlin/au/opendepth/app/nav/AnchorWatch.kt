package au.opendepth.app.nav

import android.content.SharedPreferences

/** Where the anchor is and how far the boat may swing before the alarm. */
data class Anchor(val lat: Double, val lng: Double, val radiusM: Double, val setMillis: Long)

/** What the anchor watch makes of the latest fix. */
data class AnchorStatus(
    /** Boat to anchor, metres. */
    val distanceM: Double,
    val bearingFromAnchor: Double,
    /** Outside the circle on [AnchorWatch.FIXES_TO_ALARM] fixes in a row. */
    val dragging: Boolean,
    /** The fix is too rough to tell (accuracy worse than the radius). */
    val gpsPoor: Boolean
)

/**
 * Anchor alarm logic: the boat may swing anywhere inside the circle; leaving
 * it on two fixes in a row means the anchor is dragging (one stray fix
 * doesn't sound the alarm). Fixes much rougher than the circle are reported
 * as poor GPS instead of guessed at. Pure Kotlin, unit-tested.
 */
class AnchorWatch(val anchor: Anchor) {

    private var outsideCount = 0
    var maxDistanceM = 0.0
        private set

    /** Keeps the furthest swing when only the circle size changes. */
    fun carryOver(previous: AnchorWatch) {
        maxDistanceM = previous.maxDistanceM
    }

    fun update(lat: Double, lng: Double, accuracyM: Double?): AnchorStatus {
        val distance = NavGeo.distanceM(anchor.lat, anchor.lng, lat, lng)
        val bearing = NavGeo.bearing(anchor.lat, anchor.lng, lat, lng)
        val poor = accuracyM != null && accuracyM > maxOf(anchor.radiusM, POOR_ACCURACY_FLOOR_M)
        if (!poor) {
            maxDistanceM = maxOf(maxDistanceM, distance)
            outsideCount = if (distance > anchor.radiusM) outsideCount + 1 else 0
        }
        return AnchorStatus(distance, bearing, outsideCount >= FIXES_TO_ALARM, poor)
    }

    companion object {
        const val FIXES_TO_ALARM = 2
        /** Accuracy up to this is always usable, even with a small circle. */
        const val POOR_ACCURACY_FLOOR_M = 25.0
        /** Radii offered, metres. */
        val RADII_M = listOf(15.0, 25.0, 40.0, 60.0, 100.0, 150.0)
        const val DEFAULT_RADIUS_M = 40.0
    }
}

/** The set anchor, kept so the watch carries on after SeaBathy restarts. */
class AnchorRepository(private val preferences: SharedPreferences) {
    fun load(): Anchor? {
        if (!preferences.contains(K_LAT)) return null
        return Anchor(
            lat = preferences.getString(K_LAT, null)?.toDoubleOrNull() ?: return null,
            lng = preferences.getString(K_LNG, null)?.toDoubleOrNull() ?: return null,
            radiusM = preferences.getString(K_RADIUS, null)?.toDoubleOrNull() ?: AnchorWatch.DEFAULT_RADIUS_M,
            setMillis = preferences.getLong(K_SET, 0L)
        )
    }

    fun save(anchor: Anchor?) {
        val edit = preferences.edit()
        if (anchor == null) {
            edit.remove(K_LAT).remove(K_LNG).remove(K_RADIUS).remove(K_SET)
        } else {
            edit.putString(K_LAT, anchor.lat.toString())
                .putString(K_LNG, anchor.lng.toString())
                .putString(K_RADIUS, anchor.radiusM.toString())
                .putLong(K_SET, anchor.setMillis)
        }
        edit.apply()
    }

    /** The radius last used, offered next time. */
    var lastRadiusM: Double
        get() = preferences.getString(K_LAST_RADIUS, null)?.toDoubleOrNull() ?: AnchorWatch.DEFAULT_RADIUS_M
        set(value) { preferences.edit().putString(K_LAST_RADIUS, value.toString()).apply() }

    private companion object {
        const val K_LAT = "anchor_lat"
        const val K_LNG = "anchor_lng"
        const val K_RADIUS = "anchor_radius_m"
        const val K_SET = "anchor_set_millis"
        const val K_LAST_RADIUS = "anchor_last_radius_m"
    }
}
