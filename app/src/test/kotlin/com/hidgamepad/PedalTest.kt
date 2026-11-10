package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PedalTest {

    @Test
    fun `pedal is zero before the ramp start`() {
        assertEquals(0f, pedalValue(0f))
        assertEquals(0f, pedalValue(PEDAL_RAMP_START))
        assertEquals(0f, pedalValue(0.05f))
        assertEquals(0f, pedalValue(-0.4f)) // clamp below track
    }

    @Test
    fun `pedal is full beyond the ramp end`() {
        assertEquals(1f, pedalValue(PEDAL_RAMP_END))
        assertEquals(1f, pedalValue(1f))
        assertEquals(1f, pedalValue(1.4f)) // clamp beyond track
    }

    @Test
    fun `pedal ramps linearly between the edges`() {
        // 0.5 sits exactly mid-ramp: (0.5-0.1)/0.8 = 0.5 (within float precision)
        assertEquals(0.5f, pedalValue(0.5f), 1e-5f)
        // (0.25-0.1)/0.8 = 0.1875
        assertEquals(0.1875f, pedalValue(0.25f), 1e-6f)
        // quarter of the travel range -> quarter of the value
        assertEquals((pedalValue(0.5f) - 0f) / (pedalValue(PEDAL_RAMP_END) - 0f), 0.5f, 1e-6f)
    }

    @Test
    fun `pedal value is monotonically non-decreasing along the track`() {
        var prev = -1f
        for (step in 0..100) {
            val v = pedalValue((step / 100f) * 2f - 0.2f) // sweep -0.2 .. 1.8
            assertTrue("step $step: $v < $prev", v >= prev)
            prev = v
        }
    }

    @Test
    fun `combined pedal value is deepest press and empty is released`() {
        assertEquals(0f, combinePedal(emptyList()))
        assertEquals(0.4f, combinePedal(listOf(0.4f)))
        assertEquals(0.8f, combinePedal(listOf(0.3f, 0.8f, 0.5f)))
    }

    @Test
    fun `released finger drops out of the map leaving a neutral pedal`() {
        // Simulates the widget's per-pointer map: press two fingers, lift one, lift the rest.
        val values = mutableMapOf<Long, Float>()
        values[1L] = pedalValue(0.9f)   // deep press
        values[2L] = pedalValue(0.25f)  // shallow second finger on the same pedal
        assertEquals(1f, combinePedal(values.values))
        values.remove(1L)
        assertEquals(0.1875f, combinePedal(values.values), 1e-6f)
        values.remove(2L)
        assertEquals(0f, combinePedal(values.values))
    }

    @Test
    fun `encoder accepts an intermediate pedal value`() {
        // The exact value the UI maps mid-travel must survive the triggerByte path.
        val report = GamepadState(lt = pedalValue(0.5f)).encodeReport()
        assertEquals(128, report[7].toInt() and 0xFF) // 0.5 * 255 = 127.5 -> 128
    }
}
