package com.hidgamepad

/**
 * The steps of the Home page.
 *
 * The app opens on Home on every cold start: pick the host (Windows PC or Android), then the
 * controller mode (Gamepad, Mouse, Keyboard), and go straight into the controller.
 */
enum class HomeStep {
    /** Which kind of host will be controlled — this also picks the HID descriptor. */
    HOST,

    /** Which controller to start with (Gamepad, Mouse, Keyboard). */
    MODE,
    ;

    /** The next step, or null when this is the last one. */
    fun next(): HomeStep? = entries.getOrNull(ordinal + 1)

    /** The previous step, or null when this is the first one. */
    fun previous(): HomeStep? = entries.getOrNull(ordinal - 1)

    /** True on the step that ends on the controller. */
    val isFinal: Boolean get() = this == MODE

    companion object {
        /** The step Home starts on. */
        val start: HomeStep = HOST
    }
}

/** One of the controllers offered on Home's mode step. */
data class HomeMode(val mode: ControllerMode, val detail: String)

/**
 * The modes Home offers: Gamepad, Mouse and Keyboard, never WASD. WASD is a game-specific,
 * keyboard-driven page someone picks once they know they want it, so Home keeps the three
 * general-purpose choices and points people at them.
 */
val HOME_MODES: List<HomeMode> = listOf(
    HomeMode(ControllerMode.GAMEPAD, "Buttons, sticks, triggers and tilt."),
    HomeMode(ControllerMode.MOUSE, "A touchpad with gestures, clicks and scroll."),
    HomeMode(ControllerMode.KEYBOARD, "A full QWERTY layout with a function row."),
)
