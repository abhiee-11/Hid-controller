package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RgbBacklightTest {

    private val base = RgbConfig(enabled = true, speed = 1f, brightness = 1f)

    private fun light(x: Float, y: Float, config: RgbConfig = base, pressed: Boolean = false, flashes: List<Flash> = emptyList()) =
        keyLight(config, x, y, index = 7, pressed = pressed, flashes = flashes, timeMillis = 3_000L)

    @Test
    fun `static lights every key the preset colour`() {
        val cfg = base.copy(effect = RgbEffect.STATIC, staticColor = 1)
        assertEquals(light(0.1f, 0.5f, cfg).color, light(0.9f, 0.5f, cfg).color)
        assertEquals(light(0.5f, 0.1f, cfg).color, light(0.5f, 0.9f, cfg).color)
    }

    @Test
    fun `color cycle gives every key one colour`() {
        val cfg = base.copy(effect = RgbEffect.COLOR_CYCLE)
        assertEquals(light(0.1f, 0.2f, cfg).color, light(0.9f, 0.8f, cfg).color)
    }

    @Test
    fun `rainbow gives neighbouring keys different hues`() {
        val cfg = base.copy(effect = RgbEffect.RAINBOW)
        assertTrue(light(0.1f, 0.5f, cfg).color != light(0.35f, 0.5f, cfg).color)
        assertTrue(light(0.1f, 0.5f, cfg).color != light(0.6f, 0.5f, cfg).color)
    }

    @Test
    fun `wave is brightest near the band, dim elsewhere`() {
        val cfg = base.copy(effect = RgbEffect.WAVE)
        val colors = (0..20).map { i -> light(i / 20f, 0.5f, cfg).intensity }
        assertTrue("some keys must be near full brightness", colors.any { it > 0.5f })
        assertTrue("some keys must be dim", colors.any { it < 0.35f })
    }

    @Test
    fun `reactive lights only a pressed key`() {
        val cfg = base.copy(effect = RgbEffect.REACTIVE, staticColor = 2)
        val idle = light(0.4f, 0.5f, cfg)
        val active = light(0.4f, 0.5f, cfg, pressed = true)
        assertTrue(idle.intensity < active.intensity)
        assertEquals(1f, active.intensity, 0.001f)
    }

    @Test
    fun `ripple is strongest at the origin of the flash`() {
        val cfg = base.copy(effect = RgbEffect.RIPPLE, staticColor = 3)
        val flash = Flash(usage = KeyUsage.A, x = 0.5f, y = 0.5f, atMillis = 3_000L - 50)
        val atOrigin = light(0.5f, 0.5f, cfg, flashes = listOf(flash))
        val farAway = light(0.05f, 0.05f, cfg, flashes = listOf(flash))
        assertTrue(atOrigin.intensity > 0.6f)
        assertTrue(farAway.intensity < atOrigin.intensity)
    }

    @Test
    fun `aurora, fire, meteor, starlight and comet stay in colour range`() {
        for (effect in listOf(RgbEffect.AURORA, RgbEffect.FIRE, RgbEffect.METEOR, RgbEffect.STARLIGHT, RgbEffect.COMET, RgbEffect.FLICKER, RgbEffect.SNAKE, RgbEffect.SCANNER, RgbEffect.ARROW)) {
            val zone = light(0.4f, 0.5f, base.copy(effect = effect))
            assertTrue("$effect intensity must be finite and non-negative", zone.intensity >= 0f)
        }
    }

    @Test
    fun `brightness scales the backlight colour`() {
        val dim = light(0.4f, 0.5f, base.copy(effect = RgbEffect.STATIC, brightness = 0.3f))
        val full = light(0.4f, 0.5f, base.copy(effect = RgbEffect.STATIC, brightness = 1f))
        assertTrue(dim.color != full.color)
        assertEquals(dim.intensity, full.intensity, 0.001f)
    }

    @Test
    fun `brightness steps clamp to the supported range`() {
        assertEquals(0.2f, adjustBrightness(0.1f, 0.1f), 0.001f)
        assertEquals(RGB_BRIGHTNESS_MIN, adjustBrightness(0.1f, -0.1f), 0.001f)
        assertEquals(RGB_BRIGHTNESS_MAX, adjustBrightness(0.9f, 0.1f), 0.001f)
        assertEquals(RGB_BRIGHTNESS_MAX, adjustBrightness(1f, 0.1f), 0.001f)
        assertEquals(0.5f, adjustBrightness(0.6f, -0.1f), 0.001f)
    }

    @Test
    fun `every effect produces a colour for every key`() {
        for (effect in RgbEffect.entries) {
            val cfg = base.copy(effect = effect)
            for (x in listOf(0.05f, 0.3f, 0.55f, 0.8f, 0.97f)) {
                for (y in listOf(0.1f, 0.5f, 0.9f)) {
                    val zone = keyLight(cfg, x, y, index = 3, pressed = false, flashes = emptyList(), timeMillis = 1234L)
                    assertTrue("$effect @ ($x,$y) produced a non-finite intensity", zone.intensity.isFinite())
                    assertTrue("$effect @ ($x,$y) intensity negative", zone.intensity >= 0f)
                }
            }
        }
    }

    @Test
    fun `hash is deterministic and in range`() {
        for (a in -50..50 step 7) {
            for (b in 0..20) {
                val h = hash2(a, b)
                assertTrue(h >= 0f && h < 1f)
            }
        }
        assertEquals(hash2(4, 9), hash2(4, 9), 0.0f)
    }

    @Test
    fun `no effect ever reports an intensity above one, even with a future-dated flash`() {
        // A Flash stamped ahead of the clock (the system clock moved backwards) made the
        // reactive effect compute a full pass plus extra, and RIPPLE multiply it again.
        val future = Flash(usage = KeyUsage.A, x = 0.4f, y = 0.5f, atMillis = 3_000L + 10_000L)
        for (effect in RgbEffect.entries) {
            val zone = light(0.4f, 0.5f, base.copy(effect = effect), flashes = listOf(future))
            assertTrue("$effect intensity ${zone.intensity} must be at most 1", zone.intensity <= 1f)
            assertTrue("$effect intensity ${zone.intensity} must be at least 0", zone.intensity >= 0f)
        }
    }

    @Test
    fun `ripple is clamped to full intensity at the flash origin`() {
        val cfg = base.copy(effect = RgbEffect.RIPPLE, staticColor = 3)
        val flash = Flash(usage = KeyUsage.A, x = 0.5f, y = 0.5f, atMillis = 2_990L)
        val zone = light(0.5f, 0.5f, cfg, flashes = listOf(flash))
        assertTrue("intensity must not exceed 1, got ${zone.intensity}", zone.intensity <= 1f)
    }
}
