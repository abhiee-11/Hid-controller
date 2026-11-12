package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Test

class PedalTest {

    @Test
    fun `a pressed pedal is full travel`() {
        // The trigger is digital: the phone screen cannot feel travel, so a touch anywhere
        // on the strip is a full pull. The old position ramp put the "R2" label — centred in
        // the strip — at half travel, which a host that thresholds the trigger reads as
        // "not pressed".
        assertEquals(1f, pedalValue(pressed = true))
    }

    @Test
    fun `a released pedal is no travel`() {
        assertEquals(0f, pedalValue(pressed = false))
    }

    @Test
    fun `a pressed trigger encodes as full travel`() {
        // The whole 0..255 range, not half of it: a host reads the trigger as an axis and
        // only reacts once the value crosses its own threshold.
        val pressed = GamepadState(rt = pedalValue(pressed = true)).encodeReport()
        assertEquals(255, pressed[8].toInt() and 0xFF)
        val released = GamepadState(rt = pedalValue(pressed = false)).encodeReport()
        assertEquals(0, released[8].toInt() and 0xFF)
    }

    @Test
    fun `combined pedal value is pressed while any finger is down`() {
        assertEquals(0f, combinePedal(emptyList()))
        assertEquals(1f, combinePedal(listOf(1f)))
        assertEquals(1f, combinePedal(listOf(1f, 1f)))
    }

    @Test
    fun `released finger drops out of the map leaving a neutral pedal`() {
        // Simulates the widget's per-pointer map: press two fingers, lift one, lift the rest.
        // Lifting one finger must not drop the trigger while the other is still down.
        val values = mutableMapOf<Long, Float>()
        values[1L] = pedalValue(pressed = true)
        values[2L] = pedalValue(pressed = true)
        assertEquals(1f, combinePedal(values.values))
        values.remove(1L)
        assertEquals(1f, combinePedal(values.values))
        values.remove(2L)
        assertEquals(0f, combinePedal(values.values))
    }
}
