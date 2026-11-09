package au.opendepth.app.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** The navigation alarms, each with its own sound and repeat interval. */
enum class NavAlarm(val beeps: Int, val repeatMs: Long?) {
    ARRIVAL(beeps = 2, repeatMs = null),
    FINISHED(beeps = 3, repeatMs = null),
    OFF_COURSE(beeps = 3, repeatMs = 20_000L),
    SHALLOW(beeps = 4, repeatMs = 8_000L),
    ANCHOR(beeps = 5, repeatMs = 6_000L)
}

/**
 * Sounds and vibrates for navigation alarms. Repeating alarms sound again
 * while they stay active, until silenced; silencing lasts until the alarm
 * clears and comes back.
 */
class NavAlarms(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private val tones: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, TONE_VOLUME) }.getOrNull()
    private val lastSounded = mutableMapOf<NavAlarm, Long>()

    var soundOn = true
    var vibrateOn = true

    /** Muted from the navigation display: alarms still show, but make no sound or vibration. */
    var muted = false

    /** Sounds [alarm] now (one-off alarms) or when due (repeating ones). */
    fun trigger(alarm: NavAlarm) {
        val now = SystemClock.elapsedRealtime()
        val last = lastSounded[alarm]
        val due = last == null || (alarm.repeatMs != null && now - last >= alarm.repeatMs)
        if (!due) return
        lastSounded[alarm] = now
        play(alarm.beeps)
    }

    /** The alarm's condition has gone: it may sound again next time. */
    fun clear(alarm: NavAlarm) {
        lastSounded.remove(alarm)
    }

    fun release() {
        runCatching { tones?.release() }
    }

    private fun play(beeps: Int) {
        if (muted) return
        if (soundOn) {
            runCatching { tones?.startTone(ToneGenerator.TONE_PROP_BEEP2, beeps * BEEP_MS) }
        }
        if (vibrateOn) {
            val pattern = LongArray(beeps * 2) { if (it % 2 == 0) BUZZ_GAP_MS else BUZZ_MS }
            val effect = VibrationEffect.createWaveform(pattern, -1)
            // Tagged as an alarm, so it still buzzes with the screen off or the app in the background.
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    vibrator?.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                }
            }
        }
    }

    private companion object {
        const val TONE_VOLUME = 100
        const val BEEP_MS = 220
        const val BUZZ_MS = 250L
        const val BUZZ_GAP_MS = 120L
    }
}
