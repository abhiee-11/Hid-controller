package com.hidgamepad

import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

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

private fun modeLabel(mode: ControllerMode): String = when (mode) {
    ControllerMode.GAMEPAD -> "Gamepad"
    ControllerMode.MOUSE -> "Mouse"
    ControllerMode.KEYBOARD -> "Keyboard"
}

private fun hostTitle(hostType: HostType): String = when (hostType) {
    HostType.WINDOWS -> "Windows PC"
    HostType.ANDROID -> "Android phone or tablet"
}


@Composable
fun GamepadScreen(
    statusText: String,
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
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences("hid_gamepad", Context.MODE_PRIVATE) }
    var hostType by remember { mutableStateOf(hostTypeFromStorage(prefs.getString("host_type", null))) }
    var controllerMode by remember { mutableStateOf(controllerModeFromStorage(prefs.getString("controller_mode", null))) }
    var mouseSensitivity by remember { mutableStateOf(prefs.getFloat("mouse_sensitivity", 1f)) }
    var needsSetup by remember {
        mutableStateOf(needsOnboarding(prefs.getBoolean("setup_complete", false), prefs.getString("last_host_address", null)))
    }
    var onboardingStep by remember { mutableStateOf(0) }
    var modeNotice by remember { mutableStateOf("") }
    if (needsSetup) {
        SetupPanel(
            hostType = hostType,
            mode = controllerMode,
            bondedDevices = bondedDevices,
            onDiscoverable = onDiscoverable,
            onHostType = { newType ->
                hostType = newType
                prefs.edit().putString("host_type", newType.name).apply()
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
    val keyboardState = KeyboardState(keyboardModifiers, keyboardKeys)
    LaunchedEffect(keyboardState) { onKeyboardState(keyboardState) }

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
    }

    fun switchMode(newMode: ControllerMode) {
        if (newMode == controllerMode) return
        onReleaseAllInputs()
        resetLocalInputs()
        controllerMode = newMode
        prefs.edit().putString("controller_mode", newMode.name).apply()
        modeNotice = modeLabel(newMode) + " mode active"
    }

    var settingsOpen by remember { mutableStateOf(false) }

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
            .padding(16.dp),
    ) {
        // --- top bar: status + settings controls ---
        Row(
            Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("HID Controller", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE8EAF0))
                Text(
                    (if (statusText.startsWith("Connected")) "● " else "○ ") + statusText,
                    fontSize = 12.sp,
                    color = when {
                        statusText.startsWith("Connected") -> Color(0xFF9BB08D)
                        statusText.startsWith("Connecting") -> Color(0xFFE0B75A)
                        else -> Color(0xFF9AA1B2)
                    },
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var modeMenuOpen by remember { mutableStateOf(false) }
                Box {
                    Button(onClick = { modeMenuOpen = !modeMenuOpen }, modifier = Modifier.height(48.dp)) {
                        Text(modeLabel(controllerMode) + " ▼", fontSize = 12.sp)
                    }
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
                    Button(onClick = { devicesOpen = !devicesOpen }, modifier = Modifier.height(48.dp)) {
                        Text("Devices", fontSize = 12.sp)
                    }
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
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                Button(onClick = { settingsOpen = true }, modifier = Modifier.height(48.dp)) {
                    Text("Settings", fontSize = 12.sp)
                }
            }
        }
        if (modeNotice.isNotBlank()) {
            Text(modeNotice, fontSize = 12.sp, color = Color(0xFFE8EAF0), modifier = Modifier.padding(bottom = 4.dp))
        }
        when (controllerMode) {
            ControllerMode.GAMEPAD -> {
                Text("Gamepad mode", fontSize = 12.sp, color = Color(0xFF9AA1B2))
                if (tiltOn) {
                    Text("Tilt controls left-stick X. Touch stick controls Y.", fontSize = 11.sp, color = Color(0xFF9AA1B2))
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
            val stageW = maxWidth
            val stageH = maxHeight
            val pad = 8.dp
            val abxyPad = 10.dp
            val shoulderTop = 8.dp
            val lowerTop = shoulderTop + 64.dp + 4.dp
            val byWidth = (stageW - (pad * 3f + abxyPad)) / 4f
            val byHeight = stageH - lowerTop
            val stick = byWidth.coerceAtMost(byHeight).coerceAtMost(160.dp).coerceAtLeast(48.dp)
            val abxy = stick + abxyPad
            val face = (abxy / 3.15f).coerceAtLeast(48.dp)
            val abxyTop = lowerTop - ((abxy - stick) / 2f)

            DPadControl(
                stick,
                onHat = { model.hat = it },
                modifier = Modifier.align(Alignment.TopStart).padding(top = lowerTop),
            )
            JoystickControl(
                stick,
                modifier = Modifier.align(Alignment.TopStart).padding(start = stick + pad, top = lowerTop),
            ) { x, y -> model.touchLx = x; model.ly = y }
            JoystickControl(
                stick,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = abxy + pad, top = lowerTop),
            ) { x, y -> model.rx = x; model.ry = y }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = abxyTop)
                    .size(abxy),
            ) {
                val aColor = Color(0xFF5CC26A)
                val bColor = Color(0xFFE06A5A)
                val xColor = Color(0xFF6BA7E0)
                val yColor = Color(0xFFE0B75A)
                GamepadButton("Y", face, yColor, hapticsEnabled, hapticsStrength, { model.down(Btn.Y) }, { model.up(Btn.Y) }, Modifier.align(Alignment.TopCenter))
                GamepadButton("X", face, xColor, hapticsEnabled, hapticsStrength, { model.down(Btn.X) }, { model.up(Btn.X) }, Modifier.align(Alignment.CenterStart))
                GamepadButton("B", face, bColor, hapticsEnabled, hapticsStrength, { model.down(Btn.B) }, { model.up(Btn.B) }, Modifier.align(Alignment.CenterEnd))
                GamepadButton("A", face, aColor, hapticsEnabled, hapticsStrength, { model.down(Btn.A) }, { model.up(Btn.A) }, Modifier.align(Alignment.BottomCenter))
            }

            Row(
                Modifier.align(Alignment.TopStart).padding(top = shoulderTop),
                horizontalArrangement = Arrangement.spacedBy(pad),
            ) {
                GamepadButton("L1", 54.dp, DpadColor, hapticsEnabled, hapticsStrength, { model.down(Btn.L1) }, { model.up(Btn.L1) })
                PedalControl("L2", 48.dp, 64.dp, { model.lt = it })
            }
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = shoulderTop),
                horizontalArrangement = Arrangement.spacedBy(pad),
            ) {
                GamepadButton("SEL", 48.dp, Color(0xFF4A4E5A), hapticsEnabled, hapticsStrength, { model.down(Btn.SELECT) }, { model.up(Btn.SELECT) })
                GamepadButton("START", 48.dp, Color(0xFF4A4E5A), hapticsEnabled, hapticsStrength, { model.down(Btn.START) }, { model.up(Btn.START) })
            }
            Row(
                Modifier.align(Alignment.TopEnd).padding(top = shoulderTop),
                horizontalArrangement = Arrangement.spacedBy(pad),
            ) {
                PedalControl("R2", 48.dp, 64.dp, { model.rt = it })
                GamepadButton("R1", 54.dp, DpadColor, hapticsEnabled, hapticsStrength, { model.down(Btn.R1) }, { model.up(Btn.R1) })
            }
                }
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
                        color = Color(0xFF9AA1B2),
                        maxLines = 2,
                    )
                }
            }
            ControllerMode.KEYBOARD -> {
                KeyboardStage(
                    modifiers = keyboardModifiers,
                    pressedKeys = keyboardKeys,
                    hapticsEnabled = hapticsEnabled,
                    hapticsStrength = hapticsStrength,
                    onModifier = { bit, down ->
                        keyboardModifiers = if (down) keyboardModifiers or bit else keyboardModifiers and bit.inv()
                    },
                    onKey = { usage, down ->
                        keyboardKeys = if (down) keyboardKeys + usage else keyboardKeys - usage
                    },
                )
            }
        }
    }
    if (settingsOpen) {
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
                onRunSetup = { settingsOpen = false; onboardingStep = 1; needsSetup = true },
                onMode = { switchMode(it) },
                onHostType = { newType ->
                    hostType = newType
                    prefs.edit().putString("host_type", newType.name).apply()
                },
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
            )
        }
    }
}

