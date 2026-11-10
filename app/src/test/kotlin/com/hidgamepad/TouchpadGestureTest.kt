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
        assertEquals(
            listOf(PadAction.Scroll(wheel = -1, hPan = 0)),
            r.frame(fr(82, pt(1, 100f, 180f), pt(2, 140f, 180f))),
        )
    }

    @Test
    fun `two finger sideways drag pans horizontally`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 100f, 240f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 100f, 240f)))
        assertEquals(
            listOf(PadAction.Scroll(wheel = 0, hPan = 1)),
            r.frame(fr(70, pt(1, 120f, 200f), pt(2, 120f, 240f))),
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
            listOf(PadAction.Scroll(wheel = -1, hPan = 0)),
            r.frame(fr(80, pt(1, 100f, 180f), pt(2, 140f, 180f))),
        )
    }

    @Test
    fun `lifting one of two fingers does not click or scroll`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        assertEquals(
            listOf(PadAction.Scroll(wheel = -1, hPan = 0)),
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
}
