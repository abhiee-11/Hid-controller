package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeTest {

    @Test
    fun `home has two steps and no counter on them`() {
        assertEquals(2, HomeStep.entries.size)
    }

    @Test
    fun `home starts at the host step`() {
        assertEquals(HomeStep.HOST, HomeStep.start)
    }

    @Test
    fun `next walks forward through every step`() {
        assertEquals(HomeStep.MODE, HomeStep.HOST.next())
    }

    @Test
    fun `there is no step after the last one`() {
        assertNull(HomeStep.MODE.next())
    }

    @Test
    fun `previous walks back through every step`() {
        assertEquals(HomeStep.HOST, HomeStep.MODE.previous())
    }

    @Test
    fun `there is no step before the first one`() {
        assertNull(HomeStep.HOST.previous())
    }

    @Test
    fun `the final step is the one that ends on the controller`() {
        assertEquals(true, HomeStep.MODE.isFinal)
        assertEquals(false, HomeStep.HOST.isFinal)
    }

    @Test
    fun `home never offers the WASD mode`() {
        // WASD is a game-specific, keyboard-only page; it is a choice for people who already
        // know they want it, so Home keeps the three general-purpose modes.
        val modes = HOME_MODES.map { it.mode }
        assertEquals(
            listOf(ControllerMode.GAMEPAD, ControllerMode.MOUSE, ControllerMode.KEYBOARD),
            modes,
        )
        assertEquals(listOf("WASD"), (ControllerMode.entries - modes.toSet()).map { it.name })
    }
}
