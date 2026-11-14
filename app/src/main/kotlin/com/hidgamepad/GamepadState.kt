package com.hidgamepad

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Pure gamepad state -> HID report encoding.
 * Gamepad payload layout for report ID 3 (9 bytes — payload only; no report-ID byte inside):
 *  b0..b1 : 16 buttons (Android-positional bit order — matches the frozen Android combo descriptor)
 *  b2     : left stick X    (signed -127..127)
 *  b3     : left stick Y    (signed -127..127)
 *  b4     : hat switch low nibble (0-7; 15 = neutral), high nibble padding (0)
 *  b5     : right stick Z   (signed -127..127)
 *  b6     : right stick Rz  (signed -127..127)
 *  b7     : left trigger / Brake  (0..255)  [Simulation page, ABS_BRAKE]
 *  b8     : right trigger / Accelerator (0..255) [Simulation page, ABS_GAS]
 */
data class GamepadState(
    val lx: Float = 0f,
    val ly: Float = 0f,
    val rx: Float = 0f,
    val ry: Float = 0f,
    val lt: Float = 0f,
    val rt: Float = 0f,
    val buttons: Int = 0,
    val hat: Int = HAT_NEUTRAL,
) {
    companion object {
        const val HAT_NEUTRAL = 15
        const val REPORT_SIZE = 9
    }
}

/** One combined Bluetooth HID descriptor with four report-ID collections. */
const val KEYBOARD_REPORT_ID = 1
const val MOUSE_REPORT_ID = 2
const val GAMEPAD_REPORT_ID = 3
const val CONSUMER_REPORT_ID = 4
const val KEYBOARD_REPORT_SIZE = 8
const val MOUSE_REPORT_SIZE = 5
const val GAMEPAD_REPORT_SIZE = 9

/**
 * The highest key usage the registered keyboard collection declares (Usage Maximum 101).
 *
 * Every key the on-screen keyboard can press has to sit inside this range, because the host
 * decodes the keyboard collection as the range the descriptor declares. A key outside it
 * would be ignored by the host, and since the host caches the descriptor at pairing time,
 * the only fix would be a re-pair.
 */
const val KEYBOARD_USAGE_MAX = 0x65

enum class ControllerMode { GAMEPAD, MOUSE, KEYBOARD, WASD }

enum class HostType { WINDOWS, ANDROID }

fun controllerModeFromStorage(name: String?): ControllerMode =
    runCatching { ControllerMode.valueOf(name ?: "") }.getOrDefault(ControllerMode.GAMEPAD)

fun hostTypeFromStorage(name: String?): HostType =
    runCatching { HostType.valueOf(name ?: "") }.getOrDefault(HostType.WINDOWS)

// The app cold-starts into Home (host + mode choice). Background resume never shows it again;
// Home is re-opened from Settings ("Home") or the next cold start.

fun pairInstructions(hostType: HostType): String = when (hostType) {
    HostType.WINDOWS -> "1. Turn Bluetooth on on both devices.\n" +
        "2. Tap Make discoverable.\n" +
        "3. On Windows open: Settings → Bluetooth & devices → Add device.\n" +
        "4. Select this phone and accept pairing.\n" +
        "5. Return here and choose the paired PC."
    HostType.ANDROID -> "1. Turn Bluetooth on on both phones.\n" +
        "2. Tap Make discoverable.\n" +
        "3. On the receiving device, open Bluetooth settings.\n" +
        "4. Select this phone and accept pairing.\n" +
        "5. Return here and choose the paired device.\n\n" +
        "Some Android phones do not support Bluetooth HID gamepad, mouse, or keyboard hosts."
}

/** Bit positions in the 16-button mask. Android-positional order, validated against GKME/Gamepad. */
object Btn {
    const val A = 0
    const val B = 1
    const val X = 3
    const val Y = 4
    const val L1 = 6
    const val R1 = 7
    const val SELECT = 10
    const val START = 11
    const val MODE = 12
    const val L3 = 13
    const val R3 = 14
}

