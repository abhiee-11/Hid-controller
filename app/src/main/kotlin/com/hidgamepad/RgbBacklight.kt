package com.hidgamepad

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.sin

private val DeckColor = Color(0xFF06070A)
private val KeyFill = Color(0xFF14161C)
private val KeyFillPressed = Color(0xFF1B1E26)
private val KeyBorder = Color(0xFF04050A)
private val LegendOff = Color(0xFF7A8196)
private val LedOn = Color(0xFF4CC38A)
private val LedOff = Color(0xFF3A4156)
private val ZONE_HUES = floatArrayOf(265f, 170f, 40f, 330f)
private val STATIC_HUES = floatArrayOf(265f, 170f, 40f, 330f, 210f)

enum class LightMode(val label: String) {
    Rainbow("Rainbow"), Breathing("Breathing"), Static("Static"), Reactive("Reactive")
}

enum class ZoneMode(val label: String) { PerKey("Per key"), FourZones("4 zones") }

@Stable
class KeyboardBacklight(
    enabled: Boolean = true,
    mode: LightMode = LightMode.Rainbow,
    brightness: Int = 80,
    staticIndex: Int = 0,
    zones: ZoneMode = ZoneMode.PerKey,
    /** Persist callback: (enabled, mode, brightness, staticIndex, zones) -> Unit. */
    private val onPersist: (Boolean, LightMode, Int, Int, ZoneMode) -> Unit = { _, _, _, _, _ -> },
) {
    var enabled by mutableStateOf(enabled)
    var mode by mutableStateOf(mode)
    var brightness by mutableStateOf(brightness.coerceIn(10, 100))
    var staticIndex by mutableStateOf(staticIndex)
    var zones by mutableStateOf(zones)
    var toast by mutableStateOf<String?>(null)

    fun toggle() {
        enabled = !enabled
        toast = if (enabled) "Lighting on" else "Lighting off"
        persist()
    }

    fun change(delta: Int) {
        enabled = true
        brightness = (brightness + delta).coerceIn(10, 100)
        toast = "Brightness $brightness%"
        persist()
    }

    fun setBrightnessPct(pct: Int) {
        enabled = true
        brightness = pct.coerceIn(10, 100)
        persist()
    }

    fun selectMode(m: LightMode) {
        mode = m
        enabled = true
        persist()
    }

    fun selectZones(z: ZoneMode) {
        zones = z
        persist()
    }

    fun selectStatic(i: Int) {
        staticIndex = i
        mode = LightMode.Static
        enabled = true
        persist()
    }

    fun nextMode() {
        val all = LightMode.entries
        mode = all[(mode.ordinal + 1) % all.size]
        enabled = true
        toast = mode.label
        persist()
    }

    fun prevMode() {
        val all = LightMode.entries
        mode = all[(mode.ordinal - 1 + all.size) % all.size]
        enabled = true
        toast = mode.label
        persist()
    }

    private fun persist() = onPersist(enabled, mode, brightness, staticIndex, zones)
}

/** Case-insensitive read for legacy stored names. */
private fun String?.toLightMode(): LightMode =
    LightMode.entries.firstOrNull { it.name.equals(this, ignoreCase = true) } ?: LightMode.Rainbow

private fun String?.toZoneMode(): ZoneMode =
    ZoneMode.entries.firstOrNull { it.name.equals(this, ignoreCase = true) } ?: ZoneMode.PerKey

/**
 * The brightness key was stored as a Float by an older build, so read it defensively: a stored
 * Float or Int both become the 10..100 step the UI uses.
 */
private fun readStoredBrightness(prefs: android.content.SharedPreferences, default: Int): Int {
    val raw = runCatching { prefs.getFloat("rgb_brightness", default.toFloat()) }.getOrNull()
    if (raw != null) return raw.toInt().coerceIn(10, 100)
    val asInt = runCatching { prefs.getInt("rgb_brightness", default) }.getOrNull()
    return (asInt ?: default).coerceIn(10, 100)
}

/**
 * Read the persisted backlight state from [prefs] (keys rgb_on, rgb_mode, rgb_brightness,
 * rgb_color, rgb_zones).
 */
fun loadBacklight(prefs: android.content.SharedPreferences): KeyboardBacklight =
    KeyboardBacklight(
        enabled = prefs.getBoolean("rgb_on", true),
        mode = prefs.getString("rgb_mode", null).toLightMode(),
        brightness = readStoredBrightness(prefs, 80),
        staticIndex = prefs.getInt("rgb_color", 0),
        zones = prefs.getString("rgb_zones", null).toZoneMode(),
        onPersist = { e, m, b, s, z ->
            prefs.edit()
                .putBoolean("rgb_on", e)
                .putString("rgb_mode", m.name)
                .putInt("rgb_brightness", b)
                .putInt("rgb_color", s)
                .putString("rgb_zones", z.name)
                .apply()
        },
    )

