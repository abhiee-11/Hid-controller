package com.hidgamepad

import kotlin.math.roundToInt

/**
 * Which HID descriptor the phone registers. The host caches the descriptor at pairing
 * time, so a profile is fixed until the user re-pairs.
 */
enum class HidProfile {
    /** Keyboard + mouse + gamepad + consumer over one link. The Android/tablet profile. */
    COMPOSITE,

    /**
     * A dedicated gamepad whose buttons and axes follow the W3C standard layout, so
     * DirectInput hosts expose a real gamepad.
     */
    GAMEPAD_ONLY,
}

/** The gamepad-only report: 2 button bytes, four 16-bit axes, two trigger bytes, one hat. */
const val GAMEPAD_ONLY_REPORT_SIZE = 13

/**
 * The profile the host was last paired with, read from storage.
 *
 * Defaults to [selected] on first run: a fresh install has no stale host cache, so the
 * profile the host type derives is also the one the host will pair with.
 */
fun pairedProfileFromStorage(name: String?, selected: HidProfile): HidProfile =
    name?.let { runCatching { HidProfile.valueOf(it) }.getOrNull() } ?: selected

/**
 * The HID descriptor the host needs, derived from the connection choice.
 *
 * The two hosts want different orderings: Android's kernel maps gamepad buttons positionally,
 * so the composite descriptor's `Btn` order is correct, while Windows maps them by
 * declaration order, so the gamepad-only W3C order is correct. Deriving the profile here
 * removes the separate picker that could be set to a combination that works on neither.
 */
fun profileForHostType(hostType: HostType): HidProfile = when (hostType) {
    HostType.ANDROID -> HidProfile.COMPOSITE
    HostType.WINDOWS -> HidProfile.GAMEPAD_ONLY
}

/** Maps a canonical button bit to its W3C standard-layout wire bit, or -1 if unmapped. */
fun w3cWireBit(canonicalBit: Int): Int = when (canonicalBit) {
    Btn.A -> 0
    Btn.B -> 1
    Btn.X -> 2
    Btn.Y -> 3
    Btn.L1 -> 4
    Btn.R1 -> 5
    Btn.SELECT -> 8
    Btn.START -> 9
    Btn.L3 -> 10
    Btn.R3 -> 11
    Btn.MODE -> 12
    else -> -1
}

/**
 * True when the profile the host type derives is not the one the host was paired with.
 *
 * The host caches the report descriptor at pairing time, so a host-type change only takes
 * effect after the user removes the phone and pairs it again. Until then the app has to
 * say so, or the user sees "nothing changed" and concludes the feature is broken.
 */
fun profileNeedsRepair(current: HidProfile, registered: HidProfile): Boolean =
    current != registered

/** The descriptor the phone registers for the given profile. */
fun descriptorFor(profile: HidProfile): ByteArray = when (profile) {
    HidProfile.COMPOSITE -> COMBINED_HID_DESCRIPTOR
    HidProfile.GAMEPAD_ONLY -> GAMEPAD_ONLY_DESCRIPTOR
}

/**
 * The Bluetooth HID subclass the phone registers with.
 *
 * The composite profile uses SUBCLASS1_NONE so the descriptor speaks for itself: Android's
 * InputReader then reads each collection separately instead of collapsing them under
 * GAMEPAD, which is what suppressed SOURCE_MOUSE. The gamepad-only profile uses
 * SUBCLASS2_GAMEPAD, because a device that carries nothing but a gamepad should say so.
 */
fun subclassFor(profile: HidProfile): Byte = when (profile) {
    HidProfile.COMPOSITE -> 0x00
    HidProfile.GAMEPAD_ONLY -> 0x02
}

/** The report the phone sends for the given profile. */
fun reportFor(profile: HidProfile, state: GamepadState): ByteArray = when (profile) {
    HidProfile.COMPOSITE -> state.encodeReport()
    HidProfile.GAMEPAD_ONLY -> encodeGamepadOnlyReport(state)
}

/**
 * The report to send for the current inputs.
 *
 * The host caches the report descriptor at pairing time, so after a profile change it still
 * decodes the profile it was paired with. Reports must follow [paired], never the newly
 * [selected] profile, until the user confirms a re-pair — otherwise the host mis-parses a
 * report of the wrong length as garbage input.
 */
fun reportForHost(selected: HidProfile, paired: HidProfile, state: GamepadState): ByteArray =
    reportFor(paired, state)

/**
 * Encodes a gamepad snapshot into the gamepad-only report.
 *
 * The button bytes follow the W3C standard layout, so a browser's Gamepad API and
 * DirectInput both see a conventional gamepad. The axes are X, Y, Rx, Ry then the two
 * triggers, which is the order the standard layout expects.
 */
