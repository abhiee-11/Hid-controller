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
    onHostType: (HostType) -> Unit,
    onMode: (ControllerMode) -> Unit,
    onStep: (TutorialStep) -> Unit,
    onFinish: () -> Unit,
) {
    val colors = LocalTutorialColors

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
        // A short landscape phone cannot fit the artwork plus three choice rows plus the
        // pinned footer; on short windows the artwork makes way so nothing scrolls.
        val shortWindow = maxHeight < 400.dp
        val contentWidth = if (wide) 680.dp else maxWidth

        Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (shortWindow) 6.dp else 12.dp),
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
                    fontSize = if (shortWindow) 16.sp else 20.sp,
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
                Spacer(Modifier.height(if (shortWindow) 2.dp else 6.dp))
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
                if (wide && !shortWindow) {
                    TutorialArtwork(step, Modifier.size(160.dp))
                }
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (step) {
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
                                compact = shortWindow,
                            )
                            ChoiceRow(
                                title = "Android phone or tablet",
                                detail = "Games and app control on another Android device.",
                                selected = hostType == HostType.ANDROID,
                                onClick = { onHostType(HostType.ANDROID) },
                                compact = shortWindow,
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
                                text = "Choose the controller mode to start with. You can switch modes any time from the app.",
                                fontSize = 14.sp,
                                color = colors.body,
                            )
                            for (choice in ONBOARDING_MODES) {
                                ChoiceRow(
                                    title = modeLabel(choice.mode),
                                    detail = choice.detail,
                                    selected = mode == choice.mode,
                                    onClick = { onMode(choice.mode) },
                                    compact = shortWindow,
                                )
                            }
                        }
                    }
                }
            }

            if (!wide) {
                TutorialArtwork(step, Modifier.size(140.dp))
            }
        }

        // ---- footer: back and next (pinned, so it never falls below the fold) -----
        Row(
            Modifier
                .widthIn(max = contentWidth)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 16.dp, vertical = 8.dp),
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
                ) { Text("Open Controller") }
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
    compact: Boolean = false,
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
            .padding(if (compact) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 2.dp)) {
            Text(text = title, fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.SemiBold, color = colors.title)
            Text(text = detail, fontSize = if (compact) 11.sp else 12.sp, color = colors.body)
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
