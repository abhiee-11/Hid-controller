package com.hidgamepad

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The runtime permissions the HID device needs, on the API levels where they are runtime
 * permissions. Below Android 12 the same capability is covered by the legacy BLUETOOTH and
 * BLUETOOTH_ADMIN permissions, which the system grants at install time — so there is nothing
 * to request and the gate is trivially satisfied.
 */
private val BLUETOOTH_PERMISSIONS: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
        emptyArray()
    }

/**
 * Asked for together with the Bluetooth permissions, but never a gate: refusing it only hides
 * the foreground-service notification, and the controller still runs. Gating on it would brick
 * the app on Android 13+ for a user who declined notifications.
 */
private val NOTIFICATION_PERMISSIONS = arrayOf(Manifest.permission.POST_NOTIFICATIONS)

/** Everything the first-run dialog asks for. */
private val REQUESTED_PERMISSIONS = BLUETOOTH_PERMISSIONS + NOTIFICATION_PERMISSIONS

/** Fullscreen landscape gamepad; owns the manager lifecycle and permission flow. */
class MainActivity : ComponentActivity() {

    private lateinit var manager: HidGamepadManager
    private var hapticsOn by mutableStateOf(true)
    private var hapticsStrength by mutableStateOf(1.0f)
    private var permissionsGranted by mutableStateOf(false)

    /** The master HID switch. Off unregisters the HID device and stops the service. */
    private var hidEnabled by mutableStateOf(true)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            // Only the Bluetooth grants gate the app: a refused notification permission must
            // not stop the controller from running.
            val granted = BLUETOOTH_PERMISSIONS.all { grants[it] == true }
            permissionsGranted = granted
            if (granted) ensureHidService()
        }

    private val discoverableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                Log.i("HidGamepad", "Bluetooth adapter state: $state")
                if (state == BluetoothAdapter.STATE_ON) ensureHidService()
            }
        }
    }
    private var receiverRegistered = false

    private fun ensureHidService() {
        if (!shouldRunHidService(hidEnabled, permissionsGranted)) {
            Log.i("HidGamepad", "activity: HID is off — not starting the service")
            return
        }
        Log.i("HidGamepad", "activity: ensureHidService")
        manager = HidRuntime.get(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, HidControllerService::class.java))
        } else {
            startService(Intent(this, HidControllerService::class.java))
        }
        manager.start()
    }

    /**
     * The user flipped the master HID switch. Off releases every input, unregisters the HID
     * device and stops the service (removing its notification); on starts it again. The
     * preference is persisted so a phone left off stays off across a restart.
     */
    private fun updateHidEnabled(enabled: Boolean) {
        if (hidEnabled == enabled) return
        hidEnabled = enabled
        getSharedPreferences("hid_gamepad", MODE_PRIVATE)
            .edit().putBoolean("hid_enabled", enabled).apply()
        if (enabled) {
            Log.i("HidGamepad", "activity: HID switch ON")
            if (permissionsGranted) ensureHidService()
        } else {
            Log.i("HidGamepad", "activity: HID switch OFF")
            manager.releaseAllInputs()
            manager.stop()
            stopService(Intent(this, HidControllerService::class.java))
        }
    }

    private fun hasRequiredPermissions(): Boolean = BLUETOOTH_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun readHidEnabled(): Boolean {
        val prefs = getSharedPreferences("hid_gamepad", MODE_PRIVATE)
        val stored = if (prefs.contains("hid_enabled")) prefs.getBoolean("hid_enabled", true) else null
        return hidEnabledFromStorage(stored)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Immersive fullscreen, keep screen on (orientation is locked in the manifest).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        manager = HidRuntime.get(this)
        hapticsOn = getSharedPreferences("hid_gamepad", MODE_PRIVATE).getBoolean("haptics_on", true)
        hapticsStrength = getSharedPreferences("hid_gamepad", MODE_PRIVATE).getFloat("haptics_strength", 1.0f)
        hidEnabled = readHidEnabled()

        val btFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, btFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, btFilter)
        }
        receiverRegistered = true

        val missing = BLUETOOTH_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            permissionsGranted = true
            ensureHidService()
        } else {
            // Ask for notifications in the same dialog; a refusal does not gate the app.
            permissionLauncher.launch(REQUESTED_PERMISSIONS)
        }

        setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF141519)),
            ) {
                if (permissionsGranted) {
                    val status by manager.status.collectAsState()
                    val pairedProfile by manager.pairedProfile.collectAsState()
                    GamepadScreen(
                        statusText = if (hidEnabled) statusLabel(status) else "HID off — turn the switch on to enable",
                        // The state word, without the host's name: the report travels to a
                        // public server and must not carry another device's identity.
                        hidState = if (hidEnabled) hidStateWord(status) else "off",
                        bondedDevices = { manager.bondedDevices() },
                        onConnect = { device: android.bluetooth.BluetoothDevice -> manager.connect(device) },
                        onDiscoverable = {
                            discoverableLauncher.launch(
                                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300),
                            )
                        },
                        hapticsEnabled = hapticsOn,
                        hapticsStrength = hapticsStrength,
                        onHapticsChange = { enabled ->
                            hapticsOn = enabled
                            getSharedPreferences("hid_gamepad", MODE_PRIVATE)
                                .edit().putBoolean("haptics_on", enabled).apply()
                        },
                        onHapticsStrengthChange = { strength ->
                            hapticsStrength = strength
                            getSharedPreferences("hid_gamepad", MODE_PRIVATE)
                                .edit().putFloat("haptics_strength", strength).apply()
                        },
                        onGamepadState = { state -> manager.updateGamepad(state) },
                        onKeyboardState = { state -> manager.updateKeyboard(state) },
                        onMouseState = { state -> manager.sendMouse(state) },
                        onConsumerState = { state -> manager.sendConsumer(state) },
                        onReleaseAllInputs = { manager.releaseAllInputs() },
                        registeredProfile = pairedProfile,
                        onProfileChanged = { newProfile -> manager.hidProfile = newProfile },
                        onConfirmRepair = { manager.confirmRepaired() },
                        hidEnabled = hidEnabled,
                        onHidEnabledChange = { updateHidEnabled(it) },
                    )
                } else {
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Bluetooth permissions are required to run the gamepad",
                            color = Color.White,
                            fontSize = 14.sp,
                        )
                        Button(onClick = {
                            if (REQUESTED_PERMISSIONS.any { shouldShowRequestPermissionRationale(it) }) {
                                permissionLauncher.launch(REQUESTED_PERMISSIONS)
                            } else {
                                // Permanently denied: the system dialog will never show again.
                                startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.fromParts("package", packageName, null)),
                                )
                            }
                        }) {
                            Text("Grant permissions")
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val granted = hasRequiredPermissions()
        if (granted != permissionsGranted) permissionsGranted = granted
        if (!granted) {
            Log.i("HidGamepad", "activity: onResume without Bluetooth permissions — not starting HID")
            return
        }
        if (!shouldRunHidService(hidEnabled, granted)) {
            Log.i("HidGamepad", "activity: onResume with HID switched off — leaving it off")
            return
        }
        ensureHidService()
        manager.onResume()
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(bluetoothReceiver)
        super.onDestroy()
    }

    override fun onPause() {
        manager.releaseAllInputs()
        super.onPause()
    }
}
