package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class TiltTest {

    private fun close(a: Float, b: Float, eps: Float = 1e-4f): Boolean = kotlin.math.abs(a - b) <= eps

    // ---------- quaternion primitives ----------

    @Test
    fun `identity quaternion has zero twist`() {
        assertEquals(0f, twistZ(floatArrayOf(0f, 0f, 0f, 1f)), 1e-7f)
    }

    @Test
    fun `twistZ recovers the rotation angle about Z`() {
        val third = (PI / 6).toFloat() // 30 degrees
        val half = (PI / 4).toFloat()  // 45 degrees
        assertTrue(close(third, twistZ(quatFromTwistZ(third))))
        assertTrue(close(half, twistZ(quatFromTwistZ(half))))
        assertTrue(close(-half, twistZ(quatFromTwistZ(-half))))
    }

    @Test
    fun `relative twist is conjugate composition`() {
        // q0 = +30deg, sample = +60deg -> relative = +30deg
        val q0 = quatFromTwistZ((PI / 6).toFloat())
        val q = quatFromTwistZ((PI / 3).toFloat())
        assertTrue(close((PI / 6).toFloat(), relativeTwist(q, q0)))
        // sample at the calibrated pose -> 0
        assertTrue(close(0f, relativeTwist(q0, q0)))
        // sample behind the zero pose -> -30deg
        assertTrue(close(-(PI / 6).toFloat(), relativeTwist(quatFromTwistZ(0f), q0)))
    }

    @Test
    fun `conjugate composes to identity rotation`() {
        val q = quatFromTwistZ((PI / 3).toFloat())
        val identity = quatMul(quatConj(q), q)
        assertTrue(close(0f, identity[0], 1e-5f))
        assertTrue(close(0f, identity[1], 1e-5f))
        assertTrue(close(0f, identity[2], 1e-5f))
        assertTrue(close(1f, identity[3], 1e-5f))
    }

    // ---------- steering shaping ----------

    @Test
    fun `steering deadzone flattens the center`() {
        assertEquals(0f, applySteering(0f, 0.1f, 1f))
        assertEquals(0f, applySteering(0.09f, 0.1f, 1f))
        assertEquals(0f, applySteering(-0.09f, 0.1f, 1f))
    }

    @Test
    fun `steering applies sensitivity past the deadzone`() {
        // (0.5-0.1)/0.9 * 2.0 = 0.888...
        assertTrue(close(0.8889f, applySteering(0.5f, 0.1f, 2f), 1e-3f))
        // sensitivity 1 keeps the reshaped value
        assertTrue(close((0.5f - 0.1f) / 0.9f, applySteering(0.5f, 0.1f, 1f)))
    }

    @Test
    fun `steering clamps to the stick range and keeps sign`() {
        assertEquals(1f, applySteering(4f, 0.05f, 2f))
        assertEquals(-1f, applySteering(-4f, 0.05f, 2f))
        assertTrue(applySteering(-0.7f, 0.05f, 1f) < 0f)
    }

    @Test
    fun `zero deadzone passes the raw value through unshaped`() {
        assertEquals(0f, applySteering(0f, 0f, 1f))
        assertTrue(close(0.5f, applySteering(0.5f, 0f, 1f), 1e-5f))
    }

    @Test
    fun `full wheel twist equals the calibration cap on both sides`() {
        val identity = floatArrayOf(0f, 0f, 0f, 1f)
        // steer value = -twist / MAX_STEER_RAD: a clockwise wheel twist saturates at -1,
        // the opposite direction at +1.
        val cw = quatFromTwistZ(TiltController.MAX_STEER_RAD)
        assertEquals(-1f, -(relativeTwist(cw, identity) / TiltController.MAX_STEER_RAD), 1e-5f)
        val ccw = quatFromTwistZ(-TiltController.MAX_STEER_RAD)
        assertEquals(1f, -(relativeTwist(ccw, identity) / TiltController.MAX_STEER_RAD), 1e-5f)
    }

    // ---------- smoothing ----------

    @Test
    fun `ema moves a quarter toward the target each event`() {
        assertEquals(0.25f, ema(0f, 1f, TiltController.SMOOTHING_ALPHA), 1e-7f)
        assertEquals(0.4375f, ema(0.25f, 1f, TiltController.SMOOTHING_ALPHA), 1e-7f)
    }

    @Test
    fun `ema with no change holds steady`() {
        assertEquals(0.5f, ema(0.5f, 0.5f, TiltController.SMOOTHING_ALPHA), 1e-7f)
    }

    @Test
    fun `ema converges from -1 to +1 over time`() {
        var v = -1f
        repeat(200) { v = ema(v, 1f, TiltController.SMOOTHING_ALPHA) }
        assertTrue("converged: $v", v > 0.9999f)
    }

    @Test
    fun `tilt owns left stick X while enabled`() {
        assertEquals(0.6f, resolveLeftStickX(true, -0.9f, 0.6f), 1e-7f)
        assertEquals(-0.4f, resolveLeftStickX(true, 0.8f, -0.4f), 1e-7f)
    }

    @Test
    fun `touch owns left stick X while tilt is disabled`() {
        assertEquals(-0.9f, resolveLeftStickX(false, -0.9f, 0.6f), 1e-7f)
        assertEquals(0.8f, resolveLeftStickX(false, 0.8f, -0.4f), 1e-7f)
    }

    @Test
    fun `toggling tilt does not erase a held touch X value`() {
        val heldTouchX = -0.9f
        val tiltCenter = 0f
        assertEquals(heldTouchX, resolveLeftStickX(false, heldTouchX, tiltCenter), 1e-7f)
    }
}
