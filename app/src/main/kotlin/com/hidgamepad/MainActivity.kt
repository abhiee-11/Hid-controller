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

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_ADVERTISE,
    Manifest.permission.POST_NOTIFICATIONS,
)

/** Fullscreen landscape gamepad; owns the manager lifecycle and permission flow. */
class MainActivity : ComponentActivity() {

    private lateinit var manager: HidGamepadManager
    private var hapticsOn by mutableStateOf(true)
    private var hapticsStrength by mutableStateOf(1.0f)
    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val granted = grants.values.all { it }
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
        Log.i("HidGamepad", "activity: ensureHidService")
        manager = HidRuntime.get(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, HidControllerService::class.java))
        } else {
            startService(Intent(this, HidControllerService::class.java))
        }
        manager.start()
    }

    private fun hasRequiredPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
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

        val btFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, btFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, btFilter)
        }
        receiverRegistered = true

        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            permissionsGranted = true
            ensureHidService()
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
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
                        statusText = statusLabel(status),
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
                            if (REQUIRED_PERMISSIONS.any { shouldShowRequestPermissionRationale(it) }) {
                                permissionLauncher.launch(REQUIRED_PERMISSIONS)
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
