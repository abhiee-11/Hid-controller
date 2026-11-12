package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Test

private fun pt(id: Long, x: Float, y: Float) = PadPoint(id, x, y)
private fun fr(t: Long, vararg pts: PadPoint) = PadFrame(t, pts.toList())
private fun recog() = TouchpadGestureRecognizer(density = 1f, sensitivity = 1f)

class TouchpadGestureTest {

    @Test
    fun `one finger below slop emits nothing`() {
        val r = recog()
        assertEquals(emptyList<PadAction>(), r.frame(fr(0, pt(1, 100f, 100f))))
        assertEquals(emptyList<PadAction>(), r.frame(fr(16, pt(1, 104f, 100f))))
    }

    @Test
    fun `drag past slop flushes buffered delta then moves per frame`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 100f)))
        r.frame(fr(16, pt(1, 104f, 100f)))
        assertEquals(listOf(PadAction.Move(9, 0)), r.frame(fr(32, pt(1, 109f, 100f))))
        assertEquals(listOf(PadAction.Move(5, 0)), r.frame(fr(48, pt(1, 114f, 100f))))
    }

    @Test
    fun `tap emits one left click on lift`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 100f)))
        assertEquals(listOf(PadAction.Click(1)), r.frame(fr(120)))
    }

    @Test
    fun `jitter inside slop is discarded and still taps`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 100f)))
        assertEquals(emptyList<PadAction>(), r.frame(fr(16, pt(1, 103f, 100f))))
        assertEquals(listOf(PadAction.Click(1)), r.frame(fr(60)))
    }

    @Test
    fun `hold longer than tap timeout emits nothing`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 100f)))
        assertEquals(emptyList<PadAction>(), r.frame(fr(400)))
    }

    @Test
    fun `one frame flick emits move and never a click`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 100f)))
        assertEquals(listOf(PadAction.Move(300, -40)), r.frame(fr(16, pt(1, 400f, 60f))))
        assertEquals(emptyList<PadAction>(), r.frame(fr(24)))
    }

    @Test
    fun `second tap then hold arms a drag button`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 100f)))
        assertEquals(listOf(PadAction.Click(1)), r.frame(fr(100)))
        assertEquals(emptyList<PadAction>(), r.frame(fr(150, pt(1, 101f, 100f))))
        assertEquals(emptyList<PadAction>(), r.tick(300))
        assertEquals(listOf(PadAction.Button(1, true)), r.tick(400))
        assertEquals(listOf(PadAction.Move(20, 0)), r.frame(fr(416, pt(1, 121f, 100f))))
        assertEquals(listOf(PadAction.Button(1, false)), r.frame(fr(500)))
    }

    @Test
    fun `second tap released before arming is a normal click`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 100f)))
        r.frame(fr(90))
        r.frame(fr(150, pt(1, 101f, 100f)))
        assertEquals(listOf(PadAction.Click(1)), r.frame(fr(250)))
    }

    @Test
    fun `reset releases a held drag button`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 100f)))
        r.frame(fr(90))
        r.frame(fr(150, pt(1, 101f, 100f)))
        assertEquals(listOf(PadAction.Button(1, true)), r.tick(400))
        assertEquals(listOf(PadAction.Button(1, false)), r.reset())
    }

    @Test
    fun `two finger vertical drag scrolls in notches`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        assertEquals(emptyList<PadAction>(), r.frame(fr(66, pt(1, 100f, 195f), pt(2, 140f, 195f))))
        // 20 dp of travel at the default rate is two notches.
        assertEquals(
            listOf(PadAction.Scroll(wheel = -2, hPan = 0)),
            r.frame(fr(82, pt(1, 100f, 180f), pt(2, 140f, 180f))),
        )
    }

    @Test
    fun `two finger sideways drag pans horizontally`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 100f, 240f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 100f, 240f)))
        assertEquals(
            listOf(PadAction.Scroll(wheel = 0, hPan = 2)),
            r.frame(fr(70, pt(1, 120f, 200f), pt(2, 120f, 240f))),
        )
    }

    @Test
    fun `the scroll step is the documented dp per notch`() {
        // Ten dp of travel per notch at the default sensitivity, so 20 dp is two notches.
        assertEquals(10f, SCROLL_STEP_DP)
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        assertEquals(
            listOf(PadAction.Scroll(wheel = -2, hPan = 0)),
            r.frame(fr(70, pt(1, 100f, 180f), pt(2, 140f, 180f))),
        )
    }

    @Test
    fun `the scroll step follows the sensitivity`() {
        // The same 20 dp of travel is twice the notches at double sensitivity, and half at
        // half sensitivity.
        val fast = TouchpadGestureRecognizer(density = 1f, sensitivity = 2f)
        fast.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        fast.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        assertEquals(
            listOf(PadAction.Scroll(wheel = -4, hPan = 0)),
            fast.frame(fr(70, pt(1, 100f, 180f), pt(2, 140f, 180f))),
        )

        val slow = TouchpadGestureRecognizer(density = 1f, sensitivity = 0.5f)
        slow.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        slow.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        assertEquals(
            listOf(PadAction.Scroll(wheel = -1, hPan = 0)),
            slow.frame(fr(70, pt(1, 100f, 180f), pt(2, 140f, 180f))),
        )
    }

    @Test
    fun `two finger tap is a right click`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        assertEquals(listOf(PadAction.Click(2)), r.frame(fr(120)))
    }

    @Test
    fun `staggered second finger landing stays a two finger gesture`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f)))
        r.frame(fr(20, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(60, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        assertEquals(
            listOf(PadAction.Scroll(wheel = -2, hPan = 0)),
            r.frame(fr(80, pt(1, 100f, 180f), pt(2, 140f, 180f))),
        )
    }

    @Test
    fun `lifting one of two fingers does not click or scroll`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        assertEquals(
            listOf(PadAction.Scroll(wheel = -2, hPan = 0)),
            r.frame(fr(70, pt(1, 100f, 180f), pt(2, 140f, 180f))),
        )
        assertEquals(emptyList<PadAction>(), r.frame(fr(90, pt(1, 100f, 180f))))
        assertEquals(emptyList<PadAction>(), r.frame(fr(100)))
    }

    @Test
    fun `a tap right after a scroll is a fresh right click`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(70, pt(1, 100f, 180f), pt(2, 140f, 180f)))
        r.frame(fr(90))
        r.frame(fr(300, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        assertEquals(listOf(PadAction.Click(2)), r.frame(fr(360)))
    }

    @Test
    fun `two fingers moving apart zoom and frame the gesture`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        assertEquals(
            listOf(PadAction.ZoomStart, PadAction.Zoom(2)),
            r.frame(fr(70, pt(1, 85f, 200f), pt(2, 155f, 200f))),
        )
        assertEquals(listOf(PadAction.ZoomEnd), r.frame(fr(90)))
    }

    @Test
    fun `pinch wins when scroll and pinch cross together`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        assertEquals(
            listOf(PadAction.ZoomStart, PadAction.Zoom(2)),
            r.frame(fr(70, pt(1, 85f, 180f), pt(2, 155f, 180f))),
        )
    }

    @Test
    fun `a live pinch is not replaced by scroll`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(70, pt(1, 85f, 200f), pt(2, 155f, 200f)))
        assertEquals(
            emptyList<PadAction>(),
            r.frame(fr(90, pt(1, 85f, 170f), pt(2, 155f, 170f))),
        )
    }

    @Test
    fun `reset ends a live pinch`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(70, pt(1, 85f, 200f), pt(2, 155f, 200f)))
        assertEquals(listOf(PadAction.ZoomEnd), r.reset())
    }

    @Test
    fun `three finger tap is a middle click`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f), pt(3, 180f, 200f)))
        assertEquals(listOf(PadAction.Click(3)), r.frame(fr(140)))
    }

    @Test
    fun `three finger swipe emits one host action`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f), pt(3, 180f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f), pt(3, 180f, 200f)))
        assertEquals(
            listOf(PadAction.Host(PadDirection.LEFT)),
            r.frame(fr(70, pt(1, 50f, 200f), pt(2, 90f, 200f), pt(3, 130f, 200f))),
        )
        assertEquals(
            emptyList<PadAction>(),
            r.frame(fr(90, pt(1, 0f, 200f), pt(2, 40f, 200f), pt(3, 80f, 200f))),
        )
        assertEquals(emptyList<PadAction>(), r.frame(fr(120)))
    }

    @Test
    fun `three finger short drag is neither tap nor swipe`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f), pt(3, 180f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f), pt(3, 180f, 200f)))
        assertEquals(emptyList<PadAction>(), r.frame(fr(70, pt(1, 90f, 200f), pt(2, 130f, 200f), pt(3, 170f, 200f))))
        assertEquals(emptyList<PadAction>(), r.frame(fr(100)))
    }

    @Test
    fun `four fingers do not click`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f), pt(3, 180f, 200f), pt(4, 220f, 200f)))
        assertEquals(emptyList<PadAction>(), r.frame(fr(120)))
    }

    @Test
    fun `four finger double tap toggles the pad glow once`() {
        val r = recog()
        val four = arrayOf(pt(1, 100f, 200f), pt(2, 140f, 200f), pt(3, 180f, 200f), pt(4, 220f, 200f))
        r.frame(fr(0, *four))
        assertEquals(emptyList<PadAction>(), r.frame(fr(100)))
        r.frame(fr(200, *four))
        assertEquals(listOf(PadAction.PadGlowToggle), r.frame(fr(300)))
    }

    @Test
    fun `a lone four finger tap does not toggle the glow`() {
        val r = recog()
        val four = arrayOf(pt(1, 100f, 200f), pt(2, 140f, 200f), pt(3, 180f, 200f), pt(4, 220f, 200f))
        r.frame(fr(0, *four))
        assertEquals(emptyList<PadAction>(), r.frame(fr(100)))
        // A second lone tap well past the double-tap window still emits nothing.
        r.frame(fr(1000, *four))
        assertEquals(emptyList<PadAction>(), r.frame(fr(1100)))
    }

    @Test
    fun `reset clears a pending pad glow tap`() {
        val r = recog()
        val four = arrayOf(pt(1, 100f, 200f), pt(2, 140f, 200f), pt(3, 180f, 200f), pt(4, 220f, 200f))
        r.frame(fr(0, *four))
        assertEquals(emptyList<PadAction>(), r.frame(fr(100)))
        r.reset()
        r.frame(fr(200, *four))
        assertEquals(emptyList<PadAction>(), r.frame(fr(300)))
    }

    @Test
    fun `two fingers landing in separate frames still tap`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f)))
        r.frame(fr(20, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        assertEquals(listOf(PadAction.Click(2)), r.frame(fr(140)))
    }

    @Test
    fun `three fingers landing in separate frames still tap`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f)))
        r.frame(fr(20, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(40, pt(1, 100f, 200f), pt(2, 140f, 200f), pt(3, 180f, 200f)))
        assertEquals(listOf(PadAction.Click(3)), r.frame(fr(160)))
    }

    @Test
    fun `three fingers spread wide and landing in separate frames do not swipe`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f)))
        r.frame(fr(20, pt(1, 100f, 200f), pt(2, 160f, 200f)))
        r.frame(fr(40, pt(1, 100f, 200f), pt(2, 160f, 200f), pt(3, 220f, 200f)))
        assertEquals(
            emptyList<PadAction>(),
            r.frame(fr(80, pt(1, 100f, 200f), pt(2, 160f, 200f), pt(3, 220f, 200f))),
        )
    }

    @Test
    fun `lifting one finger early and holding the other past the tap window does not click`() {
        // The finger that stays down turns the gesture into a hold, not a tap.
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 110f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 110f, 200f)))
        assertEquals(emptyList<PadAction>(), r.frame(fr(70, pt(1, 100f, 200f))))
        assertEquals(emptyList<PadAction>(), r.frame(fr(300)))
    }

    @Test
    fun `second finger landing after the first is still a two finger gesture`() {
        // Real hardware: one finger lands, jitters a few pixels, and the second finger arrives
        // a few frames later. There is no fixed settle timer — only the tap window commits the
        // family — so the gesture must still become TWO.
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f)))
        r.frame(fr(50, pt(1, 103f, 200f)))
        r.frame(fr(80, pt(1, 103f, 200f), pt(2, 140f, 200f)))
        assertEquals(listOf(PadAction.Click(2)), r.frame(fr(140)))
    }

    @Test
    fun `two finger tap with a staggered lift is still a right click`() {
        // Fingers never leave in the same frame on a touchscreen.
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        assertEquals(emptyList<PadAction>(), r.frame(fr(70, pt(1, 100f, 200f))))
        assertEquals(listOf(PadAction.Click(2)), r.frame(fr(100)))
    }

    @Test
    fun `a finger that lifts early while the other keeps moving does not click`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        assertEquals(emptyList<PadAction>(), r.frame(fr(70, pt(1, 100f, 200f))))
        assertEquals(emptyList<PadAction>(), r.frame(fr(100, pt(1, 180f, 200f))))
        assertEquals(emptyList<PadAction>(), r.frame(fr(140)))
    }

    @Test
    fun `four fingers sliding do not swipe`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f), pt(3, 180f, 200f), pt(4, 220f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f), pt(3, 180f, 200f), pt(4, 220f, 200f)))
        assertEquals(
            emptyList<PadAction>(),
            r.frame(fr(70, pt(1, 40f, 200f), pt(2, 80f, 200f), pt(3, 120f, 200f), pt(4, 160f, 200f))),
        )
    }
}