@Composable
private fun SetupPanel(
    hostType: HostType,
    mode: ControllerMode,
    bondedDevices: () -> List<BluetoothDevice>,
    onDiscoverable: () -> Unit,
    onHostType: (HostType) -> Unit,
    onMode: (ControllerMode) -> Unit,
    onConnect: (BluetoothDevice) -> Boolean,
    onStep: (Int) -> Unit,
    step: Int,
    onFinish: () -> Unit,
) {
    var pairedList by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
    LaunchedEffect(step) { if (step == 3) pairedList = bondedDevices() }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (step) {
            0 -> {
                Text("HID Controller", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE8EAF0))
                Text("Turn this phone into a wireless gamepad, mouse, or keyboard.", fontSize = 14.sp, color = Color(0xFFC7CDD8))
                Button(onClick = { onStep(1) }, modifier = Modifier.height(52.dp)) { Text("Get started") }
            }
            1 -> {
                Text("What do you want to control?", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE8EAF0))
                Button(onClick = { onHostType(HostType.WINDOWS); onStep(2) }, modifier = Modifier.fillMaxWidth().height(84.dp)) {
                    Text("🖥 Windows PC\nPlay PC games or control Windows with gamepad, mouse, or keyboard.")
                }
                Button(onClick = { onHostType(HostType.ANDROID); onStep(2) }, modifier = Modifier.fillMaxWidth().height(84.dp)) {
                    Text("📱 Android phone or tablet\nPlay games or control a compatible Android device with Bluetooth HID.")
                }
                Text("Compatibility depends on the receiving device accepting Bluetooth HID input.", fontSize = 12.sp, color = Color(0xFF9AA1B2))
            }
            2 -> {
                Text("How do you want to use it?", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE8EAF0))
                Button(onClick = { onMode(ControllerMode.GAMEPAD); onStep(3) }, modifier = Modifier.fillMaxWidth().height(76.dp)) {
                    Text("🎮 Gamepad\nButtons, sticks, triggers, pedals, and tilt. Best for games.")
                }
                Button(onClick = { onMode(ControllerMode.MOUSE); onStep(3) }, modifier = Modifier.fillMaxWidth().height(76.dp)) {
                    Text("🖱 Mouse\nTouchpad, clicks, and scroll. Best for navigation.")
                }
                Button(onClick = { onMode(ControllerMode.KEYBOARD); onStep(3) }, modifier = Modifier.fillMaxWidth().height(76.dp)) {
                    Text("⌨ Keyboard\nQWERTY typing, arrows, and shortcuts. Best for typing.")
                }
            }
            3 -> {
                Text(if (hostType == HostType.WINDOWS) "Pair with your Windows PC" else "Pair with your Android device", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE8EAF0))
                Text(pairInstructions(hostType), fontSize = 13.sp, color = Color(0xFFC7CDD8))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDiscoverable, modifier = Modifier.height(48.dp)) { Text("Make discoverable") }
                    Button(onClick = { pairedList = bondedDevices() }, modifier = Modifier.height(48.dp)) { Text("Refresh paired devices") }
                }
                Text("Paired devices", fontSize = 14.sp, color = Color(0xFFE8EAF0))
                if (pairedList.isEmpty()) Text("No paired devices found", fontSize = 12.sp, color = Color(0xFF9AA1B2))
                for (d in pairedList) {
                    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(deviceLabel(d), color = Color(0xFFE8EAF0), modifier = Modifier.weight(1f))
                        Button(onClick = { if (onConnect(d)) { selectedDevice = d; onStep(4) } }, modifier = Modifier.height(48.dp)) { Text("Connect") }
                    }
                }
                Button(onClick = { onStep(4) }, modifier = Modifier.height(48.dp), enabled = selectedDevice != null) { Text("Continue") }
            }
            else -> {
                Text("Ready", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE8EAF0))
                Text("Connected to: ${selectedDevice?.let { deviceLabel(it) } ?: "not selected yet"}", fontSize = 14.sp, color = Color(0xFFC7CDD8))
                Text("Mode: ${modeLabel(mode)}", fontSize = 14.sp, color = Color(0xFFC7CDD8))
                Button(onClick = onFinish, modifier = Modifier.height(52.dp)) { Text("Start using ${modeLabel(mode)}") }
            }
        }
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
    onMode: (ControllerMode) -> Unit,
    onHostType: (HostType) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onHapticsStrength: (Float) -> Unit,
    onTilt: (Boolean) -> Unit,
    onTiltSensitivity: (Float) -> Unit,
    onTiltDeadzone: (Float) -> Unit,
    onTiltZero: () -> Unit,
    onMouseSensitivity: (Float) -> Unit,
    onClearInputs: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF1A1B21)).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE8EAF0), modifier = Modifier.weight(1f))
            Button(onClick = onClose, modifier = Modifier.height(48.dp)) { Text("Close") }
        }
        Text("Connection", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE8EAF0))
        Text("Connected host: $statusText", fontSize = 12.sp, color = Color(0xFFC7CDD8))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onReconnect, modifier = Modifier.height(48.dp)) { Text("Reconnect") }
            Button(onClick = onChangeDevice, modifier = Modifier.height(48.dp)) { Text("Change device") }
            Button(onClick = onDiscoverable, modifier = Modifier.height(48.dp)) { Text("Make discoverable") }
        }
        Button(onClick = onRunSetup, modifier = Modifier.height(48.dp)) { Text("Run setup again") }
        Text("Controls", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE8EAF0))
        Text("Mode: ${modeLabel(controllerMode)}", fontSize = 12.sp, color = Color(0xFFC7CDD8))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (mode in ControllerMode.entries) {
                Button(onClick = { onMode(mode) }, modifier = Modifier.height(48.dp)) { Text(modeLabel(mode)) }
            }
        }
        Text("Host type: ${hostTitle(hostType)}", fontSize = 12.sp, color = Color(0xFFC7CDD8))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (type in HostType.entries) {
                Button(onClick = { onHostType(type) }, modifier = Modifier.height(48.dp)) { Text(hostTitle(type)) }
            }
        }
        Text("Decides what three-finger swipes send. Change it if the swipes do nothing.", fontSize = 11.sp, color = Color(0xFF9AA1B2))
        Button(onClick = { onHaptics(!hapticsEnabled) }, modifier = Modifier.height(48.dp)) {
            Text(if (hapticsEnabled) "Haptics ON" else "Haptics OFF")
        }
        Text("Haptics strength · ${"%.2f".format(hapticsStrength)}", fontSize = 12.sp, color = Color(0xFFC7CDD8))
        Slider(value = hapticsStrength, onValueChange = onHapticsStrength, valueRange = 0.25f..1.5f, steps = 4, modifier = Modifier.width(220.dp))
        Button(onClick = { onTilt(!tiltOn) }, modifier = Modifier.height(48.dp)) { Text(if (tiltOn) "Tilt ON" else "Tilt OFF") }
        Text("Tilt sensitivity ×${"%.1f".format(tiltSensitivity)}", fontSize = 12.sp, color = Color(0xFFC7CDD8))
        Slider(value = tiltSensitivity, onValueChange = onTiltSensitivity, valueRange = 0.5f..2.0f, steps = 9, modifier = Modifier.width(220.dp))
        Text("Tilt deadzone ${"%.2f".format(tiltDeadzone)}", fontSize = 12.sp, color = Color(0xFFC7CDD8))
        Slider(value = tiltDeadzone, onValueChange = onTiltDeadzone, valueRange = 0f..0.3f, steps = 15, modifier = Modifier.width(220.dp))
        Text("Tilt owns left-stick X when on — touch stick controls Y only", fontSize = 11.sp, color = Color(0xFF9AA1B2))
        Button(onClick = onTiltZero, modifier = Modifier.height(48.dp)) { Text("Calibrate / zero") }
        Text("Sensor: $sensorDescription", fontSize = 11.sp, color = Color(0xFF9AA1B2))
        Text("Mouse sensitivity ×${"%.2f".format(mouseSensitivity)}", fontSize = 12.sp, color = Color(0xFFC7CDD8))
        Slider(value = mouseSensitivity, onValueChange = onMouseSensitivity, valueRange = 0.25f..2.0f, steps = 7, modifier = Modifier.width(220.dp))
        Button(onClick = onClearInputs, modifier = Modifier.height(48.dp)) { Text("Clear all held inputs") }
        Text("Information", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE8EAF0))
        Text("HID status: $statusText", fontSize = 12.sp, color = Color(0xFFC7CDD8))
        Text("Help / pairing instructions", fontSize = 12.sp, color = Color(0xFFC7CDD8))
        Text(pairInstructions(hostType), fontSize = 12.sp, color = Color(0xFF9AA1B2))
        if (hostType == HostType.ANDROID) {
            Text("Android compatibility note: some Android phones do not support Bluetooth HID hosts.", fontSize = 12.sp, color = Color(0xFF9AA1B2))
        }
        Text("Phone haptics are phone-local press feedback, not host-controlled game rumble.", fontSize = 12.sp, color = Color(0xFF9AA1B2))
    }
}

