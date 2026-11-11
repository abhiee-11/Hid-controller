package com.hidgamepad

/** One three-finger swipe, resolved for the host type the user selected in setup. */
sealed interface HostAction {
    data class Keys(val modifiers: Int, val key: Int) : HostAction
    data class Consumer(val usage: Int) : HostAction
    data object None : HostAction
}

/**
 * Guesses the host type from the connected device's Bluetooth name, so a tablet gets the
 * Android gesture mapping without the user having to re-run setup. Falls back to
 * [current] when the name says nothing useful.
 */
fun hostTypeForDeviceName(name: String, current: HostType = HostType.WINDOWS): HostType {
    val lower = name.lowercase()
    val androidWords = listOf("tab", "tablet", "pixel", "galaxy", "android", "phone", "oneplus", "redmi", "mi ")
    val windowsWords = listOf("desktop", "laptop", "pc", "notebook", "thinkpad", "surface")
    return when {
        androidWords.any { it in lower } -> HostType.ANDROID
        windowsWords.any { it in lower } -> HostType.WINDOWS
        else -> current
    }
}

/**
 * Windows: Win+Tab Task View, Win+D show desktop, Alt+Left/Right history navigation.
 * Android: consumer Home/Back/Forward (HID 1.12 usages); Android has no global Forward or
 * show-desktop, so down is [HostAction.None] and forward relies on the host app honouring
 * AC Forward.
 */
fun hostActionFor(direction: PadDirection, hostType: HostType): HostAction = when (hostType) {
    HostType.WINDOWS -> when (direction) {
        PadDirection.UP -> HostAction.Keys(MOD_LEFT_GUI, KeyUsage.TAB)
        PadDirection.DOWN -> HostAction.Keys(MOD_LEFT_GUI, KeyUsage.D)
        PadDirection.LEFT -> HostAction.Keys(MOD_LEFT_ALT, KeyUsage.LEFT)
        PadDirection.RIGHT -> HostAction.Keys(MOD_LEFT_ALT, KeyUsage.RIGHT)
    }
    HostType.ANDROID -> when (direction) {
        PadDirection.UP -> HostAction.Consumer(ConsumerKey.AC_HOME)
        PadDirection.DOWN -> HostAction.None
        PadDirection.LEFT -> HostAction.Consumer(ConsumerKey.AC_BACK)
        PadDirection.RIGHT -> HostAction.Consumer(ConsumerKey.AC_FORWARD)
    }
}
