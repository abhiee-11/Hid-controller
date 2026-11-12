package com.hidgamepad

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The first-use tutorial.
 *
 * Five steps that teach what the app is and how to get a host connected. The content and the
 * order are the ones the app has always asked for; what is new is that the user can step
 * through it, go back, **skip** it, and replay it from Settings.
 *
 * The layout adapts: on a window 600 dp or wider the artwork sits beside the text, below that
 * they stack, and the content column is capped and centred so nothing sprawls across a
 * tablet.
 */
@Composable
fun TutorialPanel(
    step: TutorialStep,
    hostType: HostType,
    mode: ControllerMode,
    bondedDevices: () -> List<BluetoothDevice>,
    onDiscoverable: () -> Unit,
    onHostType: (HostType) -> Unit,
    onMode: (ControllerMode) -> Unit,
    onConnect: (BluetoothDevice) -> Boolean,
    onStep: (TutorialStep) -> Unit,
    onFinish: () -> Unit,
) {
    val colors = LocalTutorialColors
    var selectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
    var deviceList by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            // The app is landscape-locked and this device has a 67 px cutout on the left:
            // without this the "Skip" button sits under it.
            .safeDrawingPadding()
            // Modal: the controller underneath must not see touches meant for the tutorial.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        val wide = maxWidth >= 600.dp
        val contentWidth = if (wide) 680.dp else maxWidth

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- header: skip is always reachable -------------------------------
            Row(
                Modifier
                    
                    .widthIn(max = contentWidth)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Getting started",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.title,
                    modifier = Modifier.weight(1f),
                )
                TextButtonish("Skip", onClick = { onStep(step.skipTarget()) })
            }

            // ---- progress --------------------------------------------------------
            Column(
                Modifier
                    
                    .widthIn(max = contentWidth)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally),
            ) {
                Text(
                    text = "Step ${step.number} of ${TutorialStep.count}",
                    fontSize = 12.sp,
                    color = colors.hint,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.track),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(step.number / TutorialStep.count.toFloat())
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.accent),
                    )
                }
            }

            // ---- the step --------------------------------------------------------
            Row(
                Modifier
                    
                    .widthIn(max = contentWidth)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (wide) {
                    TutorialArtwork(step, Modifier.size(160.dp))
                }
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (step) {
                        TutorialStep.WELCOME -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "Welcome to HID Controller",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.title,
                            )
                            Text(
                                text = "Turn this phone into a wireless gamepad, mouse or keyboard for another device. Everything happens over Bluetooth - no app is needed on the other side.",
                                fontSize = 14.sp,
                                color = colors.body,
                            )
                            Text(
                                text = "You can skip this, and change every answer later in Settings.",
                                fontSize = 12.sp,
                                color = colors.hint,
                            )
                        }

                        TutorialStep.HOST -> {
                            Text(
                                text = "What will you control?",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.title,
                            )
                            Text(
                                text = "This also chooses the HID descriptor the phone registers. You can change it later in Settings.",
                                fontSize = 14.sp,
                                color = colors.body,
                            )
                            ChoiceRow(
                                title = "Windows PC",
                                detail = "PC games and desktop control.",
                                selected = hostType == HostType.WINDOWS,
                                onClick = { onHostType(HostType.WINDOWS) },
                            )
                            ChoiceRow(
                                title = "Android phone or tablet",
                                detail = "Games and app control on another Android device.",
                                selected = hostType == HostType.ANDROID,
                                onClick = { onHostType(HostType.ANDROID) },
                            )
                        }

                        TutorialStep.MODE -> {
                            Text(
                                text = "How will you use it?",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.title,
                            )
                            Text(
                                text = "You can switch modes any time from the app.",
                                fontSize = 14.sp,
                                color = colors.body,
                            )
                            ChoiceRow(
                                title = "Gamepad",
                                detail = "Buttons, sticks, triggers and tilt.",
                                selected = mode == ControllerMode.GAMEPAD,
                                onClick = { onMode(ControllerMode.GAMEPAD) },
                            )
                            ChoiceRow(
                                title = "Mouse",
                                detail = "A touchpad with gestures, clicks and scroll.",
                                selected = mode == ControllerMode.MOUSE,
                                onClick = { onMode(ControllerMode.MOUSE) },
                            )
                            ChoiceRow(
                                title = "Keyboard",
                                detail = "A full QWERTY layout with a symbols layer.",
                                selected = mode == ControllerMode.KEYBOARD,
                                onClick = { onMode(ControllerMode.KEYBOARD) },
                            )
                        }

                        TutorialStep.PAIR -> {
                            Text(
                                text = "Pair with your host",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.title,
                            )
                            Text(
                                text = "Pairing always starts on the host. Make the phone discoverable, then pick it on the host Bluetooth screen.",
                                fontSize = 14.sp,
                                color = colors.body,
                            )
                            Text(
                                text = pairInstructions(hostType),
                                fontSize = 13.sp,
                                color = colors.hint,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onDiscoverable,
                                    modifier = Modifier.height(48.dp),
                                ) { Text("Make discoverable") }
                                Button(
                                    onClick = { deviceList = bondedDevices() },
                                    modifier = Modifier.height(48.dp),
                                ) { Text("Refresh devices") }
                            }
                            if (deviceList.isEmpty()) {
                                Text(
                                    text = "No paired devices found yet.",
                                    fontSize = 12.sp,
                                    color = colors.hint,
                                )
                            } else {
                                for (device in deviceList) {
                                    val name = runCatching { device.name }.getOrNull() ?: "Bluetooth device"
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(56.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = name,
                                            fontSize = 14.sp,
                                            color = colors.title,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Button(
                                            onClick = {
                                                if (onConnect(device)) selectedDevice = device
                                            },
                                            enabled = selectedDevice != device,
                                            modifier = Modifier.height(48.dp),
                                        ) { Text("Connect") }
                                    }
                                }
                            }
                        }

                        TutorialStep.READY -> {
                            Text(
                                text = "You are all set",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.title,
                            )
                            Text(
                                text = "The controller works even before a host is paired - you can pair later from Devices.",
                                fontSize = 14.sp,
                                color = colors.body,
                            )
                            Text(
                                text = "Host: ${hostTitle(hostType)}   Mode: ${modeLabel(mode)}",
                                fontSize = 13.sp,
                                color = colors.hint,
                            )
                        }
                    }
                }
            }

            if (!wide) {
                TutorialArtwork(step, Modifier.size(140.dp))
            }

            // ---- footer: back and next ------------------------------------------
            Row(
                Modifier
                    
                    .widthIn(max = contentWidth)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step.previous() != null) {
                    Button(
                        onClick = { step.previous()?.let(onStep) },
                        modifier = Modifier.height(48.dp),
                    ) { Text("Back") }
                }
                Spacer(Modifier.weight(1f))
                if (step.isFinal) {
                    Button(
                        onClick = onFinish,
                        modifier = Modifier.height(48.dp),
                    ) { Text("Start using the app") }
                } else {
                    Button(
                        onClick = { step.next()?.let(onStep) },
                        modifier = Modifier.height(48.dp),
                    ) { Text("Next") }
                }
            }
        }
    }
}

