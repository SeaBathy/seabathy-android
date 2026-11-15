package au.opendepth.app.backup

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import au.opendepth.app.BuildConfig
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.ui.PanelDialogs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Backup and restore: save everything to a file, restore it, and
 * a daily backup to a folder the user picks (any the phone can save to,
 * including cloud drives such as Google Drive or OneDrive, through Android's
 * own file picker; the app never sees an account or password).
 */
class BackupFeature(
    private val activity: ComponentActivity,
    private val panels: PanelDialogs,
    private val preferences: SharedPreferences,
    private val toast: (String) -> Unit
) {
    private var dialog: Dialog? = null

    private val saveFile = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) backUpTo(uri)
    }

    private val openFile = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreFrom(uri)
    }

    private val pickFolder = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onFailure {
            toast("That folder can't be saved to: pick another")
            return@registerForActivityResult
        }
        preferences.edit().putString(AppBackup.KEY_FOLDER, uri.toString()).putLong(AppBackup.KEY_LAST, 0L).apply()
        Thread { autoBackup(activity.applicationContext, force = true) }.start()
        toast("Daily backup on: the first one is being saved now")
        show()
    }

    fun show() {
        dialog?.dismiss()
        val body = panels.body()
        body.addView(panels.caption(
            "A backup holds your waypoints and their photos, routes, tracks and settings. Map files and downloads " +
                "(surveys, chart packs, tides, sea conditions) aren't included: load or download them again.",
            topPaddingDp = 0
        ))
        panels.addRow(body, panels.row("Back up now", "Save a backup file where you choose") {
            saveFile.launch("SeaBathy-backup-${day(System.currentTimeMillis())}.zip")
        })
        panels.addRow(body, panels.row("Restore a backup", "From a SeaBathy backup file (phone or Desktop)") {
            openFile.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        })
        val folder = preferences.getString(AppBackup.KEY_FOLDER, null)
        val last = preferences.getLong(AppBackup.KEY_LAST, 0L)
        panels.addRow(body, panels.row(
            "Daily backup",
            if (folder == null) "Off: pick a folder (Google Drive, OneDrive, SD card…) to turn it on"
            else "On, to ${folderName(folder)}" + if (last > 0) " · last ${day(last)}" else ""
        ) { pickFolder.launch(null) })
        if (folder != null) {
            panels.addRow(body, panels.row("Turn daily backup off", "Backups already saved stay in the folder") {
                runCatching { activity.contentResolver.releasePersistableUriPermission(Uri.parse(folder), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                preferences.edit().remove(AppBackup.KEY_FOLDER).remove(AppBackup.KEY_LAST).apply()
                show()
            })
        }
        body.addView(panels.caption("The daily backup keeps the newest $KEEP in the folder, saved when you leave the app.", topPaddingDp = 4))
        dialog = panels.showPanel("Backup and restore", body)
    }

    /** Called when the app goes to the background: a daily backup, if one is due. */
    fun onStop() {
        if (preferences.getString(AppBackup.KEY_FOLDER, null) == null) return
        if (System.currentTimeMillis() - preferences.getLong(AppBackup.KEY_LAST, 0L) < DAY_MS) return
        val context = activity.applicationContext
        Thread { autoBackup(context, force = false) }.start()
    }

    private fun backUpTo(uri: Uri) {
        Thread {
            val ok = runCatching {
                activity.contentResolver.openOutputStream(uri)?.use { AppBackup.write(preferences, activity.filesDir, it, BuildConfig.VERSION_NAME) }
                    ?: error("no stream")
            }.onFailure { DiagnosticLog.logError(TAG, "backup FAILED", it) }.isSuccess
            activity.runOnUiThread { toast(if (ok) "Backup saved" else "Couldn't save the backup") }
        }.start()
    }

    private fun restoreFrom(uri: Uri) {
        val summary = runCatching {
            activity.contentResolver.openInputStream(uri)?.use { AppBackup.stage(it, activity.filesDir) }
        }.onFailure { DiagnosticLog.logError(TAG, "reading backup FAILED", it) }.getOrNull()
        if (summary == null) {
            toast("That isn't a SeaBathy backup")
            return
        }
        fun count(n: Int, what: String) = "$n $what${if (n == 1) "" else "s"}"
        val what = count(summary.waypoints, "waypoint") + ", " + count(summary.routes, "route") +
            (if (summary.app == "android") ", " + count(summary.tracks, "track") + ", " + count(summary.photos, "photo") else "")
        val message = if (summary.app == "desktop") {
            "A SeaBathy Desktop backup from ${day(summary.createdMillis)}: $what. They are added to the ones on this phone. The app restarts."
        } else {
            "From ${day(summary.createdMillis)}: $what, and settings. This replaces the waypoints, routes, tracks and settings on this phone. The app restarts."
        }
        panels.confirm("Restore this backup?", message, confirmLabel = "Restore", destructive = summary.app != "desktop", onConfirm = {
            dialog?.dismiss()
            restart()
        })
    }

    /** Restarts the app; [AppBackup.applyPending] runs first thing at the next start. */
    private fun restart() {
        val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
        activity.startActivity(Intent.makeRestartActivityTask(intent.component))
        Runtime.getRuntime().exit(0)
    }

    private fun folderName(uri: String): String =
        runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(uri)).substringAfterLast(':').ifEmpty { "the chosen folder" } }
            .getOrDefault("the chosen folder")

    companion object {
        private const val TAG = "Backup"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val KEEP = 5
        private const val PREFIX = "SeaBathy-backup-"

        private fun day(millis: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))

        /** Saves a backup into the chosen folder and keeps only the newest [KEEP]. Off the main thread. */
        fun autoBackup(context: Context, force: Boolean) {
            val prefs = context.getSharedPreferences(au.opendepth.app.MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
            val folder = prefs.getString(AppBackup.KEY_FOLDER, null) ?: return
            val now = System.currentTimeMillis()
            if (!force && now - prefs.getLong(AppBackup.KEY_LAST, 0L) < DAY_MS) return
            val resolver = context.contentResolver
            runCatching {
                val tree = Uri.parse(folder)
                val treeId = DocumentsContract.getTreeDocumentId(tree)
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
                val name = "$PREFIX${day(now)}.zip"
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
                val existing = mutableListOf<Pair<String, String>>()
                resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val n = c.getString(1) ?: continue
                        if (n.startsWith(PREFIX) && n.endsWith(".zip")) existing += c.getString(0) to n
                    }
                }
                // Today's replaces any made earlier today.
                existing.filter { it.second == name }.forEach { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it.first)) }
                val doc = DocumentsContract.createDocument(resolver, parent, "application/zip", name) ?: error("couldn't create $name")
                resolver.openOutputStream(doc)?.use { AppBackup.write(prefs, context.filesDir, it, BuildConfig.VERSION_NAME, now) } ?: error("no stream")
                prefs.edit().putLong(AppBackup.KEY_LAST, now).apply()
                existing.filter { it.second != name }.sortedByDescending { it.second }.drop(KEEP - 1).forEach {
                    DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it.first))
                }
                DiagnosticLog.log(TAG, "daily backup saved")
            }.onFailure { DiagnosticLog.logError(TAG, "daily backup FAILED", it) }
        }
    }
}
