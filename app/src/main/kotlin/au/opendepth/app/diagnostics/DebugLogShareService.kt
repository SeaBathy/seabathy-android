package au.opendepth.app.diagnostics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider

/** Hands the diagnostic log file to the phone's normal share sheet. */
class DebugLogShareService(private val context: Context) {

    /** @return the share intent, or null when no log has been written yet. */
    fun buildShareIntent(): Intent? {
        val file = DiagnosticLog.file()?.takeIf { it.exists() && it.length() > 0L } ?: return null

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = LOG_MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share debug log"
        )
    }

    private companion object {
        const val LOG_MIME_TYPE = "text/plain"
    }
}