/** -1..1 -> signed byte -127..127 (never -128). */
fun axisByte(v: Float): Byte = (v * 127f).roundToInt().coerceIn(-127, 127).toByte()

/** 0..1 -> 0..255 byte. */
fun triggerByte(v: Float): Byte = (v * 255f).roundToInt().coerceIn(0, 255).toByte()

/** ~8% deadzone; output stays continuous outside the deadzone. */
fun applyDeadzone(v: Float, deadzone: Float = 0.08f): Float {
    val a = abs(v)
    if (a <= deadzone) return 0f
    return (a - deadzone) / (1f - deadzone) * sign(v)
}

/** Encode a gamepad snapshot into the fixed 9-byte HID report. */
fun GamepadState.encodeReport(): ByteArray = byteArrayOf(
    (buttons and 0xFF).toByte(),
    ((buttons shr 8) and 0xFF).toByte(),
    axisByte(lx),
    axisByte(ly),
    (hat and 0x0F).toByte(),
    axisByte(rx),
    axisByte(ry),
    triggerByte(lt),
    triggerByte(rt),
)

const val MOD_LEFT_CTRL = 0x01
const val MOD_LEFT_SHIFT = 0x02
const val MOD_LEFT_ALT = 0x04
const val MOD_LEFT_GUI = 0x08
const val MOD_RIGHT_CTRL = 0x10
const val MOD_RIGHT_SHIFT = 0x20
const val MOD_RIGHT_ALT = 0x40
const val MOD_RIGHT_GUI = 0x80

/**
 * The eight modifier bits the keyboard collection declares (E0..E7). A modifier outside this
 * set would be one the host does not read.
 */
val MODIFIER_BITS = setOf(
    MOD_LEFT_CTRL, MOD_LEFT_SHIFT, MOD_LEFT_ALT, MOD_LEFT_GUI,
    MOD_RIGHT_CTRL, MOD_RIGHT_SHIFT, MOD_RIGHT_ALT, MOD_RIGHT_GUI,
)

object KeyUsage {
    const val A = 0x04
    const val B = 0x05
    const val C = 0x06
    const val D = 0x07
    const val E = 0x08
    const val F = 0x09
    const val G = 0x0A
    const val H = 0x0B
    const val I = 0x0C
    const val J = 0x0D
    const val K = 0x0E
    const val L = 0x0F
    const val M = 0x10
    const val N = 0x11
    const val O = 0x12
    const val P = 0x13
    const val Q = 0x14
    const val R = 0x15
    const val S = 0x16
    const val T = 0x17
    const val U = 0x18
    const val V = 0x19
    const val W = 0x1A
    const val X = 0x1B
    const val Y = 0x1C
    const val Z = 0x1D
    const val NUM_1 = 0x1E
    const val NUM_2 = 0x1F
    const val NUM_3 = 0x20
    const val NUM_4 = 0x21
    const val NUM_5 = 0x22
    const val NUM_6 = 0x23
    const val NUM_7 = 0x24
    const val NUM_8 = 0x25
    const val NUM_9 = 0x26
    const val NUM_0 = 0x27
    const val ENTER = 0x28
    const val ESC = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val CAPSLOCK = 0x39
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52

    // The function keys. Every one of them is inside the range the registered descriptor
    // already declares (Usage Maximum 0x65), so they cost nothing on the wire and need no
    // re-pair.
    const val F1 = 0x3A
    const val F2 = 0x3B
    const val F3 = 0x3C
    const val F4 = 0x3D
    const val F5 = 0x3E
    const val F6 = 0x3F
    const val F7 = 0x40
    const val F8 = 0x41
    const val F9 = 0x42
    const val F10 = 0x43
    const val F11 = 0x44
    const val F12 = 0x45
}
/**
 * Decode the Caps Lock bit from an LED output report.
 *
 * The host signals keyboard LEDs with report ID 1 and a payload whose low bits hold one bit per
 * LED usage, in descriptor order: bit0 = Num Lock, bit1 = Caps Lock, bit2 = Scroll Lock,
 * bit3 = Compose, bit4 = Kana. An empty payload means the host stopped sending LED reports, so
 * caps reads as off.
 */
