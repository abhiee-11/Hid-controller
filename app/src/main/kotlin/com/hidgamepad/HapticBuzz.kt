package com.hidgamepad

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.roundToInt

/**
 * Button-press buzz backed directly by the system vibrator so the intensity can
 * be scaled. `performHapticFeedback` only offers fixed presets, but the raw
 * vibrator takes an amplitude per effect:
 *  - strength 0.25 (less)  -> quiet, short buzz (amplitude ~64)
 *  - strength 1.0 (normal) -> strong, standard buzz
 *  - strength 1.5 (more)   -> max amplitude (255, saturated) with a longer pulse
 */
object HapticBuzz {

    /** Vibrate once with the given strength multiplier; no-op without a usable vibrator. */
    fun buzz(context: Context, strength: Float) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        val clamped = strength.coerceIn(0.25f, 1.5f)
        val amplitude = (255f * clamped).roundToInt().coerceIn(1, 255)
        val durationMs = (30f * clamped).coerceIn(10f, 60f).toInt()
        try {
            val effectAmplitude = if (vibrator.hasAmplitudeControl()) amplitude else VibrationEffect.DEFAULT_AMPLITUDE
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs.toLong(), effectAmplitude))
        } catch (_: SecurityException) {
            // Missing permission or platform restriction: haptics are optional feedback and
            // must never crash the controller.
        } catch (_: IllegalArgumentException) {
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(context: Context): Vibrator? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java) ?: return null
            return manager.defaultVibrator
        }
        return context.getSystemService(Vibrator::class.java)
    }
}
