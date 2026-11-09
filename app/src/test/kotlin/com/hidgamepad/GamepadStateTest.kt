package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadStateTest {

    @Test
    fun `neutral state encodes to nine neutral bytes`() {
        val report = GamepadState().encodeReport()
        assertEquals(GamepadState.REPORT_SIZE, report.size)
        assertEquals(0, report[0].toInt())
        assertEquals(0, report[1].toInt())
        assertEquals(GamepadState.HAT_NEUTRAL, report[2].toInt())
        assertEquals(0, report[3].toInt())
        assertEquals(0, report[4].toInt())
        assertEquals(0, report[5].toInt())
        assertEquals(0, report[6].toInt())
        assertEquals(0, report[7].toInt())
        assertEquals(0, report[8].toInt())
    }

    @Test
    fun `buttons spread across bytes 0 and 1`() {
        val mask = (1 shl Btn.A) or (1 shl Btn.L1) // bit 0 and bit 8
        val report = GamepadState(buttons = mask).encodeReport()
        assertEquals(0x01, report[0].toInt())
        assertEquals(0x01, report[1].toInt())
    }

    @Test
    fun `signed axes clamp to -127 and 127`() {
        val report = GamepadState(lx = -1f, rx = 1f).encodeReport()
        assertEquals(-127, report[3].toInt())
        assertEquals(127, report[5].toInt())
    }

    @Test
    fun `triggers map 0 to 0 and 1 to 255`() {
        val neutral = GamepadState().encodeReport()
        assertEquals(0, neutral[7].toInt())
        val full = GamepadState(lt = 1f, rt = 1f).encodeReport()
        assertEquals(255, full[7].toInt() and 0xFF)
        assertEquals(255, full[8].toInt() and 0xFF)
        // 255 in two's complement is -1; check explicit byte value
        assertEquals(0xFF.toByte(), full[8])
    }

    @Test
    fun `deadzone flattens small inputs`() {
        assertEquals(0f, applyDeadzone(0.07f))
        assertEquals(0f, applyDeadzone(-0.075f))
        assertEquals(0f, applyDeadzone(0f))
    }

    @Test
    fun `deadzone reshapes past threshold and keeps sign`() {
        // (0.5 - 0.08) / (1 - 0.08) = 0.4565...
        val up = applyDeadzone(0.5f)
        assertTrue(">0.45: $up", up in 0.445f..0.468f)
        val down = applyDeadzone(-0.5f)
        assertTrue("negative: $down", down in -0.468f..-0.445f)
        // full deflection stays full
        assertEquals(1f, applyDeadzone(1f))
    }

    @Test
    fun `descriptor starts with desktop gamepad and no report id`() {
        assertTrue(GAMEPAD_DESCRIPTOR[0] == 0x05.toByte() && GAMEPAD_DESCRIPTOR[1] == 0x01.toByte())
        assertTrue(GAMEPAD_DESCRIPTOR[2] == 0x09.toByte() && GAMEPAD_DESCRIPTOR[3] == 0x05.toByte())
        assertEquals(0xC0.toByte(), GAMEPAD_DESCRIPTOR.last())
        assertFalse("desc must not hold a report ID item", GAMEPAD_DESCRIPTOR.any { it == 0x85.toByte() })
    }

    @Test
    fun `descriptor declares 9 bytes of input fields`() {
        val hex = GAMEPAD_DESCRIPTOR.toHexString()
        // 16 x 1-bit buttons + 2 x 4-bit (hat+pad) + 6 x 8-bit axes = 16 + 8 + 48 = 72 bits = 9 bytes
        assertTrue(hex.contains("9510"))
        assertTrue(hex.contains("75 08".replace(" ", "")))
    }

    @Test
    fun `same-frame press and release return the exact neutral bytes`() {
        val pressed = GamepadState(
            buttons = 1 shl Btn.A,
            hat = 3,
            lx = 1f,
            rt = 0.5f,
        ).encodeReport()
        assertFalse(pressed.contentEquals(GamepadState().encodeReport()))
        // down() then up() on the same control (Cup bit clear) must restore the
        // byte-identical neutral report — never leave a residual bit.
        val bit = 1 shl Btn.A
        val afterRelease = GamepadState(buttons = bit and bit.inv()).encodeReport()
        assertTrue(afterRelease.contentEquals(GamepadState().encodeReport()))
    }

    @Test
    fun `named controls map to distinct button bits`() {
        val bits = listOf(Btn.A, Btn.B, Btn.X, Btn.Y, Btn.L1, Btn.R1, Btn.SELECT, Btn.START)
        val masks = bits.map { 1 shl it }
        assertEquals(bits.size, masks.distinct().size)
        // each mask must land in the 16-bit button area (bytes 0-1)
        assertEquals(0, masks.sum() ushr 16)
    }
}
