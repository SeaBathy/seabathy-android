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
        runCatching {
            // Every second even when still: the anchor alarm treats silence as lost GPS.
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, activeListener)
            registered = true
        }.onFailure { Log.w(TAG, "GPS provider unavailable", it) }
        runCatching {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3_000L, 5f, activeListener)
            registered = true
        }.onFailure { Log.w(TAG, "Network provider unavailable", it) }
        if (!registered) listener = null
    }

    fun stop() {
        listener?.let { active ->
            runCatching { locationManager.removeUpdates(active) }
                .onFailure { Log.w(TAG, "Unable to remove location updates", it) }
        }
        listener = null
    }

    private companion object { const val TAG = "SeaBathyLocation" }
}
