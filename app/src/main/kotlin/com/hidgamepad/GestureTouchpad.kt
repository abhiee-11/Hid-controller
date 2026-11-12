package com.hidgamepad

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Buttonless trackpad: pointer frames go to a [TouchpadGestureRecognizer], the resulting
 * actions go to a [TouchpadGestureBridge], and only the drawing and the 16 ms ticker are
 * Compose-specific. Everything that decides what a gesture means lives in those two classes.
 */
@Composable
fun GestureTouchpad(
    sensitivity: Float,
    hostType: HostType,
    hostConnected: Boolean,
    hapticsEnabled: Boolean,
    hapticsStrength: Float,
    onMouseState: (MouseState) -> Unit,
    onKeyboardState: (KeyboardState) -> Unit,
    onConsumerState: (ConsumerState) -> Unit,
    modifier: Modifier = Modifier,
    padGlow: PadGlowConfig = PadGlowConfig(),
    padGlowTimeMillis: Long = 0L,
    gesturesEnabled: Boolean = true,
) {
    val density = LocalDensity.current.density
    val context = LocalContext.current
    val mouseNow by rememberUpdatedState(onMouseState)
    val keyboardNow by rememberUpdatedState(onKeyboardState)
    val consumerNow by rememberUpdatedState(onConsumerState)
    val hapticsOn by rememberUpdatedState(hapticsEnabled)
    val hapticsStrengthNow by rememberUpdatedState(hapticsStrength)

    val handler = remember { Handler(Looper.getMainLooper()) }
    // Debug aid: shows what the recognizer produced even when no host is connected.
    LaunchedEffect(Unit) { Log.i("HidGamepad", "GESTURE pad ready density=$density") }
    // One recognizer and one bridge for the pad's whole life: rebuilding them mid-gesture
    // would drop a held button or a held Ctrl without releasing it.
    val recognizer = remember(density) {
        TouchpadGestureRecognizer(density = density, sensitivity = sensitivity)
    }
    val bridge = remember {
        TouchpadGestureBridge(
            hostType = hostType,
            sendMouse = { mouseNow(it) },
            sendKeyboard = { keyboardNow(it) },
            sendConsumer = { consumerNow(it) },
            schedule = { delayMs, action -> handler.postDelayed(action, delayMs) },
            hapticClick = { if (hapticsOn) HapticBuzz.buzz(context, hapticsStrengthNow) },
        )
    }
    LaunchedEffect(sensitivity) { recognizer.sensitivity = sensitivity }
    LaunchedEffect(hostType) { bridge.hostType = hostType }

    fun dispatch(actions: List<PadAction>) {
        if (actions.isEmpty()) return
        bridge.dispatch(actions)
    }

    // Turning gestures off must not leave a button or Ctrl held on the host.
    LaunchedEffect(gesturesEnabled) {
        if (!gesturesEnabled) {
            dispatch(recognizer.reset())
            bridge.releaseAll()
        }
    }

    var fingerDown by remember { mutableStateOf(false) }

    LaunchedEffect(fingerDown) {
        while (fingerDown) {
            delay(16)
            dispatch(recognizer.tick(SystemClock.uptimeMillis()))
        }
    }

    DisposableEffect(recognizer, bridge) {
        onDispose {
            dispatch(recognizer.reset())
            bridge.releaseAll()
        }
    }

    // A host that went away must not inherit a held button or a held Ctrl.
    LaunchedEffect(hostConnected) {
        if (!hostConnected) {
            dispatch(recognizer.reset())
            bridge.releaseAll()
        }
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val padModifier = if (gesturesEnabled) {
            Modifier.fillMaxSize().pointerInput(Unit) {
                try {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        fingerDown = true
                        val initialFrame = PadFrame(
                            timeMs = SystemClock.uptimeMillis(),
                            points = listOf(PadPoint(down.id.value, down.position.x, down.position.y)),
                        )
                        dispatch(recognizer.frame(initialFrame))
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                for (c in event.changes) c.consume()
                                val frame = PadFrame(
                                    timeMs = SystemClock.uptimeMillis(),
                                    points = event.changes.filter { it.pressed }
                                        .map { PadPoint(it.id.value, it.position.x, it.position.y) },
                                )
                                val actions = recognizer.frame(frame)
                                if (actions.isNotEmpty()) {
                                    Log.i("HidGamepad", "GESTURE actions=$actions")
                                }
                                dispatch(actions)
                                if (event.changes.none { it.pressed }) break
                            }
                        } finally {
                            fingerDown = false
                        }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    Log.e("HidGamepad", "GESTURE pointer loop failed", e)
                    fingerDown = false
                }
            }
        } else {
            Modifier.fillMaxSize()
        }
        Canvas(padModifier) {
            // The pad is inset so the halo has somewhere to go: a glow drawn outside the
            // border is clipped by the canvas when the pad fills it edge to edge.
            val inset = 18f
            val padTopLeft = Offset(inset, inset)
            val padSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawRoundRect(
                color = Color(0xFF26272F),
                topLeft = padTopLeft,
                size = padSize,
                cornerRadius = CornerRadius(20f, 20f),
            )
            drawRoundRect(
                color = Color(0xFF555C6B),
                topLeft = padTopLeft,
                size = padSize,
                cornerRadius = CornerRadius(20f, 20f),
                style = Stroke(width = 2f),
            )
            // The pad glow: a soft halo that spills outward past the border, only when enabled.
            val glowColor = padGlowColor(padGlow, position = 0f, touched = fingerDown, timeMillis = padGlowTimeMillis)
            if (glowColor != null) {
                for (i in 1..3) {
                    val spread = i * 3f
                    drawRoundRect(
                        color = glowColor.copy(alpha = 0.22f / i),
                        topLeft = Offset(padTopLeft.x - spread, padTopLeft.y - spread),
                        size = Size(padSize.width + spread * 2, padSize.height + spread * 2),
                        cornerRadius = CornerRadius(20f + spread, 20f + spread),
                        style = Stroke(width = 2f + spread),
                    )
                }
            }
            // Faint dot-grid so the surface reads as a touchpad, not a joystick track.
            val dotColor = Color(0xFF3A3B45).copy(alpha = 0.55f)
            val step = 36f
            var x = padTopLeft.x + step
            while (x < padTopLeft.x + padSize.width - 8f) {
                var y = padTopLeft.y + step
                while (y < padTopLeft.y + padSize.height - 8f) {
                    drawCircle(dotColor, radius = 2.5f, center = Offset(x, y))
                    y += step
                }
                x += step
            }
        }
    }
}
