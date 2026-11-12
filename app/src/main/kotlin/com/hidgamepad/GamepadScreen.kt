package com.hidgamepad

import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/** Mutable controller model; every field is a Compose state so changes retrigger report pushes. */
private class ControllerModel {
    var touchLx by mutableStateOf(0f)
    var tiltLx by mutableStateOf(0f)
    var ly by mutableStateOf(0f)
    var rx by mutableStateOf(0f)
    var ry by mutableStateOf(0f)
    var lt by mutableStateOf(0f)
    var rt by mutableStateOf(0f)
    var hat by mutableStateOf(GamepadState.HAT_NEUTRAL)
    var buttons by mutableStateOf(0)

    fun down(bit: Int) {
        buttons = buttons or (1 shl bit)
    }

    fun up(bit: Int) {
        buttons = buttons and (1 shl bit).inv()
    }
}

private val RGB_DECK_COLOR = Color(0xFF06070A)

/** One-line text for the top bar given the manager status. */
fun statusLabel(status: HidGamepadManager.Status): String = when (status) {
    is HidGamepadManager.Status.Unsupported -> "Bluetooth not available on this phone"
    is HidGamepadManager.Status.BluetoothOff -> "Bluetooth is off — turn it on"
    is HidGamepadManager.Status.Unregistered -> "Starting HID service…"
    is HidGamepadManager.Status.Registered -> "Ready — waiting for a host to connect"
    is HidGamepadManager.Status.Connecting -> "Connecting to ${status.deviceName}…"
    is HidGamepadManager.Status.Connected -> "Connected: ${status.deviceName}"
    is HidGamepadManager.Status.ConnectFailed ->
        "Couldn't start connection to ${status.deviceName} — retry from Devices"
    is HidGamepadManager.Status.ConnectTimedOut ->
        "Couldn't reach ${status.deviceName} — open Devices to retry"
}

private fun deviceLabel(device: BluetoothDevice): String = try {
    device.name ?: device.address
} catch (_: SecurityException) {
    device.address
}

private fun safeDeviceAddress(device: BluetoothDevice): String = try {
    device.address
} catch (_: SecurityException) {
    ""
}

fun modeLabel(mode: ControllerMode): String = when (mode) {
    ControllerMode.GAMEPAD -> "Gamepad"
    ControllerMode.MOUSE -> "Mouse"
    ControllerMode.KEYBOARD -> "Keyboard"
}

private fun rgbEffectLabel(effect: RgbEffect): String = when (effect) {
    RgbEffect.STATIC -> "Static"
    RgbEffect.BREATHING -> "Breathing"
    RgbEffect.COLOR_CYCLE -> "Color cycle"
    RgbEffect.RAINBOW -> "Rainbow"
    RgbEffect.WAVE -> "Wave"
    RgbEffect.REACTIVE -> "Reactive"
    RgbEffect.RIPPLE -> "Ripple"
    RgbEffect.AURORA -> "Aurora"
    RgbEffect.FLICKER -> "Flicker"
    RgbEffect.STARLIGHT -> "Starlight"
    RgbEffect.COMET -> "Comet"
    RgbEffect.FIRE -> "Fire"
    RgbEffect.METEOR -> "Meteor"
    RgbEffect.SNAKE -> "Snake"
    RgbEffect.SCANNER -> "Scanner"
    RgbEffect.ARROW -> "Arrows"
}

private fun rgbFlowLabel(flow: RgbFlow): String = when (flow) {
    RgbFlow.LEFT_TO_RIGHT -> "Left to right"
    RgbFlow.RIGHT_TO_LEFT -> "Right to left"
}

fun hostTitle(hostType: HostType): String = when (hostType) {
    HostType.WINDOWS -> "Windows PC"
    HostType.ANDROID -> "Android phone or tablet"
}

/** Names the HID collections a profile registers, so the choice is legible. */
fun profileLabel(profile: HidProfile): String = when (profile) {
    HidProfile.COMPOSITE -> "Keyboard + mouse + gamepad"
    HidProfile.GAMEPAD_ONLY -> "Gamepad only"
}


