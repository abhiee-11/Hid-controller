package com.hidgamepad

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.PI

/** The pad-glow effects: only the ones that make sense on a single border loop. */
enum class PadGlowEffect { STATIC, BREATHING, COLOR_CYCLE, RAINBOW, REACTIVE }

/**
 * The Mouse-mode touchpad's border glow. Independent of [RgbConfig]: own prefs keys
 * (`pad_glow_*`), own effect, colour and brightness.
 */
data class PadGlowConfig(
    val enabled: Boolean = false,
    val effect: PadGlowEffect = PadGlowEffect.STATIC,
    val speed: Float = 1f,
    val color: Int = 0,
    val brightness: Float = 1f,
)

/**
 * The border colour at [position] (0..1 around the loop) and [timeMillis], or null when the
 * glow is off. [touched] is true while a finger is down on the pad; only REACTIVE reads it.
 */
fun padGlowColor(
    config: PadGlowConfig,
    position: Float,
    touched: Boolean,
    timeMillis: Long,
): Color? {
    if (!config.enabled) return null
    val t = timeMillis / 1000f
    val sb = config.brightness.coerceIn(RGB_BRIGHTNESS_MIN, RGB_BRIGHTNESS_MAX)
    val preset = RGB_PRESETS[config.color.coerceIn(RGB_PRESETS.indices)]

    fun presetAt(intensity: Float) =
        Color.hsv(preset.hue, preset.sat, (preset.value * intensity * sb).coerceIn(0f, 1f))

    return when (config.effect) {
        PadGlowEffect.STATIC -> presetAt(1f)
        PadGlowEffect.BREATHING ->
            presetAt(0.1f + (1f - cos(PI.toFloat() * 2f * t * config.speed * 0.5f)) * 0.45f)
        PadGlowEffect.COLOR_CYCLE ->
            Color.hsv((t * config.speed * 60f).mod360(), 0.85f, sb)
        PadGlowEffect.RAINBOW ->
            Color.hsv(((position * 360f) + t * config.speed * 80f).mod360(), 0.85f, 0.95f * sb)
        PadGlowEffect.REACTIVE -> presetAt(if (touched) 1f else 0.14f)
    }
}
