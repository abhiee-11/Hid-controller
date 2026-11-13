package com.hidgamepad

/**
 * What a key on the keyboard grid does when it is pressed.
 */
sealed interface KeyAction {
    /** An ordinary key: one HID usage. */
    data class Usage(val usage: Int) : KeyAction

    /** A modifier, held while it is touched: Ctrl, Shift, Alt or Win. */
    data class Modifier(val bit: Int) : KeyAction

    /**
     * A momentary, app-local modifier, never sent on the wire: while Fn is held the number
     * row emits the function keys instead of the digits, like a laptop's Fn layer. There is no
     * HID usage for Fn, so the host can never see it.
     */
    data object Fn : KeyAction
}

/** One key on the keyboard grid. */
data class KeyCap(
    val label: String,
    val action: KeyAction,
    /** How much of the row's width it takes. */
    val weight: Float = 1f,
)

/**
 * The function key row. It sits above the number row, so F1-F12 are always one tap away.
 *
 * Every usage here is inside the range the registered descriptor already declares
 * (Usage Maximum 0x65), so the row costs nothing on the wire and needs no re-pair.
 */
fun functionRow(): List<KeyCap> = listOf(
    cap("F1", KeyUsage.F1), cap("F2", KeyUsage.F2), cap("F3", KeyUsage.F3),
    cap("F4", KeyUsage.F4), cap("F5", KeyUsage.F5), cap("F6", KeyUsage.F6),
    cap("F7", KeyUsage.F7), cap("F8", KeyUsage.F8), cap("F9", KeyUsage.F9),
    cap("F10", KeyUsage.F10), cap("F11", KeyUsage.F11), cap("F12", KeyUsage.F12),
)

/** The keyboard: digits, letters, the modifiers, Space and the arrow keys. */
fun keyboardRows(): List<List<KeyCap>> = listOf(
    listOf(
        cap("Esc", KeyUsage.ESC),
        cap("1", KeyUsage.NUM_1), cap("2", KeyUsage.NUM_2), cap("3", KeyUsage.NUM_3),
        cap("4", KeyUsage.NUM_4), cap("5", KeyUsage.NUM_5), cap("6", KeyUsage.NUM_6),
        cap("7", KeyUsage.NUM_7), cap("8", KeyUsage.NUM_8), cap("9", KeyUsage.NUM_9),
        cap("0", KeyUsage.NUM_0),
        cap("Backspace", KeyUsage.BACKSPACE, 2f),
    ),
    listOf(
        cap("Tab", KeyUsage.TAB),
        cap("Q", KeyUsage.Q), cap("W", KeyUsage.W), cap("E", KeyUsage.E), cap("R", KeyUsage.R),
        cap("T", KeyUsage.T), cap("Y", KeyUsage.Y), cap("U", KeyUsage.U), cap("I", KeyUsage.I),
        cap("O", KeyUsage.O), cap("P", KeyUsage.P),
        cap("Enter", KeyUsage.ENTER, 2f),
    ),
    listOf(
        cap("Shift", KeyAction.Modifier(MOD_LEFT_SHIFT)),
        cap("A", KeyUsage.A), cap("S", KeyUsage.S), cap("D", KeyUsage.D), cap("F", KeyUsage.F),
        cap("G", KeyUsage.G), cap("H", KeyUsage.H), cap("J", KeyUsage.J), cap("K", KeyUsage.K),
        cap("L", KeyUsage.L),
        cap("Shift", KeyAction.Modifier(MOD_RIGHT_SHIFT)),
    ),
    listOf(
        cap("Ctrl", KeyAction.Modifier(MOD_LEFT_CTRL)),
        cap("Z", KeyUsage.Z), cap("X", KeyUsage.X), cap("C", KeyUsage.C), cap("V", KeyUsage.V),
        cap("B", KeyUsage.B), cap("N", KeyUsage.N), cap("M", KeyUsage.M),
        cap("Alt", KeyAction.Modifier(MOD_LEFT_ALT)),
    ),
    // Fn sits at the bottom left, like a laptop; Caps next, then Win, Space and the arrows.
    // The arrow keys are narrow so Space gets most of the row. Caps is a plain HID CapsLock
    // usage: the host toggles its own caps state and reports it back on the LED output report.
    listOf(
        KeyCap("Fn", KeyAction.Fn),
        cap("Caps", KeyUsage.CAPSLOCK),
        cap("Win", KeyAction.Modifier(MOD_LEFT_GUI)),
        cap("Space", KeyUsage.SPACE, 2f),
        cap("\u2190", KeyUsage.LEFT, 0.5f), cap("\u2191", KeyUsage.UP, 0.5f),
        cap("\u2193", KeyUsage.DOWN, 0.5f), cap("\u2192", KeyUsage.RIGHT, 0.5f),
    ),
)

/**
 * The function key a number key emits while Fn is held, or null when the key is not part of
 * the Fn layer. Fn+1..0 map to F1..F10, like a laptop.
 */
fun fnUsageFor(usage: Int): Int? = when (usage) {
    KeyUsage.NUM_1 -> KeyUsage.F1
    KeyUsage.NUM_2 -> KeyUsage.F2
    KeyUsage.NUM_3 -> KeyUsage.F3
    KeyUsage.NUM_4 -> KeyUsage.F4
    KeyUsage.NUM_5 -> KeyUsage.F5
    KeyUsage.NUM_6 -> KeyUsage.F6
    KeyUsage.NUM_7 -> KeyUsage.F7
    KeyUsage.NUM_8 -> KeyUsage.F8
    KeyUsage.NUM_9 -> KeyUsage.F9
    KeyUsage.NUM_0 -> KeyUsage.F10
    else -> null
}

private fun cap(label: String, usage: Int, weight: Float = 1f) =
    KeyCap(label, KeyAction.Usage(usage), weight)

private fun cap(label: String, modifier: KeyAction.Modifier, weight: Float = 1f) =
    KeyCap(label, modifier, weight)
