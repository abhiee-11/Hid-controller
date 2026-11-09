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
        /** connect() was accepted but no HID connection materialised within 10 s. */
        data class ConnectTimedOut(val deviceName: String) : Status
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
    /** Cap for the registerApp backoff so a permanently failing stack still retries forever. */
    private val RETRY_MAX_MS = 10_000L
    /** How long connect() is allowed to sit in CONNECTING before we give up. */
    private val CONNECT_TIMEOUT_MS = 10_000L

    private var hidDevice: BluetoothHidDevice? = null
    private var appRegistered = false
    private var proxyPending = false
    private var hostDevice: BluetoothDevice? = null
    private var lastReport: ByteArray? = null
    private var registerRetryCount = 0
    private var connectTimeoutTarget: String? = null
    private var reportsSent = 0

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
        if (proxyPending || hidDevice != null) {
            refreshConnected("start: proxy already up")
            return
        }
        Log.i(TAG, "start(): fetching HID DEVICE proxy")
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
            Log.i(TAG, "HID_DEVICE proxy connected")
            mainHandler.removeCallbacks(retryProxy)
            proxyPending = false
            hidDevice = proxy as? BluetoothHidDevice
            registerRetryCount = 0
            registerApp()
            refreshConnected("proxy connected")
        }

        override fun onServiceDisconnected(profile: Int) {
            // Proxy died (BT turned off, crash of BT stack): drop state, activity will restart us.
            Log.i(TAG, "HID_DEVICE service disconnected")
            hidDevice = null
            appRegistered = false
            hostDevice = null
            lastReport = null
            stopHeartbeat()
            stopConnectionPoll()
            _status.value = if (bluetoothEnabled()) Status.Unregistered else Status.BluetoothOff
            // Self-heal: if Bluetooth is still on but the proxy died silently, fetch it again.
            if (bluetoothEnabled()) mainHandler.postDelayed({ start() }, RETRY_MS)
        }
    }

    /** In BluetoothHidDevice the getConnectedDevices() list is the authoritative host state:
     *  SDP callbacks can lag or silently miss host-initiated connects, so every status
     *  transition is reconciled against this query. An empty right after a plugged-event
     *  is not trusted: the clear needs two consecutive empty truths. */
    private var emptyTruthChecks = 0
    private var lastEmptyQueryAt = 0L
    private fun refreshConnected(reason: String) {
        val hd = hidDevice ?: return
        val list = try {
            hd.getConnectedDevices()
        } catch (_: SecurityException) {
            return
        }
        Log.i(TAG, "connected devices ($reason): [${list.joinToString { deviceName(it) }}]")
        val live = list.firstOrNull()
        if (live != null) {
            val wasHost = hostDevice
            hostDevice = live
            emptyTruthChecks = 0
            if (_status.value != Status.Connected(deviceName(live)) || wasHost != live) {
                _status.value = Status.Connected(deviceName(live))
            }
            startHeartbeat()
            cancelConnectTimeout()
        } else {
            // The stack's list can lag behind a just-arrived connection for under a second,
            // so a clear requires two empties spaced at least 1s apart.
            val now = System.currentTimeMillis()
            val spaced = now - lastEmptyQueryAt >= 1000
            lastEmptyQueryAt = now
            emptyTruthChecks = if (spaced) emptyTruthChecks + 1 else emptyTruthChecks
            if (_status.value is Status.Connected) {
                if (emptyTruthChecks < 2) {
                    Log.i(TAG, "connected list empty (waiting for confirmation): keeping host")
                } else {
                    Log.i(TAG, "connected list empty twice: clearing stale host")
                    hostDevice = null
                    lastReport = null
                    stopHeartbeat()
                    _status.value = if (appRegistered) Status.Registered else Status.Unregistered
                }
            }
        }
    }

    /** While registered, poll the authoritative list so a status lag never outlives ~5 s. */
    private val connectionPoll = object : Runnable {
        override fun run() {
            if (appRegistered && hidDevice != null) {
                refreshConnected("poll")
                mainHandler.postDelayed(this, 5000)
            }
        }
    }

    private fun startConnectionPoll() {
        mainHandler.removeCallbacks(connectionPoll)
        mainHandler.postDelayed(connectionPoll, 5000)
    }

    private fun stopConnectionPoll() {
        mainHandler.removeCallbacks(connectionPoll)
        emptyTruthChecks = 0
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "onAppStatusChanged registered=$registered plugged=${pluggedDevice?.let { deviceName(it) }}")
            appRegistered = registered
            if (registered) {
                registerRetryCount = 0
                mainHandler.removeCallbacks(retryRegister)
                if (pluggedDevice != null) {
                    // SDP reports the plugged host immediately; the queryable list can lag
                    // behind, so trust this signal first and let refreshConnected confirm.
                    hostDevice = pluggedDevice
                    _status.value = Status.Connected(deviceName(pluggedDevice))
                    startHeartbeat()
                    cancelConnectTimeout()
                }
                refreshConnected("app registered")
                if (hostDevice == null && _status.value !is Status.Connected) {
                    _status.value = Status.Registered
                }
                startConnectionPoll()
            } else {
                hostDevice = null
                lastReport = null
                stopHeartbeat()
                stopConnectionPoll()
                _status.value = Status.Unregistered
                // The stack can reject registration (e.g. another HID app holds it); retry with backoff.
                if (hidDevice != null) scheduleRegisterRetry()
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.i(TAG, "onConnectionStateChanged ${deviceName(device)} state=$state " +
                "(${stateName(state)})")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> refreshConnected("state connected")
                BluetoothProfile.STATE_CONNECTING -> {
                    hostDevice = device
                    if (_status.value !is Status.Connected && _status.value !is Status.Connecting) {
                        _status.value = Status.Connecting(deviceName(device))
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING -> {
                    // Query first: only clear the host when the truth is that nobody is connected.
                    refreshConnected("state disconnected")
                    if (_status.value !is Status.Connected) {
                        hostDevice = null
                        lastReport = null
                        stopHeartbeat()
                        _status.value = if (appRegistered) Status.Registered else Status.Unregistered
                    }
                }
            }
        }
    }

    private fun stateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> state.toString()
    }

    fun registerApp() {
        val hd = hidDevice ?: return
        if (appRegistered || proxyPending) return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "HID Gamepad",
            "Bluetooth gamepad for host computers",
            "HidGamepad",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            GAMEPAD_DESCRIPTOR,
        )
        Log.i(TAG, "registerApp requested (attempt ${registerRetryCount + 1})")
        try {
            // API 28+ signature: QoS settings optional (pass null for defaults).
            hd.registerApp(sdp, null, null, executor, hidCallback)
        } catch (_: SecurityException) {
            Log.w(TAG, "registerApp failed: SecurityException")
            _status.value = Status.Unregistered
            scheduleRegisterRetry()
        } catch (_: IllegalStateException) {
            Log.w(TAG, "registerApp failed: IllegalStateException")
            _status.value = Status.Unregistered
            scheduleRegisterRetry()
        }
    }

    /** Exponential backoff for failed registrations: 1.5s, 3s, 6s … capped at 10s, forever. */
    private fun scheduleRegisterRetry() {
        val delay = ((RETRY_MS / 2) shl registerRetryCount.coerceAtMost(3)).coerceAtMost(RETRY_MAX_MS)
        registerRetryCount++
        Log.i(TAG, "registration retry scheduled in ${delay}ms")
        mainHandler.removeCallbacks(retryRegister)
        mainHandler.postDelayed(retryRegister, delay)
    }

    fun connect(device: BluetoothDevice): Boolean {
        val hd = hidDevice ?: return false
        if (!appRegistered) return false
        val ok = try {
            hd.connect(device)
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
        Log.i(TAG, "connect(${deviceName(device)}) -> $ok")
        if (ok) {
            _status.value = Status.Connecting(deviceName(device))
            connectTimeoutTarget = deviceName(device)
            mainHandler.removeCallbacks(connectTimeout)
            mainHandler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        }
        return ok
    }

    /** When a pending connect() never reaches CONNECTED, surface it so the user can retry
     *  via the Devices menu — and re-check the truth first, a host-initiated connect may
     *  have completed meanwhile. */
    private val connectTimeout = Runnable {
        val name = connectTimeoutTarget
        connectTimeoutTarget = null
        if (_status.value !is Status.Connected) {
            refreshConnected("connect timeout")
            if (_status.value !is Status.Connected && name != null) {
                Log.w(TAG, "connect to $name timed out")
                hostDevice = null
                lastReport = null
                _status.value = if (appRegistered) Status.ConnectTimedOut(name) else Status.Unregistered
            }
        }
    }

    private fun cancelConnectTimeout() {
        connectTimeoutTarget = null
        mainHandler.removeCallbacks(connectTimeout)
    }

    /** Called when the activity returns to the foreground: re-check the truth, and
     *  re-register if the proxy survived but the app record did not. */
    fun onResume() {
        if (!bluetoothEnabled()) return
        val hd = hidDevice
        if (hd != null) {
            if (!appRegistered) registerApp()
            if (appRegistered) startConnectionPoll()
        } else {
            start()
        }
        refreshConnected("onResume")
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
            if (hd.sendReport(device, 0, report)) {
                reportsSent++
                if (reportsSent % 100 == 0) Log.i(TAG, "reports sent: $reportsSent")
            } else {
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
        cancelConnectTimeout()
        stopConnectionPoll()
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