@Composable
fun GamepadScreen(
    statusText: String,
    hidState: String = "unknown",
    bondedDevices: () -> List<BluetoothDevice>,
    onConnect: (BluetoothDevice) -> Boolean,
    onDiscoverable: () -> Unit,
    hapticsEnabled: Boolean,
    hapticsStrength: Float,
    onHapticsChange: (Boolean) -> Unit,
    onHapticsStrengthChange: (Float) -> Unit,
    onGamepadState: (GamepadState) -> Unit,
    onKeyboardState: (KeyboardState) -> Unit,
    onMouseState: (MouseState) -> Unit,
    onConsumerState: (ConsumerState) -> Unit,
    onReleaseAllInputs: () -> Unit,
    registeredProfile: HidProfile = HidProfile.COMPOSITE,
    onProfileChanged: (HidProfile) -> Unit = {},
    onConfirmRepair: () -> Unit = {},
    hidEnabled: Boolean = true,
    onHidEnabledChange: (Boolean) -> Unit = {},
    /** True while the host has its Caps Lock LED on (from the host's LED output report). */
    capsLockLed: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences("hid_gamepad", Context.MODE_PRIVATE) }
    var hostType by remember { mutableStateOf(hostTypeFromStorage(prefs.getString("host_type", null))) }
    var controllerMode by remember { mutableStateOf(controllerModeFromStorage(prefs.getString("controller_mode", null))) }
    var mouseSensitivity by remember { mutableStateOf(prefs.getFloat("mouse_sensitivity", 1f)) }
    var needsSetup by remember {
        mutableStateOf(needsOnboarding(prefs.getBoolean("setup_complete", false)))
    }
    var onboardingStep by remember { mutableStateOf(TutorialStep.start) }
    // The report's send runs in this scope, not the panel's, so closing the panel while a
    // report is on the wire does not cancel it.
    val reportScope = rememberCoroutineScope()
    if (needsSetup) {
        TutorialPanel(
            hostType = hostType,
            mode = controllerMode,
            bondedDevices = bondedDevices,
            onDiscoverable = onDiscoverable,
            onHostType = { newType ->
                hostType = newType
                prefs.edit().putString("host_type", newType.name).apply()
                // The connection choice also picks the descriptor the host will pair with.
                onProfileChanged(profileForHostType(newType))
            },
            onMode = { newMode ->
                controllerMode = newMode
                prefs.edit().putString("controller_mode", newMode.name).apply()
            },
            onConnect = { device ->
                val ok = onConnect(device)
                if (ok) {
                    prefs.edit()
                        .putString("last_host_address", safeDeviceAddress(device))
                        .putString("last_host_name", deviceLabel(device))
                        .apply()
                }
                ok
            },
            onStep = { onboardingStep = it },
            step = onboardingStep,
            onFinish = {
                prefs.edit().putBoolean("setup_complete", true).apply()
                needsSetup = false
            },
        )
        return
    }
    var tiltOn by remember { mutableStateOf(prefs.getBoolean("tilt_on", false)) }
    var tiltSensitivity by remember { mutableStateOf(prefs.getFloat("tilt_sensitivity", 1f)) }
    var tiltDeadzone by remember { mutableStateOf(prefs.getFloat("tilt_deadzone", 0.05f)) }
    var tiltMenuOpen by remember { mutableStateOf(false) }

    val model = remember { ControllerModel() }
    val state = GamepadState(
        resolveLeftStickX(tiltOn, model.touchLx, model.tiltLx),
        model.ly,
        model.rx,
        model.ry,
        model.lt,
        model.rt,
        model.buttons,
        model.hat,
    )
    LaunchedEffect(state) { onGamepadState(state) }

    val tilt = remember { TiltController(context) { steer -> model.tiltLx = steer } }

    LaunchedEffect(tiltSensitivity, tiltDeadzone) {
        tilt.setTuning(TiltTuning(deadzone = tiltDeadzone, sensitivity = tiltSensitivity))
    }
    LaunchedEffect(tiltOn, controllerMode) {
        if (tiltOn && controllerMode == ControllerMode.GAMEPAD) tilt.start() else tilt.stop()
    }
    DisposableEffect(lifecycleOwner, tiltOn) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> tilt.onActivityPause() // listener stops, emits center
                Lifecycle.Event.ON_RESUME -> if (tiltOn && controllerMode == ControllerMode.GAMEPAD) tilt.start()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            tilt.stop()
        }
    }

    var keyboardModifiers by remember { mutableStateOf(0) }
    var keyboardKeys by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var fnHeld by remember { mutableStateOf(false) }
    // Keys pressed while Fn was held are reported as function keys for as long as they stay
    // held, like a laptop's Fn layer. Releasing Fn does not re-map an already-held key, and it
    // never emits the raw digit underneath a held Fn key.
    var fnConsumed by remember { mutableStateOf(emptySet<Int>()) }
    val reportedKeys = keyboardKeys.map { if (it in fnConsumed) fnUsageFor(it) ?: it else it }.toSet()
    val keyboardState = KeyboardState(keyboardModifiers, reportedKeys)
    LaunchedEffect(keyboardState) { onKeyboardState(keyboardState) }

    // The RGB backlight: a phone-only visual effect for the on-screen keyboard. fn+L toggles
    // it, fn+Up/Down adjusts the brightness, and the Settings panel has the full controls.
    var rgbConfig by remember {
        mutableStateOf(
            RgbConfig(
                enabled = prefs.getBoolean("rgb_on", false),
                effect = runCatching { RgbEffect.valueOf(prefs.getString("rgb_effect", "RAINBOW") ?: "RAINBOW") }
                    .getOrDefault(RgbEffect.RAINBOW),
                flow = runCatching { RgbFlow.valueOf(prefs.getString("rgb_flow", "LEFT_TO_RIGHT") ?: "LEFT_TO_RIGHT") }
                    .getOrDefault(RgbFlow.LEFT_TO_RIGHT),
                speed = prefs.getFloat("rgb_speed", 1f),
                staticColor = prefs.getInt("rgb_color", 0),
                brightness = prefs.getFloat("rgb_brightness", 1f),
            ),
        )
    }

    fun saveRgbConfig(newConfig: RgbConfig) {
        rgbConfig = newConfig
        prefs.edit()
            .putBoolean("rgb_on", newConfig.enabled)
            .putString("rgb_effect", newConfig.effect.name)
            .putString("rgb_flow", newConfig.flow.name)
            .putFloat("rgb_speed", newConfig.speed)
            .putInt("rgb_color", newConfig.staticColor)
            .putFloat("rgb_brightness", newConfig.brightness)
            .apply()
    }

    fun resetLocalInputs() {
        model.touchLx = 0f
        model.tiltLx = 0f
        model.ly = 0f
        model.rx = 0f
        model.ry = 0f
        model.lt = 0f
        model.rt = 0f
        model.hat = GamepadState.HAT_NEUTRAL
        model.buttons = 0
        keyboardModifiers = 0
        keyboardKeys = emptySet()
        fnHeld = false
        fnConsumed = emptySet()
    }

    fun switchMode(newMode: ControllerMode) {
        if (newMode == controllerMode) return
        onReleaseAllInputs()
        resetLocalInputs()
        controllerMode = newMode
        prefs.edit().putString("controller_mode", newMode.name).apply()
    }

    var settingsOpen by remember { mutableStateOf(false) }
    var reportOpen by remember { mutableStateOf(false) }

    var devicesOpen by remember { mutableStateOf(false) }
    var deviceList by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    LaunchedEffect(devicesOpen) {
        if (devicesOpen) deviceList = bondedDevices()
    }

    // safeDrawing includes display cutouts and system gesture areas. Apply it before the
    // fixed 16dp playing-field margin so every 48dp target remains reachable.
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .background(PadColors.background)
            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
    ) {
        // --- top bar: status + settings controls ---
        PadTopBar(
            statusText = statusText,
            dotColor = when {
                statusText.startsWith("Connected") -> PadColors.connected
                statusText.startsWith("Connecting") -> PadColors.pending
                else -> PadColors.secondary
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            var modeMenuOpen by remember { mutableStateOf(false) }
            Box {
                PadPill(modeLabel(controllerMode) + " ▼", accent = true, onClick = { modeMenuOpen = !modeMenuOpen })
                DropdownMenu(expanded = modeMenuOpen, onDismissRequest = { modeMenuOpen = false }) {
                    for (mode in ControllerMode.entries) {
                        DropdownMenuItem(
                            text = { Text(modeLabel(mode)) },
                            onClick = { modeMenuOpen = false; switchMode(mode) },
                        )
                    }
                }
            }
            Box {
                PadPill("Devices", accent = false, onClick = { devicesOpen = !devicesOpen })
                DropdownMenu(expanded = devicesOpen, onDismissRequest = { devicesOpen = false }) {
                    val list = deviceList
                    if (list.isEmpty()) {
                        DropdownMenuItem(text = { Text("No paired hosts yet") }, onClick = { devicesOpen = false })
                    }
                    for (d in list) {
                        DropdownMenuItem(
                            text = { Text(deviceLabel(d)) },
                            onClick = {
                                devicesOpen = false
                                if (onConnect(d)) {
                                    val label = deviceLabel(d)
                                    prefs.edit()
                                        .putString("last_host_address", safeDeviceAddress(d))
                                        .putString("last_host_name", label)
                                        .apply()
                                    // The gesture mapping follows the host you connect to:
                                    // a tablet must not receive Windows chords.
                                    val detected = hostTypeForDeviceName(label, hostType)
                                    if (detected != hostType) {
                                        hostType = detected
                                        prefs.edit().putString("host_type", detected.name).apply()
                                        onProfileChanged(profileForHostType(detected))
                                    }
                                }
                            },
                        )
                    }
                }
            }
            PadPill("Settings", accent = false, onClick = { settingsOpen = true })
        }
        when (controllerMode) {
            ControllerMode.GAMEPAD -> {
                Text("Gamepad mode", fontSize = 12.sp, color = PadColors.secondary)
                if (tiltOn) {
                    Text("Tilt controls left-stick X. Touch stick controls Y.", fontSize = 11.sp, color = PadColors.secondary)
                }
                GamepadStage(
                    model = model,
                    hapticsEnabled = hapticsEnabled,
                    hapticsStrength = hapticsStrength,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            ControllerMode.MOUSE -> {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GestureTouchpad(
                        sensitivity = mouseSensitivity,
                        hostType = hostType,
                        hostConnected = statusText.startsWith("Connected"),
                        hapticsEnabled = hapticsEnabled,
                        hapticsStrength = hapticsStrength,
                        onMouseState = onMouseState,
                        onKeyboardState = onKeyboardState,
                        onConsumerState = onConsumerState,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Tap = click · 2-finger tap = right click · 2-finger drag = scroll · " +
                            "pinch = zoom · double-tap then drag = drag · 3-finger tap/swipe",
                        fontSize = 10.sp,
                        color = PadColors.secondary,
                        maxLines = 2,
                    )
                }
            }
            ControllerMode.KEYBOARD -> {
                KeyboardStage(
                    modifiers = keyboardModifiers,
                    pressedKeys = keyboardKeys,
                    capsLockLed = capsLockLed,
                    fnHeld = fnHeld,
                    rgb = rgbConfig,
                    hapticsEnabled = hapticsEnabled,
                    hapticsStrength = hapticsStrength,
                    onModifier = { bit, down ->
                        keyboardModifiers = if (down) keyboardModifiers or bit else keyboardModifiers and bit.inv()
                    },
                    onKey = { usage, down ->
                        // Fn+L toggles the RGB backlight; Fn+Up/Down steps the brightness.
                        // Like a laptop, these are consumed and never reach the host.
                        when {
                            fnHeld && down && usage == KeyUsage.L ->
                                saveRgbConfig(rgbConfig.copy(enabled = !rgbConfig.enabled))
                            fnHeld && down && (usage == KeyUsage.UP || usage == KeyUsage.DOWN) -> {
                                val delta = if (usage == KeyUsage.UP) 0.1f else -0.1f
                                saveRgbConfig(rgbConfig.copy(brightness = adjustBrightness(rgbConfig.brightness, delta)))
                            }
                            else -> {
                                keyboardKeys = if (down) keyboardKeys + usage else keyboardKeys - usage
                                if (down) {
                                    if (fnHeld && fnUsageFor(usage) != null) fnConsumed += usage
                                } else {
                                    fnConsumed -= usage
                                }
                            }
                        }
                    },
                    onFn = { fnHeld = it },
                    onRgb = { saveRgbConfig(it) },
                )
            }
        }
    }
    if (settingsOpen && !reportOpen) {
            SettingsPanel(
                statusText = statusText,
                hostType = hostType,
                controllerMode = controllerMode,
                hapticsEnabled = hapticsEnabled,
                hapticsStrength = hapticsStrength,
                tiltOn = tiltOn,
                tiltSensitivity = tiltSensitivity,
                tiltDeadzone = tiltDeadzone,
                mouseSensitivity = mouseSensitivity,
                sensorDescription = tilt.sensorDescription,
                onClose = { settingsOpen = false },
                onReconnect = {
                    val saved = prefs.getString("last_host_address", null)
                    val device = bondedDevices().firstOrNull { safeDeviceAddress(it) == saved }
                    if (device != null) onConnect(device)
                },
                onChangeDevice = { settingsOpen = false; devicesOpen = true },
                onDiscoverable = onDiscoverable,
                onRunSetup = {
                    // Replaying the tutorial starts at the beginning, not halfway through.
                    settingsOpen = false
                    onboardingStep = TutorialStep.start
                    needsSetup = true
                },
                onReportProblem = { settingsOpen = false; reportOpen = true },
                onMode = { switchMode(it) },
                onHostType = { newType ->
                    hostType = newType
                    prefs.edit().putString("host_type", newType.name).apply()
                    // A different host means a different descriptor, so the manager re-registers.
                    onProfileChanged(profileForHostType(newType))
                },
                registeredProfile = registeredProfile,
                onConfirmRepair = onConfirmRepair,
                hidEnabled = hidEnabled,
                onHidEnabledChange = onHidEnabledChange,
                onHaptics = onHapticsChange,
                onHapticsStrength = onHapticsStrengthChange,
                onTilt = { enabled ->
                    tiltOn = enabled
                    prefs.edit().putBoolean("tilt_on", enabled).apply()
                },
                onTiltSensitivity = {
                    tiltSensitivity = it
                    prefs.edit().putFloat("tilt_sensitivity", it).apply()
                },
                onTiltDeadzone = {
                    tiltDeadzone = it
                    prefs.edit().putFloat("tilt_deadzone", it).apply()
                },
                onTiltZero = { tilt.rezero() },
                onMouseSensitivity = {
                    mouseSensitivity = it
                    prefs.edit().putFloat("mouse_sensitivity", it).apply()
                },
                onClearInputs = { resetLocalInputs(); onReleaseAllInputs() },
                rgbConfig = rgbConfig,
                onRgb = { saveRgbConfig(it) },
            )
        }
    }
    if (reportOpen) {
        ReportProblemPanel(
            // Back to Settings: that is where the report was filed from.
            onClose = { reportOpen = false; settingsOpen = true },
            controllerMode = controllerMode,
            hostType = hostType,
            // The state word, not the status line: the status line names the host, and a
            // report travels to a public server.
            hidState = hidState,
            currentScreenLabel = "Settings",
            knownTopic = resolveReportTopic(
                BuildConfig.NTFY_TOPIC,
                prefs.getString("report_topic", "") ?: "",
            ),
            tiltOn = tiltOn,
            scope = reportScope,
            onTopicSaved = { topic -> prefs.edit().putString("report_topic", topic).apply() },
        )
    }
}

