package com.hidgamepad

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Tilt steering: a rotation sensor (TYPE_GAME_ROTATION_VECTOR when the phone has one,
 * falling back to TYPE_ROTATION_VECTOR) steers the left stick X axis in landscape.
 * The driver twist about the Z axis / screen normal of the *relative* quaternion
 * (sample minus the zeroed/calibrated orientation) is mapped to a -1..1 stick value;
 * twisting the phone like a wheel turns the stick. The sensor must be of the
 * TYPE_GAME_ROTATION_VECTOR family when available: game-rotation vectors are
 * magnetometer-free, so re-zeroing is stable indoors.
 */
data class TiltTuning(
    val deadzone: Float = 0.05f,
    val sensitivity: Float = 1.0f,
)

/** Quaternion multiply a*b (both float[4] as x,y,z,w). */
fun quatMul(a: FloatArray, b: FloatArray): FloatArray {
    val ax = a[0]; val ay = a[1]; val az = a[2]; val aw = a[3]
    val bx = b[0]; val by = b[1]; val bz = b[2]; val bw = b[3]
    return floatArrayOf(
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
        aw * bw - ax * bx - ay * by - az * bz,
    )
}

/** Conjugate: the inverse of a unit quaternion. */
fun quatConj(q: FloatArray): FloatArray = floatArrayOf(-q[0], -q[1], -q[2], q[3])

/** Twist angle (radians) about the Z axis of a quaternion; ~= 2*atan2(z, w). */
fun twistZ(q: FloatArray): Float = 2f * atan2(q[2], q[3])

/** Quaternion for a rotation of [rad] about the Z axis — test helper made public for unit tests. */
fun quatFromTwistZ(rad: Float): FloatArray =
    floatArrayOf(0f, 0f, (rad / 2f).let { kotlin.math.sin(it) }, (rad / 2f).let { kotlin.math.cos(it) })

/** Relative sample = conj(zero) * sample: the twist measured from the calibrated pose. */
fun relativeTwist(sample: FloatArray, zero: FloatArray): Float = twistZ(quatMul(quatConj(zero), sample))

/**
 * Raw twist (-1..1 against [TiltController.MAX_STEER_RAD]) -> tuned stick value:
 * deadzone reshapes the center, sensitivity scales, hard-clamped to the stick range.
 */
fun applySteering(raw: Float, deadzone: Float, sensitivity: Float): Float =
    (applyDeadzone(raw, deadzone.coerceIn(0f, 1f)) * sensitivity).coerceIn(-1f, 1f)

/** Exponential moving average (alpha fraction of the new sample each call). */
fun ema(prev: Float, next: Float, alpha: Float): Float = prev + alpha * (next - prev)

/** Explicit left-stick X ownership rule: tilt owns X while it is enabled; otherwise touch owns it. */
fun resolveLeftStickX(tiltEnabled: Boolean, touchX: Float, tiltX: Float): Float =
    if (tiltEnabled) tiltX else touchX

class TiltController(
    context: Context,
    private val onSteer: (Float) -> Unit,
) : SensorEventListener {
    companion object {
        private const val TAG = "TiltController"
        /** Full stick deflection at this twist (radians); ~51 degrees of wheel twist. */
        const val MAX_STEER_RAD = 0.9f
        /** Smoothing strength per sensor event (SENSOR_DELAY_GAME is ~60 Hz). */
        const val SMOOTHING_ALPHA = 0.25f
        /** Below this both the raw value and the smoothed residue, snap to exact 0. */
        const val SNAP = 0.001f
    }

    private val manager = context.getSystemService(SensorManager::class.java)
    /** null = no usable rotation sensor; otherwise the sensor actually used. */
    val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // Sensor events fired on the main looper had the twist math competing with frame
    // production; the listener is registered on this thread instead. The thread is recreated
    // lazily in start() so a close() (which quits it) is not permanent.
    private var tiltThread = HandlerThread("tiltSteering").apply { start() }
    private var tiltHandler = Handler(tiltThread.looper)

    @Volatile private var registered = false
    @Volatile private var tuning = TiltTuning()
    @Volatile private var zero = floatArrayOf(0f, 0f, 0f, 1f)
    @Volatile private var latest: FloatArray? = null
    private var filtered = 0f
    /** True until the first sample of the current session is baked into [zero]. */
    @Volatile private var needZero = true

    /** Free-text of which sensor instance is steering (or "none"). */
    val sensorDescription: String
        get() = sensor?.let { "name=${it.name} type=${it.type}" } ?: "none"

    fun setTuning(t: TiltTuning) {
        tuning = t
    }

    /** Register the listener; logs which sensor wins. Returns [sensor] for assertions. */
    fun start(): Sensor? {
        if (!registered) {
            registered = true
            filtered = 0f
            needZero = true // calibrate against the current pose right away
            if (!tiltThread.isAlive) {
                tiltThread = HandlerThread("tiltSteering").apply { start() }
                tiltHandler = Handler(tiltThread.looper)
            }
            sensor?.let {
                manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, tiltHandler)
                Log.i(TAG, "tilt steering ON using $sensorDescription")
            } ?: Log.i(TAG, "tilt steering requested but no rotation sensor available")
        }
        return sensor
    }

    /** Unregister; stick is left at center by callers. */
    fun stop() {
        if (registered) {
            registered = false
            manager?.unregisterListener(this)
            Log.i(TAG, "tilt steering OFF — sensor listener unregistered, center emitted")
            onSteer(0f)
        }
    }

    /** Unregister and shut the sensor thread down for good. */
    fun close() {
        registered = false
        manager?.unregisterListener(this)
        onSteer(0f)
        tiltThread.quitSafely()
        tiltThread.join()
    }

    /** Calibrate: the next/currently-known physical pose becomes stick-center. */
    fun rezero() {
        val q = latest
        if (q != null) {
            zero = q.copyOf()
            needZero = false
        } else {
            // No sample yet in this session: flag it so the first event (when one
            // arrives) snaps the pose to center instead of using a stale fallback.
            needZero = true
        }
        Log.i(TAG, "rezero: q0 = [${zero[0]}, ${zero[1]}, ${zero[2]}, ${zero[3]}] (latest=${q != null})")
    }

    /** Drop any stale sample so [rezero] after re-enable uses a fresh pose. */
    fun onActivityPause() {
        latest = null
        stop()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!registered) return
        val v = event.values
        val x = v[0]
        val y = v[1]
        val z = v[2]
        val w = if (v.size >= 4) v[3] else sqrt((1f - x * x - y * y - z * z).coerceIn(0f, 1f))
        latest = floatArrayOf(x, y, z, w)
        if (needZero) {
            // First sample of the session: adopt the current pose as stick-center so the
            // wheel never jumps out of the user's hand when the toggle flips on.
            try {
                zero = latest!!.copyOf()
            } finally {
                needZero = false
            }
            Log.i(TAG, "zeroed on first sample: q0 = [${zero[0]}, ${zero[1]}, ${zero[2]}, ${zero[3]}]")
        }
        val raw = -(relativeTwist(latest!!, zero) / MAX_STEER_RAD)
        val tuned = applySteering(raw, tuning.deadzone, tuning.sensitivity)
        filtered = ema(filtered, tuned, SMOOTHING_ALPHA)
        if (tuned == 0f && kotlin.math.abs(filtered) < SNAP) filtered = 0f
        onSteer(filtered)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
