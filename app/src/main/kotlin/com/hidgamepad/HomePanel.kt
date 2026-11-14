package com.hidgamepad

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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Home, the app's entry page.
 *
 * The app opens here on every cold start: the user picks the kind of host they will control
 * (which also picks the HID descriptor), then the controller to start with, and goes straight
 * into the controller. Returning from the background never lands here again; the page is only
 * reached by a cold start or from Settings.
 *
 * Layout: a pinned header ("Home"), a split body — the headline and its explanation on
 * the left, the big choice cards on the right — and a pinned footer with Back / Next. On short
 * windows everything shrinks rather than scrolling; on very small windows the body scrolls but
 * the footer stays put.
 */
@Composable
fun HomePanel(
    step: HomeStep,
    hostType: HostType,
    mode: ControllerMode,
    onHostType: (HostType) -> Unit,
    onMode: (ControllerMode) -> Unit,
    onStep: (HomeStep) -> Unit,
    onFinish: () -> Unit,
) {
    val colors = HomeColors

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            // The app is landscape-locked and this device has a 67 px cutout on the left:
            // without this the header sits under it.
            .safeDrawingPadding()
            // Modal: the controller underneath must not see touches meant for Home.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        val wide = maxWidth >= 600.dp
        val screenWidth = maxWidth
        val shortWindow = maxHeight < 400.dp
        val contentWidth = if (wide) 940.dp else maxWidth
        val cardHeight = if (wide) (if (shortWindow) 200.dp else 236.dp) else 150.dp

        Column(Modifier.fillMaxSize()) {
            // ---- header: pinned -----------------------------------------------------
            Row(
                Modifier
                    .widthIn(max = contentWidth)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (step == HomeStep.HOST) "Home" else "Mode",
                    fontSize = if (shortWindow) 18.sp else 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.title,
                )
            }

            // ---- body ---------------------------------------------------------------
            val content: @Composable () -> Unit = {
                val textColumn: @Composable () -> Unit = {
                    Column(
                        Modifier.widthIn(max = if (wide) 320.dp else screenWidth),
                        verticalArrangement = Arrangement.spacedBy(if (shortWindow) 8.dp else 14.dp),
                    ) {
                        Text(
                            text = if (step == HomeStep.HOST) "What will you control?" else "How will you use it?",
                            fontSize = if (shortWindow) 22.sp else 30.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = if (shortWindow) 27.sp else 36.sp,
                            color = colors.title,
                        )
                        Text(
                            text = if (step == HomeStep.HOST)
                                "This also chooses the HID descriptor the phone registers. You can change it later in Settings."
                            else
                                "Choose the controller to start with. You can switch modes at any time from the app.",
                            fontSize = if (shortWindow) 12.sp else 14.sp,
                            lineHeight = if (shortWindow) 17.sp else 20.sp,
                            color = colors.body,
                        )
                    }
                }
                val cards: @Composable () -> Unit = {
                    Row(
                        Modifier
                            .then(if (wide) Modifier.weight(1f) else Modifier.fillMaxWidth())
                            .height(cardHeight),
                        horizontalArrangement = Arrangement.spacedBy(if (shortWindow) 12.dp else 18.dp),
                    ) {
                        when (step) {
                            HomeStep.HOST -> {
                                HomeCard(
                                    icon = { m -> MonitorIcon(m) },
                                    title = "Windows PC",
                                    detail = "PC games and desktop control.",
                                    selected = hostType == HostType.WINDOWS,
                                    onClick = { onHostType(HostType.WINDOWS) },
                                    modifier = Modifier.weight(1f),
                                    compact = shortWindow,
                                )
                                HomeCard(
                                    icon = { m -> PhoneIcon(m) },
                                    title = "Android phone or tablet",
                                    detail = "Games and app control on another Android device.",
                                    selected = hostType == HostType.ANDROID,
                                    onClick = { onHostType(HostType.ANDROID) },
                                    modifier = Modifier.weight(1f),
                                    compact = shortWindow,
                                )
                            }
                            HomeStep.MODE -> {
                                for (choice in HOME_MODES) {
                                    val icon: @Composable (Modifier) -> Unit = when (choice.mode) {
                                        ControllerMode.GAMEPAD -> ({ m -> GamepadIcon(m) })
                                        ControllerMode.MOUSE -> ({ m -> TouchpadIcon(m) })
                                        ControllerMode.KEYBOARD -> ({ m -> KeyboardIcon(m) })
                                        ControllerMode.WASD -> ({ m -> GamepadIcon(m) }) // never reached; Home never offers WASD
                                    }
                                    HomeCard(
                                        icon = icon,
                                        title = modeLabel(choice.mode),
                                        detail = choice.detail,
                                        selected = mode == choice.mode,
                                        onClick = { onMode(choice.mode) },
                                        modifier = Modifier.weight(1f),
                                        compact = shortWindow,
                                    )
                                }
                            }
                        }
                    }
                }
                if (wide) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        textColumn()
                        cards()
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        textColumn()
                        cards()
                    }
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (wide) 20.dp else 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.widthIn(max = contentWidth).fillMaxWidth().align(Alignment.CenterHorizontally),
                    contentAlignment = Alignment.Center,
                ) {
                    content()
                }
            }

            // ---- footer: pinned -----------------------------------------------------
            Row(
                Modifier
                    .widthIn(max = contentWidth)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
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

/** One of Home's big choice cards: icon in a chip, check when selected, text at the bottom. */
@Composable
private fun HomeCard(
    icon: @Composable (Modifier) -> Unit,
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = HomeColors
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) colors.accentContainer else colors.card)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) colors.accent else colors.track,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(if (compact) 14.dp else 18.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .size(if (compact) 46.dp else 56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.chip),
                contentAlignment = Alignment.Center,
            ) {
                icon(Modifier.size(if (compact) 26.dp else 32.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = title,
                fontSize = if (compact) 16.sp else 19.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = if (compact) 20.sp else 23.sp,
                color = colors.title,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = detail,
                fontSize = if (compact) 10.sp else 12.sp,
                lineHeight = if (compact) 14.sp else 17.sp,
                color = colors.body,
            )
        }
        if (selected) {
            Box(Modifier.align(Alignment.TopEnd).padding(if (compact) 10.dp else 14.dp)) {
                CheckBadge(Modifier.size(if (compact) 22.dp else 26.dp))
            }
        }
    }
}

