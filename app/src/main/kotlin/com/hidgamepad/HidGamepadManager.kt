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

enum class RegisterRetryDecision { WaitForStatusCallback, ScheduleRetry }

/** The stack can reject registration by returning false instead of throwing. Treat both as
 * the same retryable failure; a true result means onAppStatusChanged will report the state. */
fun registerRetryDecision(accepted: Boolean): RegisterRetryDecision =
    if (accepted) RegisterRetryDecision.WaitForStatusCallback else RegisterRetryDecision.ScheduleRetry

/** Host replacement must not carry a held input into a new host. True when a different host
 * address becomes active after another host was already active. */
fun hostReplacementNeedsNeutral(wasHostAddress: String?, liveHostAddress: String): Boolean =
    wasHostAddress != null && wasHostAddress != liveHostAddress

/** Report delivery is gated on the platform's authoritative per-device connection state. */
fun hostConnectionUsable(connectionState: Int): Boolean =
    connectionState == BluetoothProfile.STATE_CONNECTED

/** A plugged-device callback is only trustworthy when the same device reports CONNECTED. */
fun shouldAdoptPluggedDevice(registered: Boolean, connectionState: Int): Boolean =
    registered && hostConnectionUsable(connectionState)

/**
 * The master HID switch, read from storage. A fresh install has nothing stored and starts
 * with HID on, matching the app's behaviour before the switch existed.
 */
fun hidEnabledFromStorage(value: Boolean?): Boolean = value ?: true

/** The single gate every HID start path consults: the switch must be on and permissions granted. */
fun shouldRunHidService(enabled: Boolean, permissionsGranted: Boolean): Boolean =
    enabled && permissionsGranted

/**
 * Whether a lost HID proxy should be re-fetched automatically. Never after an explicit
 * [HidGamepadManager.stop]: that teardown itself disconnects the proxy, and re-fetching would
 * silently turn the master switch back on.
 */
fun shouldSelfHealProxy(stopped: Boolean, bluetoothEnabled: Boolean): Boolean =
    !stopped && bluetoothEnabled

/**
 * The HID state as a plain word, with no device name in it.
 *
 * The status *line* names the host — that is right on screen, where the user is looking at
 * their own device. It is wrong in a report: a report travels to a public server, and the
 * host's Bluetooth name (or its MAC address, when it has no friendly name) identifies
 * somebody else's device.
 */
