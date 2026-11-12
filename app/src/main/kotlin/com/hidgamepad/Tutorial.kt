package com.hidgamepad

/**
 * The steps of the first-use tutorial.
 *
 * The tutorial teaches what the app is and how to get a host connected. It is the *same*
 * five questions the app has always asked — host type, mode, pairing — presented as a
 * tutorial the user can step through, go back from, skip, and replay later.
 *
 * The model is pure so the navigation rules are unit-testable without a device: a tutorial
 * that can strand the user is a bug that is expensive to find on a phone.
 */
enum class TutorialStep {
    /** What the app is, and a Get started button. */
    WELCOME,

    /** Which kind of host will be controlled — this also picks the HID descriptor. */
    HOST,

    /** Which controller to start with. */
    MODE,

    /** How to pair, and the hosts already bonded. */
    PAIR,

    /** The summary, and the way into the app. */
    READY,
    ;

    /** The 1-based position, for "Step 2 of 5". */
    val number: Int get() = ordinal + 1

    /** The next step, or null when this is the last one. */
    fun next(): TutorialStep? = entries.getOrNull(ordinal + 1)

    /** The previous step, or null when this is the first one. */
    fun previous(): TutorialStep? = entries.getOrNull(ordinal - 1)

    /**
     * Where **Skip** goes.
     *
     * Skipping lands on the final step rather than vanishing: the user still gets the
     * summary and one obvious way into the app, and nobody is left halfway through a
     * tutorial with no way out.
     */
    fun skipTarget(): TutorialStep = READY

    /** True on the step that finishes the tutorial. */
    val isFinal: Boolean get() = this == READY

    companion object {
        /** How many steps the tutorial has. */
        val count: Int = entries.size

        /** The step the tutorial starts on. */
        val start: TutorialStep = WELCOME
    }
}
