package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboard's function row and its keys.
 *
 * The important property is that every key the app can press is inside the usage range the
 * registered descriptor already declares. If a key were outside it, the host would not
 * decode it - and because the host caches the descriptor at pairing time, the fix would need
 * a re-pair.
 */
class KeyboardShortcutsTest {

    @Test
    fun `every key on the function row or the keyboard is inside the declared usage range`() {
        val all = functionRow() + keyboardRows().flatten()
        assertTrue("the keyboard has keys", all.isNotEmpty())
        for (cap in all) {
            when (val action = cap.action) {
                is KeyAction.Usage -> assertTrue(
                    "${cap.label} usage 0x${action.usage.toString(16)} is outside 0x00..0x65",
                    action.usage in 0..KEYBOARD_USAGE_MAX,
                )
                is KeyAction.Modifier -> assertTrue(
                    "${cap.label} modifier 0x${action.bit.toString(16)} is not one modifier bit",
                    action.bit in MODIFIER_BITS,
                )
                // Fn never reaches the descriptor; every declared usage is covered above.
                is KeyAction.Fn -> Unit
                // The RGB key never reaches the descriptor either.
                is KeyAction.Rgb -> Unit
            }
        }
    }

    @Test
    fun `the function row carries F1 to F12`() {
        assertEquals((1..12).map { "F$it" }, functionRow().map { it.label })
    }

    @Test
    fun `the function keys encode to the usages the descriptor declares`() {
        val usages = functionRow().map { (it.action as KeyAction.Usage).usage }
        assertEquals((0x3A..0x45).toList(), usages)
    }

    @Test
    fun `the keyboard keeps its letters, digits and modifiers`() {
        val labels = keyboardRows().flatten().map { it.label }
        for (letter in ('A'..'Z')) {
            assertTrue("$letter is missing", letter.toString() in labels)
        }
        for (digit in 0..9) {
            assertTrue("$digit is missing", digit.toString() in labels)
        }
        assertTrue("Ctrl is missing", "Ctrl" in labels)
        assertTrue("Shift is missing", "Shift" in labels)
        assertTrue("Alt is missing", "Alt" in labels)
        assertTrue("Win is missing", "Win" in labels)
        assertTrue("Space is missing", "Space" in labels)
        assertTrue("Esc is missing", "Esc" in labels)
        assertTrue("Tab is missing", "Tab" in labels)
        assertTrue("Enter is missing", "Enter" in labels)
        assertTrue("Backspace is missing", "Backspace" in labels)
    }

    @Test
    fun `the windows key is a held modifier`() {
        val win = keyboardRows().flatten().single { it.label == "Win" }
        assertEquals(KeyAction.Modifier(MOD_LEFT_GUI), win.action)
    }

    @Test
    fun `the arrow keys are on the keyboard`() {
        val usages = keyboardRows().flatten().mapNotNull { (it.action as? KeyAction.Usage)?.usage }
        for (usage in listOf(KeyUsage.LEFT, KeyUsage.UP, KeyUsage.DOWN, KeyUsage.RIGHT)) {
            assertTrue("usage 0x${usage.toString(16)} is missing", usage in usages)
        }
    }
}
