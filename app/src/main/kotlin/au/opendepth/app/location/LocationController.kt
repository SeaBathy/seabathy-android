package au.opendepth.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log

class LocationController(
    private val context: Context,
    private val onLocation: (Location) -> Unit
) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (listener != null) return
        val activeListener = object : LocationListener {
            override fun onLocationChanged(location: Location) = offer(location)
            @Deprecated("Deprecated by Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        listener = activeListener
        var registered = false
        // Android's fused location (Android 12 on): GPS, Wi-Fi and phone masts together, so a
        // position comes in seconds indoors or under a roof, not minutes.
        if (android.os.Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in locationManager.allProviders) {
            runCatching {
                // Asked for at high accuracy, or it answers with a rough phone-mast position.
                val request = android.location.LocationRequest.Builder(1_000L)
                    .setQuality(android.location.LocationRequest.QUALITY_HIGH_ACCURACY)
                    .build()
                locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, request, context.mainExecutor, activeListener)
                registered = true
            }.onFailure { Log.w(TAG, "Fused provider unavailable", it) }
        }
        runCatching {
            // Every second even when still: the anchor alarm treats silence as lost GPS.
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, activeListener)
            registered = true
        }.onFailure { Log.w(TAG, "GPS provider unavailable", it) }
        runCatching {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3_000L, 5f, activeListener)
            registered = true
        }.onFailure { Log.w(TAG, "Network provider unavailable", it) }
        if (!registered) {
            listener = null
            return
        }
        // The boat on the chart straight away from the newest recent position the phone has,
        // while the first fresh fix comes.
        accepted = null
        newestRecent()?.let { offer(it) }
    }

    /** The last position passed on. */
    private var accepted: Location? = null

    /**
     * Passes [location] on unless a better one is still fresh: GPS, Wi-Fi and phone-mast
     * positions arrive mixed together, and a mast position can be kilometres out. So a rough
     * position never replaces a good recent one; it's only used while there's nothing better.
     */
    private fun offer(location: Location) {
        if (!shouldUse(accepted?.let { it.accuracyOrRough() to it.time }, location.accuracyOrRough(), location.time)) return
        accepted = location
        onLocation(location)
    }

    private fun Location.accuracyOrRough(): Float = if (hasAccuracy()) accuracy else ROUGH_M

    /** The newest position any provider remembers, if it's from the last few minutes. */
    @SuppressLint("MissingPermission")
    private fun newestRecent(): Location? = runCatching {
        locationManager.getProviders(true)
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.takeIf { System.currentTimeMillis() - it.time < RECENT_MS }
    }.getOrNull()

    /** Location is switched on in Android's settings. */
    val isLocationOn: Boolean
        get() = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28) locationManager.isLocationEnabled
            else locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(true)

    fun stop() {
        listener?.let { active ->
            runCatching { locationManager.removeUpdates(active) }
                .onFailure { Log.w(TAG, "Unable to remove location updates", it) }
        }
        listener = null
    }

    companion object {
        private const val TAG = "SeaBathyLocation"
        /** A remembered position this recent is shown while the first fresh fix comes. */
        const val RECENT_MS = 5 * 60_000L
        /** A position this accurate (metres) is always used. */
        const val GOOD_M = 50f
        /** Taken as the accuracy of a position that doesn't say. */
        const val ROUGH_M = 500f
        /** A good position counts as fresh this long: nothing worse replaces it meanwhile. */
        const val FRESH_MS = 30_000L

        /**
         * Whether a new position (its accuracy in metres and time) should replace the last one
         * used ([last]: accuracy and time, or null). Pure, unit-tested.
         */
        fun shouldUse(last: Pair<Float, Long>?, accuracy: Float, time: Long): Boolean {
            if (last == null || accuracy <= GOOD_M) return true
            val (lastAccuracy, lastTime) = last
            // As good as, or better than, what's shown: use it.
            if (accuracy <= lastAccuracy * 1.5f) return true
            // Worse: only once what's shown has gone stale.
            return time - lastTime > FRESH_MS
        }
    }
}
