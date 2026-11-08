package com.hidgamepad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.hypot

// Shared palette for the on-screen controls.
val TrackRing = Color(0xFF343640)
val StickKnob = Color(0xFFB0B6C8)
val DpadColor = Color(0xFF555C6B)
val DpadHeld = Color(0xFF9EB1D8)

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
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        activeId = down.id.value
                        knob = clampToTravel(down.position - Offset(radius, radius), travel)
                        emitStick(onMovedNow, knob, travel)
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
                        activeId = -1
                        knob = Offset.Zero
                        onMovedNow(0f, 0f)
                    }
                },
        ) {
            val radius = size.width / 2f
            drawCircle(TrackRing, radius = radius)
            drawCircle(TrackRing.copy(alpha = 0.5f), radius = radius * 0.08f) // deadzone hint
            drawCircle(
                StickKnob.copy(alpha = 0.4f),
                radius = radius * 0.45f,
                center = Offset(radius, radius) + knob,
            )
            drawCircle(
                StickKnob,
                radius = radius * 0.45f,
                center = Offset(radius, radius) + knob,
                style = Stroke(width = 5f),
            )
            drawCircle(StickKnob.copy(alpha = 0.6f), radius = radius * 0.26f, center = Offset(radius, radius) + knob)
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
    val zones = remember { mutableStateMapOf<Long, Int>() }
    Box(modifier.size(dpSize), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val half = size.width / 2f
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
                            if (changed) onHat(combineHats(zones.values))
                        }
                    }
                },
        ) {
            val half = size.width / 2f
            val arm = half * 0.62f
            val thick = half * 0.36f
            drawRoundRect(DpadColor, topLeft = Offset(half - thick / 2, half - arm), size = Size(thick, arm * 2))
            drawRoundRect(DpadColor, topLeft = Offset(half - arm, half - thick / 2), size = Size(arm * 2, thick))
            for ((_, zone) in zones) drawHeldArm(zone, half, arm, thick)
            drawCircle(DpadHeld.copy(alpha = 0.3f), radius = half * 0.11f, center = Offset(half, half))
        }
    }
}

private fun DrawScope.drawHeldArm(zone: Int, half: Float, arm: Float, thick: Float) {
    when (zone) {
        0 -> drawRoundRect(DpadHeld, Offset(half - thick / 2, half - arm), Size(thick, arm * 0.72f))
        2 -> drawRoundRect(DpadHeld, Offset(half + thick / 2, half - thick / 2), Size(arm - thick / 2, thick))
        4 -> drawRoundRect(DpadHeld, Offset(half - thick / 2, half + arm * 0.28f), Size(thick, arm * 0.72f))
        6 -> drawRoundRect(DpadHeld, Offset(half - arm, half - thick / 2), Size(arm - thick / 2, thick))
        1 -> drawRect(DpadHeld, Offset(half + thick * 0.1f, half - arm * 0.82f), Size(arm * 0.5f, arm * 0.5f))
        3 -> drawRect(DpadHeld, Offset(half + thick * 0.1f, half + arm * 0.32f), Size(arm * 0.5f, arm * 0.5f))
        5 -> drawRect(DpadHeld, Offset(half - arm * 0.95f, half + arm * 0.32f), Size(arm * 0.5f, arm * 0.5f))
        7 -> drawRect(DpadHeld, Offset(half - arm * 0.95f, half - arm * 0.82f), Size(arm * 0.5f, arm * 0.5f))
    }
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

/** Circular press-and-hold button: reports press/release with instantaneous visual state. */
@Composable
fun GamepadButton(
    label: String,
    dpSize: Dp,
    color: Color,
    hapticsEnabled: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val onDownNow by rememberUpdatedState(onDown)
    val onUpNow by rememberUpdatedState(onUp)
    val hapticNow by rememberUpdatedState(hapticsEnabled)
    Box(modifier.size(dpSize), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        if (!pressed) {
                            pressed = true
                            if (hapticNow) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            onDownNow()
                        }
                        // This handles both a normal release and Android's cancellation when
                        // the app backgrounds, so a button cannot remain logically pressed.
                        waitForUpOrCancellation()
                        pressed = false
                        onUpNow()
                    }
                },
        ) {
            drawCircle(if (pressed) color.copy(alpha = 0.85f) else color.copy(alpha = 0.5f))
            drawCircle(color, radius = size.width / 5f, style = Stroke(width = 3f))
        }
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
