package au.opendepth.app.diagnostics

import android.content.Context
import android.os.StrictMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's debug log: a text file in private storage, capped at
 * [MAX_BYTES], so a problem can be traced from the phone alone. Always on;
 * it only leaves the phone when the user exports it. Call [init] once
 * before [log].
 */
object DiagnosticLog {
    private const val FILE_NAME = "seabathy-debug.log"

    /** Must match the `<files-path>` entry in res/xml/file_paths.xml that shares it. */
    private const val LOG_DIR = "logs"

    /** Once a rotation is triggered, the file is reset rather than trimmed in place. */
    private const val MAX_BYTES = 4L * 1024 * 1024

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        val dir = File(context.filesDir, LOG_DIR)
        dir.mkdirs()
        val file = File(dir, FILE_NAME)
        // Builds before 1.0 kept the log in app-external storage: move it in.
        runCatching {
            val legacy = context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }
            if (legacy != null && legacy.exists()) {
                if (!file.exists()) legacy.copyTo(file)
                legacy.delete()
            }
        }
        logFile = file
    }

    /**
     * Strips URL query strings/fragments and `user:password@` credentials from
     * every line — where custom tile and basemap URLs carry API keys — so
     * nothing in the shareable log needs redacting at its call site, including
     * MapLibre's forwarded native messages and exception text.
     */
    internal fun redactUrls(text: String): String =
        if (!text.contains("://")) {
            text
        } else {
            text.replace(URL_CREDENTIALS, "$1").replace(URL_QUERY, "$1?…")
        }

    private val URL_CREDENTIALS = Regex("""(https?://)[^/\s@]+@""")
    private val URL_QUERY = Regex("""(https?://[^\s?#"')\]>]+)[?#][^\s"')\]>]*""")

    /** The log file, for sharing/viewing — null until [init] has run. */
    fun file(): File? = logFile

    @Synchronized
    fun log(tag: String, message: String) {
        val file = logFile ?: return
        // Most callers are on the main thread: without this, every log line would
        // itself be a StrictMode disk violation logged back into this file. Only this
        // append is exempt (allowThreadDiskWrites() covers the reads below too).
        val previousPolicy = StrictMode.allowThreadDiskWrites()
        try {
            runCatching {
                if (file.exists() && file.length() > MAX_BYTES) {
                    file.delete()
                    file.appendText("--- log rotated (exceeded ${MAX_BYTES / (1024 * 1024)}MB) ---\n")
                }
                file.appendText("${dateFormat.format(Date())} [$tag] ${redactUrls(message)}\n")
            }
        } finally {
            StrictMode.setThreadPolicy(previousPolicy)
        }
    }

    /** As [log], with the error's full stack trace (and causes), so the log shows where it happened. */
    fun logError(tag: String, message: String, error: Throwable) {
        log(tag, "$message\n${error.stackTraceToString()}")
    }
}
