package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardMouseModeTest {

    @Test
    fun `neutral keyboard report is eight zero bytes`() {
        val report = KeyboardState().encodeReport()
        assertEquals(8, report.size)
        for (b in report) assertEquals(0, b.toInt())
    }

    @Test
    fun `keyboard letters digits and controls encode to hid usages`() {
        assertEquals(0x04, KeyboardState(pressedKeys = setOf(KeyUsage.A)).encodeReport()[2].toInt() and 0xFF)
        assertEquals(0x1D, KeyboardState(pressedKeys = setOf(KeyUsage.Z)).encodeReport()[2].toInt() and 0xFF)
        assertEquals(0x1E, KeyboardState(pressedKeys = setOf(KeyUsage.NUM_1)).encodeReport()[2].toInt() and 0xFF)
        assertEquals(0x27, KeyboardState(pressedKeys = setOf(KeyUsage.NUM_0)).encodeReport()[2].toInt() and 0xFF)
        assertEquals(0x28, KeyboardState(pressedKeys = setOf(KeyUsage.ENTER)).encodeReport()[2].toInt() and 0xFF)
        assertEquals(0x2C, KeyboardState(pressedKeys = setOf(KeyUsage.SPACE)).encodeReport()[2].toInt() and 0xFF)
        assertEquals(0x29, KeyboardState(pressedKeys = setOf(KeyUsage.ESC)).encodeReport()[2].toInt() and 0xFF)
        assertEquals(0x2A, KeyboardState(pressedKeys = setOf(KeyUsage.BACKSPACE)).encodeReport()[2].toInt() and 0xFF)
        assertEquals(0x2B, KeyboardState(pressedKeys = setOf(KeyUsage.TAB)).encodeReport()[2].toInt() and 0xFF)
    }

    @Test
    fun `keyboard arrow usages are correct`() {
        assertEquals(0x4F, KeyboardState(pressedKeys = setOf(KeyUsage.RIGHT)).encodeReport()[2].toInt() and 0xFF)
        assertEquals(0x50, KeyboardState(pressedKeys = setOf(KeyUsage.LEFT)).encodeReport()[2].toInt() and 0xFF)
        assertEquals(0x51, KeyboardState(pressedKeys = setOf(KeyUsage.DOWN)).encodeReport()[2].toInt() and 0xFF)
        assertEquals(0x52, KeyboardState(pressedKeys = setOf(KeyUsage.UP)).encodeReport()[2].toInt() and 0xFF)
    }

    @Test
    fun `keyboard modifiers do not consume ordinary key slots`() {
        val report = KeyboardState(
            modifiers = MOD_LEFT_CTRL or MOD_LEFT_SHIFT or MOD_LEFT_ALT,
            pressedKeys = setOf(KeyUsage.C),
        ).encodeReport()
        assertEquals(MOD_LEFT_CTRL or MOD_LEFT_SHIFT or MOD_LEFT_ALT, report[0].toInt() and 0xFF)
        assertEquals(0, report[1].toInt())
        assertEquals(0x06, report[2].toInt() and 0xFF)
    }

    @Test
    fun `press and release returns keyboard to neutral`() {
        val pressed = KeyboardState(modifiers = MOD_LEFT_SHIFT, pressedKeys = setOf(KeyUsage.A)).encodeReport()
        val expected = ByteArray(8) { 0 }
        expected[0] = MOD_LEFT_SHIFT.toByte()
        expected[2] = 0x04
        assertTrue(pressed.contentEquals(expected))
        val released = KeyboardState().encodeReport()
        assertTrue(released.all { it == 0.toByte() })
    }

    @Test
    fun `six ordinary keys fit and the seventh is excluded`() {
        val keys = setOf(0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A)
        val report = KeyboardState(pressedKeys = keys).encodeReport()
        assertEquals(listOf(0x04, 0x05, 0x06, 0x07, 0x08, 0x09), report.slice(2..7).map { it.toInt() and 0xFF })
    }

    @Test
    fun `neutral mouse report is five zero bytes`() {
        val report = MouseState().encodeReport()
        assertEquals(5, report.size)
        for (b in report) assertEquals(0, b.toInt())
    }

    @Test
    fun `mouse button bits land in byte zero and never bleed into movement`() {
        // Five one-bit buttons are declared (bits 0-4), so those five bits are the buttons.
        for (bit in listOf(0x01, 0x02, 0x04, 0x08, 0x10)) {
            assertEquals(bit, MouseState(buttons = bit).encodeReport()[0].toInt() and 0xFF)
        }
        // The button field is one byte on the wire: a wider value must be masked, not spilled
        // into the dx byte.
        val wide = MouseState(buttons = 0x1FF).encodeReport()
        assertEquals(0xFF, wide[0].toInt() and 0xFF)
        assertEquals(0, wide[1].toInt())
    }

    @Test
    fun `mouse movement wheel and horizontal pan clamp as signed bytes`() {
        val report = MouseState(dx = 200, dy = -200, wheel = 400, hPan = -300).encodeReport()
        assertEquals(127, report[1].toInt())
        assertEquals(-127, report[2].toInt())
        assertEquals(127, report[3].toInt())
        assertEquals(-127, report[4].toInt())
        val reverse = MouseState(dx = -20, dy = 15, wheel = -3, hPan = 4).encodeReport()
        assertEquals(-20, reverse[1].toInt())
        assertEquals(15, reverse[2].toInt())
        assertEquals(-3, reverse[3].toInt())
        assertEquals(4, reverse[4].toInt())
    }

    @Test
    fun `large mouse deltas split into byte-sized reports`() {
        val reports = MouseState(dx = 300, dy = -300, wheel = 200, hPan = 250).splitIntoReports()
        assertTrue(reports.size > 1)
        assertEquals(300, reports.sumOf { it.dx })
        assertEquals(-300, reports.sumOf { it.dy })
        assertEquals(200, reports.sumOf { it.wheel })
        assertEquals(250, reports.sumOf { it.hPan })
        for (r in reports) {
            assertTrue(r.dx in -127..127)
            assertTrue(r.dy in -127..127)
            assertTrue(r.wheel in -127..127)
            assertTrue(r.hPan in -127..127)
        }
    }

    @Test
    fun `host replacement and mode switch neutralization uses all four report ids`() {
        val sequence = neutralReportSequence()
        assertEquals(listOf(1, 2, 3, 4), sequence.map { it.first })
        assertEquals(8, sequence[0].second.size)
        assertEquals(5, sequence[1].second.size)
        assertEquals(9, sequence[2].second.size)
        assertEquals(2, sequence[3].second.size)
        assertTrue(sequence[0].second.all { it == 0.toByte() })
        assertTrue(sequence[1].second.all { it == 0.toByte() })
        assertEquals(15, sequence[2].second[4].toInt() and 0x0F) // hat neutral
        assertTrue(sequence[3].second.all { it == 0.toByte() })
    }

    @Test
    fun `onboarding storage restores mode host and setup flag semantics`() {
        assertEquals(ControllerMode.MOUSE, controllerModeFromStorage("MOUSE"))
        assertEquals(ControllerMode.GAMEPAD, controllerModeFromStorage("NOPE"))
        assertEquals(HostType.ANDROID, hostTypeFromStorage("ANDROID"))
        assertEquals(HostType.WINDOWS, hostTypeFromStorage(null))
        // The tutorial is a one-time thing: once it has been finished it never comes back
        // on its own, whether or not a host has been chosen yet.
        assertTrue(needsOnboarding(false))
        assertFalse(needsOnboarding(true))
    }

    @Test
    fun `pair instructions are host-specific`() {
        assertTrue(pairInstructions(HostType.WINDOWS).contains("Settings → Bluetooth & devices"))
        assertTrue(pairInstructions(HostType.ANDROID).contains("Some Android phones do not support"))
    }

    @Test
    fun `caps lock LED output report decodes the caps bit`() {
        assertFalse(capsLockLedBit(byteArrayOf()))
        assertFalse(capsLockLedBit(byteArrayOf(0x00)))
        // Bit 0 is Num Lock, bit 1 is Caps Lock, bit 2 is Scroll Lock.
        assertFalse(capsLockLedBit(byteArrayOf(0x01)))
        assertTrue(capsLockLedBit(byteArrayOf(0x02)))
        assertTrue(capsLockLedBit(byteArrayOf(0x06))) // Caps + Scroll
        assertTrue(capsLockLedBit(byteArrayOf(0x03))) // Num + Caps
    }

    @Test
    fun `fn key is the first key of the bottom row`() {
        val bottom = keyboardRows().last()
        assertEquals("Fn", bottom[0].label)
        assertEquals(KeyAction.Fn, bottom[0].action)
    }

    @Test
    fun `caps and win follow fn in the bottom row`() {
        val bottom = keyboardRows().last()
        assertEquals("Caps", bottom[1].label)
        assertEquals(KeyAction.Usage(KeyUsage.CAPSLOCK), bottom[1].action)
        assertEquals("Win", bottom[2].label)
        assertTrue(bottom[2].action is KeyAction.Modifier)
    }

    @Test
    fun `fn maps the number row to the function keys`() {
        val digits = listOf(
            KeyUsage.NUM_1 to KeyUsage.F1,
            KeyUsage.NUM_2 to KeyUsage.F2,
            KeyUsage.NUM_3 to KeyUsage.F3,
            KeyUsage.NUM_4 to KeyUsage.F4,
            KeyUsage.NUM_5 to KeyUsage.F5,
            KeyUsage.NUM_6 to KeyUsage.F6,
            KeyUsage.NUM_7 to KeyUsage.F7,
            KeyUsage.NUM_8 to KeyUsage.F8,
            KeyUsage.NUM_9 to KeyUsage.F9,
            KeyUsage.NUM_0 to KeyUsage.F10,
        )
        for ((digit, fKey) in digits) {
            assertEquals(fKey, fnUsageFor(digit))
        }
        assertEquals(null, fnUsageFor(KeyUsage.A))
        assertEquals(null, fnUsageFor(KeyUsage.SPACE))
    }
}