fun capsLockLedBit(data: ByteArray): Boolean =
    data.isNotEmpty() && (data[0].toInt() and 0x02) != 0

data class KeyboardState(
    val modifiers: Int = 0,
    val pressedKeys: Set<Int> = emptySet(),
)

fun KeyboardState.encodeReport(): ByteArray {
    val keys = pressedKeys.map { it and 0xFF }.sorted().take(6)
    val out = ByteArray(8)
    out[0] = (modifiers and 0xFF).toByte()
    out[1] = 0
    for (i in keys.indices) out[2 + i] = keys[i].toByte()
    return out
}

data class MouseState(
    val buttons: Int = 0,
    val dx: Int = 0,
    val dy: Int = 0,
    val wheel: Int = 0,
    val hPan: Int = 0,
)

fun MouseState.encodeReport(): ByteArray = byteArrayOf(
    (buttons and 0xFF).toByte(),
    dx.coerceIn(-127, 127).toByte(),
    dy.coerceIn(-127, 127).toByte(),
    wheel.coerceIn(-127, 127).toByte(),
    hPan.coerceIn(-127, 127).toByte(),
)

fun MouseState.splitIntoReports(): List<MouseState> {
    if (dx == 0 && dy == 0 && wheel == 0 && hPan == 0) return listOf(this)
    var remainingDx = dx
    var remainingDy = dy
    var remainingWheel = wheel
    var remainingHPan = hPan
    val out = mutableListOf<MouseState>()
    while (remainingDx != 0 || remainingDy != 0 || remainingWheel != 0 || remainingHPan != 0) {
        val chunk = MouseState(
            buttons = buttons,
            dx = remainingDx.coerceIn(-127, 127),
            dy = remainingDy.coerceIn(-127, 127),
            wheel = remainingWheel.coerceIn(-127, 127),
            hPan = remainingHPan.coerceIn(-127, 127),
        )
        out.add(chunk)
        remainingDx -= chunk.dx
        remainingDy -= chunk.dy
        remainingWheel -= chunk.wheel
        remainingHPan -= chunk.hPan
    }
    return out
}

object ConsumerKey {
    const val PLAY_PAUSE = 0x00CD
    const val NEXT = 0x00B5
    const val PREVIOUS = 0x00B6
    const val VOLUME_UP = 0x00E9
    const val VOLUME_DOWN = 0x00EA
    const val MUTE = 0x00E2

    /** HID 1.12 consumer usages used by Android three-finger navigation. */
    const val AC_HOME = 0x0223
    const val AC_BACK = 0x0224
    const val AC_FORWARD = 0x0225
}

data class ConsumerState(val usage: Int = 0)

fun ConsumerState.encodeReport(): ByteArray = byteArrayOf(
    (usage and 0xFF).toByte(),
    ((usage shr 8) and 0xFF).toByte(),
)

fun neutralReportSequence(): List<Pair<Int, ByteArray>> = listOf(
    KEYBOARD_REPORT_ID to KeyboardState().encodeReport(),
    MOUSE_REPORT_ID to MouseState().encodeReport(),
    GAMEPAD_REPORT_ID to GamepadState().encodeReport(),
    CONSUMER_REPORT_ID to ConsumerState().encodeReport(),
)

/**
 * Frozen composite HID descriptor from research: report ID 1 keyboard, report ID 2 mouse,
 * report ID 3 Android-tuned gamepad, report ID 4 consumer control. Report IDs and collection
 * order must not change after release; any byte change requires re-pairing.
 */