fun encodeGamepadOnlyReport(state: GamepadState): ByteArray {
    var wireButtons = 0
    for (bit in 0..15) {
        if (state.buttons and (1 shl bit) == 0) continue
        val wire = w3cWireBit(bit)
        if (wire >= 0) wireButtons = wireButtons or (1 shl wire)
    }
    return byteArrayOf(
        (wireButtons and 0xFF).toByte(),
        ((wireButtons shr 8) and 0xFF).toByte(),
        *axisWord(state.lx),
        *axisWord(state.ly),
        *axisWord(state.rx),
        *axisWord(state.ry),
        triggerByte(state.lt),
        triggerByte(state.rt),
        (state.hat and 0x0F).toByte(),
    )
}

/** -1..1 -> signed 16-bit word, low byte first. */
private fun axisWord(v: Float): ByteArray {
    val scaled = (v * 32767f).roundToInt().coerceIn(-32767, 32767)
    val bits = scaled and 0xFFFF
    return byteArrayOf((bits and 0xFF).toByte(), ((bits shr 8) and 0xFF).toByte())
}

/**
 * A gamepad-only descriptor: one gamepad collection whose buttons and axes follow the
 * W3C standard layout.
 *
 * A host derives a gamepad's identity from its collection order and button order. The
 * composite descriptor puts the keyboard and the mouse first and the gamepad's buttons in
 * Android's positional order, so no host can derive a standard layout from it. This
 * descriptor exists to be the only thing in the device.
 */
val GAMEPAD_ONLY_DESCRIPTOR: ByteArray = intArrayOf(
        // ================= Gamepad collection (report ID 3) =================
        0x05, 0x01,       // Usage Page (Generic Desktop)
        0x09, 0x05,       // Usage (Gamepad)
        0xA1, 0x01,       // Collection (Application)
        0x85, 0x03,       //   Report ID (3)

        // --- 16 buttons: bits 0-15 (report bytes 0-1), W3C standard order ---
        0x05, 0x09,       //   Usage Page (Button)
        0x19, 0x01,       //   Usage Minimum (Button 1)
        0x29, 0x10,       //   Usage Maximum (Button 16)
        0x15, 0x00,       //   Logical Minimum (0)
        0x25, 0x01,       //   Logical Maximum (1)
        0x95, 0x10,       //   Report Count (16)
        0x75, 0x01,       //   Report Size (1)
        0x81, 0x02,       //   Input (Data, Variable, Absolute)

        // --- signed 16-bit sticks: X, Y (left), Rx, Ry (right) ---
        0x05, 0x01,       //   Usage Page (Generic Desktop)
        0x09, 0x30,       //   Usage (X)
        0x09, 0x31,       //   Usage (Y)
        0x09, 0x33,       //   Usage (Rx)
        0x09, 0x34,       //   Usage (Ry)
        0x16, 0x80, 0x80, //   Logical Minimum (-32768)
        0x26, 0xFF, 0x7F, //   Logical Maximum (32767)
        0x75, 0x10,       //   Report Size (16)
        0x95, 0x04,       //   Report Count (4)
        0x81, 0x02,       //   Input (Data, Variable, Absolute)

        // --- analog triggers on Generic Desktop Z and Rz (report bytes 10-11) ---
        0x09, 0x32,       //   Usage (Z)
        0x09, 0x35,       //   Usage (Rz)
        0x15, 0x00,       //   Logical Minimum (0)
        0x26, 0xFF, 0x00, //   Logical Maximum (255)
        0x75, 0x08,       //   Report Size (8)
        0x95, 0x02,       //   Report Count (2)
        0x81, 0x02,       //   Input (Data, Variable, Absolute)

        // --- hat switch: low nibble of byte 12 ---
        0x09, 0x39,       //   Usage (Hat switch)
        0x15, 0x00,       //   Logical Minimum (0)
        0x25, 0x07,       //   Logical Maximum (7)
        0x35, 0x00,       //   Physical Minimum (0 deg)
        0x46, 0x3B, 0x01, //   Physical Maximum (315 deg)
        0x65, 0x14,       //   Unit (English degrees)
        0x75, 0x04,       //   Report Size (4)
        0x95, 0x01,       //   Report Count (1)
        0x81, 0x42,       //   Input (Data, Variable, Absolute, Null State)
        0x65, 0x00,       //   Unit (None)
        0x75, 0x04,       //   Report Size (4)
        0x95, 0x01,       //   Report Count (1)
        0x81, 0x03,       //   Input (Constant, Variable, Absolute)

        0xC0,             // End Collection
).map { it.toByte() }.toByteArray()
