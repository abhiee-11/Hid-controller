package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeScheduler {
    val pending = mutableListOf<Pair<Long, () -> Unit>>()
    fun schedule(ms: Long, action: () -> Unit) {
        pending += ms to action
    }

    fun flush() {
        val copy = pending.toList()
        pending.clear()
        for ((_, action) in copy) action()
    }
}

private class Recorder {
    val mouse = mutableListOf<MouseState>()
    val keyboard = mutableListOf<KeyboardState>()
    val consumer = mutableListOf<ConsumerState>()
    var haptics = 0
    val scheduler = FakeScheduler()

    fun bridge(host: HostType) = TouchpadGestureBridge(
        hostType = host,
        sendMouse = { mouse += it },
        sendKeyboard = { keyboard += it },
        sendConsumer = { consumer += it },
        schedule = scheduler::schedule,
        hapticClick = { haptics++ },
    )
}

class TouchpadGestureBridgeTest {

    @Test
    fun `move carries the current button mask`() {
        val r = Recorder()
        val b = r.bridge(HostType.WINDOWS)
        b.dispatch(listOf(PadAction.Button(1, true)))
        b.dispatch(listOf(PadAction.Move(5, -3)))
        assertEquals(
            listOf(
                MouseState(buttons = 1),
                MouseState(buttons = 1, dx = 5, dy = -3),
            ),
            r.mouse,
        )
    }

    @Test
    fun `click presses then releases after the hold`() {
        val r = Recorder()
        val b = r.bridge(HostType.WINDOWS)
        b.dispatch(listOf(PadAction.Click(2)))
        assertEquals(listOf(MouseState(buttons = 0x02)), r.mouse)
        assertEquals(1, r.haptics)
        r.scheduler.flush()
        assertEquals(MouseState(buttons = 0), r.mouse.last())
    }

    @Test
    fun `zoom holds ctrl across the gesture`() {
        val r = Recorder()
        val b = r.bridge(HostType.WINDOWS)
        b.dispatch(listOf(PadAction.ZoomStart, PadAction.Zoom(2), PadAction.ZoomEnd))
        assertEquals(listOf(KeyboardState(MOD_LEFT_CTRL), KeyboardState(MOD_LEFT_CTRL), KeyboardState(0)), r.keyboard)
        assertEquals(listOf(MouseState(buttons = 0, wheel = 2)), r.mouse)
        assertEquals(emptyList<ConsumerState>(), r.consumer)
    }

    @Test
    fun `zoom reasserts ctrl on every step`() {
        val r = Recorder()
        val b = r.bridge(HostType.WINDOWS)
        b.dispatch(listOf(PadAction.ZoomStart, PadAction.Zoom(1), PadAction.Zoom(1), PadAction.ZoomEnd))
        assertEquals(
            listOf(KeyboardState(MOD_LEFT_CTRL), KeyboardState(MOD_LEFT_CTRL), KeyboardState(MOD_LEFT_CTRL), KeyboardState(0)),
            r.keyboard,
        )
        assertEquals(
            listOf(MouseState(buttons = 0, wheel = 1), MouseState(buttons = 0, wheel = 1)),
            r.mouse,
        )
    }

    @Test
    fun `windows swipe sends a chord and releases it`() {
        val r = Recorder()
        val b = r.bridge(HostType.WINDOWS)
        b.dispatch(listOf(PadAction.Host(PadDirection.UP)))
        assertEquals(listOf(KeyboardState(MOD_LEFT_GUI, setOf(KeyUsage.TAB))), r.keyboard)
        r.scheduler.flush()
        assertEquals(KeyboardState(0), r.keyboard.last())
    }

    @Test
    fun `android swipe sends a consumer tap and releases it`() {
        val r = Recorder()
        val b = r.bridge(HostType.ANDROID)
        b.dispatch(listOf(PadAction.Host(PadDirection.LEFT)))
        assertEquals(listOf(ConsumerState(0x0224)), r.consumer)
        r.scheduler.flush()
        assertEquals(ConsumerState(0), r.consumer.last())
    }

    @Test
    fun `android downward swipe sends nothing`() {
        val r = Recorder()
        val b = r.bridge(HostType.ANDROID)
        b.dispatch(listOf(PadAction.Host(PadDirection.DOWN)))
        assertEquals(emptyList<ConsumerState>(), r.consumer)
        assertEquals(emptyList<KeyboardState>(), r.keyboard)
    }

    @Test
    fun `releaseAll clears held buttons and ctrl`() {
        val r = Recorder()
        val b = r.bridge(HostType.WINDOWS)
        b.dispatch(listOf(PadAction.Button(1, true), PadAction.ZoomStart))
        b.releaseAll()
        assertEquals(MouseState(buttons = 0), r.mouse.last())
        assertEquals(KeyboardState(0), r.keyboard.last())
        assertEquals(ConsumerState(0), r.consumer.last())
    }

    @Test
    fun `gesture end does not leak state into the next move`() {
        val r = Recorder()
        val b = r.bridge(HostType.WINDOWS)
        b.dispatch(listOf(PadAction.Button(1, true)))
        b.dispatch(listOf(PadAction.Button(1, false)))
        b.dispatch(listOf(PadAction.Move(1, 1)))
        assertEquals(MouseState(buttons = 0, dx = 1, dy = 1), r.mouse.last())
    }
}