internal class KeyLight(val hue: Float, val intensity: Float)

internal fun KeyboardBacklight.lightFor(
    x: Float,
    tMs: Long,
    pressed: Boolean,
    sinceReleaseMs: Long,
): KeyLight? {
    if (!enabled) return null
    val b = brightness / 100f
    val zone = (x * 4f).toInt().coerceIn(0, 3)
    val xr = if (zones == ZoneMode.FourZones) (zone + 0.5f) / 4f else x
    val hue: Float
    val a: Float
    when (mode) {
        LightMode.Rainbow -> {
            hue = ((tMs % 7200L) / 20f + xr * 300f) % 360f
            a = b
        }
        LightMode.Breathing -> {
            hue = ZONE_HUES[zone]
            a = b * (0.3f + 0.7f * (0.5f + 0.5f * sin((tMs % 4398L) / 700f)))
        }
        LightMode.Static -> {
            hue = STATIC_HUES[staticIndex.coerceIn(0, STATIC_HUES.size - 1)]
            a = b
        }
        LightMode.Reactive -> {
            hue = ZONE_HUES[zone]
            a = if (pressed) b else b * max(0f, 1f - sinceReleaseMs / 400f)
        }
    }
    return if (a < 0.04f) null else KeyLight(hue, a.coerceIn(0f, 1f))
}

@Composable
fun rememberBacklightClock(active: Boolean): State<Long> {
    val ms = remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        if (active) {
            while (true) withFrameMillis { ms.longValue = it }
        }
    }
    return ms
}

fun rowCenters(weights: List<Float>): List<Float> {
    val total = weights.sum()
    var cum = 0f
    return weights.map { w ->
        val c = (cum + w / 2f) / total
        cum += w
        c
    }
}

@Composable
fun BacklitDeck(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .background(DeckColor, RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        content = content,
    )
}

@Composable
fun BacklitKey(
    label: String,
    xFrac: Float,
    backlight: KeyboardBacklight,
    clock: State<Long>,
    modifier: Modifier = Modifier,
    legendSp: Float = 14f,
    ledOn: Boolean? = null,
    hapticsEnabled: Boolean = true,
    hapticsStrength: Float = 1f,
    onDown: () -> Unit = {},
    onUp: () -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val layout = remember(label, legendSp) {
        measurer.measure(label, TextStyle(fontSize = legendSp.sp, fontWeight = FontWeight.Medium))
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    var pressed by remember { mutableStateOf(false) }
    var releasedAt by remember { mutableLongStateOf(-1_000_000L) }
    var wasPressed by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (pressed) {
            wasPressed = true
        } else if (wasPressed) {
            releasedAt = clock.value
            wasPressed = false
        }
    }
    val sc by animateFloatAsState(if (pressed) 0.97f else 1f, label = "keyScale")

    Box(
        modifier = modifier
            .scale(sc)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!pressed) {
                        pressed = true
                        if (hapticsEnabled) HapticBuzz.buzz(context, hapticsStrength)
                        onDown()
                    }
                    waitForUpOrCancellation()
                    if (pressed) {
                        pressed = false
                        onUp()
                    }
                }
            }
            .drawBehind {
                val r = 8.dp.toPx()
                val t = clock.value
                val l = backlight.lightFor(xFrac, t, pressed, t - releasedAt)

                if (l != null) {
                    // Glow behind the keycap: three stacked translucent rounded rects of
                    // increasing size. The BlurMaskFilter path needs the Android canvas,
                    // which this Compose version does not expose publicly, so this is the
                    // same halo drawn as nested rounded rects.
                    val s = (1f + l.intensity) * 1.dp.toPx()
                    val glow = Color.hsl(l.hue, 0.95f, 0.55f, (l.intensity * 0.18f).coerceIn(0f, 1f))
                    for (i in 3 downTo 1) {
                        val pad = s * i
                        drawRoundRect(
                            glow.copy(alpha = glow.alpha * i),
                            topLeft = Offset(-pad, -pad),
                            size = Size(size.width + pad * 2f, size.height + pad * 2f),
                            cornerRadius = CornerRadius(r + pad),
                        )
                    }
                }

                drawRoundRect(if (pressed) KeyFillPressed else KeyFill, cornerRadius = CornerRadius(r))
                drawRoundRect(KeyBorder, cornerRadius = CornerRadius(r), style = Stroke(1.dp.toPx()))
                drawLine(
                    Color.White.copy(alpha = 0.05f),
                    Offset(r, 1.dp.toPx()),
                    Offset(size.width - r, 1.dp.toPx()),
                    1.dp.toPx(),
                )

                val legend = if (l != null) Color.hsl(l.hue, 0.95f, 0.72f, max(0.5f, l.intensity)) else LegendOff
                val shadow = if (l != null) {
                    Shadow(Color.hsl(l.hue, 0.95f, 0.65f, l.intensity), Offset.Zero, 6.dp.toPx())
                } else null
                drawText(
                    textLayoutResult = layout,
                    color = legend,
                    topLeft = Offset(
                        (size.width - layout.size.width) / 2f,
                        (size.height - layout.size.height) / 2f,
                    ),
                    shadow = shadow,
                )

                if (ledOn != null) {
                    val c = Offset(size.width - 8.dp.toPx(), 8.dp.toPx())
                    if (ledOn) drawCircle(LedOn.copy(alpha = 0.35f), 6.dp.toPx(), c)
                    drawCircle(if (ledOn) LedOn else LedOff, 3.5.dp.toPx(), c)
                }
            },
    )
}

