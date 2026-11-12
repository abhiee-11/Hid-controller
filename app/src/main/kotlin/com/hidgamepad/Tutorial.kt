package com.hidgamepad

/**
 * The steps of first-use setup.
 *
 * When fresh opening the app, the user directly chooses the host (Windows PC or Android)
 * and the device mode (Gamepad, Mouse, Keyboard) and proceeds directly into the controller.
 */
enum class TutorialStep {
    /** Which kind of host will be controlled — this also picks the HID descriptor. */
    HOST,

    /** Which controller to start with (Gamepad, Mouse, Keyboard). */
    MODE,
    ;

    /** The 1-based position, for "Step 1 of 2". */
    val number: Int get() = ordinal + 1

    /** The next step, or null when this is the last one. */
    fun next(): TutorialStep? = entries.getOrNull(ordinal + 1)

    /** The previous step, or null when this is the first one. */
    fun previous(): TutorialStep? = entries.getOrNull(ordinal - 1)

    /**
     * Where **Skip** goes (finishes setup).
     */
    fun skipTarget(): TutorialStep = MODE

    /** True on the step that finishes setup. */
    val isFinal: Boolean get() = this == MODE

    companion object {
        /** How many steps setup has. */
        val count: Int = entries.size

        /** The step setup starts on. */
        val start: TutorialStep = HOST
    }
}
