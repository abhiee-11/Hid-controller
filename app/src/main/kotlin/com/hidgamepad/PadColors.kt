package com.hidgamepad

import androidx.compose.ui.graphics.Color

/**
 * The single source of truth for the gamepad UI palette.
 *
 * Every colour the on-screen controls use lives here, so a visual change never means
 * hunting hex values through the composables. Purely presentational: no behaviour in
 * this file.
 */
object PadColors {

    /** The app background. */
    val background = Color(0xFF0F1115)

    /** Raised surfaces: keys, pads, panels. */
    val surface = Color(0xFF232733)

    /** Hairlines and key outlines. */
    val border = Color(0xFF343A4A)

    /** Primary text. */
    val text = Color(0xFFE6E8EE)

    /** Secondary text: hints, labels, inactive states. */
    val secondary = Color(0xFF8A91A3)

    /** The one accent: pressed states, the mode pill, the mode dropdown. */
    val accent = Color(0xFF7C5CFF)

    /** The connected indicator. */
    val connected = Color(0xFF4CC38A)

    /** Connecting / retrying indicator. */
    val pending = Color(0xFFD9A93A)

    // Face buttons. Each is a muted tint with a ring and a letter in the same hue.
    val faceA = Color(0xFF3FA66B)
    val faceB = Color(0xFFD9534F)
    val faceX = Color(0xFF4A86D6)
    val faceY = Color(0xFFD9A93A)

    // Analog sticks.
    val stickKnob = Color(0xFF3A4156)
    val stickKnobBorder = Color(0xFF6B7390)
}