private val DESCRIPTOR_PATTERN = intArrayOf(
    // ================= Keyboard collection (report ID 1) =================
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x06,       // Usage (Keyboard)
    0xA1, 0x01,       // Collection (Application)
    0x85, 0x01,       //   Report ID (1)
    0x05, 0x07,       //   Usage Page (Keyboard/Keypad)
    // Modifier byte: 8 one-bit modifier usages E0..E7.
    0x19, 0xE0,       //   Usage Minimum (Left Control)
    0x29, 0xE7,       //   Usage Maximum (Right GUI)
    0x15, 0x00,       //   Logical Minimum (0)
    0x25, 0x01,       //   Logical Maximum (1)
    0x75, 0x01,       //   Report Size (1)
    0x95, 0x08,       //   Report Count (8)
    0x81, 0x02,       //   Input (Data, Variable, Absolute)
    // Reserved byte: one constant byte.
    0x95, 0x01,       //   Report Count (1)
    0x75, 0x08,       //   Report Size (8)
    0x81, 0x01,       //   Input (Constant, Variable, Absolute)
    // LED output report: consumed by the app to drive the on-screen CapsLock indicator.
    0x05, 0x08,       //   Usage Page (LED)
    0x19, 0x01,       //   Usage Minimum (Num Lock)
    0x29, 0x05,       //   Usage Maximum (Kana)
    0x15, 0x00,       //   Logical Minimum (0)
    0x25, 0x01,       //   Logical Maximum (1)
    0x75, 0x01,       //   Report Size (1)
    0x95, 0x05,       //   Report Count (5)
    0x91, 0x02,       //   Output (Data, Variable, Absolute)
    0x95, 0x01,       //   Report Count (1)
    0x75, 0x03,       //   Report Size (3)
    0x91, 0x01,       //   Output (Constant, Variable, Absolute)
    0x05, 0x07,       //   Usage Page (Keyboard/Keypad)
    // Six ordinary key bytes: usages 0..0x65.
    0x19, 0x00,       //   Usage Minimum (0)
    0x29, 0x65,       //   Usage Maximum (101)
    0x15, 0x00,       //   Logical Minimum (0)
    0x25, 0x65,       //   Logical Maximum (101)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x06,       //   Report Count (6)
    0x81, 0x00,       //   Input (Data, Array, Absolute)
    0xC0,             // End Collection

    // ================= Mouse collection (report ID 2) =================
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x02,       // Usage (Mouse)
    0xA1, 0x01,       // Collection (Application)
    0x85, 0x02,       //   Report ID (2)
    0x09, 0x01,       //   Usage (Pointer)
    0xA1, 0x00,       //   Collection (Physical)
    // Buttons: five one-bit buttons plus three padding bits (byte 0).
    0x05, 0x09,       //   Usage Page (Button)
    0x19, 0x01,       //   Usage Minimum (Button 1)
    0x29, 0x05,       //   Usage Maximum (Button 5)
    0x15, 0x00,       //   Logical Minimum (0)
    0x25, 0x01,       //   Logical Maximum (1)
    0x75, 0x01,       //   Report Size (1)
    0x95, 0x05,       //   Report Count (5)
    0x81, 0x02,       //   Input (Data, Variable, Absolute)
    0x95, 0x01,       //   Report Count (1)
    0x75, 0x03,       //   Report Size (3)
    0x81, 0x01,       //   Input (Constant, Variable, Absolute)
    // Relative axes X, Y, Vertical Wheel (bytes 1..3).
    0x05, 0x01,       //   Usage Page (Generic Desktop)
    0x09, 0x30,       //   Usage (X)
    0x09, 0x31,       //   Usage (Y)
    0x09, 0x38,       //   Usage (Wheel)
    0x15, 0x81,       //   Logical Minimum (-127)
    0x25, 0x7F,       //   Logical Maximum (127)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x03,       //   Report Count (3)
    0x81, 0x06,       //   Input (Data, Variable, Relative)
    // Horizontal AC Pan (byte 4).
    0x05, 0x0C,       //   Usage Page (Consumer)
    0x0A, 0x38, 0x02, //   Usage (AC Pan)
    0x15, 0x81,       //   Logical Minimum (-127)
    0x25, 0x7F,       //   Logical Maximum (127)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x01,       //   Report Count (1)
    0x81, 0x06,       //   Input (Data, Variable, Relative)
    0xC0,             // End Collection (Pointer)
    0xC0,             // End Collection (Mouse Application)

    // ================= Gamepad collection (report ID 3) =================
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Gamepad)
    0xA1, 0x01,       // Collection (Application)
    0x85, 0x03,       //   Report ID (3)
    // --- 16 buttons: bits 0-15 (report bytes 0-1) ---
    0x05, 0x09,       //   Usage Page (Button)
    0x19, 0x01,       //   Usage Minimum (Button 1)
    0x29, 0x10,       //   Usage Maximum (Button 16)
    0x15, 0x00,       //   Logical Minimum (0)
    0x25, 0x01,       //   Logical Maximum (1)
    0x95, 0x10,       //   Report Count (16)
    0x75, 0x01,       //   Report Size (1)
    0x81, 0x02,       //   Input (Data, Variable, Absolute)
    // --- signed left-stick axes: X, Y (bytes 2-3) ---
    0x05, 0x01,       //   Usage Page (Generic Desktop)
    0x09, 0x30,       //   Usage (X)
    0x09, 0x31,       //   Usage (Y)
    0x15, 0x81,       //   Logical Minimum (-127)
    0x25, 0x7F,       //   Logical Maximum (127)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x02,       //   Report Count (2)
    0x81, 0x02,       //   Input (Data, Variable, Absolute)
    // --- hat switch: low nibble of byte 3 ---
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
    // --- signed right-stick axes: Z, Rz (bytes 6-7) ---
    0x09, 0x32,       //   Usage (Z)
    0x09, 0x35,       //   Usage (Rz)
    0x15, 0x81,       //   Logical Minimum (-127)
    0x25, 0x7F,       //   Logical Maximum (127)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x02,       //   Report Count (2)
    0x81, 0x02,       //   Input (Data, Variable, Absolute)
    // --- analog triggers on the HID Simulation page (bytes 8-9) ---
    0x05, 0x02,       //   Usage Page (Simulation)
    0x09, 0xC5,       //   Usage (Brake)   -> left trigger
    0x09, 0xC4,       //   Usage (Accelerator) -> right trigger
    0x15, 0x00,       //   Logical Minimum (0)
    0x26, 0xFF, 0x00, //   Logical Maximum (255)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x02,       //   Report Count (2)
    0x81, 0x02,       //   Input (Data, Variable, Absolute)
    0xC0,             // End Collection

    // ================= Consumer control collection (report ID 4) =================
    0x05, 0x0C,       // Usage Page (Consumer)
    0x09, 0x01,       // Usage (Consumer Control)
    0xA1, 0x01,       // Collection (Application)
    0x85, 0x04,       //   Report ID (4)
    0x15, 0x00,       //   Logical Minimum (0)
    0x26, 0xFF, 0x03, //   Logical Maximum (1023)
    0x19, 0x00,       //   Usage Minimum (0)
    0x2A, 0xFF, 0x03, //   Usage Maximum (1023)
    0x75, 0x10,       //   Report Size (16)
    0x95, 0x01,       //   Report Count (1)
    0x81, 0x00,       //   Input (Data, Array, Absolute)
    0xC0,             // End Collection
)

val COMBINED_HID_DESCRIPTOR: ByteArray = ByteArray(DESCRIPTOR_PATTERN.size) { DESCRIPTOR_PATTERN[it].toByte() }
