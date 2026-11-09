package au.opendepth.app.download

import android.content.Context
import android.content.SharedPreferences

/**
 * The last finished offline download not yet offered for import. The
 * service records it; the app offers it the next time it is on screen (or
 * at once, if it already is). Without this, a download finished with
 * notifications off — or while the user was in the app and never looked
 * at the notification — never reached the map.
 */
object CompletedDownloads {
    private const val PREFS = "completed_downloads"
    private const val KEY_PATH = "path"
    private const val KEY_NAME = "name"
    private const val KEY_ATTRIBUTION = "attribution"

    class Pending(val path: String, val sourceName: String, val attribution: String)

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(context: Context, path: String, sourceName: String, attribution: String) {
        prefs(context).edit()
            .putString(KEY_PATH, path)
            .putString(KEY_NAME, sourceName)
            .putString(KEY_ATTRIBUTION, attribution)
            .apply()
    }

    fun pending(context: Context): Pending? {
        val prefs = prefs(context)
        val path = prefs.getString(KEY_PATH, null) ?: return null
        return Pending(path, prefs.getString(KEY_NAME, null) ?: "Download", prefs.getString(KEY_ATTRIBUTION, null).orEmpty())
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
