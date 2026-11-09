package au.opendepth.app.data

import android.content.SharedPreferences
import au.opendepth.app.diagnostics.DiagnosticLog

/**
 * Called when a stored JSON list can no longer be parsed. The repositories
 * then fall back to an empty list, and the next save would silently replace
 * the user's real data with it — so the raw text is copied to a side key
 * first, where it survives and can be recovered from a debug log or a fixed
 * build.
 */
internal fun SharedPreferences.preserveUnreadable(key: String, raw: String, error: Throwable) {
    val backupKey = "${key}_unreadable"
    if (getString(backupKey, null) != raw) {
        edit().putString(backupKey, raw).apply()
    }
    DiagnosticLog.logError("Storage", "could not read $key; kept a copy under $backupKey", error)
}
