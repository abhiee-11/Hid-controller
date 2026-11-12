package com.hidgamepad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

// The palette for the on-screen controls lives in PadColors.

// Shared drawing metrics, so the sticks, the D-pad and the shoulders stay in step.
private val KNOB_RADIUS = 22.dp
private val HAIRLINE = 1.5f.dp
private val DPAD_ARM_WIDTH = 26.dp
private val DPAD_CORNER = 8.dp
private val PAD_CORNER = 14.dp

/** One analog stick drawn on a Canvas. Handles one pointer at a time; other pointers can
 *  rest on buttons elsewhere because this widget only claims its own hit area.
 *  Emits HID-style values: X right = +1, Y down = +1, dead-zoned, centered on release. */
@Composable
fun JoystickControl(
    dpSize: Dp,
    modifier: Modifier = Modifier,
    onMoved: (x: Float, y: Float) -> Unit,
) {
    val onMovedNow by rememberUpdatedState(onMoved)
    var activeId by remember { mutableStateOf(-1L) }
    var knob by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.size(dpSize), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // size here is the widget's size in pixels (PointerInputScope.size).
                    val radius = size.width / 2f
                    val travel = radius * 0.7f
                    // Outer finally covers the composable leaving composition; the inner
                    // finally covers ACTION_UP / ACTION_CANCEL at the end of every gesture.
                    try {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            activeId = down.id.value
                            knob = clampToTravel(down.position - Offset(radius, radius), travel)
                            emitStick(onMovedNow, knob, travel)
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    var tracked = event.changes.firstOrNull { it.id.value == activeId }
                                    if (tracked == null || !tracked.pressed) {
                                        // Tracked finger lifted: adopt a still-pressed one (two-finger
                                        // handover) so the stick stays live until ALL fingers lift.
                                        val adopt = event.changes.firstOrNull { it.id.value != activeId && it.pressed }
                                            ?: break
                                        activeId = adopt.id.value
                                        tracked = adopt
                                    }
                                    knob = clampToTravel(tracked.position - Offset(radius, radius), travel)
                                    emitStick(onMovedNow, knob, travel)
                                }
                            } finally {
                                activeId = -1
                                knob = Offset.Zero
                                onMovedNow(0f, 0f)
                            }
                        }
                    } catch (_: Exception) {
                        // Gesture handler cancelled (composition left mid-drag); the finally
                        // above already reported the neutral position.
                    }
                },
        ) {
            val radius = size.width / 2f
            val center = Offset(radius, radius)
            val knobRadius = minOf(KNOB_RADIUS.toPx(), radius * 0.62f)
            // Base: a filled disc with a hairline rim.
            drawCircle(PadColors.surface, radius = radius)
            drawCircle(PadColors.border, radius = radius, style = Stroke(width = HAIRLINE.toPx()))
            // Dashed inner ring marks the travel limit.
            drawCircle(
                PadColors.border,
                radius = radius * 0.66f,
                style = Stroke(
                    width = HAIRLINE.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(6f.dp.toPx(), 6f.dp.toPx()),
                    ),
                ),
            )
            // Knob.
            drawCircle(PadColors.stickKnob, radius = knobRadius, center = center + knob)
            drawCircle(
                PadColors.stickKnobBorder,
                radius = knobRadius,
                center = center + knob,
                style = Stroke(width = 2f.dp.toPx()),
            )
        }
    }
}

private fun clampToTravel(delta: Offset, travel: Float): Offset {
    val mag = hypot(delta.x, delta.y)
    return if (mag > travel) delta * (travel / mag) else delta
}

private fun emitStick(onMoved: (Float, Float) -> Unit, knob: Offset, travel: Float) {
    val x = knob.x / travel
    val y = knob.y / travel // Android/HID gamepad convention: screen down = positive Y
    onMoved(applyDeadzone(x), applyDeadzone(y))
}