/** The selected-card badge: a filled circle with a contrasting check. */
@Composable
private fun CheckBadge(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        drawCircle(HomeColors.accent, radius = w / 2f, center = Offset(w / 2f, w / 2f))
        drawLine(
            color = HomeColors.background,
            start = Offset(w * 0.26f, w * 0.52f),
            end = Offset(w * 0.44f, w * 0.68f),
            strokeWidth = w * 0.11f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = HomeColors.background,
            start = Offset(w * 0.44f, w * 0.68f),
            end = Offset(w * 0.74f, w * 0.34f),
            strokeWidth = w * 0.11f,
            cap = StrokeCap.Round,
        )
    }
}

private val ICON_STROKE_FRACTION = 0.075f

private fun stroke(w: Float) = Stroke(width = w * ICON_STROKE_FRACTION, cap = StrokeCap.Round)

@Composable
private fun MonitorIcon(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = HomeColors.title,
            topLeft = Offset(w * 0.06f, h * 0.14f),
            size = Size(w * 0.88f, h * 0.54f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.10f, w * 0.10f),
            style = stroke(w),
        )
        drawLine(
            color = HomeColors.title,
            start = Offset(w * 0.50f, h * 0.76f),
            end = Offset(w * 0.50f, h * 0.88f),
            strokeWidth = w * ICON_STROKE_FRACTION,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = HomeColors.title,
            start = Offset(w * 0.30f, h * 0.90f),
            end = Offset(w * 0.70f, h * 0.90f),
            strokeWidth = w * ICON_STROKE_FRACTION,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun PhoneIcon(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = HomeColors.title,
            topLeft = Offset(w * 0.27f, h * 0.05f),
            size = Size(w * 0.46f, h * 0.90f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f, w * 0.12f),
            style = stroke(w),
        )
        drawLine(
            color = HomeColors.title,
            start = Offset(w * 0.42f, h * 0.17f),
            end = Offset(w * 0.58f, h * 0.17f),
            strokeWidth = w * ICON_STROKE_FRACTION,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun GamepadIcon(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = HomeColors.title,
            topLeft = Offset(w * 0.02f, h * 0.28f),
            size = Size(w * 0.96f, h * 0.44f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.22f, h * 0.22f),
            style = stroke(w),
        )
        drawLine(
            color = HomeColors.title,
            start = Offset(w * 0.27f, h * 0.37f),
            end = Offset(w * 0.27f, h * 0.63f),
            strokeWidth = w * ICON_STROKE_FRACTION,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = HomeColors.title,
            start = Offset(w * 0.14f, h * 0.50f),
            end = Offset(w * 0.40f, h * 0.50f),
            strokeWidth = w * ICON_STROKE_FRACTION,
            cap = StrokeCap.Round,
        )
        drawCircle(HomeColors.title, radius = w * 0.045f, center = Offset(w * 0.66f, h * 0.43f))
        drawCircle(HomeColors.title, radius = w * 0.045f, center = Offset(w * 0.79f, h * 0.57f))
    }
}

@Composable
private fun TouchpadIcon(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = HomeColors.title,
            topLeft = Offset(w * 0.29f, h * 0.06f),
            size = Size(w * 0.42f, h * 0.88f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.13f, w * 0.13f),
            style = stroke(w),
        )
        drawCircle(HomeColors.title, radius = w * 0.055f, center = Offset(w * 0.50f, h * 0.34f))
        drawLine(
            color = HomeColors.title,
            start = Offset(w * 0.40f, h * 0.66f),
            end = Offset(w * 0.60f, h * 0.66f),
            strokeWidth = w * ICON_STROKE_FRACTION,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun KeyboardIcon(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = HomeColors.title,
            topLeft = Offset(w * 0.04f, h * 0.22f),
            size = Size(w * 0.92f, h * 0.56f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.09f, w * 0.09f),
            style = stroke(w),
        )
        val keyW = w * 0.115f
        val keyH = h * 0.085f
        val gap = w * 0.075f
        val startX = w * 0.5f - (keyW * 3 + gap * 2) / 2f
        val startY = h * 0.40f
        for (row in 0..1) {
            for (col in 0..2) {
                drawRoundRect(
                    color = HomeColors.title,
                    topLeft = Offset(startX + col * (keyW + gap), startY + row * (keyH + gap * 0.75f)),
                    size = Size(keyW, keyH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.025f, w * 0.025f),
                )
            }
        }
    }
}

/** Home's palette, matching the mockup: near-black blues, steel-blue accent. */
private object HomeColors {
    val background = Color(0xFF141519)
    val card = Color(0xFF1C202A)
    val track = Color(0xFF2A303C)
    val chip = Color(0xFF242B38)
    val title = Color(0xFFE8EAF0)
    val body = Color(0xFFC7CDD8)
    val accent = Color(0xFF82B4EA)
    val accentContainer = Color(0xFF1E3450)
}
