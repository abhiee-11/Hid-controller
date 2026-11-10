package com.hidgamepad

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
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
) {
    val density = LocalDensity.current.density
    val context = LocalContext.current
    val mouseNow by rememberUpdatedState(onMouseState)
    val keyboardNow by rememberUpdatedState(onKeyboardState)
    val consumerNow by rememberUpdatedState(onConsumerState)
    val hapticsOn by rememberUpdatedState(hapticsEnabled)
    val hapticsStrengthNow by rememberUpdatedState(hapticsStrength)

    val handler = remember { Handler(Looper.getMainLooper()) }
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

    // Virtual cursor position, driven by the same deltas the host receives.
    var pointerPosition by remember { mutableStateOf(Offset(-1f, -1f)) }
    var fingerDown by remember { mutableStateOf(false) }

    LaunchedEffect(fingerDown) {
        while (fingerDown) {
            delay(16)
            bridge.dispatch(recognizer.tick(SystemClock.uptimeMillis()))
        }
    }

    DisposableEffect(recognizer, bridge) {
        onDispose {
            bridge.dispatch(recognizer.reset())
            bridge.releaseAll()
        }
    }

    // A host that went away must not inherit a held button or a held Ctrl.
    LaunchedEffect(hostConnected) {
        if (!hostConnected) {
            bridge.dispatch(recognizer.reset())
            bridge.releaseAll()
        }
    }

    val arrowPath = remember {
        Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, 25f)
            lineTo(7.2f, 18.8f)
            lineTo(12f, 31f)
            lineTo(16f, 29.2f)
            lineTo(11.3f, 17f)
            lineTo(19.5f, 17f)
            close()
        }
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                try {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        fingerDown = true
                        val surfaceWidth = size.width.toFloat()
                        val surfaceHeight = size.height.toFloat()
                        if (pointerPosition.x < 0f) {
                            pointerPosition = Offset(surfaceWidth / 2f, surfaceHeight / 2f)
                        }
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
                                bridge.dispatch(actions)
                                for (action in actions) {
                                    if (action is PadAction.Move) {
                                        pointerPosition = Offset(
                                            (pointerPosition.x + action.dx).coerceIn(0f, surfaceWidth - 1f),
                                            (pointerPosition.y + action.dy).coerceIn(0f, surfaceHeight - 1f),
                                        )
                                    }
                                }
                                if (event.changes.none { it.pressed }) break
                            }
                        } finally {
                            fingerDown = false
                        }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    fingerDown = false
                }
            },
        ) {
            drawRoundRect(color = Color(0xFF26272F), cornerRadius = CornerRadius(20f, 20f))
            drawRoundRect(
                color = Color(0xFF555C6B),
                cornerRadius = CornerRadius(20f, 20f),
                style = Stroke(width = 2f),
            )
            // Faint dot-grid so the surface reads as a touchpad, not a joystick track.
            val dotColor = Color(0xFF3A3B45).copy(alpha = 0.55f)
            val step = 36f
            var x = step
            while (x < size.width - 8f) {
                var y = step
                while (y < size.height - 8f) {
                    drawCircle(dotColor, radius = 2.5f, center = Offset(x, y))
                    y += step
                }
                x += step
            }
            val px = if (pointerPosition.x < 0f) size.width / 2f else pointerPosition.x
            val py = if (pointerPosition.y < 0f) size.height / 2f else pointerPosition.y
            val fill = Color.White
            val outline = Color(0xFF141519)
            translate(px, py) {
                drawPath(arrowPath, outline, alpha = 0.95f)
                drawPath(arrowPath, fill)
            }
        }
    }
}