fun hidStateWord(status: HidGamepadManager.Status): String = when (status) {
    is HidGamepadManager.Status.Unsupported -> "unsupported"
    is HidGamepadManager.Status.BluetoothOff -> "bluetooth off"
    is HidGamepadManager.Status.Unregistered -> "not registered"
    is HidGamepadManager.Status.Registered -> "registered"
    is HidGamepadManager.Status.Connecting -> "connecting"
    is HidGamepadManager.Status.Connected -> "connected"
    is HidGamepadManager.Status.ConnectFailed -> "connection failed"
    is HidGamepadManager.Status.ConnectTimedOut -> "connection timed out"
}

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
        data class ConnectFailed(val deviceName: String) : Status
        /** connect() was accepted but no HID connection materialised within 10 s. */
        data class ConnectTimedOut(val deviceName: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Unregistered)
    val status: StateFlow<Status> = _status

    /**
     * Fired on the main thread whenever the HID app registration changes. The foreground
     * service uses it to show its notification only after registration actually succeeds.
     */
    var onRegistrationChanged: ((Boolean) -> Unit)? = null

    /** Whether the HID app is currently registered with the stack. */
    val isRegistered: Boolean get() = appRegistered

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

    /**
     * Which HID descriptor the phone registers. The host caches the descriptor at pairing
     * time, so a change takes effect on the host only after the user re-pairs.
     */
    var hidProfile: HidProfile = HidProfile.COMPOSITE
        set(value) {
            if (field == value) return
            field = value
            if (appRegistered) {
                // Publish the new descriptor to the local stack so the host reads it when it
                // re-pairs. The host keeps its cached descriptor until then, so reports still
                // follow pairedProfile and the UI keeps asking for a re-pair.
                Log.i(TAG, "HID profile changed to $value - re-registering")
                reregisterForProfile()
            }
        }

    /**
     * The profile the host was paired with. The host caches the descriptor at pairing
     * time, so this is what the host actually decodes until the user re-pairs. Reports are
     * encoded with this, never with [hidProfile], or the host parses the wrong length.
     */
    private val _pairedProfile = MutableStateFlow(HidProfile.COMPOSITE)
    val pairedProfile: StateFlow<HidProfile> = _pairedProfile

    /** True while a profile change is tearing the old registration down to publish a new one. */
    private var reregisterPending = false

    /** True between the stack accepting registerApp() and its status callback arriving.
     * Without this, a second start() re-registers while the first attempt is still in flight;
     * the stack rejects the duplicate and schedules a spurious retry. */
    private var registerInFlight = false
    private var proxyPending = false

    /**
     * True after an explicit [stop]. The proxy teardown in [stop] fires
     * `onServiceDisconnected`, whose self-heal would otherwise re-register the HID device
     * seconds later — turning the master switch back on by itself.
     */
    private var stopped = false
    private var hostDevice: BluetoothDevice? = null
    private var lastGamepadReport: ByteArray? = null
    private var lastKeyboardReport: ByteArray? = null
    private var registerRetryCount = 0
    private var connectTimeoutTarget: String? = null
    private var reportsSent = 0
    private var lastDeliveryDropLogAt = 0L

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

    /** Authoritative per-device state; -1 when the proxy or permission is unavailable. */
    private fun connectionStateOf(device: BluetoothDevice): Int = try {
        hidDevice?.getConnectionState(device) ?: -1
    } catch (_: SecurityException) {
        -1
    }

    /**
     * Loads the profile that matches the connection the user chose, so a registration started
     * by the foreground service - before the settings screen is ever opened - uses the right
     * descriptor. The profile is derived from the host type; there is no separate pref.
     */
    private fun loadStoredProfile() {
        val prefs = appContext.getSharedPreferences("hid_gamepad", Context.MODE_PRIVATE)
        val profile = profileForHostType(hostTypeFromStorage(prefs.getString("host_type", null)))
        if (profile != hidProfile) {
            Log.i(TAG, "restoring HID profile for host type: $profile")
            hidProfile = profile
        }
        // The host's descriptor is the one it was paired with, which can lag the selection.
        val paired = pairedProfileFromStorage(prefs.getString("paired_profile", null), profile)
        if (paired != _pairedProfile.value) {
            Log.i(TAG, "restoring paired HID profile: $paired")
            _pairedProfile.value = paired
        }
    }

    /**
     * The user confirms the host was re-paired. The phone cannot observe the host's cached
     * descriptor, so this explicit signal is what clears the warning and switches reports to
     * the selected profile.
     */
    fun confirmRepaired() {
        val profile = hidProfile
        if (_pairedProfile.value == profile) return
        Log.i(TAG, "user confirmed a re-pair; reports now follow $profile")
        _pairedProfile.value = profile
        appContext.getSharedPreferences("hid_gamepad", Context.MODE_PRIVATE)
            .edit().putString("paired_profile", profile.name).apply()
        // Re-seed so the host gets one report in the new format even before the next input.
        lastGamepadReport = reportForHost(profile, profile, GamepadState())
    }

    /**
     * Unregister and re-register so a profile change publishes its descriptor to the local
     * stack. The live host is dropped, which is expected: it has to re-pair to read the new
     * descriptor, and the UI tells the user so.
     */
    private fun reregisterForProfile() {
        val hd = hidDevice ?: return
        if (reregisterPending) return
        reregisterPending = true
        Log.i(TAG, "re-registering for profile $hidProfile")
        mainHandler.removeCallbacks(retryRegister)
        registerRetryCount = 0
        try {
            hd.unregisterApp()
        } catch (_: Exception) {
            Log.w(TAG, "unregisterApp failed while switching profile")
        }
        appRegistered = false
        registerInFlight = false
        hostDevice = null
        lastGamepadReport = null
        lastKeyboardReport = null
        stopHeartbeat()
        stopConnectionPoll()
        _status.value = Status.Unregistered
        // If the unregister callback never arrives, retry the registration anyway.
        mainHandler.postDelayed(retryRegister, RETRY_MS)
    }

    /** Fetch the HID DEVICE proxy, then register the app (SDP record + descriptor). */
    fun start() {
        stopped = false
        loadStoredProfile()
        val a = adapter ?: run {
            Log.w(TAG, "start(): no Bluetooth adapter — HID device unsupported")
            _status.value = Status.Unsupported
            return
        }
        if (!a.isEnabled) {
            Log.i(TAG, "start(): Bluetooth is off — registration deferred until it turns on")
            _status.value = Status.BluetoothOff
            return
        }
        if (hidDevice != null) {
            Log.i(TAG, "start(): proxy already up (registered=$appRegistered)")
            if (!appRegistered) registerApp()
            refreshConnected("start: proxy already up")
            return
        }
        if (proxyPending) {
            Log.i(TAG, "start(): proxy fetch already pending")
            refreshConnected("start: proxy pending")
            return
        }
        Log.i(TAG, "start(): fetching HID DEVICE proxy")
        proxyPending = true
        _status.value = Status.Unregistered
        try {
            a.getProfileProxy(appContext, serviceListener, BluetoothProfile.HID_DEVICE)
        } catch (_: SecurityException) {
            Log.w(TAG, "start(): getProfileProxy denied — check BLUETOOTH_CONNECT")
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
            registerInFlight = false
            hostDevice = null
            lastGamepadReport = null
            lastKeyboardReport = null
            stopHeartbeat()
            stopConnectionPoll()
            _status.value = if (bluetoothEnabled()) Status.Unregistered else Status.BluetoothOff
            // Self-heal: if Bluetooth is still on but the proxy died silently, fetch it again.
            // Skip it after an explicit stop(), which is the master switch being turned off.
            if (shouldSelfHealProxy(stopped, bluetoothEnabled())) {
                mainHandler.postDelayed({ start() }, RETRY_MS)
            }
        }
    }

    private fun setActiveHost(live: BluetoothDevice) {
        val wasHost = hostDevice
        val replacing = hostReplacementNeedsNeutral(wasHost?.address, live.address)
        hostDevice = live
        Log.i(
            TAG,
            "active host: ${deviceName(live)}/${live.address} connState=${connectionStateOf(live)}",
        )
        if (replacing) {
            releaseAllInputs()
        }
        emptyTruthChecks = 0
        _status.value = Status.Connected(deviceName(live))
        startHeartbeat()
        cancelConnectTimeout()
    }

    private fun clearNoLiveHost() {
        if (hostDevice != null) {
            Log.i(TAG, "clearing active host and releasing inputs")
            releaseAllInputs()
        }
        stopHeartbeat()
        cancelConnectTimeout()
        hostDevice = null
        lastGamepadReport = null
        lastKeyboardReport = null
        _status.value = if (appRegistered) Status.Registered else Status.Unregistered
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
            setActiveHost(live)
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
                    clearNoLiveHost()
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
            registerInFlight = false
            appRegistered = registered
            onRegistrationChanged?.invoke(registered)
            if (registered) {
                reregisterPending = false
                registerRetryCount = 0
                mainHandler.removeCallbacks(retryRegister)
                if (pluggedDevice != null) {
                    // The SDP callback can carry a stale "last plugged" device even when the
                    // link is down. Only adopt it when the authoritative state agrees.
                    val connState = connectionStateOf(pluggedDevice)
                    if (shouldAdoptPluggedDevice(registered, connState)) {
                        setActiveHost(pluggedDevice)
                    } else {
                        Log.i(
                            TAG,
                            "ignoring plugged=${deviceName(pluggedDevice)} connState=$connState " +
                                "(not an authoritative CONNECTED)",
                        )
                        refreshConnected("app registered, plugged not connected")
                    }
                } else {
                    refreshConnected("app registered")
                }
                if (hostDevice == null && _status.value !is Status.Connected) {
                    _status.value = Status.Registered
                }
                startConnectionPoll()
            } else {
                if (hostDevice != null) releaseAllInputs()
                hostDevice = null
                lastGamepadReport = null
                lastKeyboardReport = null
                stopHeartbeat()
                stopConnectionPoll()
                _status.value = Status.Unregistered
                if (reregisterPending) {
                    // The unregister that precedes a profile change completed: publish the
                    // new descriptor now instead of scheduling a backoff retry.
                    reregisterPending = false
                    mainHandler.removeCallbacks(retryRegister)
                    registerApp()
                } else if (hidDevice != null) {
                    // The stack can reject registration (e.g. another HID app holds it); retry with backoff.
                    scheduleRegisterRetry()
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.i(TAG, "onConnectionStateChanged ${deviceName(device)} state=$state " +
                "(${stateName(state)})")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Do not treat a reconnect as a re-pair: the host keeps the descriptor it
                    // cached when it was paired, so only the user can confirm a re-pair.
                    refreshConnected("state connected")
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    Log.i(TAG, "connecting to ${deviceName(device)}; awaiting CONNECTED before sending")
                    if (_status.value !is Status.Connected && _status.value !is Status.Connecting) {
                        _status.value = Status.Connecting(deviceName(device))
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING -> {
                    if (hostDevice != null && device.address != hostDevice?.address) {
                        Log.i(TAG, "ignoring disconnect for non-active host ${deviceName(device)}")
                    } else {
                        val other = try {
                            hidDevice?.getConnectedDevices()?.firstOrNull { it.address != device.address }
                        } catch (_: SecurityException) {
                            null
                        }
                        if (other != null) {
                            setActiveHost(other)
                        } else {
                            clearNoLiveHost()
                        }
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
        if (appRegistered || proxyPending || registerInFlight) return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "HID Gamepad",
            "Bluetooth gamepad for host computers",
            "HidGamepad",
            subclassFor(hidProfile),
            descriptorFor(hidProfile),
        )
        Log.i(TAG, "registerApp requested (attempt ${registerRetryCount + 1})")
        registerInFlight = true
        val accepted: Boolean = try {
            // API 28+ signature: QoS settings optional (pass null for defaults).
            hd.registerApp(sdp, null, null, executor, hidCallback)
        } catch (_: SecurityException) {
            Log.w(TAG, "registerApp failed: SecurityException")
            false
        } catch (_: IllegalStateException) {
            Log.w(TAG, "registerApp failed: IllegalStateException")
            false
        }
        if (registerRetryDecision(accepted) == RegisterRetryDecision.ScheduleRetry) {
            Log.w(TAG, "registerApp rejected: scheduled retry")
            registerInFlight = false
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
        val name = deviceName(device)
        val hd = hidDevice
        if (hd == null || !appRegistered) {
            cancelConnectTimeout()
            _status.value = Status.ConnectFailed(name)
            return false
        }
        val ok = try {
            hd.connect(device)
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
        Log.i(TAG, "connect($name) -> $ok")
        if (ok) {
            _status.value = Status.Connecting(name)
            connectTimeoutTarget = name
            mainHandler.removeCallbacks(connectTimeout)
            mainHandler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        } else {
            cancelConnectTimeout()
            _status.value = Status.ConnectFailed(name)
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
                if (hostDevice != null) releaseAllInputs()
                hostDevice = null
                lastGamepadReport = null
                lastKeyboardReport = null
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

    /** Push a fresh gamepad snapshot; sends only when the report actually changed. */
    fun updateGamepad(state: GamepadState) {
        if (hostDevice == null) return
        val report = reportForHost(hidProfile, _pairedProfile.value, state)
        if (lastGamepadReport?.contentEquals(report) == true) return
        lastGamepadReport = report
        sendReportNow(GAMEPAD_REPORT_ID, report)
    }

    fun updateKeyboard(state: KeyboardState) {
        if (hostDevice == null) return
        val report = state.encodeReport()
        if (lastKeyboardReport?.contentEquals(report) == true) return
        lastKeyboardReport = report
        sendReportNow(KEYBOARD_REPORT_ID, report)
    }

    fun sendMouse(state: MouseState) {
        if (hostDevice == null) return
        val split = state.splitIntoReports()
        for ((index, report) in split.withIndex()) {
            logMouseSend(
                index = index,
                total = split.size,
                sourceState = state,
                chunk = report,
            )
            sendReportNow(MOUSE_REPORT_ID, report.encodeReport())
        }
    }

    private fun runtimeTargetSummary(): String {
        val hd = hidDevice
        val host = hostDevice
        return "registered=$appRegistered host=${host?.let { deviceName(it) + "/" + it.address }} hidDevice=${hd != null} connState=${host?.let { hd?.getConnectionState(it) } ?: -1}"
    }

    private fun logMouseSend(index: Int, total: Int, sourceState: MouseState, chunk: MouseState) {
        val host = hostDevice
        val payload = chunk.encodeReport()
        val payloadHex = payload.joinToString(" ") { "%02X".format(it) }
        val splitDescription = if (total > 1) " chunk ${index + 1}/$total of source dx=${sourceState.dx} dy=${sourceState.dy}" else ""
        Log.i(
            TAG,
            "MOUSE REPORT id=$MOUSE_REPORT_ID payload=[$payloadHex] length=${payload.size} " +
                "target=${runtimeTargetSummary()}$splitDescription",
        )
    }

    private fun sendReportNow(reportId: Int, payload: ByteArray) {
        val device = hostDevice ?: run {
            Log.w(TAG, "sendReportNow id=$reportId dropped: no connected host; target=${runtimeTargetSummary()}")
            return
        }
        val hd = hidDevice ?: run {
            Log.w(TAG, "sendReportNow id=$reportId dropped: no HID device proxy; host=${deviceName(device)}")
            return
        }
        // sendReport() returning true only means the stack accepted the write. Gate on the
        // authoritative connection state so a stale host reference cannot stream reports.
        val connState = connectionStateOf(device)
        if (!hostConnectionUsable(connState)) {
            val now = System.currentTimeMillis()
            if (now - lastDeliveryDropLogAt >= 1_000) {
                lastDeliveryDropLogAt = now
                Log.w(
                    TAG,
                    "sendReportNow id=$reportId dropped: host not connected connState=$connState " +
                        "target=${runtimeTargetSummary()}",
                )
            }
            return
        }
        try {
            val payloadHex = payload.joinToString(" ") { "%02X".format(it) }
            val accepted = hd.sendReport(device, reportId, payload)
            Log.i(
                TAG,
                "sendReport id=$reportId payload=[$payloadHex] length=${payload.size} " +
                    "target=${runtimeTargetSummary()} acceptedByAndroid=$accepted",
            )
            if (accepted) {
                reportsSent++
                if (reportsSent % 100 == 0) Log.i(TAG, "reports sent: $reportsSent")
            } else {
                // A stale host connection can keep the status callback alive while the stack
                // rejects reports; make that condition visible during diagnosis.
                Log.w(TAG, "Bluetooth stack rejected a HID report (id=$reportId, target=${runtimeTargetSummary()})")
            }
        } catch (_: IllegalArgumentException) {
            Log.w(TAG, "sendReport id=$reportId failed: IllegalArgumentException target=${runtimeTargetSummary()}")
        } catch (_: SecurityException) {
            Log.w(TAG, "sendReport id=$reportId failed: SecurityException target=${runtimeTargetSummary()}")
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            // Heartbeat is gamepad-only; relative mouse movement must never be repeated here.
            lastGamepadReport?.let { sendReportNow(GAMEPAD_REPORT_ID, it) }
            mainHandler.postDelayed(this, 100)
        }
    }

    private fun startHeartbeat() {
        // Seed the neutral report so the host starts receiving a heartbeat right away,
        // even before the first input happens.
        if (lastGamepadReport == null) {
            lastGamepadReport = reportForHost(hidProfile, _pairedProfile.value, GamepadState())
        }
        if (lastKeyboardReport == null) lastKeyboardReport = KeyboardState().encodeReport()
        lastGamepadReport?.let { sendReportNow(GAMEPAD_REPORT_ID, it) }
        mainHandler.removeCallbacks(heartbeat)
        mainHandler.postDelayed(heartbeat, 100)
    }

    private fun stopHeartbeat() {
        mainHandler.removeCallbacks(heartbeat)
    }

    fun sendConsumer(state: ConsumerState) {
        if (hostDevice == null) return
        sendReportNow(CONSUMER_REPORT_ID, state.encodeReport())
    }

    /** Immediately release every HID control, including when Android backgrounds the activity. */
    fun releaseAllInputs() {
        val keyboard = KeyboardState().encodeReport()
        val mouse = MouseState().encodeReport()
        val gamepad = reportForHost(hidProfile, _pairedProfile.value, GamepadState())
        val consumer = ConsumerState().encodeReport()
        sendReportNow(KEYBOARD_REPORT_ID, keyboard)
        sendReportNow(MOUSE_REPORT_ID, mouse)
        sendReportNow(GAMEPAD_REPORT_ID, gamepad)
        sendReportNow(CONSUMER_REPORT_ID, consumer)
        lastGamepadReport = gamepad
        lastKeyboardReport = keyboard
    }

    /** Unregister the HID app and release the profile proxy; safe to call repeatedly. */
    fun stop() {
        Log.i(TAG, "stop(): unregistering and releasing the HID proxy")
        stopped = true
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
        registerInFlight = false
        hostDevice = null
        lastGamepadReport = null
        lastKeyboardReport = null
        _status.value = Status.Unregistered
    }
}
