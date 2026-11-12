package com.hidgamepad

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The "report a problem" panel.
 *
 * The user types what went wrong and presses one button. The device and screen details are
 * attached to every report automatically and are deliberately **not** shown — the point of
 * them is that the person filing the report should not have to know or care what they are.
 *
 * The panel adapts to the window it is given: on a compact screen the content fills the width,
 * on a tablet or a wide window it is capped and centred so the text field does not stretch
 * across a desk. The description field grows with the height it is given.
 *
 * It is a **modal** surface: it consumes every touch that reaches it, so a stray tap cannot
 * move the on-screen stick underneath it and send the host phantom input.
 *
 * @param knownTopic the topic the app already knows, or null when there is none and the user
 *   has to supply one. The topic field is only shown when it is null.
 * @param scope the scope the send runs in. It is the caller's, not the panel's, so a report that
 *   is already on the wire is not cancelled by closing the panel.
 */
@Composable
fun ReportProblemPanel(
    onClose: () -> Unit,
    controllerMode: ControllerMode,
    hostType: HostType,
    hidState: String,
    currentScreenLabel: String,
    knownTopic: String?,
    tiltOn: Boolean,
    scope: CoroutineScope,
    onTopicSaved: (String) -> Unit,
) {
    val context = LocalContext.current

    var description by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf(knownTopic ?: "") }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }

    fun send() {
        val useTopic = (knownTopic ?: topic).trim()
        if (sending || description.isBlank()) return
        sending = true
        result = ""
        scope.launch {
            val activity = context.findActivity()
            val details = activity?.let {
                collectDeviceScreenInfo(it).toDetailsBlock(
                    screenLabel = currentScreenLabel,
                    mode = controllerMode,
                    hostType = hostType,
                    hidStatus = hidState,
                    tiltOn = tiltOn,
                )
            }
            when (val outcome = ProblemReporter.send(useTopic, REPORT_TITLE, description, details)) {
                is ReportOutcome.Sent -> {
                    result = "Report sent. Thank you."
                    failed = false
                    // Only a topic the user typed is worth remembering; the build-time one is
                    // already in the app and has no business being copied into storage.
                    if (knownTopic == null) onTopicSaved(useTopic)
                }
                is ReportOutcome.Failed -> {
                    result = outcome.reason
                    failed = true
                }
            }
            sending = false
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF141519))
            .safeDrawingPadding()
            .padding(16.dp)
            // Modal: swallow anything that gets past the fields and the buttons, so the
            // controller underneath does not see it.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        // A wide window gets a capped, centred column; a compact one gets everything.
        val contentWidth: Dp = if (maxWidth >= 600.dp) 640.dp else maxWidth
        val descriptionLines: Int = if (maxWidth >= 600.dp) 6 else 3

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                Modifier
                    .widthIn(max = contentWidth)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Report a problem",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE8EAF0),
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onClose, modifier = Modifier.height(48.dp)) { Text("Close") }
                }

                Text(
                    text = "Describe what went wrong and it will be sent straight to the app's " +
                        "maintainer, along with this device's screen details.",
                    fontSize = 12.sp,
                    color = Color(0xFF9AA1B2),
                )

                TextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("What went wrong?") },
                    minLines = descriptionLines,
                    maxLines = descriptionLines + 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                )

                if (knownTopic == null) {
                    TextField(
                        value = topic,
                        onValueChange = { topic = it },
                        label = { Text("Report topic") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "The topic is the name the maintainer gave you. Without it the " +
                            "report cannot be sent.",
                        fontSize = 11.sp,
                        color = Color(0xFF9AA1B2),
                    )
                }

                Button(
                    onClick = { send() },
                    enabled = !sending && description.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text(if (sending) "Sending…" else "Send report")
                }

                if (result.isNotEmpty()) {
                    Text(
                        text = result,
                        fontSize = 12.sp,
                        color = if (failed) Color(0xFFE06A5A) else Color(0xFF9BB08D),
                    )
                }
            }
        }
    }
}

/** Unwraps a [Context] to the activity that owns it, when there is one. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