private const val USAGE_L = 0x0F
private const val USAGE_LEFT = 0x50
private const val USAGE_RIGHT = 0x4F
private const val USAGE_UP = 0x52
private const val USAGE_DOWN = 0x51
private val SHORTCUT_KEYS = setOf(USAGE_L, USAGE_UP, USAGE_DOWN, USAGE_LEFT, USAGE_RIGHT)

class FnShortcuts(
    private val backlight: KeyboardBacklight,
    private val scope: CoroutineScope,
) {
    var fnHeld by mutableStateOf(false)
        private set

    private val swallowed = mutableSetOf<Int>()
    private var repeatJob: Job? = null

    fun onFn(pressed: Boolean) {
        fnHeld = pressed
        if (!pressed) stopRepeat()
    }

    fun onKey(usage: Int, pressed: Boolean, send: (Int, Boolean) -> Unit) {
        if (pressed) {
            if (fnHeld && usage in SHORTCUT_KEYS) {
                swallowed.add(usage)
                when (usage) {
                    USAGE_L -> backlight.toggle()
                    USAGE_UP -> startRepeat(+10)
                    USAGE_DOWN -> startRepeat(-10)
                    USAGE_LEFT -> backlight.prevMode()
                    USAGE_RIGHT -> backlight.nextMode()
                }
            } else {
                send(usage, true)
            }
        } else if (swallowed.remove(usage)) {
            stopRepeat()
        } else {
            send(usage, false)
        }
    }

    private fun startRepeat(delta: Int) {
        stopRepeat()
        repeatJob = scope.launch {
            backlight.change(delta)
            while (true) {
                delay(150)
                backlight.change(delta)
            }
        }
    }

    private fun stopRepeat() {
        repeatJob?.cancel()
        repeatJob = null
    }
}

@Composable
fun BacklightToast(backlight: KeyboardBacklight, modifier: Modifier = Modifier) {
    val msg = backlight.toast
    LaunchedEffect(msg) {
        if (msg != null) {
            delay(1000)
            if (backlight.toast == msg) backlight.toast = null
        }
    }
    if (msg != null) {
        Text(
            text = msg,
            color = Color(0xFFE6E8EE),
            fontSize = 13.sp,
            modifier = modifier
                .background(Color(0xFF232733), CircleShape)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun BacklightSettingsSection(backlight: KeyboardBacklight, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Keyboard lighting", color = Color(0xFFE6E8EE), fontSize = 14.sp)
            Switch(checked = backlight.enabled, onCheckedChange = { backlight.toggle() })
        }
        Text("Fn+L toggles · Fn+Up/Down brightness (hold to repeat) · Fn+Left/Right effect", fontSize = 11.sp, color = Color(0xFF8A91A3))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LightMode.entries.forEach { m ->
                FilterChip(
                    selected = backlight.mode == m,
                    onClick = { backlight.selectMode(m) },
                    label = { Text(m.label) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZoneMode.entries.forEach { z ->
                FilterChip(
                    selected = backlight.zones == z,
                    onClick = { backlight.selectZones(z) },
                    label = { Text(z.label) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Colour", color = Color(0xFF8A91A3), fontSize = 13.sp)
            STATIC_HUES.forEachIndexed { i, h ->
                Box(
                    Modifier
                        .size(26.dp)
                        .background(Color.hsl(h, 0.85f, 0.62f), CircleShape)
                        .border(2.dp, if (backlight.staticIndex == i) Color.White else Color.Transparent, CircleShape)
                        .clickable {
                            backlight.selectStatic(i)
                        },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Brightness ${backlight.brightness}%", color = Color(0xFF8A91A3), fontSize = 13.sp)
            Slider(
                value = backlight.brightness.toFloat(),
                onValueChange = { backlight.setBrightnessPct((it / 10f).toInt() * 10) },
                valueRange = 10f..100f,
                steps = 8,
                modifier = Modifier.size(width = 180.dp, height = 32.dp),
            )
        }
    }
}
