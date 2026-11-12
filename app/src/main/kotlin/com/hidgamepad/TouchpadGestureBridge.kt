package com.hidgamepad

/** Gap between a chord/consumer press and its release, so a host cannot coalesce the pair. */
const val CHORD_HOLD_MS = 40L

/**
 * Turns [PadAction]s into HID report sends. Pure Kotlin except for the injected
 * [schedule], so the release timing is testable without Android.
 *
 * Owns the held mouse buttons and the Ctrl modifier a pinch holds; [releaseAll] is the
 * outer guarantee on teardown and backgrounding.
 */
class TouchpadGestureBridge(
    hostType: HostType,
    private val sendMouse: (MouseState) -> Unit,
    private val sendKeyboard: (KeyboardState) -> Unit,
    private val sendConsumer: (ConsumerState) -> Unit,
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit,
    private val hapticClick: () -> Unit = {},
) {
    /** Live host type; mutable so a host change never rebuilds the held-button state. */
    var hostType: HostType = hostType

    private var buttons = 0
    private var ctrlHeld = false

    fun dispatch(actions: List<PadAction>) {
        for (action in actions) when (action) {
            is PadAction.Move -> sendMouse(MouseState(buttons, action.dx, action.dy, 0, 0))
            is PadAction.Scroll -> sendMouse(MouseState(buttons, 0, 0, action.wheel, action.hPan))
            is PadAction.Click -> click(action.button)
            is PadAction.Button -> {
                val mask = maskOf(action.button)
                buttons = if (action.down) buttons or mask else buttons and mask.inv()
                sendMouse(MouseState(buttons))
            }
            is PadAction.ZoomStart -> {
                ctrlHeld = true
                sendKeyboard(KeyboardState(MOD_LEFT_CTRL))
            }
            is PadAction.Zoom -> {
                // Re-assert Ctrl every step: a pause or a reconnect zeroes the host's keyboard
                // state, and a wheel without Ctrl is just a scroll.
                sendKeyboard(KeyboardState(MOD_LEFT_CTRL))
                sendMouse(MouseState(buttons, 0, 0, action.steps, 0))
            }
            is PadAction.ZoomEnd -> {
                ctrlHeld = false
                sendKeyboard(KeyboardState(0))
            }
            is PadAction.Host -> hostAction(action.direction)
        }
    }

    fun releaseAll() {
        buttons = 0
        ctrlHeld = false
        sendMouse(MouseState(0))
        sendKeyboard(KeyboardState(0))
        sendConsumer(ConsumerState(0))
    }

    private fun click(button: Int) {
        val mask = maskOf(button)
        buttons = buttons or mask
        sendMouse(MouseState(buttons))
        hapticClick()
        schedule(CHORD_HOLD_MS) {
            buttons = buttons and mask.inv()
            sendMouse(MouseState(buttons))
        }
    }

    private fun hostAction(direction: PadDirection) {
        when (val action = hostActionFor(direction, hostType)) {
            is HostAction.Keys -> {
                sendKeyboard(KeyboardState(action.modifiers, setOf(action.key)))
                schedule(CHORD_HOLD_MS) {
                    sendKeyboard(KeyboardState(if (ctrlHeld) MOD_LEFT_CTRL else 0))
                }
            }
            is HostAction.Consumer -> {
                sendConsumer(ConsumerState(action.usage))
                schedule(CHORD_HOLD_MS) { sendConsumer(ConsumerState(0)) }
            }
            HostAction.None -> Unit
        }
    }

    private fun maskOf(button: Int): Int = 1 shl (button - 1)
}