/** D-pad: eight hat positions from touch position (cardinals + diagonals), multi-finger safe. */
@Composable
fun DPadControl(
    dpSize: Dp,
    onHat: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val onHatNow by rememberUpdatedState(onHat)
    val zones = remember { mutableStateMapOf<Long, Int>() }
    Box(modifier.size(dpSize), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val half = size.width / 2f
                    // The finally runs whenever the gesture ends (UP/CANCEL) or the whole
                    // handler is torn down, so the D-pad can never stay pressed in the report.
                    try {
                        awaitEachGesture {
                            while (true) {
                                val event = awaitPointerEvent()
                                var changed = false
                                for (c in event.changes) {
                                    if (c.pressed) {
                                        val zone = hatZone(c.position - Offset(half, half), half)
                                        if (zone != null) {
                                            if (zones[c.id.value] != zone) {
                                                zones[c.id.value] = zone
                                                changed = true
                                            }
                                        } else if (zones.remove(c.id.value) != null) {
                                            // Finger slid back into the center dead area: release its zone.
                                            changed = true
                                        }
                                    } else if (zones.remove(c.id.value) != null) {
                                        changed = true
                                    }
                                }
                                if (changed) onHatNow(combineHats(zones.values))
                            }
                        }
                    } finally {
                        zones.clear()
                        onHatNow(GamepadState.HAT_NEUTRAL)
                    }
                },
        ) {
            val half = size.width / 2f
            val arm = half
            val thick = minOf(DPAD_ARM_WIDTH.toPx(), size.width * 0.42f)
            val corner = CornerRadius(DPAD_CORNER.toPx(), DPAD_CORNER.toPx())
            drawRoundRect(
                PadColors.surface,
                topLeft = Offset(half - thick / 2, half - arm),
                size = Size(thick, arm * 2),
                cornerRadius = corner,
            )
            drawRoundRect(
                PadColors.surface,
                topLeft = Offset(half - arm, half - thick / 2),
                size = Size(arm * 2, thick),
                cornerRadius = corner,
            )
            val held = heldCardinals(zones.values)
            for (zone in held) drawHeldArm(zone, half, arm, thick)
            drawTriangles(half, thick, held)
        }
    }
}

private fun DrawScope.drawHeldArm(zone: Int, half: Float, arm: Float, thick: Float) {
    val corner = CornerRadius(DPAD_CORNER.toPx(), DPAD_CORNER.toPx())
    when (zone) {
        0 -> drawRoundRect(PadColors.accent, Offset(half - thick / 2, half - arm), Size(thick, arm), corner)
        2 -> drawRoundRect(PadColors.accent, Offset(half, half - thick / 2), Size(arm, thick), corner)
        4 -> drawRoundRect(PadColors.accent, Offset(half - thick / 2, half), Size(thick, arm), corner)
        6 -> drawRoundRect(PadColors.accent, Offset(half - arm, half - thick / 2), Size(arm, thick), corner)
    }
}

/**
 * The cardinal directions a set of hat zones lights up. A diagonal lights both of its
 * cardinals, so the highlight always matches the direction the finger is in.
 */
private fun heldCardinals(zones: Collection<Int>): Set<Int> {
    val out = mutableSetOf<Int>()
    for (z in zones) {
        when (z) {
            0 -> out += 0
            2 -> out += 2
            4 -> out += 4
            6 -> out += 6
            1 -> { out += 0; out += 2 }
            3 -> { out += 2; out += 4 }
            5 -> { out += 4; out += 6 }
            7 -> { out += 6; out += 0 }
        }
    }
    return out
}

/** Small direction triangles at the tip of each arm, brightened when that direction is held. */
private fun DrawScope.drawTriangles(half: Float, thick: Float, held: Set<Int>) {
    val t = 5f.dp.toPx()
    val margin = 4f.dp.toPx()
    val d = half - t - margin
    if (d <= 0f) return
    fun shade(zone: Int) = if (zone in held) PadColors.accent else PadColors.secondary
    // North (up)
    drawPath(
        Path().apply {
            moveTo(half, half - d - t)
            lineTo(half - t, half - d + t)
            lineTo(half + t, half - d + t)
            close()
        },
        shade(0),
    )
    // East (right)
    drawPath(
        Path().apply {
            moveTo(half + d + t, half)
            lineTo(half + d - t, half - t)
            lineTo(half + d - t, half + t)
            close()
        },
        shade(2),
    )
    // South (down)
    drawPath(
        Path().apply {
            moveTo(half, half + d + t)
            lineTo(half - t, half + d - t)
            lineTo(half + t, half + d - t)
            close()
        },
        shade(4),
    )
    // West (left)
    drawPath(
        Path().apply {
            moveTo(half - d - t, half)
            lineTo(half - d + t, half - t)
            lineTo(half - d + t, half + t)
            close()
        },
        shade(6),
    )
}