/** A tappable row used for the choices. */
@Composable
private fun ChoiceRow(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalTutorialColors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.accentContainer else colors.card)
            .border(
                width = 1.dp,
                color = if (selected) colors.accent else colors.track,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.title)
            Text(text = detail, fontSize = 12.sp, color = colors.body)
        }
        if (selected) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.accent),
            )
        }
    }
}

/** A quiet text button, so the tutorial's Skip does not shout. */
@Composable
private fun TextButtonish(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = LocalTutorialColors.accent,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/**
 * A small drawing for each step.
 *
 * Drawn rather than shipped as an image so it scales to any screen, needs no density
 * buckets, and can never render as a missing glyph.
 */
@Composable
private fun TutorialArtwork(step: TutorialStep, modifier: Modifier = Modifier) {
    val colors = LocalTutorialColors
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        when (step) {
            TutorialStep.WELCOME -> {
                // A gamepad silhouette.
                val body = Path().apply {
                    moveTo(w * 0.12f, h * 0.42f)
                    cubicTo(w * 0.12f, h * 0.24f, w * 0.36f, h * 0.30f, w * 0.50f, h * 0.38f)
                    cubicTo(w * 0.64f, h * 0.30f, w * 0.88f, h * 0.24f, w * 0.88f, h * 0.42f)
                    cubicTo(w * 0.88f, h * 0.70f, w * 0.72f, h * 0.76f, w * 0.62f, h * 0.58f)
                    lineTo(w * 0.38f, h * 0.58f)
                    cubicTo(w * 0.28f, h * 0.76f, w * 0.12f, h * 0.70f, w * 0.12f, h * 0.42f)
                    close()
                }
                drawPath(body, color = colors.card)
                drawPath(body, color = colors.accent, style = Stroke(width = 3.dp.toPx()))
                drawCircle(colors.accent, radius = w * 0.055f, center = Offset(w * 0.68f, h * 0.50f))
                drawCircle(colors.accent, radius = w * 0.055f, center = Offset(w * 0.79f, h * 0.56f))
                drawLine(
                    colors.body,
                    Offset(w * 0.24f, h * 0.50f),
                    Offset(w * 0.36f, h * 0.50f),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    colors.body,
                    Offset(w * 0.30f, h * 0.44f),
                    Offset(w * 0.30f, h * 0.56f),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            TutorialStep.HOST -> {
                // A screen and a phone, linked.
                drawRoundRect(
                    colors.card,
                    topLeft = Offset(w * 0.08f, h * 0.20f),
                    size = Size(w * 0.52f, h * 0.46f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                )
                drawRoundRect(
                    colors.accent,
                    topLeft = Offset(w * 0.08f, h * 0.20f),
                    size = Size(w * 0.52f, h * 0.46f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                    style = Stroke(width = 3.dp.toPx()),
                )
                drawRoundRect(
                    colors.card,
                    topLeft = Offset(w * 0.66f, h * 0.42f),
                    size = Size(w * 0.22f, h * 0.40f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
                )
                drawRoundRect(
                    colors.accent,
                    topLeft = Offset(w * 0.66f, h * 0.42f),
                    size = Size(w * 0.22f, h * 0.40f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
                    style = Stroke(width = 3.dp.toPx()),
                )
                drawLine(
                    colors.accent,
                    Offset(w * 0.60f, h * 0.50f),
                    Offset(w * 0.66f, h * 0.50f),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            TutorialStep.MODE -> {
                // A stick, a pad and a keyboard, side by side.
                drawCircle(colors.card, radius = w * 0.13f, center = Offset(w * 0.20f, h * 0.46f))
                drawCircle(
                    colors.accent,
                    radius = w * 0.13f,
                    center = Offset(w * 0.20f, h * 0.46f),
                    style = Stroke(width = 3.dp.toPx()),
                )
                drawRoundRect(
                    colors.card,
                    topLeft = Offset(w * 0.42f, h * 0.32f),
                    size = Size(w * 0.24f, h * 0.30f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                )
                drawRoundRect(
                    colors.accent,
                    topLeft = Offset(w * 0.42f, h * 0.32f),
                    size = Size(w * 0.24f, h * 0.30f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                    style = Stroke(width = 3.dp.toPx()),
                )
                for (row in 0..2) {
                    for (column in 0..3) {
                        drawRoundRect(
                            colors.body,
                            topLeft = Offset(w * 0.74f + column * w * 0.05f, h * 0.32f + row * h * 0.11f),
                            size = Size(w * 0.035f, h * 0.07f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                        )
                    }
                }
            }

            TutorialStep.PAIR -> {
                // Two devices and a link between them.
                drawCircle(colors.card, radius = w * 0.10f, center = Offset(w * 0.22f, h * 0.50f))
                drawCircle(
                    colors.accent,
                    radius = w * 0.10f,
                    center = Offset(w * 0.22f, h * 0.50f),
                    style = Stroke(width = 3.dp.toPx()),
                )
                drawCircle(colors.card, radius = w * 0.10f, center = Offset(w * 0.78f, h * 0.50f))
                drawCircle(
                    colors.accent,
                    radius = w * 0.10f,
                    center = Offset(w * 0.78f, h * 0.50f),
                    style = Stroke(width = 3.dp.toPx()),
                )
                drawLine(
                    colors.accent,
                    Offset(w * 0.36f, h * 0.50f),
                    Offset(w * 0.64f, h * 0.50f),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    colors.accent,
                    Offset(w * 0.36f, h * 0.50f),
                    Offset(w * 0.64f, h * 0.50f),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            TutorialStep.READY -> {
                // A tick.
                val stroke = 6.dp.toPx()
                drawLine(
                    colors.accent,
                    Offset(w * 0.28f, h * 0.52f),
                    Offset(w * 0.44f, h * 0.68f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    colors.accent,
                    Offset(w * 0.44f, h * 0.68f),
                    Offset(w * 0.74f, h * 0.34f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** The tutorial's palette, matching the app's existing dark surfaces. */
private object LocalTutorialColors {
    val background = Color(0xFF141519)
    val card = Color(0xFF1C202A)
    val track = Color(0xFF2A303C)
    val title = Color(0xFFE8EAF0)
    val body = Color(0xFFC7CDD8)
    val hint = Color(0xFF9AA1B2)
    val accent = Color(0xFF82B4EA)
    val accentContainer = Color(0xFF1E3450)
}
