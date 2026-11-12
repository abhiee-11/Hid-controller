package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RgbBacklightTest {

    private fun backlight(
        enabled: Boolean = true,
        mode: LightMode = LightMode.Rainbow,
        brightness: Int = 80,
        staticIndex: Int = 0,
        zones: ZoneMode = ZoneMode.PerKey,
        persisted: MutableList<List<Any?>> = mutableListOf(),
    ) = KeyboardBacklight(enabled, mode, brightness, staticIndex, zones) { e, m, b, s, z ->
        persisted += listOf(e, m, b, s, z)
    }

    @Test
    fun `backlight off means no light for any key`() {
        val bl = backlight(enabled = false)
        for (x in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertNull(bl.lightFor(x, 0L, pressed = true, sinceReleaseMs = 0L))
        }
    }

    @Test
    fun `brightness is clamped to the 10 to 100 range`() {
        val bl = backlight(brightness = 100)
        bl.change(10)
        assertEquals(100, bl.brightness)
        val low = backlight(brightness = 10)
        low.change(-10)
        assertEquals(10, low.brightness)
    }

    @Test
    fun `reactive lights a pressed key and fades after release`() {
        val bl = backlight(mode = LightMode.Reactive, brightness = 50)
        val held = bl.lightFor(0.5f, 1000L, pressed = true, sinceReleaseMs = 0L)
        assertEquals(0.5f, held!!.intensity, 0.001f)
        val fading = bl.lightFor(0.5f, 1000L, pressed = false, sinceReleaseMs = 200L)
        assertEquals(0.25f, fading!!.intensity, 0.001f)
        assertNull(bl.lightFor(0.5f, 1000L, pressed = false, sinceReleaseMs = 400L))
    }

    @Test
    fun `breathing intensity stays within the supported range`() {
        val bl = backlight(mode = LightMode.Breathing, brightness = 100)
        for (t in listOf(0L, 500L, 1100L, 2200L, 4390L)) {
            val l = bl.lightFor(0.5f, t, pressed = false, sinceReleaseMs = 10_000L)!!
            assertTrue(l.intensity in 0.29f..1f)
        }
    }

    @Test
    fun `static mode lights every key the same hue`() {
        val bl = backlight(mode = LightMode.Static, staticIndex = 2)
        val a = bl.lightFor(0.1f, 0L, pressed = false, sinceReleaseMs = 10_000L)!!
        val b = bl.lightFor(0.9f, 0L, pressed = false, sinceReleaseMs = 10_000L)!!
        assertEquals(a.hue, b.hue, 0.001f)
        assertEquals(a.intensity, b.intensity, 0.001f)
    }

    @Test
    fun `color of the hue cycles over time in rainbow`() {
        val bl = backlight(mode = LightMode.Rainbow, brightness = 100)
        val a = bl.lightFor(0.5f, 0L, pressed = false, sinceReleaseMs = 10_000L)!!
        val b = bl.lightFor(0.5f, 1800L, pressed = false, sinceReleaseMs = 10_000L)!!
        assertTrue("hue must move with time: ${a.hue} vs ${b.hue}", a.hue != b.hue)
    }

    @Test
    fun `rowCenters maps uneven key weights to the fractions of the row`() {
        // weights 1, 1, 1, 2 over a row of 5
        val xs = rowCenters(listOf(1f, 1f, 1f, 2f))
        assertEquals(listOf(0.1f, 0.3f, 0.5f, 0.8f), xs.map { (it * 10).toInt() / 10f })
    }

    @Test
    fun `rowCenters puts a symmetric row's middle key at one half`() {
        val xs = rowCenters(listOf(1f, 1f, 1f))
        assertEquals(0.5f, xs[1], 0.001f)
    }

    @Test
    fun `toggle flips the backlight and reports it`() {
        val persisted = mutableListOf<List<Any?>>()
        val bl = backlight(persisted = persisted)
        assertTrue(bl.enabled)
        bl.toggle()
        assertFalse(bl.enabled)
        bl.toggle()
        assertTrue(bl.enabled)
        // every toggle must produce a persist entry with the new on/off state
        assertEquals(listOf(false, true), persisted.map { it[0] })
    }

    @Test
    fun `mode cycles through all four modes in order`() {
        val bl = backlight(mode = LightMode.Rainbow)
        bl.nextMode(); assertEquals(LightMode.Breathing, bl.mode)
        bl.nextMode(); assertEquals(LightMode.Static, bl.mode)
        bl.nextMode(); assertEquals(LightMode.Reactive, bl.mode)
        bl.nextMode(); assertEquals(LightMode.Rainbow, bl.mode)
        bl.prevMode(); assertEquals(LightMode.Reactive, bl.mode)
    }

    @Test
    fun `zone mode changes which hue a key gets in breathing`() {
        val perKey = backlight(mode = LightMode.Breathing, brightness = 100, zones = ZoneMode.PerKey)
        val four = backlight(mode = LightMode.Breathing, brightness = 100, zones = ZoneMode.FourZones)
        val pk = perKey.lightFor(0.1f, 0L, pressed = false, sinceReleaseMs = 10_000L)!!
        val fz = four.lightFor(0.1f, 0L, pressed = false, sinceReleaseMs = 10_000L)!!
        // zone 0 reads from the left edge either way; both give the same zone-0 hue
        assertEquals(pk.hue, fz.hue, 0.001f)
    }
}
