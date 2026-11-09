package au.opendepth.app.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import au.opendepth.app.diagnostics.DiagnosticLog
import org.maplibre.android.MapLibre
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps MapLibre "online" so it always asks the local tile server
 * (127.0.0.1) for tiles.
 *
 * When MapLibre thinks the phone is offline it holds back every request,
 * local ones too, so offline layers never drew with the wifi off. Pinning it
 * online fixes that; a real remote request with no internet just fails
 * fast. Pinning means remote tiles that failed while offline wait out their
 * backoff, so [onInternetRegained] toggles the flag to retry them at once.
 */
object LocalTileConnectivity {
    private const val TAG = "LocalTileConnectivity"

    private val installed = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Call after `MapLibre.getInstance`; safe to call again on activity recreation. */
    fun install(context: Context) {
        MapLibre.setConnected(true)
        if (!installed.compareAndSet(false, true)) return

        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val edge = InternetEdgeDetector(initial = manager.hasInternet())
        DiagnosticLog.log(TAG, "MapLibre pinned connected=true; systemHasInternet=${edge.current}")

        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    update(edge, capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                }

                override fun onLost(network: Network) = update(edge, false)
            })
        }.onFailure { DiagnosticLog.logError(TAG, "could not register network callback", it) }
    }

    private fun update(edge: InternetEdgeDetector, hasInternet: Boolean) {
        if (edge.update(hasInternet)) mainHandler.post(::onInternetRegained)
    }

    private fun onInternetRegained() {
        DiagnosticLog.log(TAG, "internet regained: pulsing MapLibre connectivity to retry remote requests")
        MapLibre.setConnected(false)
        MapLibre.setConnected(true)
    }

    private fun ConnectivityManager.hasInternet(): Boolean =
        getNetworkCapabilities(activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

/** Reports only false -> true transitions of real internet reachability. */
internal class InternetEdgeDetector(initial: Boolean) {
    @Volatile
    var current: Boolean = initial
        private set

    /** Returns true when internet has just come back. */
    @Synchronized
    fun update(hasInternet: Boolean): Boolean {
        val regained = hasInternet && !current
        current = hasInternet
        return regained
    }
}
