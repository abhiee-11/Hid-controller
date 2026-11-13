package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WASD page's key layout.
 *
 * The important property is the same one the keyboard has: every key the page can press must
 * sit inside the usage range the registered descriptor already declares, and every modifier
 * must be one of the eight declared bits. A key outside them would be ignored by the host, and
 * because the host caches the descriptor at pairing time the fix would need a re-pair.
 */
class WasdLayoutTest {

    @Test
    fun `every WASD key is inside the declared usage range`() {
        val keys = wasdLayout().keys
        assertTrue("the WASD page has keys", keys.isNotEmpty())
        for (cap in keys) {
            val action = cap.action
            if (action is KeyAction.Usage) {
                assertTrue(
                    "${cap.label} usage 0x${action.usage.toString(16)} is outside 0x00..0x65",
                    action.usage in 0..KEYBOARD_USAGE_MAX,
                )
            }
        }
    }

    @Test
    fun `every WASD modifier is one declared modifier bit`() {
        for (cap in wasdLayout().keys) {
            val action = cap.action
            if (action is KeyAction.Modifier) {
                assertTrue(
                    "${cap.label} modifier 0x${action.bit.toString(16)} is not one modifier bit",
                    action.bit in MODIFIER_BITS,
                )
            }
        }
    }

    @Test
    fun `the WASD page carries the movement and action keys`() {
        val labels = wasdLayout().keys.map { it.label }.toSet()
        for (label in listOf("W", "A", "S", "D", "Space", "Shift", "Ctrl", "E", "R", "Q", "F", "Tab", "Esc", "Enter")) {
            assertTrue("$label is missing", label in labels)
        }
    }

    @Test
    fun `each letter key maps to its own usage`() {
        val expected = mapOf(
            "W" to KeyUsage.W, "A" to KeyUsage.A, "S" to KeyUsage.S, "D" to KeyUsage.D,
            "E" to KeyUsage.E, "R" to KeyUsage.R, "Q" to KeyUsage.Q, "F" to KeyUsage.F,
        )
        val byLabel = wasdLayout().keys.associateBy { it.label }
        for ((label, usage) in expected) {
            assertEquals(label, KeyAction.Usage(usage), byLabel.getValue(label).action)
        }
        assertEquals(KeyAction.Usage(KeyUsage.SPACE), byLabel.getValue("Space").action)
        assertEquals(KeyAction.Usage(KeyUsage.TAB), byLabel.getValue("Tab").action)
        assertEquals(KeyAction.Usage(KeyUsage.ESC), byLabel.getValue("Esc").action)
        assertEquals(KeyAction.Usage(KeyUsage.ENTER), byLabel.getValue("Enter").action)
        assertEquals(KeyAction.Modifier(MOD_LEFT_SHIFT), byLabel.getValue("Shift").action)
        assertEquals(KeyAction.Modifier(MOD_LEFT_CTRL), byLabel.getValue("Ctrl").action)
    }

    @Test
    fun `the realistic held set fits the six-key report`() {
        // Shift is a modifier and does not consume a key slot; the six ordinary keys fit exactly.
        val state = KeyboardState(
            modifiers = MOD_LEFT_SHIFT,
            pressedKeys = setOf(KeyUsage.W, KeyUsage.A, KeyUsage.S, KeyUsage.D, KeyUsage.SPACE, KeyUsage.E),
        )
        val report = state.encodeReport()
        assertEquals(MOD_LEFT_SHIFT, report[0].toInt() and 0xFF)
        val keys = report.slice(2..7).map { it.toInt() and 0xFF }
        assertEquals(
            listOf(KeyUsage.W, KeyUsage.A, KeyUsage.S, KeyUsage.D, KeyUsage.SPACE, KeyUsage.E).sorted(),
            keys.sorted(),
        )
        assertTrue("no key slot is empty", keys.none { it == 0 })
    }
}
