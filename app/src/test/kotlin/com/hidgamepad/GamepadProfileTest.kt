package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadProfileTest {

    @Test
    fun `gamepad only report is thirteen bytes`() {
        assertEquals(13, encodeGamepadOnlyReport(GamepadState()).size)
    }

    @Test
    fun `gamepad only report puts the buttons in the w3c standard order`() {
        // W3C standard layout: 0=A 1=B 2=X 3=Y 4=L1 5=R1 8=SELECT 9=START 10=L3 11=R3 12=MODE
        val state = GamepadState(buttons = 1 shl Btn.X)
        val report = encodeGamepadOnlyReport(state)
        assertEquals(0b0000_0100, report[0].toInt() and 0xFF)
    }

    @Test
    fun `every canonical button reaches a distinct w3c wire bit`() {
        val canonical = listOf(
            Btn.A, Btn.B, Btn.X, Btn.Y, Btn.L1, Btn.R1,
            Btn.SELECT, Btn.START, Btn.L3, Btn.R3, Btn.MODE,
        )
        val wires = canonical.map { w3cWireBit(it) }
        assertEquals(wires.size, wires.toSet().size)
        assertEquals(0, w3cWireBit(Btn.A))
        assertEquals(1, w3cWireBit(Btn.B))
        assertEquals(2, w3cWireBit(Btn.X))
        assertEquals(3, w3cWireBit(Btn.Y))
        assertEquals(4, w3cWireBit(Btn.L1))
        assertEquals(5, w3cWireBit(Btn.R1))
        assertEquals(8, w3cWireBit(Btn.SELECT))
        assertEquals(9, w3cWireBit(Btn.START))
        assertEquals(10, w3cWireBit(Btn.L3))
        assertEquals(11, w3cWireBit(Btn.R3))
        assertEquals(12, w3cWireBit(Btn.MODE))
    }

    @Test
    fun `sticks are signed sixteen bit and centred at zero`() {
        val report = encodeGamepadOnlyReport(GamepadState(lx = 0f, ly = 0f))
        assertEquals(0, report[2].toInt())
        assertEquals(0, report[3].toInt())
        assertEquals(0, report[4].toInt())
        assertEquals(0, report[5].toInt())
    }

    @Test
    fun `right stick is at axes two and three`() {
        val report = encodeGamepadOnlyReport(GamepadState(rx = 1f, ry = -1f))
        assertEquals(32767, axisWord(report, 6))
        assertEquals(-32767, axisWord(report, 8))
    }

    @Test
    fun `triggers are unsigned bytes at bytes ten and eleven`() {
        val report = encodeGamepadOnlyReport(GamepadState(lt = 1f, rt = 0f))
        assertEquals(255, report[10].toInt() and 0xFF)
        assertEquals(0, report[11].toInt() and 0xFF)
    }

    @Test
    fun `the descriptor declares a gamepad collection and no keyboard`() {
        val text = GAMEPAD_ONLY_DESCRIPTOR.toHex()
        assertTrue("usage page generic desktop", text.contains("05 01"))
        assertTrue("usage gamepad", text.contains("09 05"))
        assertTrue("must not declare a keyboard collection", !text.contains("05 07"))
        assertTrue("must not declare a mouse collection", !text.contains("09 02"))
    }

    @Test
    fun `the descriptor axes are X Y Rx Ry then Z and Rz`() {
        val text = GAMEPAD_ONLY_DESCRIPTOR.toHex()
        assertTrue(text.contains("09 30"))
        assertTrue(text.contains("09 31"))
        assertTrue(text.contains("09 33"))
        assertTrue(text.contains("09 34"))
        assertTrue(text.contains("09 32"))
        assertTrue(text.contains("09 35"))
    }

    @Test
    fun `the descriptor uses sixteen bit axes and eight bit triggers`() {
        val text = GAMEPAD_ONLY_DESCRIPTOR.toHex()
        // Report Size 16 for the sticks, Report Size 8 for the triggers.
        assertTrue("sixteen bit axes", text.contains("75 10"))
        assertTrue("eight bit triggers", text.contains("75 08"))
        // Sixteen one-bit buttons.
        assertTrue("sixteen buttons", text.contains("95 10"))
    }

    @Test
    fun `profile round trips through storage`() {
        assertEquals(HidProfile.COMPOSITE, hidProfileFromStorage(null))
        assertEquals(HidProfile.COMPOSITE, hidProfileFromStorage("COMPOSITE"))
        assertEquals(HidProfile.GAMEPAD_ONLY, hidProfileFromStorage("GAMEPAD_ONLY"))
        // An unrecognised stored value must fall back to the profile that has always
        // worked, not silently switch the user's pairing to a different descriptor.
        assertEquals(HidProfile.COMPOSITE, hidProfileFromStorage("nonsense"))
    }

    @Test
    fun `the canonical mask survives both encoders without stray bits`() {
        // The two profiles must not corrupt each other: a canonical button must land on
        // exactly one wire bit in each profile, and no unused canonical bit may leak.
        val composite = reportFor(HidProfile.COMPOSITE, GamepadState(buttons = 1 shl Btn.X))
        assertEquals(0x08, composite[0].toInt() and 0xFF)

        val only = reportFor(HidProfile.GAMEPAD_ONLY, GamepadState(buttons = 1 shl Btn.X))
        assertEquals(0x04, only[0].toInt() and 0xFF)
        assertEquals(0, only[1].toInt() and 0xFF)
    }

    @Test
    fun `an empty mask produces no button bits in either profile`() {
        for (profile in HidProfile.entries) {
            val report = reportFor(profile, GamepadState())
            assertEquals(0, report[0].toInt() and 0xFF)
            assertEquals(0, report[1].toInt() and 0xFF)
        }
    }
}

private fun axisWord(report: ByteArray, offset: Int): Int {
    val lo = report[offset].toInt() and 0xFF
    val hi = report[offset + 1].toInt()
    return (hi shl 8) or lo
}

private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
