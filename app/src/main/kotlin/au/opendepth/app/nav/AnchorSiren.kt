package au.opendepth.app.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import au.opendepth.app.diagnostics.DiagnosticLog

/**
 * The anchor alarm's sound: the phone's alarm tone, looping on the alarm
 * volume (so it rings with the ringer on silent), and a buzz that repeats,
 * both until [stop]. A beep every few seconds is easy to sleep through.
 */
class AnchorSiren(context: Context) {

    private val context = context.applicationContext
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        this.context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        this.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private var player: MediaPlayer? = null
    private var vibrating = false

    /** True while the tone is playing; false when there is no tone to play (the caller beeps instead). */
    val isRinging: Boolean get() = player != null

    fun start(sound: Boolean, vibrate: Boolean) {
        if (sound && player == null) player = ring()
        if (!sound) stopTone()
        if (vibrate && !vibrating) buzz()
        if (!vibrate) stopBuzz()
    }

    fun stop() {
        stopTone()
        stopBuzz()
    }

    private fun ring(): MediaPlayer? {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // The alarm tone, or failing that the ringtone or notification sound.
        val tones = listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
            .mapNotNull { RingtoneManager.getDefaultUri(it) }
        for (uri in tones) {
            val player = MediaPlayer()
            val started = runCatching {
                player.setAudioAttributes(attributes)
                player.setDataSource(context, uri)
                player.isLooping = true
                player.prepare()
                player.start()
            }.isSuccess
            if (started) return player
            runCatching { player.release() }
            DiagnosticLog.log(TAG, "couldn't play ${describe(uri)}")
        }
        return null
    }

    private fun describe(uri: Uri) = uri.lastPathSegment ?: uri.toString()

    private fun buzz() {
        // Long buzzes with short gaps, repeating from the start until cancelled.
        val effect = VibrationEffect.createWaveform(longArrayOf(0, BUZZ_MS, GAP_MS), 0)
        vibrating = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator?.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        }.isSuccess && vibrator != null
    }

    private fun stopTone() {
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        player = null
    }

    private fun stopBuzz() {
        if (vibrating) runCatching { vibrator?.cancel() }
        vibrating = false
    }

    private companion object {
        const val TAG = "AnchorSiren"
        const val BUZZ_MS = 800L
        const val GAP_MS = 400L
    }
}