/**
 * The app's title, the connection state and the row of outlined action pills.
 *
 * The pills are supplied by the caller so this bar stays a pure piece of chrome.
 */
@Composable
private fun PadTopBar(
    statusText: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_logo),
            contentDescription = null,
            // Unspecified, so the mark keeps its own colour: Icon would otherwise tint it
            // with the content colour.
            tint = Color.Unspecified,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text("HID Controller", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = PadColors.text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(dotColor, CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    statusText,
                    fontSize = 11.sp,
                    color = PadColors.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/** An outlined pill button: the mode dropdown, Devices and Settings. */
@Composable
private fun PadPill(
    text: String,
    accent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, if (accent) PadColors.accent else PadColors.border),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (accent) PadColors.accent else PadColors.text,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * The gamepad playing field: the shoulders, the centre pills, the D-pad, both sticks and
 * the face-button diamond.
 *
 * Layout only. Every control keeps the callbacks it had before, and the sizes come from the
 * space that is actually available, so nothing overlaps on a small window.
 */
@Composable
private fun GamepadStage(
    model: ControllerModel,
    hapticsEnabled: Boolean,
    hapticsStrength: Float,
    modifier: Modifier = Modifier,
) {
    val shoulderWidth = 108.dp
    val triggerHeight = 44.dp
    val bumperHeight = 30.dp
    val shoulderGap = 6.dp
    val shoulderBlock = triggerHeight + shoulderGap + bumperHeight
    val faceSize = 64.dp
    val abxy = faceSize * 3f
    // The widest the D-pad may get, so the sticks always have room.
    val maxDpad = 150.dp
    // The field spreads the D-pad, the two sticks and the face diamond with equal gaps,
    // never closer together than this.
    val minFieldGap = 24.dp
    val centrePillWidth = 84.dp
    val centrePillHeight = 30.dp
    val fieldGap = 12.dp

    BoxWithConstraints(modifier.fillMaxSize()) {
        val stageW = maxWidth
        val stageH = maxHeight
        // The stick size is bounded by both axes: by the height left under the shoulders, and
        // by the width the D-pad, the three gaps and the face diamond leave behind.
        val stickByHeight = stageH - shoulderBlock - fieldGap
        val stickByWidth = (stageW - maxDpad - abxy - minFieldGap * 3f) / 2f
        val stick = minOf(stickByWidth, stickByHeight).coerceIn(56.dp, 190.dp)
        val dpad = (stick * 0.72f).coerceIn(96.dp, maxDpad)

        Column(Modifier.fillMaxSize()) {
            // Shoulders and the centre pills.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column {
                    PedalControl("L2", shoulderWidth, triggerHeight, { model.lt = it })
                    Spacer(Modifier.height(shoulderGap))
                    PadRectButton(
                        label = "L1",
                        width = shoulderWidth,
                        height = bumperHeight,
                        color = PadColors.secondary,
                        hapticsEnabled = hapticsEnabled,
                        hapticsStrength = hapticsStrength,
                        onDown = { model.down(Btn.L1) },
                        onUp = { model.up(Btn.L1) },
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PadRectButton(
                        label = "SELECT",
                        width = centrePillWidth,
                        height = centrePillHeight,
                        color = PadColors.secondary,
                        hapticsEnabled = hapticsEnabled,
                        hapticsStrength = hapticsStrength,
                        onDown = { model.down(Btn.SELECT) },
                        onUp = { model.up(Btn.SELECT) },
                    )
                    PadRectButton(
                        label = "START",
                        width = centrePillWidth,
                        height = centrePillHeight,
                        color = PadColors.secondary,
                        hapticsEnabled = hapticsEnabled,
                        hapticsStrength = hapticsStrength,
                        onDown = { model.down(Btn.START) },
                        onUp = { model.up(Btn.START) },
                    )
                }
                Spacer(Modifier.weight(1f))
                Column {
                    PedalControl("R2", shoulderWidth, triggerHeight, { model.rt = it })
                    Spacer(Modifier.height(shoulderGap))
                    PadRectButton(
                        label = "R1",
                        width = shoulderWidth,
                        height = bumperHeight,
                        color = PadColors.secondary,
                        hapticsEnabled = hapticsEnabled,
                        hapticsStrength = hapticsStrength,
                        onDown = { model.down(Btn.R1) },
                        onUp = { model.up(Btn.R1) },
                    )
                }
            }
            Spacer(Modifier.height(fieldGap))
            // The playing field. Equal gaps between the four controls, so the two sticks sit
            // close together instead of leaving a hole in the middle of the screen.
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                DPadControl(dpad, onHat = { model.hat = it })
                JoystickControl(stick) { x, y -> model.touchLx = x; model.ly = y }
                JoystickControl(stick) { x, y -> model.rx = x; model.ry = y }
                Box(Modifier.size(abxy)) {
                    GamepadButton("Y", faceSize, PadColors.faceY, hapticsEnabled, hapticsStrength, { model.down(Btn.Y) }, { model.up(Btn.Y) }, Modifier.align(Alignment.TopCenter))
                    GamepadButton("X", faceSize, PadColors.faceX, hapticsEnabled, hapticsStrength, { model.down(Btn.X) }, { model.up(Btn.X) }, Modifier.align(Alignment.CenterStart))
                    GamepadButton("B", faceSize, PadColors.faceB, hapticsEnabled, hapticsStrength, { model.down(Btn.B) }, { model.up(Btn.B) }, Modifier.align(Alignment.CenterEnd))
                    GamepadButton("A", faceSize, PadColors.faceA, hapticsEnabled, hapticsStrength, { model.down(Btn.A) }, { model.up(Btn.A) }, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}

@Preview(name = "Gamepad screen", widthDp = 857, heightDp = 384, showBackground = true, backgroundColor = 0xFF0F1115)
@Composable
private fun GamepadScreenPreview() {
    val model = remember { ControllerModel() }
    Column(
        Modifier
            .fillMaxSize()
            .background(PadColors.background)
            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
    ) {
        PadTopBar(
            statusText = "Connected: Windows PC",
            dotColor = PadColors.connected,
            modifier = Modifier.fillMaxWidth(),
        ) {
            PadPill("Gamepad ▼", accent = true, onClick = {})
            PadPill("Devices", accent = false, onClick = {})
            PadPill("Settings", accent = false, onClick = {})
        }
        Spacer(Modifier.height(8.dp))
        GamepadStage(
            model = model,
            hapticsEnabled = true,
            hapticsStrength = 1f,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(name = "Gamepad field", widthDp = 640, heightDp = 300, showBackground = true, backgroundColor = 0xFF0F1115)
@Composable
private fun GamepadStagePreview() {
    val model = remember { ControllerModel() }
    Column(
        Modifier
            .fillMaxSize()
            .background(PadColors.background)
            .padding(16.dp),
    ) {
        GamepadStage(
            model = model,
            hapticsEnabled = true,
            hapticsStrength = 1f,
            modifier = Modifier.fillMaxSize(),
        )
    }
}


@Composable
private fun SettingsPanel(
    statusText: String,
    hostType: HostType,
    controllerMode: ControllerMode,
    hapticsEnabled: Boolean,
    hapticsStrength: Float,
    tiltOn: Boolean,
    tiltSensitivity: Float,
    tiltDeadzone: Float,
    mouseSensitivity: Float,
    sensorDescription: String,
    onClose: () -> Unit,
    onReconnect: () -> Unit,
    onChangeDevice: () -> Unit,
    onDiscoverable: () -> Unit,
    onRunSetup: () -> Unit,
    onReportProblem: () -> Unit,
    onMode: (ControllerMode) -> Unit,
    onHostType: (HostType) -> Unit,
    registeredProfile: HidProfile,
    onConfirmRepair: () -> Unit,
    hidEnabled: Boolean,
    onHidEnabledChange: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onHapticsStrength: (Float) -> Unit,
    onTilt: (Boolean) -> Unit,
    onTiltSensitivity: (Float) -> Unit,
    onTiltDeadzone: (Float) -> Unit,
    onTiltZero: () -> Unit,
    onMouseSensitivity: (Float) -> Unit,
    onClearInputs: () -> Unit,
    rgbConfig: RgbConfig,
    onRgb: (RgbConfig) -> Unit,
) {
    val profile = profileForHostType(hostType)
    Box(Modifier.fillMaxSize().background(PadColors.background).safeDrawingPadding()) {
    Column(
        // Capped and centred on a wide screen so the settings do not stretch across a
        // tablet; a compact screen gets the whole width.
        Modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .align(Alignment.TopCenter)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PadColors.text, modifier = Modifier.weight(1f))
            Button(onClick = onClose, modifier = Modifier.height(48.dp)) { Text("Close") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "HID controller: ${if (hidEnabled) "ON" else "OFF"}",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = PadColors.text,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = hidEnabled, onCheckedChange = onHidEnabledChange)
        }
        // Reporting a problem sits directly under the master switch: it is the one action a
        // user reaches for when something is not working, and it should not be buried at the
        // bottom of a long sheet.
        Button(
            onClick = onReportProblem,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("Report a problem") }
        Text(
            "Off releases every input, unregisters the HID device and removes the notification. " +
                "Phone Bluetooth and other Bluetooth connections are unaffected.",
            fontSize = 11.sp,
            color = PadColors.secondary,
        )
        Text("Connection", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PadColors.text)
        Text("Connected host: $statusText", fontSize = 12.sp, color = PadColors.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onReconnect, modifier = Modifier.height(48.dp)) { Text("Reconnect") }
            Button(onClick = onChangeDevice, modifier = Modifier.height(48.dp)) { Text("Change device") }
            Button(onClick = onDiscoverable, modifier = Modifier.height(48.dp)) { Text("Make discoverable") }
        }
        Button(onClick = onRunSetup, modifier = Modifier.height(48.dp)) { Text("Getting started") }
        Text("Controls", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PadColors.text)
        Text("Mode: ${modeLabel(controllerMode)}", fontSize = 12.sp, color = PadColors.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (mode in ControllerMode.entries) {
                Button(onClick = { onMode(mode) }, modifier = Modifier.height(48.dp)) { Text(modeLabel(mode)) }
            }
        }
        Text("Host type: ${hostTitle(hostType)}", fontSize = 12.sp, color = PadColors.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (type in HostType.entries) {
                Button(onClick = { onHostType(type) }, modifier = Modifier.height(48.dp)) { Text(hostTitle(type)) }
            }
        }
        Text("Decides what three-finger swipes send. Change it if the swipes do nothing.", fontSize = 11.sp, color = PadColors.secondary)
        Text(
            "It also picks the HID descriptor the host needs: Android gets ${profileLabel(HidProfile.COMPOSITE)}, " +
                "Windows gets ${profileLabel(HidProfile.GAMEPAD_ONLY)}. Changing it needs a re-pair, because " +
                "the host caches the descriptor at pairing time.",
            fontSize = 11.sp,
            color = PadColors.pending,
        )
        if (profileNeedsRepair(current = profile, registered = registeredProfile)) {
            Text(
                "Re-pair needed: the host is still using the ${profileLabel(registeredProfile)} " +
                    "descriptor. Remove the phone on the host, tap Make discoverable here, " +
                    "then add the phone on the host again.",
                fontSize = 11.sp,
                color = PadColors.faceB,
            )
            Button(onClick = onConfirmRepair, modifier = Modifier.height(48.dp)) {
                Text("I've re-paired the host")
            }
        }
        Button(onClick = { onHaptics(!hapticsEnabled) }, modifier = Modifier.height(48.dp)) {
            Text(if (hapticsEnabled) "Haptics ON" else "Haptics OFF")
        }
        Text("Haptics strength · ${"%.2f".format(hapticsStrength)}", fontSize = 12.sp, color = PadColors.secondary)
        Slider(value = hapticsStrength, onValueChange = onHapticsStrength, valueRange = 0.25f..1.5f, steps = 4, modifier = Modifier.width(220.dp))
        Button(onClick = { onTilt(!tiltOn) }, modifier = Modifier.height(48.dp)) { Text(if (tiltOn) "Tilt ON" else "Tilt OFF") }
        Text("Tilt sensitivity Ã—${"%.1f".format(tiltSensitivity)}", fontSize = 12.sp, color = PadColors.secondary)
        Slider(value = tiltSensitivity, onValueChange = onTiltSensitivity, valueRange = 0.5f..2.0f, steps = 9, modifier = Modifier.width(220.dp))
        Text("Tilt deadzone ${"%.2f".format(tiltDeadzone)}", fontSize = 12.sp, color = PadColors.secondary)
        Slider(value = tiltDeadzone, onValueChange = onTiltDeadzone, valueRange = 0f..0.3f, steps = 15, modifier = Modifier.width(220.dp))
        Text("Tilt owns left-stick X when on — touch stick controls Y only", fontSize = 11.sp, color = PadColors.secondary)
        Button(onClick = onTiltZero, modifier = Modifier.height(48.dp)) { Text("Calibrate / zero") }
        Text("Sensor: $sensorDescription", fontSize = 11.sp, color = PadColors.secondary)
        Text("Pointer & scroll speed Ã—${"%.2f".format(mouseSensitivity)}", fontSize = 12.sp, color = PadColors.secondary)
        Slider(value = mouseSensitivity, onValueChange = onMouseSensitivity, valueRange = 0.25f..2.0f, steps = 7, modifier = Modifier.width(220.dp))
        Button(onClick = onClearInputs, modifier = Modifier.height(48.dp)) { Text("Clear all held inputs") }
        Text("Information", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PadColors.text)
        Text("HID status: $statusText", fontSize = 12.sp, color = PadColors.secondary)
        Text("Help / pairing instructions", fontSize = 12.sp, color = PadColors.secondary)
        Text(pairInstructions(hostType), fontSize = 12.sp, color = PadColors.secondary)
        if (hostType == HostType.ANDROID) {
            Text("Android compatibility note: some Android phones do not support Bluetooth HID hosts.", fontSize = 12.sp, color = PadColors.secondary)
        }
        Text("Phone haptics are phone-local press feedback, not host-controlled game rumble.", fontSize = 12.sp, color = PadColors.secondary)
        // --- RGB backlight: a phone-only, purely visual effect ---
        Text("RGB backlight", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PadColors.text)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Backlight: ${if (rgbConfig.enabled) "ON" else "OFF"}",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = PadColors.text,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = rgbConfig.enabled,
                onCheckedChange = { enabled -> onRgb(rgbConfig.copy(enabled = enabled)) },
            )
        }
        Text("Four zones, phone-local. Fn+L toggles; Fn+Up and Fn+Down set the brightness.", fontSize = 11.sp, color = PadColors.secondary)
        Text("Effect: ${rgbEffectLabel(rgbConfig.effect)}", fontSize = 12.sp, color = PadColors.secondary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (effect in RgbEffect.entries) {
                PadPill(
                    rgbEffectLabel(effect),
                    accent = rgbConfig.effect == effect,
                    onClick = { onRgb(rgbConfig.copy(effect = effect)) },
                    modifier = Modifier.height(34.dp),
                )
            }
        }
        Text("Flow: ${rgbFlowLabel(rgbConfig.flow)}", fontSize = 12.sp, color = PadColors.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (flow in RgbFlow.entries) {
                Button(onClick = { onRgb(rgbConfig.copy(flow = flow)) }, modifier = Modifier.height(48.dp)) {
                    Text(rgbFlowLabel(flow))
                }
            }
        }
        Text("Speed x${"%.2f".format(rgbConfig.speed)}", fontSize = 12.sp, color = PadColors.secondary)
        Slider(value = rgbConfig.speed, onValueChange = { onRgb(rgbConfig.copy(speed = it)) }, valueRange = 0.25f..2f, steps = 6, modifier = Modifier.width(220.dp))
        Text("Brightness ${"%.0f".format(rgbConfig.brightness * 100)}%", fontSize = 12.sp, color = PadColors.secondary)
        Slider(value = rgbConfig.brightness, onValueChange = { onRgb(rgbConfig.copy(brightness = it)) }, valueRange = 0.1f..1f, steps = 8, modifier = Modifier.width(220.dp))
        Text("Colour", fontSize = 12.sp, color = PadColors.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((index, preset) in RGB_PRESETS.withIndex()) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.hsv(preset.hue, preset.sat, preset.value))
                        .border(
                            1.dp,
                            if (rgbConfig.staticColor == index) PadColors.accent else PadColors.border,
                            CircleShape,
                        )
                        .clickable(onClick = { onRgb(rgbConfig.copy(staticColor = index)) }),
                )
            }
        }
    }
    }
}

