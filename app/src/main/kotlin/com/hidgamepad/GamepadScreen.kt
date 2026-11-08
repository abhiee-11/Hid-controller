package com.hidgamepad

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Mutable controller model; every field is a Compose state so changes retrigger report pushes. */
private class ControllerModel {
    var lx by mutableStateOf(0f)
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
    is HidGamepadManager.Status.Registered -> "Ready — waiting for the tablet to pair"
    is HidGamepadManager.Status.Connecting -> "Connecting to ${status.deviceName}…"
    is HidGamepadManager.Status.Connected -> "Connected: ${status.deviceName}"
}

private fun deviceLabel(device: BluetoothDevice): String = try {
    device.name ?: device.address
} catch (_: SecurityException) {
    device.address
}

@Composable
fun GamepadScreen(
    statusText: String,
    bondedDevices: () -> List<BluetoothDevice>,
    onConnect: (BluetoothDevice) -> Boolean,
    onDiscoverable: () -> Unit,
    hapticsEnabled: Boolean,
    onHapticsChange: (Boolean) -> Unit,
    onState: (GamepadState) -> Unit,
) {
    val model = remember { ControllerModel() }
    val state = GamepadState(model.lx, model.ly, model.rx, model.ry, model.lt, model.rt, model.buttons, model.hat)
    LaunchedEffect(state) { onState(state) }

    var devicesOpen by remember { mutableStateOf(false) }
    var deviceList by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    LaunchedEffect(devicesOpen) {
        if (devicesOpen) deviceList = bondedDevices()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
        // --- top bar: status + settings controls ---
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(250.dp)) {
                Text("BT Gamepad", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE8EAF0))
                Text(statusText, fontSize = 12.sp, color = Color(0xFF9BB08D))
            }
            Spacer(Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onDiscoverable, modifier = Modifier.height(32.dp)) {
                    Text("Discoverable", fontSize = 12.sp)
                }
                Box {
                    Button(onClick = { devicesOpen = !devicesOpen }, modifier = Modifier.height(32.dp)) {
                        Text("Devices", fontSize = 12.sp)
                    }
                    DropdownMenu(expanded = devicesOpen, onDismissRequest = { devicesOpen = false }) {
                        val list = deviceList
                        if (list.isEmpty()) {
                            DropdownMenuItem(text = { Text("No paired tablets yet") }, onClick = { devicesOpen = false })
                        }
                        for (d in list) {
                            DropdownMenuItem(
                                text = { Text(deviceLabel(d)) },
                                onClick = { devicesOpen = false; onConnect(d) },
                            )
                        }
                    }
                }
                Button(onClick = { onHapticsChange(!hapticsEnabled) }, modifier = Modifier.height(32.dp)) {
                    Text(if (hapticsEnabled) "Haptics ON" else "Haptics OFF", fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        // --- stage ---
        Box(Modifier.fillMaxSize()) {
            // Left cluster: D-pad above the left stick (bottom-left)
            Column(
                Modifier.align(Alignment.BottomStart),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DPadControl(200.dp, onHat = { model.hat = it })
                Spacer(Modifier.height(10.dp))
                JoystickControl(160.dp) { x, y -> model.lx = x; model.ly = y }
            }

            // Right cluster: ABXY above the right stick (bottom-right)
            Column(
                Modifier.align(Alignment.BottomEnd),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val aColor = Color(0xFF5CC26A)
                val bColor = Color(0xFFE06A5A)
                val xColor = Color(0xFF6BA7E0)
                val yColor = Color(0xFFE0B75A)
                Box(Modifier.size(210.dp, 235.dp)) {
                    GamepadButton("Y", 58.dp, yColor, hapticsEnabled, { model.down(Btn.Y) }, { model.up(Btn.Y) }, Modifier.align(Alignment.TopCenter))
                    GamepadButton("X", 58.dp, xColor, hapticsEnabled, { model.down(Btn.X) }, { model.up(Btn.X) }, Modifier.align(Alignment.CenterStart))
                    GamepadButton("B", 58.dp, bColor, hapticsEnabled, { model.down(Btn.B) }, { model.up(Btn.B) }, Modifier.align(Alignment.CenterEnd))
                    GamepadButton("A", 58.dp, aColor, hapticsEnabled, { model.down(Btn.A) }, { model.up(Btn.A) }, Modifier.align(Alignment.BottomCenter))
                }
                Spacer(Modifier.height(10.dp))
                JoystickControl(160.dp) { x, y -> model.rx = x; model.ry = y }
            }

            // Shoulders on top corners, Start/Select in the middle of the stage
            Row(
                Modifier.align(Alignment.TopStart),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GamepadButton("L1", 52.dp, DpadColor, hapticsEnabled, { model.down(Btn.L1) }, { model.up(Btn.L1) })
                GamepadButton("L2", 52.dp, DpadColor, hapticsEnabled, { model.lt = 1f }, { model.lt = 0f })
            }
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                GamepadButton("SEL", 46.dp, Color(0xFF4A4E5A), hapticsEnabled, { model.down(Btn.SELECT) }, { model.up(Btn.SELECT) })
                GamepadButton("START", 46.dp, Color(0xFF4A4E5A), hapticsEnabled, { model.down(Btn.START) }, { model.up(Btn.START) })
            }
            Row(
                Modifier.align(Alignment.TopEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GamepadButton("R2", 52.dp, DpadColor, hapticsEnabled, { model.rt = 1f }, { model.rt = 0f })
                GamepadButton("R1", 52.dp, DpadColor, hapticsEnabled, { model.down(Btn.R1) }, { model.up(Btn.R1) })
            }
        }
    }
}
