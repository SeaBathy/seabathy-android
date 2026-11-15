package au.opendepth.app

import android.app.Application
import android.content.Context
import au.opendepth.app.backup.AppBackup

/** Restores a backup chosen before the restart, before anything reads the settings. */
class SeaBathyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            AppBackup.applyPending(getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE), filesDir)
        }
    }
}
