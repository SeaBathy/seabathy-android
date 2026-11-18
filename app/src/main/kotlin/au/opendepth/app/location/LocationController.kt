package au.opendepth.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log

class LocationController(
    context: Context,
    private val onLocation: (Location) -> Unit
) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (listener != null) return
        val activeListener = object : LocationListener {
            override fun onLocationChanged(location: Location) = onLocation(location)
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
                locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 1_000L, 0f, activeListener)
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
        newestRecent()?.let { onLocation(it) }
    }

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

    private companion object {
        const val TAG = "SeaBathyLocation"
        /** A remembered position this recent is shown while the first fresh fix comes. */
        const val RECENT_MS = 5 * 60_000L
    }
}
