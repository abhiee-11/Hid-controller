package com.hidgamepad

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Bluetooth HID DEVICE profile wrapper: app registration, host connection lifecycle and
 *  report sending (change-triggered + 100 ms heartbeat while connected). All Bluetooth calls
 *  assume the runtime permissions have been granted — MainActivity gates that. */
class HidGamepadManager(context: Context) {

    private companion object {
        const val TAG = "HidGamepad"
    }

    sealed interface Status {
        data object Unsupported : Status
        data object BluetoothOff : Status
        data object Unregistered : Status
        data object Registered : Status
        data class Connecting(val deviceName: String) : Status
        data class Connected(val deviceName: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Unregistered)
    val status: StateFlow<Status> = _status

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executor { r -> mainHandler.post(r) }

    /** Delay between automatic retries when the proxy/registration fails transiently. */
    private val RETRY_MS = 3000L

    private var hidDevice: BluetoothHidDevice? = null
    private var appRegistered = false
    private var proxyPending = false
    private var hostDevice: BluetoothDevice? = null
    private var lastReport: ByteArray? = null

    fun bluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun bondedDevices(): List<BluetoothDevice> = try {
        adapter?.bondedDevices?.toList().orEmpty()
    } catch (_: SecurityException) {
        emptyList()
    }

    fun deviceName(device: BluetoothDevice): String = try {
        device.name ?: device.address
    } catch (_: SecurityException) {
        device.address
    }

    /** Fetch the HID DEVICE proxy, then register the app (SDP record + descriptor). */
    fun start() {
        val a = adapter ?: run {
            _status.value = Status.Unsupported
            return
        }
        if (!a.isEnabled) {
            _status.value = Status.BluetoothOff
            return
        }
        if (proxyPending || hidDevice != null) return
        proxyPending = true
        _status.value = Status.Unregistered
        try {
            a.getProfileProxy(appContext, serviceListener, BluetoothProfile.HID_DEVICE)
        } catch (_: SecurityException) {
            proxyPending = false
        }
        // Some stacks never invoke the listener; retry the proxy fetch once in a while.
        mainHandler.removeCallbacks(retryProxy)
        mainHandler.postDelayed(retryProxy, RETRY_MS)
    }

    private val retryProxy = Runnable {
        if (hidDevice == null) {
            proxyPending = false
            start()
        }
    }

    private val retryRegister = Runnable {
        if (hidDevice != null && !appRegistered) registerApp()
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            mainHandler.removeCallbacks(retryProxy)
            proxyPending = false
            hidDevice = proxy as? BluetoothHidDevice
            registerApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            // Proxy died (BT turned off, crash of BT stack): drop state, activity will restart us.
            hidDevice = null
            appRegistered = false
            hostDevice = null
            lastReport = null
            stopHeartbeat()
            _status.value = if (bluetoothEnabled()) Status.Unregistered else Status.BluetoothOff
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            appRegistered = registered
            if (registered) {
                mainHandler.removeCallbacks(retryRegister)
                if (pluggedDevice != null) {
                    hostDevice = pluggedDevice
                    _status.value = Status.Connected(deviceName(pluggedDevice))
                    startHeartbeat()
                } else if (_status.value !is Status.Connected) {
                    _status.value = Status.Registered
                }
            } else {
                hostDevice = null
                lastReport = null
                stopHeartbeat()
                _status.value = Status.Unregistered
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    hostDevice = device
                    _status.value = Status.Connected(deviceName(device))
                    startHeartbeat()
                }
                BluetoothProfile.STATE_CONNECTING -> _status.value = Status.Connecting(deviceName(device))
                BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING -> {
                    hostDevice = null
                    lastReport = null
                    stopHeartbeat()
                    _status.value = if (appRegistered) Status.Registered else Status.Unregistered
                }
            }
        }
    }

    fun registerApp() {
        val hd = hidDevice ?: return
        if (appRegistered || proxyPending) return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "HID Gamepad",
            "Bluetooth gamepad for Android tablet",
            "HidGamepad",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            GAMEPAD_DESCRIPTOR,
        )
        try {
            // API 28+ signature: QoS settings optional (pass null for defaults).
            hd.registerApp(sdp, null, null, executor, hidCallback)
        } catch (_: SecurityException) {
            _status.value = Status.Unregistered
            mainHandler.removeCallbacks(retryRegister)
            mainHandler.postDelayed(retryRegister, RETRY_MS)
        } catch (_: IllegalStateException) {
            _status.value = Status.Unregistered
            mainHandler.removeCallbacks(retryRegister)
            mainHandler.postDelayed(retryRegister, RETRY_MS)
        }
    }

    fun connect(device: BluetoothDevice): Boolean {
        val hd = hidDevice ?: return false
        if (!appRegistered) return false
        return try {
            val ok = hd.connect(device)
            if (ok) _status.value = Status.Connecting(deviceName(device))
            ok
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }

    /** Push a fresh snapshot; sends only when the report actually changed. */
    fun update(state: GamepadState) {
        if (hostDevice == null) return
        val report = state.encodeReport()
        if (lastReport?.contentEquals(report) == true) return
        lastReport = report
        sendNow(report)
    }

    private fun sendNow(report: ByteArray) {
        val device = hostDevice ?: return
        val hd = hidDevice ?: return
        try {
            if (!hd.sendReport(device, 0, report)) {
                // A stale host connection can keep the status callback alive while the stack
                // rejects reports; make that condition visible during diagnosis.
                Log.w(TAG, "Bluetooth stack rejected a HID report")
            }
        } catch (_: IllegalArgumentException) {
        } catch (_: SecurityException) {
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            lastReport?.let(::sendNow)
            mainHandler.postDelayed(this, 100)
        }
    }

    private fun startHeartbeat() {
        // Seed the neutral report so the host starts receiving a heartbeat right away,
        // even before the first input happens.
        if (lastReport == null) lastReport = GamepadState().encodeReport()
        lastReport?.let(::sendNow)
        mainHandler.removeCallbacks(heartbeat)
        mainHandler.postDelayed(heartbeat, 100)
    }

    private fun stopHeartbeat() {
        mainHandler.removeCallbacks(heartbeat)
    }

    /** Immediately release every HID control, including when Android backgrounds the activity. */
    fun releaseAllInputs() {
        val neutral = GamepadState().encodeReport()
        lastReport = neutral
        sendNow(neutral)
    }

    /** Unregister the HID app and release the profile proxy; safe to call repeatedly. */
    fun stop() {
        stopHeartbeat()
        releaseAllInputs()
        mainHandler.removeCallbacks(retryProxy)
        mainHandler.removeCallbacks(retryRegister)
        hidDevice?.let { hd ->
            try {
                if (appRegistered) hd.unregisterApp()
            } catch (_: Exception) {
            }
            try {
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hd)
            } catch (_: Exception) {
            }
        }
        hidDevice = null
        appRegistered = false
        hostDevice = null
        lastReport = null
        _status.value = Status.Unregistered
    }
}
