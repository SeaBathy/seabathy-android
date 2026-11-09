package au.opendepth.app.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.maplibre.android.MapLibre
import org.maplibre.android.log.Logger
import org.maplibre.android.log.LoggerDefinition
import java.util.concurrent.atomic.AtomicInteger

/**
 * Routes MapLibre's native warnings/errors, and a snapshot of its
 * connectivity flag, into [DiagnosticLog].
 *
 * `LocalTileConnectivity` pins MapLibre's flag to true, so the startup
 * snapshot (and the one logged on a style load failure) should always read
 * `mapLibreConnected=true`; false there means the override did not take.
 */
object MapLibreDiagnostics {
    private const val TAG = "MapLibreDiag"

    /**
     * Caps forwarded native lines, per process, so a flood of per-tile
     * errors cannot rotate the log file and erase the restore evidence.
     */
    private const val MAX_FORWARDED_NATIVE_LINES = 200

    private val forwarded = AtomicInteger(0)

    /**
     * Safe to call on every Activity creation: re-installs the same
     * process-wide logger, which holds no Activity or Context reference.
     */
    fun install(context: Context) {
        Logger.setLoggerDefinition(ForwardingLogger)
        DiagnosticLog.log(TAG, "startup: ${describeConnectivity(context)}")
    }

    fun describeConnectivity(context: Context): String {
        val systemOnline = runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            manager?.getNetworkCapabilities(manager.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrNull()
        val mapLibreConnected = runCatching { MapLibre.isConnected() }.getOrNull()
        return "systemHasInternet=$systemOnline mapLibreConnected=$mapLibreConnected"
    }

    private fun forward(level: String, tag: String, message: String, error: Throwable?) {
        if (forwarded.incrementAndGet() > MAX_FORWARDED_NATIVE_LINES) return
        val text = "native $level [$tag] $message"
        if (error != null) DiagnosticLog.logError(TAG, text, error) else DiagnosticLog.log(TAG, text)
    }

    /** Keeps MapLibre's default logcat output and additionally captures W/E into [DiagnosticLog]. */
    private object ForwardingLogger : LoggerDefinition {
        override fun v(tag: String, msg: String) { Log.v(tag, msg) }
        override fun v(tag: String, msg: String, tr: Throwable) { Log.v(tag, msg, tr) }
        override fun d(tag: String, msg: String) { Log.d(tag, msg) }
        override fun d(tag: String, msg: String, tr: Throwable) { Log.d(tag, msg, tr) }
        override fun i(tag: String, msg: String) { Log.i(tag, msg) }
        override fun i(tag: String, msg: String, tr: Throwable) { Log.i(tag, msg, tr) }
        override fun w(tag: String, msg: String) { Log.w(tag, msg); forward("W", tag, msg, null) }
        override fun w(tag: String, msg: String, tr: Throwable) { Log.w(tag, msg, tr); forward("W", tag, msg, tr) }
        override fun e(tag: String, msg: String) { Log.e(tag, msg); forward("E", tag, msg, null) }
        override fun e(tag: String, msg: String, tr: Throwable) { Log.e(tag, msg, tr); forward("E", tag, msg, tr) }
    }
}