private data class KeySpec(val label: String, val usage: Int? = null, val modifier: Int = 0)

@Composable
private fun KeyboardStage(
    modifiers: Int,
    pressedKeys: Set<Int>,
    hapticsEnabled: Boolean,
    hapticsStrength: Float,
    onModifier: (Int, Boolean) -> Unit,
    onKey: (Int, Boolean) -> Unit,
) {
    val rows = listOf(
        listOf(
            KeySpec("Esc", KeyUsage.ESC), KeySpec("1", KeyUsage.NUM_1), KeySpec("2", KeyUsage.NUM_2),
            KeySpec("3", KeyUsage.NUM_3), KeySpec("4", KeyUsage.NUM_4), KeySpec("5", KeyUsage.NUM_5),
            KeySpec("6", KeyUsage.NUM_6), KeySpec("7", KeyUsage.NUM_7), KeySpec("8", KeyUsage.NUM_8),
            KeySpec("9", KeyUsage.NUM_9), KeySpec("0", KeyUsage.NUM_0), KeySpec("Backspace", KeyUsage.BACKSPACE),
        ),
        listOf(
            KeySpec("Tab", KeyUsage.TAB), KeySpec("Q", KeyUsage.Q), KeySpec("W", KeyUsage.W),
            KeySpec("E", KeyUsage.E), KeySpec("R", KeyUsage.R), KeySpec("T", KeyUsage.T),
            KeySpec("Y", KeyUsage.Y), KeySpec("U", KeyUsage.U), KeySpec("I", KeyUsage.I),
            KeySpec("O", KeyUsage.O), KeySpec("P", KeyUsage.P), KeySpec("Enter", KeyUsage.ENTER),
        ),
        listOf(
            KeySpec("Shift", modifier = MOD_LEFT_SHIFT), KeySpec("A", KeyUsage.A), KeySpec("S", KeyUsage.S),
            KeySpec("D", KeyUsage.D), KeySpec("F", KeyUsage.F), KeySpec("G", KeyUsage.G),
            KeySpec("H", KeyUsage.H), KeySpec("J", KeyUsage.J), KeySpec("K", KeyUsage.K),
            KeySpec("L", KeyUsage.L), KeySpec("Shift", modifier = MOD_LEFT_SHIFT),
        ),
        listOf(
            KeySpec("Ctrl", modifier = MOD_LEFT_CTRL), KeySpec("Z", KeyUsage.Z), KeySpec("X", KeyUsage.X),
            KeySpec("C", KeyUsage.C), KeySpec("V", KeyUsage.V), KeySpec("B", KeyUsage.B),
            KeySpec("N", KeyUsage.N), KeySpec("M", KeyUsage.M), KeySpec("Alt", modifier = MOD_LEFT_ALT),
        ),
        listOf(
            KeySpec("Space", KeyUsage.SPACE), KeySpec("←", KeyUsage.LEFT), KeySpec("↑", KeyUsage.UP),
            KeySpec("↓", KeyUsage.DOWN), KeySpec("→", KeyUsage.RIGHT),
        ),
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val keyboardRowHeight = ((maxHeight - 16.dp) / 5f).coerceAtLeast(48.dp)
        Column(Modifier.fillMaxSize().padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in rows) {
                Row(Modifier.fillMaxWidth().height(keyboardRowHeight), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (spec in row) {
                        val active = if (spec.modifier != 0) (modifiers and spec.modifier) != 0 else spec.usage != null && spec.usage in pressedKeys
                        KeyboardKeyButton(
                            label = spec.label,
                            active = active,
                            hapticsEnabled = hapticsEnabled,
                            hapticsStrength = hapticsStrength,
                            onDown = { if (spec.modifier != 0) onModifier(spec.modifier, true) else spec.usage?.let { onKey(it, true) } },
                            onUp = { if (spec.modifier != 0) onModifier(spec.modifier, false) else spec.usage?.let { onKey(it, false) } },
                            modifier = Modifier.weight(if (spec.label == "Space" || spec.label == "Backspace" || spec.label == "Enter") 2f else 1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}
