package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end seam between the two halves of the touchpad: pointer frames in, HID mouse
 * report bytes out. These are the exact frame sequences a touchscreen produces, including
 * fingers landing and lifting in separate frames.
 */
class TouchpadGesturePipelineTest {

    private class Capture {
        val mouse = mutableListOf<MouseState>()
        val recognizer = TouchpadGestureRecognizer(density = 1f, sensitivity = 1f)
        private val scheduled = mutableListOf<() -> Unit>()
        val bridge = TouchpadGestureBridge(
            hostType = HostType.WINDOWS,
            sendMouse = { mouse += it },
            sendKeyboard = { },
            sendConsumer = { },
            schedule = { _, action -> scheduled += action },
        )

        fun frame(t: Long, vararg pts: PadPoint) {
            bridge.dispatch(recognizer.frame(PadFrame(t, pts.toList())))
        }

        /** Runs the pending delayed actions, as the main-thread handler would. */
        fun runScheduled() {
            val copy = scheduled.toList()
            scheduled.clear()
            copy.forEach { it() }
        }
    }

    @Test
    fun `two finger tap ends in a right click report`() {
        val c = Capture()
        // Both fingers down, then they leave a few frames apart, as on real hardware.
        c.frame(0, PadPoint(1, 100f, 200f), PadPoint(2, 140f, 200f))
        c.frame(40, PadPoint(1, 100f, 200f), PadPoint(2, 140f, 200f))
        c.frame(80, PadPoint(1, 100f, 200f))
        c.frame(120)
        c.runScheduled()

        val pressed = c.mouse.last { it.buttons != 0 }
        assertEquals(0x02, pressed.buttons)
        // The release must follow the press.
        assertEquals(0, c.mouse.last().buttons)
    }

    @Test
    fun `one finger tap ends in a left click report`() {
        val c = Capture()
        c.frame(0, PadPoint(1, 100f, 200f))
        c.frame(60)
        c.runScheduled()

        val pressed = c.mouse.last { it.buttons != 0 }
        assertEquals(0x01, pressed.buttons)
        assertEquals(0, c.mouse.last().buttons)
    }

    @Test
    fun `two finger tap where the second finger lands late is still a right click`() {
        val c = Capture()
        c.frame(0, PadPoint(1, 100f, 200f))
        c.frame(50, PadPoint(1, 103f, 200f))
        c.frame(90, PadPoint(1, 103f, 200f), PadPoint(2, 140f, 200f))
        c.frame(150)
        c.runScheduled()

        val pressed = c.mouse.last { it.buttons != 0 }
        assertEquals(0x02, pressed.buttons)
    }

    @Test
    fun `a two finger drag scrolls instead of clicking`() {
        val c = Capture()
        c.frame(0, PadPoint(1, 100f, 200f), PadPoint(2, 140f, 200f))
        c.frame(40, PadPoint(1, 100f, 200f), PadPoint(2, 140f, 200f))
        c.frame(80, PadPoint(1, 100f, 180f), PadPoint(2, 140f, 180f))
        c.frame(120)

        assertEquals(0, c.mouse.count { it.buttons != 0 })
        assertTrue(c.mouse.any { it.wheel != 0 })
    }
}