/** Touch delta -> hat index (0 N, 2 E, 4 S, 6 W, odd = diagonals). Center dead area = null. */
private fun hatZone(delta: Offset, half: Float): Int? {
    val mag = hypot(delta.x, delta.y)
    if (mag < half * 0.18f) return null
    val deg = (Math.toDegrees(Math.atan2(-delta.y.toDouble(), delta.x.toDouble())) % 360.0 + 360.0) % 360.0
    return when {
        deg < 22.5 || deg >= 337.5 -> 2 // E
        deg < 67.5 -> 1                 // NE
        deg < 112.5 -> 0                // N
        deg < 157.5 -> 7                // NW
        deg < 202.5 -> 6                // W
        deg < 247.5 -> 5                // SW
        deg < 292.5 -> 4                // S
        else -> 3                       // SE
    }
}

/** Merge simultaneous D-pad zones: two cardinals make a diagonal, singletons pass through. */
private fun combineHats(values: Collection<Int>): Int = when {
    values.isEmpty() -> GamepadState.HAT_NEUTRAL
    values.size == 1 -> values.first()
    else -> {
        val up = 0 in values
        val east = 2 in values
        val south = 4 in values
        val west = 6 in values
        when {
            up && east -> 1
            east && south -> 3
            south && west -> 5
            west && up -> 7
            else -> values.first()
        }
    }
}

/**
 * Digital trigger (L2 / R2): a vertical strip that is either pressed or released.
 *
 * The phone screen cannot feel travel, so the trigger gives no half presses: a touch
 * anywhere on the strip is a full pull (1) and lifting the finger releases it (0). The
 * touch position is deliberately ignored — mapping it to a ramp put the "R2" label, which
 * is centred in the strip, at exactly half travel, and a host that thresholds the
 * trigger read that as "not pressed". The value returns to 0 on release, touch cancel, or
 * the widget leaving composition. Each pressed pointer is tracked individually (multi-touch
 * safe) and the pedals use [combinePedal] semantics: the pedal stays pressed while any
 * finger is on it, and other on-screen controls are untouched.
 */

/** The value a pressed pedal reports: a full pull. */
fun pedalValue(pressed: Boolean): Float = if (pressed) 1f else 0f

/** One pressed finger's value per pointer; the pedal is pressed while any of them is. */
fun combinePedal(values: Collection<Float>): Float = values.maxOrNull() ?: 0f

