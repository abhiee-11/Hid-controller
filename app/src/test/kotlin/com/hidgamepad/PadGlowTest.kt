package com.hidgamepad

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PadGlowTest {

    private val base = PadGlowConfig(enabled = true, effect = PadGlowEffect.STATIC, brightness = 1f)

    @Test
    fun `disabled pad glow draws nothing`() {
        assertNull(padGlowColor(base.copy(enabled = false), 0.5f, false, 0L))
    }

    @Test
    fun `enabled static glow draws the preset colour`() {
        val c = padGlowColor(base, 0.5f, false, 0L)
        assertTrue(c != null && c.alpha > 0f)
    }

    @Test
    fun `glow does not depend on the keyboard backlight being on`() {
        // The pad glow is independent: its own config is all it needs.
        assertNotNull(padGlowColor(base, 0.2f, false, 1234L))
    }

    @Test
    fun `rainbow changes hue around the border and over time`() {
        // Hue wraps every 360 degrees, so compare opposite midpoints, not the endpoints.
        val a = padGlowColor(base.copy(effect = PadGlowEffect.RAINBOW), 0f, false, 0L)!!
        val b = padGlowColor(base.copy(effect = PadGlowEffect.RAINBOW), 0.5f, false, 0L)!!
        val later = padGlowColor(base.copy(effect = PadGlowEffect.RAINBOW), 0f, false, 500L)!!
        assertNotEquals(a, b)
        assertNotEquals(a, later)
    }

    @Test
    fun `reactive is dim until touched, then bright`() {
        val cfg = base.copy(effect = PadGlowEffect.REACTIVE)
        val off = padGlowColor(cfg, 0.5f, false, 0L)!!
        val on = padGlowColor(cfg, 0.5f, true, 0L)!!
        assertTrue("touched must be brighter", luminance(on) > luminance(off))
    }

    @Test
    fun `brightness scales the glow`() {
        val dim = padGlowColor(base.copy(brightness = 0.2f), 0.5f, false, 0L)!!
        val bright = padGlowColor(base.copy(brightness = 1f), 0.5f, false, 0L)!!
        assertTrue(luminance(bright) > luminance(dim))
    }

    private fun luminance(c: Color) = c.red * 0.2126f + c.green * 0.7152f + c.blue * 0.0722f
}
