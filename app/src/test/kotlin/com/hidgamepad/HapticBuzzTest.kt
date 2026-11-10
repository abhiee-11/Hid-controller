package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class HapticBuzzTest {

    @Test
    fun strengthIsClampedToSupportedRange() {
        assertEquals(0.25f, 0.1f.coerceIn(0.25f, 1.5f))
        assertEquals(1.5f, 3.0f.coerceIn(0.25f, 1.5f))
        assertEquals(1.0f, 1.0f.coerceIn(0.25f, 1.5f))
    }

    @Test
    fun amplitudeScalesWithStrength() {
        val less = (255f * 0.25f).roundToInt().coerceIn(1, 255)
        val normal = (255f * 1.0f).roundToInt().coerceIn(1, 255)
        val more = (255f * 1.5f).roundToInt().coerceIn(1, 255)
        assertEquals(64, less)
        assertEquals(255, normal)
        assertEquals(255, more) // saturated at max
        assertTrue(less < normal)
    }

    @Test
    fun durationScalesWithStrength() {
        val lessMs = (30f * 0.25f).coerceIn(10f, 60f).toInt()
        val normalMs = (30f * 1.0f).coerceIn(10f, 60f).toInt()
        val moreMs = (30f * 1.5f).coerceIn(10f, 60f).toInt()
        assertEquals(10, lessMs)
        assertEquals(30, normalMs)
        assertEquals(45, moreMs)
    }
}