@Composable
fun PedalControl(
    label: String,
    width: Dp,
    height: Dp,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val onValueNow by rememberUpdatedState(onValue)
    val values = remember { mutableStateMapOf<Long, Float>() }
    // Read in composition so the label and the fill follow the press state.
    val active = combinePedal(values.values) > 0.01f
    Box(modifier.size(width, height).padding(horizontal = 1.dp)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // The outer finally handles widget teardown mid-gesture; the trim-drop
                    // inside the loop handles fingers lifting or sliding off the track.
                    try {
                        awaitEachGesture {
                            while (true) {
                                val event = awaitPointerEvent()
                                var changed = false
                                for (c in event.changes) {
                                    if (c.pressed) {
                                        // The trigger is digital: where on the strip the
                                        // finger landed does not change the value.
                                        val v = pedalValue(pressed = true)
                                        if (values[c.id.value] != v) {
                                            values[c.id.value] = v
                                            changed = true
                                        }
                                    } else if (values.remove(c.id.value) != null) {
                                        changed = true
                                    }
                                }
                                if (changed) onValueNow(combinePedal(values.values))
                            }
                        }
                    } finally {
                        values.clear()
                        onValueNow(0f)
                    }
                },
        ) {
            val corner = CornerRadius(PAD_CORNER.toPx(), PAD_CORNER.toPx())
            drawRoundRect(PadColors.surface, cornerRadius = corner)
            drawRoundRect(PadColors.border, cornerRadius = corner, style = Stroke(width = HAIRLINE.toPx()))
            if (active) {
                drawRoundRect(PadColors.accent.copy(alpha = 0.55f), cornerRadius = corner)
            }
        }
        Text(
            label,
            color = if (active) PadColors.text else PadColors.secondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * Circular press-and-hold button: reports press/release with instantaneous visual state.
 * [hapticsStrength] scales the press buzz (0.25 = less .. 1.5 = more, per HapticBuzz).
 */
@Composable
fun GamepadButton(
    label: String,
    dpSize: Dp,
    color: Color,
    hapticsEnabled: Boolean,
    hapticsStrength: Float,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val onDownNow by rememberUpdatedState(onDown)
    val onUpNow by rememberUpdatedState(onUp)
    val hapticNow by rememberUpdatedState(hapticsEnabled)
    val strengthNow by rememberUpdatedState(hapticsStrength)
    Box(
        modifier.size(dpSize).scale(if (pressed) 0.95f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        var wasPressed = false
                        try {
                            awaitFirstDown(requireUnconsumed = false)
                            if (!pressed) {
                                pressed = true
                                wasPressed = true
                                if (hapticNow) {
                                    HapticBuzz.buzz(context, strengthNow)
                                }
                                onDownNow()
                            }
                            // waitForUpOrCancellation returns on both a normal release and
                            // Android's cancellation when the app backgrounds, so a same-frame
                            // press+release can never leave the button logically pressed.
                            waitForUpOrCancellation()
                        } finally {
                            if (wasPressed || pressed) {
                                pressed = false
                                onUpNow()
                            }
                        }
                    }
                },
        ) {
            val stroke = HAIRLINE.toPx()
            // A dark tinted disc with a hairline ring in the button's own colour.
            drawCircle(color.copy(alpha = if (pressed) 0.45f else 0.22f))
            drawCircle(
                color.copy(alpha = if (pressed) 1f else 0.9f),
                radius = size.width / 2f - stroke,
                style = Stroke(width = stroke),
            )
        }
        Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A rounded rectangular press-and-hold button: the shoulders and the centre pills.
 *
 * Same press/release contract as [GamepadButton] — instant press state, release on lift or
 * cancel — only the shape differs.
 */
@Composable
fun PadRectButton(
    label: String,
    width: Dp,
    height: Dp,
    color: Color,
    hapticsEnabled: Boolean,
    hapticsStrength: Float,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val onDownNow by rememberUpdatedState(onDown)
    val onUpNow by rememberUpdatedState(onUp)
    val hapticNow by rememberUpdatedState(hapticsEnabled)
    val strengthNow by rememberUpdatedState(hapticsStrength)
    val shape = RoundedCornerShape(PAD_CORNER)
    Box(
        modifier
            .size(width, height)
            .scale(if (pressed) 0.95f else 1f)
            .background(if (pressed) color.copy(alpha = 0.42f) else PadColors.surface, shape)
            .border(1.dp, if (pressed) color else PadColors.border, shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    var wasPressed = false
                    try {
                        awaitFirstDown(requireUnconsumed = false)
                        if (!pressed) {
                            pressed = true
                            wasPressed = true
                            if (hapticNow) {
                                HapticBuzz.buzz(context, strengthNow)
                            }
                            onDownNow()
                        }
                        waitForUpOrCancellation()
                    } finally {
                        if (wasPressed || pressed) {
                            pressed = false
                            onUpNow()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = PadColors.text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** Rectangular keyboard press target with a clear held state. */
@Composable
fun KeyboardKeyButton(
    label: String,
    active: Boolean,
    hapticsEnabled: Boolean,
    hapticsStrength: Float,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val onDownNow by rememberUpdatedState(onDown)
    val onUpNow by rememberUpdatedState(onUp)
    val hapticNow by rememberUpdatedState(hapticsEnabled)
    val strengthNow by rememberUpdatedState(hapticsStrength)
    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        var wasPressed = false
                        try {
                            awaitFirstDown(requireUnconsumed = false)
                            if (!pressed) {
                                pressed = true
                                wasPressed = true
                                if (hapticNow) HapticBuzz.buzz(context, strengthNow)
                                onDownNow()
                            }
                            waitForUpOrCancellation()
                        } finally {
                            if (wasPressed || pressed) {
                                pressed = false
                                onUpNow()
                            }
                        }
                    }
                },
        ) {
            val keyCorner = CornerRadius(8f.dp.toPx(), 8f.dp.toPx())
            drawRoundRect(
                if (active || pressed) PadColors.accent.copy(alpha = 0.45f) else PadColors.surface,
                cornerRadius = keyCorner,
            )
            drawRoundRect(
                PadColors.border,
                cornerRadius = keyCorner,
                style = Stroke(width = HAIRLINE.toPx()),
            )
        }
        Text(
            label,
            color = PadColors.text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
