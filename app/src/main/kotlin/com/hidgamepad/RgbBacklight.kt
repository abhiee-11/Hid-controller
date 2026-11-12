package com.hidgamepad

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.PI

/**
 * The RGB backlight: every key is its own light, like a per-key RGB gaming keyboard.
 *
 * This is a phone-local, purely visual effect - there is no HID usage for RGB and no
 * descriptor change here. The effects are named after the ones found in gaming laptops and
 * keyboards (ASUS ROG, Razer, Corsair iCUE, MSI, SteelSeries).
 */

/** The backlight effects, in the order the Settings section shows them. */
enum class RgbEffect {
    STATIC,
    BREATHING,
    COLOR_CYCLE,
    RAINBOW,
    WAVE,
    REACTIVE,
    RIPPLE,
    AURORA,
    FLICKER,
    STARLIGHT,
    COMET,
    FIRE,
    METEOR,
    SNAKE,
    SCANNER,
    ARROW,
}

/** The direction of travel for the animated effects. */
enum class RgbFlow { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

/**
 * The user's RGB backlight settings, persisted in shared preferences.
 *
 * [staticColor] is an index into [RGB_PRESETS]; the presets are HSV triples so the intensity
 * of any preset scales without extra colour math. [brightness] scales every effect, for the
 * Fn + Up / Fn + Down brightness shortcuts.
 */
data class RgbConfig(
    val enabled: Boolean = false,
    val effect: RgbEffect = RgbEffect.RAINBOW,
    val flow: RgbFlow = RgbFlow.LEFT_TO_RIGHT,
    val speed: Float = 1f,
    val staticColor: Int = 0,
    val brightness: Float = 1f,
)

/** A preset backlight colour, as HSV so it can be scaled by intensity cheaply. */
data class RgbPreset(val name: String, val hue: Float, val sat: Float, val value: Float)

/** The colour swatches the Settings section offers. Index = [RgbConfig.staticColor]. */
val RGB_PRESETS: List<RgbPreset> = listOf(
    RgbPreset("Violet", 265f, 0.52f, 1f),
    RgbPreset("Red", 0f, 0.75f, 0.95f),
    RgbPreset("Green", 140f, 0.7f, 0.9f),
    RgbPreset("Blue", 217f, 0.72f, 0.95f),
    RgbPreset("Cyan", 190f, 0.85f, 0.9f),
    RgbPreset("Amber", 38f, 0.85f, 0.95f),
    RgbPreset("White", 0f, 0f, 1f),
)

/** A momentary press, used by the reactive effects. */
data class Flash(val usage: Int, val x: Float, val y: Float, val atMillis: Long)

/** A key's light: colour and intensity. */
data class RgbZone(val color: Color, val intensity: Float)

/** How long a press keeps influencing the reactive effects, in ms. */
const val FLASH_LIFETIME_MS = 1800L

/**
 * The light for one key at [timeMillis].
 *
 * [x] and [y] are the normalised position of the key in the grid (0..1); [index] is a stable
 * per-key index used to seed the hash noise. [pressed] and [flashes] drive the reactive
 * effects.
 */
fun keyLight(
    config: RgbConfig,
    x: Float,
    y: Float,
    index: Int,
    pressed: Boolean,
    flashes: List<Flash>,
    timeMillis: Long,
): RgbZone {
    val t = timeMillis / 1000f
    val sb = config.brightness
    val preset = RGB_PRESETS[config.staticColor.coerceIn(RGB_PRESETS.indices)]
    val speed = config.speed

    fun presetAt(intensity: Float): RgbZone {
        val scaled = (preset.value * intensity * config.brightness).coerceIn(0f, 1f)
        return RgbZone(Color.hsv(preset.hue, preset.sat, scaled), intensity)
    }

    // The reactive effects keep a decaying energy field per key.
    val flashEnergy = flashes.filter { timeMillis - it.atMillis <= FLASH_LIFETIME_MS }
        .maxOfOrNull { f -> (1f - (timeMillis - f.atMillis) / FLASH_LIFETIME_MS.toFloat()) }

    fun flashNear(x: Float, y: Float, falloff: Float = 0.05f): Float {
        var energy = 0f
        for (f in flashes) {
            val age = timeMillis - f.atMillis
            if (age > FLASH_LIFETIME_MS) continue
            val dx = f.x - x
            val dy = f.y - y
            val d = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            energy = max(energy, max(0f, 1f - d / falloff) * (1f - age / FLASH_LIFETIME_MS.toFloat()))
        }
        return energy
    }

    return when (config.effect) {
        RgbEffect.STATIC -> presetAt(1f)

        RgbEffect.BREATHING -> {
            val intensity = 0.1f + (1f - cos(PI.toFloat() * 2f * t * speed * 0.5f)) * 0.45f
            presetAt(intensity)
        }

        RgbEffect.COLOR_CYCLE ->
            RgbZone(Color.hsv((t * speed * 60f).mod360(), 0.85f, sb), 1f)

        RgbEffect.RAINBOW -> {
            val direction = if (config.flow == RgbFlow.LEFT_TO_RIGHT) 1f else -1f
            RgbZone(Color.hsv(((x * 360f) + direction * t * speed * 80f).mod360(), 0.85f, 0.95f * sb), 1f)
        }

        RgbEffect.WAVE -> {
            val direction = if (config.flow == RgbFlow.LEFT_TO_RIGHT) 1f else -1f
            val band = 0.5f + 0.5f * sin((t * speed * 1.9f * direction - x * 1.6f - y * 0.9f) * PI.toFloat())
            presetAt(0.1f + 0.9f * band * band)
        }

        RgbEffect.REACTIVE ->
            presetAt(if (pressed || (flashEnergy ?: 0f) > 0.05f) max(1f, flashEnergy ?: 0f) else 0.14f)

        RgbEffect.RIPPLE -> presetAt(max(0.14f, flashNear(x, y, 0.18f) * 1.6f))

        RgbEffect.AURORA -> {
            val n = sin(x * 2.7f + t * speed * 0.6f + 1.7f * sin(y * 3.1f + t * speed * 0.45f + 0.9f))
            RgbZone(Color.hsv((165f + 80f * n).mod360(), 0.78f, (0.62f + 0.38f * abs(n)).coerceIn(0f, 1f) * sb), 1f)
        }

        RgbEffect.FLICKER -> {
            val intensity = 0.45f + 0.55f * hash2(index, floor(t * speed * 9f).toInt())
            presetAt(intensity)
        }

        RgbEffect.STARLIGHT -> {
            val star = hash2(index, floor(t * speed * 2.2f).toInt())
            presetAt(if (star > 0.86f) (star - 0.86f) / 0.14f else 0.1f)
        }

        RgbEffect.COMET -> {
            val direction = if (config.flow == RgbFlow.LEFT_TO_RIGHT) 1f else -1f
            val head = (frac(t * speed * 0.55f) * 1.6f - 0.3f) * direction
            val tail = if (direction > 0f) x - head else head - x
            val intensity = when {
                tail <= 0f -> 0.95f * sb
                tail <= 0.4f -> (1f - tail / 0.4f).pow(1.6f) * 0.95f * sb
                else -> 0.08f * sb
            }
            presetAt(intensity)
        }

        RgbEffect.FIRE -> {
            val noise = hash2((x * 97f).toInt(), (y * 61f).toInt() + (floor(t * speed * 11f).toInt() * 31))
            RgbZone(Color.hsv((16f + noise * 32f).mod360(), 0.9f, (0.45f + 0.55f * noise) * sb), 1f)
        }

        RgbEffect.METEOR -> {
            var intensity = 0.06f
            for (m0 in 0..2) {
                val phase = hash2(m0, 977)
                val drift = hash2(m0, 653)
                val head = frac(t * speed * 0.35f + phase)
                val mx = drift * 0.8f + 0.05f
                val pos = head * 1.25f - 0.12f
                val px = mx + 0.45f * pos
                val py = pos
                val d = kotlin.math.hypot((x - px).toDouble(), (y - py).toDouble()).toFloat()
                intensity = max(intensity, max(0f, 1f - d / 0.055f))
            }
            presetAt(intensity)
        }

        RgbEffect.SNAKE -> {
            val direction = if (config.flow == RgbFlow.LEFT_TO_RIGHT) 1f else -1f
            val head = frac(t * speed * 0.16f) * direction
            val dx = if (direction > 0f) head - x else x - head
            presetAt(max(0.1f, exp(-dx * 14f) * sb))
        }

        RgbEffect.SCANNER -> {
            val direction = if (config.flow == RgbFlow.LEFT_TO_RIGHT) 1f else -1f
            val pos = 0.5f + 0.5f * sin((t * speed * direction * 1.6f) * PI.toFloat())
            RgbZone(Color.hsv(190f, 0.85f, exp(-(x - pos) * (x - pos) / 0.006f) * sb), 1f)
        }

        RgbEffect.ARROW -> {
            val direction = if (config.flow == RgbFlow.LEFT_TO_RIGHT) 1f else -1f
            val head = frac(t * speed * 0.4f) * 0.55f
            val local = if (direction > 0f) x else 1f - x
            val back = local - head
            val intensity = when {
                back <= 0f -> 0.15f
                back <= 0.22f -> (1f - back / 0.22f).pow(1.7f)
                else -> 0.15f
            }
            presetAt(max(0.15f, intensity * sb))
        }
    }
}

/** The brightness the user can set: 0.1 .. 1.0, clamped after each Fn+Up/Down step. */
const val RGB_BRIGHTNESS_MIN = 0.1f
const val RGB_BRIGHTNESS_MAX = 1f

/** Read the stored brightness, tolerating an Int percentage written by newer builds. */
fun readStoredBrightness(prefs: android.content.SharedPreferences, default: Float, key: String = "rgb_brightness"): Float {
    val asFloat = runCatching { prefs.getFloat(key, default) }.getOrNull()
    if (asFloat != null) return asFloat.coerceIn(RGB_BRIGHTNESS_MIN, RGB_BRIGHTNESS_MAX)
    val asInt = runCatching { prefs.getInt(key, (default * 100f).toInt()) }.getOrNull()
    return ((asInt ?: (default * 100f).toInt()) / 100f).coerceIn(RGB_BRIGHTNESS_MIN, RGB_BRIGHTNESS_MAX)
}

fun adjustBrightness(current: Float, delta: Float): Float =
    (current + delta).coerceIn(RGB_BRIGHTNESS_MIN, RGB_BRIGHTNESS_MAX)

/** Deterministic [0, 1) hash of two ints, used to seed the noise-based effects. */
internal fun hash2(a: Int, b: Int): Float {
    var h = a * 374761393 + b * 668265263 + 0x2545F491
    h = (h xor (h ushr 16)) * -0x7A143595 // 0x85EBCA6B
    h = h xor (h ushr 13)
    h = h * -0x3D8D0F09 // 0xC2B2AE35
    h = h xor (h ushr 16)
    return (h and Int.MAX_VALUE).toFloat() / Int.MAX_VALUE.toFloat()
}

internal fun frac(value: Float): Float = value - floor(value)

internal fun Float.mod360(): Float = ((this % 360f) + 360f) % 360f
