package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The haptic arithmetic.
 *
 * [HapticBuzz.buzz] itself needs a real Android vibrator, so what these tests pin is the pure
 * amplitude/duration mapping that `buzz` calls — not a copy of the formula, which would keep
 * passing even if the production numbers changed.
 */
class HapticBuzzTest {

    @Test
    fun `strength is clamped to the supported range`() {
        // 0.1 clamps up to the 0.25 floor; 3.0 clamps down to the 1.5 ceiling.
        assertEquals(64, HapticBuzz.amplitudeFor(0.1f))
        assertEquals(255, HapticBuzz.amplitudeFor(3.0f))
    }

    @Test
    fun `amplitude scales with strength`() {
        val less = HapticBuzz.amplitudeFor(0.25f)
        val normal = HapticBuzz.amplitudeFor(1.0f)
        val more = HapticBuzz.amplitudeFor(1.5f)
        assertEquals(64, less)
        assertEquals(255, normal)
        assertEquals(255, more) // saturated at max
        assertTrue(less < normal)
    }

    @Test
    fun `duration scales with strength`() {
        assertEquals(10, HapticBuzz.durationMsFor(0.25f))
        assertEquals(30, HapticBuzz.durationMsFor(1.0f))
        assertEquals(45, HapticBuzz.durationMsFor(1.5f))
    }
}
