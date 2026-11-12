package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `the gamepad-only descriptor parses to exactly a thirteen byte report`() {
        // A byte-level parse catches short-item size mistakes that substring checks cannot:
        // 0x27 is a four-byte item, so it would swallow the following Report Size byte.
        val summary = parseDescriptor(GAMEPAD_ONLY_DESCRIPTOR)
        assertEquals(
            GAMEPAD_ONLY_REPORT_SIZE * 8,
            summary.bitsFor(GAMEPAD_REPORT_ID),
        )
        val wide = summary.fieldsFor(GAMEPAD_REPORT_ID).filter { it.reportSize == 16 }
        assertEquals("one sixteen-bit field", 1, wide.size)
        assertEquals("four sixteen-bit axes", 4, wide[0].reportCount)
    }

    @Test
    fun `profile names are stable across versions`() {
        // The storage key is what a user's paired host was registered with; renaming an enum
        // entry would silently re-pair them onto the wrong descriptor.
        assertEquals("COMPOSITE", HidProfile.COMPOSITE.name)
        assertEquals("GAMEPAD_ONLY", HidProfile.GAMEPAD_ONLY.name)
    }

    @Test
    fun `profile labels name the collections the profile carries`() {
        assertEquals("Keyboard + mouse + gamepad", profileLabel(HidProfile.COMPOSITE))
        assertEquals("Gamepad only", profileLabel(HidProfile.GAMEPAD_ONLY))
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
    fun `a profile the host has not re-paired for is reported as pending`() {
        // The host caches the descriptor at pairing time, so switching the profile only
        // takes effect after the user removes and pairs the phone again. The app has to be
        // able to say so, otherwise the user sees "nothing changed" and gives up.
        assertTrue(profileNeedsRepair(current = HidProfile.COMPOSITE, registered = HidProfile.GAMEPAD_ONLY))
        assertTrue(profileNeedsRepair(current = HidProfile.GAMEPAD_ONLY, registered = HidProfile.COMPOSITE))
        assertFalse(profileNeedsRepair(current = HidProfile.GAMEPAD_ONLY, registered = HidProfile.GAMEPAD_ONLY))
        assertFalse(profileNeedsRepair(current = HidProfile.COMPOSITE, registered = HidProfile.COMPOSITE))
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

    @Test
    fun `the paired profile falls back to the selected one on first run`() {
        // A fresh install has no stale host cache, so the selected profile is also the one
        // the host will be paired with; warning about a re-pair would be wrong.
        assertEquals(HidProfile.GAMEPAD_ONLY, pairedProfileFromStorage(null, HidProfile.GAMEPAD_ONLY))
        assertEquals(HidProfile.COMPOSITE, pairedProfileFromStorage(null, HidProfile.COMPOSITE))
    }

    @Test
    fun `a stored paired profile survives a later profile change`() {
        // This is the whole point of the field: after switching to GAMEPAD_ONLY the host
        // still decodes COMPOSITE, and the app must keep remembering that until re-paired.
        assertEquals(HidProfile.COMPOSITE, pairedProfileFromStorage("COMPOSITE", HidProfile.GAMEPAD_ONLY))
        assertEquals(HidProfile.GAMEPAD_ONLY, pairedProfileFromStorage("GAMEPAD_ONLY", HidProfile.COMPOSITE))
    }

    @Test
    fun `an unrecognised stored paired profile falls back to the selected one`() {
        assertEquals(HidProfile.GAMEPAD_ONLY, pairedProfileFromStorage("nonsense", HidProfile.GAMEPAD_ONLY))
    }

    @Test
    fun `reports follow the paired profile not the newly selected one`() {
        // The defect this guards: after a switch to GAMEPAD_ONLY without a re-pair, the host
        // still decodes the 9-byte composite, so a 13-byte report is parsed as garbage.
        val stale = reportForHost(
            selected = HidProfile.GAMEPAD_ONLY,
            paired = HidProfile.COMPOSITE,
            state = GamepadState(),
        )
        assertEquals(9, stale.size)
        val fresh = reportForHost(
            selected = HidProfile.GAMEPAD_ONLY,
            paired = HidProfile.GAMEPAD_ONLY,
            state = GamepadState(),
        )
        assertEquals(13, fresh.size)
    }
}

private fun axisWord(report: ByteArray, offset: Int): Int {
    val lo = report[offset].toInt() and 0xFF
    val hi = report[offset + 1].toInt()
    return (hi shl 8) or lo
}

private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
