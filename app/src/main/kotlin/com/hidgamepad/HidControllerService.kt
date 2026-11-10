package com.hidgamepad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps the HID registration alive across activity backgrounding.
 *
 * Android documents that a HID Device app can be unregistered when it leaves the foreground.
 * Running this foreground service (type connectedDevice) is therefore part of the controller
 * contract, not a convenience wrapper. The service holds the same HidGamepadManager instance
 * the activity uses.
 */
class HidControllerService : Service() {

    private companion object {
        const val TAG = "HidGamepad"
        const val CHANNEL_ID = "hid_controller"
        const val NOTIFICATION_ID = 7
    }

    /**
     * The activity receiver only exists while the UI exists. Without this service-level
     * receiver, turning Bluetooth off and back on while the app is closed leaves the HID
     * manager sitting in BluetoothOff with nothing to wake it.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            Log.i(TAG, "service: adapter state changed to $state")
            if (state == BluetoothAdapter.STATE_ON) {
                Log.i(TAG, "service: Bluetooth is on — restarting HID registration")
                HidRuntime.get(this@HidControllerService).start()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "service: onCreate — starting HID runtime")
        HidRuntime.get(this).start()
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothStateReceiver, filter)
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "service: onStartCommand")
        HidRuntime.get(this).start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "service: onDestroy — unregistering HID runtime")
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was never registered (create failed early); nothing to release.
        }
        HidRuntime.stop()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HID controller",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the Bluetooth HID controller registered"
                setShowBadge(false)
                lightColor = Color.BLUE
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let {
            android.app.PendingIntent.getActivity(
                this,
                0,
                it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("HID Controller active")
            .setContentText("Bluetooth HID controller is registered")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}

/** Process-wide holder for the single HidGamepadManager instance. */
object HidRuntime {
    @Volatile
    var manager: HidGamepadManager? = null
        private set

    @Volatile
    private var contextRef: Context? = null

    fun get(context: Context): HidGamepadManager {
        val existing = manager
        if (existing != null) return existing
        synchronized(this) {
            val again = manager
            if (again != null) return again
            val appContext = context.applicationContext
            contextRef = appContext
            val created = HidGamepadManager(appContext)
            manager = created
            return created
        }
    }

    fun stop() {
        synchronized(this) {
            manager?.stop()
            manager = null
            contextRef = null
        }
    }
}
