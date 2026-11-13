package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The WASD mode exists, is labelled, and survives a restart. */
class WasdModeTest {

    @Test
    fun `WASD is a controller mode with its own label`() {
        assertTrue(ControllerMode.entries.contains(ControllerMode.WASD))
        assertEquals("WASD", modeLabel(ControllerMode.WASD))
        // The existing modes keep their labels.
        assertEquals("Gamepad", modeLabel(ControllerMode.GAMEPAD))
        assertEquals("Mouse", modeLabel(ControllerMode.MOUSE))
        assertEquals("Keyboard", modeLabel(ControllerMode.KEYBOARD))
    }

    @Test
    fun `a stored WASD mode round-trips`() {
        assertEquals(ControllerMode.WASD, controllerModeFromStorage("WASD"))
    }
}