@Composable
private fun KeyboardStage(
    modifiers: Int,
    pressedKeys: Set<Int>,
    capsLockLed: Boolean,
    fnHeld: Boolean,
    rgb: RgbConfig,
    onRgb: (RgbConfig) -> Unit,
    hapticsEnabled: Boolean,
    hapticsStrength: Float,
    onModifier: (Int, Boolean) -> Unit,
    onKey: (Int, Boolean) -> Unit,
    onFn: (Boolean) -> Unit,
) {
    // The function row sits above the number row, so F1-F12 are always one tap away.
    val rows = listOf(functionRow()) + keyboardRows()
    // The backlight's clock and its press flashes share one time base.
    val rgbStart = remember { System.currentTimeMillis() }
    // The backlight's clock: a slow tick drives the glow. It only runs while the RGB
    // backlight is on, and stays at 0 otherwise so nothing animates.
    val rgbClock by produceState(initialValue = 0L, key1 = rgb.enabled) {
        if (!rgb.enabled) {
            value = 0L
            return@produceState
        }
        while (true) {
            value = System.currentTimeMillis() - rgbStart
            delay(80)
        }
    }
    // Recent key presses, for the ripple and reactive effects. Capped and pruned on append.
    val flashes = remember { mutableStateListOf<Flash>() }
    // When the RGB key was pressed down, for its 500 ms long-press detection.
    var rgbDownAt by remember { mutableStateOf(0L) }
    // A near-black deck with rounded corners so the glow stands out.
    val keyGap = if (rgb.enabled) 7.dp else 4.dp
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(RGB_DECK_COLOR)
            .padding(10.dp),
    ) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rowCount = rows.size
        val keyboardRowHeight = ((maxHeight - keyGap * (rowCount + 1)) / rowCount).coerceAtLeast(40.dp)
        // Scroll rather than clip: on a short window the 40 dp floor makes the rows taller than
        // the stage, and the bottom row (Win, Space, arrows) would otherwise be cut off.
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(keyGap),
        ) {
            var keyIndex = 0
            for ((rowIndex, row) in rows.withIndex()) {
                val rowWeight = row.sumOf { it.weight.toDouble() }.toFloat()
                val keyY = rowIndex / (rows.size - 1).toFloat()
                var keyX = 0f
                Row(Modifier.fillMaxWidth().height(keyboardRowHeight), horizontalArrangement = Arrangement.spacedBy(keyGap)) {
                    for (cap in row) {
                        val index = keyIndex++
                        val action = cap.action
                        // The key's normalised centre, used to place it in the RGB field.
                        val keyCenterX = (keyX + cap.weight / 2f) / rowWeight
                        keyX += cap.weight
                        // The Caps key's dot mirrors the host's LED output report, not a local
                        // latch, so it is lit exactly while the host has caps lock on.
                        val isCaps =
                            action is KeyAction.Usage && action.usage == KeyUsage.CAPSLOCK
                        val isFn = action is KeyAction.Fn
                        val isRgb = action is KeyAction.Rgb
                        val pressed = action is KeyAction.Usage && action.usage in pressedKeys
                        val active = when (action) {
                            is KeyAction.Modifier -> (modifiers and action.bit) != 0
                            is KeyAction.Fn -> fnHeld
                            is KeyAction.Rgb -> false
                            is KeyAction.Usage -> pressed
                        }
                        val usage = (action as? KeyAction.Usage)?.usage
                        val zone = if (rgb.enabled) {
                            keyLight(
                                rgb,
                                keyCenterX,
                                keyY,
                                index = index,
                                pressed = pressed,
                                flashes = flashes,
                                timeMillis = rgbClock,
                            )
                        } else {
                            null
                        }
                        // Unlit means: the backlight is off, or reactive mode with nothing pressed.
                        val unlit = zone == null || (rgb.effect == RgbEffect.REACTIVE && !pressed)
                        val backlight = if (unlit) null else zone?.color
                        val backlightIntensity = if (unlit) 0f else zone?.intensity ?: 0f
                        KeyboardKeyButton(
                            label = cap.label,
                            active = active,
                            hapticsEnabled = hapticsEnabled,
                            hapticsStrength = hapticsStrength,
                            onDown = {
                                // Feed the backlight's reactive effects with a press at this key.
                                if (rgb.enabled && usage != null) {
                                    val now = System.currentTimeMillis() - rgbStart
                                    flashes.add(Flash(usage, keyCenterX, keyY, now))
                                    while (flashes.size > 12) flashes.removeAt(0)
                                }
                                when (action) {
                                    is KeyAction.Modifier -> onModifier(action.bit, true)
                                    is KeyAction.Fn -> onFn(true)
                                    is KeyAction.Rgb -> rgbDownAt = System.currentTimeMillis()
                                    is KeyAction.Usage -> onKey(action.usage, true)
                                }
                            },
                            onUp = {
                                when (action) {
                                    is KeyAction.Modifier -> onModifier(action.bit, false)
                                    is KeyAction.Fn -> onFn(false)
                                    is KeyAction.Rgb -> {
                                        // Tap toggles the backlight; a 500 ms hold steps to the next effect.
                                        val held = System.currentTimeMillis() - rgbDownAt
                                        if (held >= 500) {
                                            val order = RgbEffect.entries
                                            val next = order[(order.indexOf(rgb.effect) + 1) % order.size]
                                            onRgb(rgb.copy(effect = next))
                                        } else {
                                            onRgb(rgb.copy(enabled = !rgb.enabled))
                                        }
                                    }
                                    is KeyAction.Usage -> onKey(action.usage, false)
                                }
                            },
                            indicator = isCaps,
                            indicatorOn = capsLockLed,
                            backlight = backlight,
                            backlightIntensity = backlightIntensity,
                            modifier = Modifier.weight(cap.weight).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
    }
}
