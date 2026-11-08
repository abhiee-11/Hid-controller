package com.hidgamepad

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Pure gamepad state -> HID report encoding.
 * Report layout (9 bytes, no report ID — matches [GAMEPAD_DESCRIPTOR] exactly):
 *  b0..b1 : 16 buttons (b0 = bits 0-7, b1 = bits 8-15)
 *  b2     : low nibble hat switch (0-7; 15 = neutral), high nibble padding (0)
 *  b3     : left stick X   (signed -127..127) [Usage 0x30]
 *  b4     : left stick Y   (signed -127..127) [Usage 0x31]
 *  b5     : right stick Z  (signed -127..127) [Usage 0x32]
 *  b6     : right stick Rz (signed -127..127) [Usage 0x35]
 *  b7     : left trigger Rx  (0..255)         [Usage 0x33]
 *  b8     : right trigger Ry (0..255)         [Usage 0x34]
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

/** Bit positions in the 16-button mask. */
object Btn {
    const val A = 0
    const val B = 1
    const val X = 2
    const val Y = 3
    const val L1 = 8
    const val R1 = 9
    const val SELECT = 10
    const val START = 11
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
    (hat and 0x0F).toByte(),
    axisByte(lx),
    axisByte(ly),
    axisByte(rx),
    axisByte(ry),
    triggerByte(lt),
    triggerByte(rt),
)

/**
 * Standard gamepad HID report descriptor (no report ID, 72 input bits = 9 bytes):
 * 16 buttons | hat switch (4-bit) + 4-bit pad | X | Y | Z | Rz (signed 8 each) | Rx | Ry (unsigned 8 each).
 * Written as ints, converted to signed bytes once.
 */
private val DESCRIPTOR_PATTERN = intArrayOf(
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Gamepad)
    0xA1, 0x01,       // Collection (Application)
    // --- 16 buttons: bits 0-15 (report bytes 0-1) ---
    0x05, 0x09,       //   Usage Page (Button)
    0x19, 0x01,       //   Usage Minimum (Button 1)
    0x29, 0x10,       //   Usage Maximum (Button 16)
    0x15, 0x00,       //   Logical Minimum (0)
    0x25, 0x01,       //   Logical Maximum (1)
    0x95, 0x10,       //   Report Count (16)
    0x75, 0x01,       //   Report Size (1)
    0x81, 0x02,       //   Input (Data, Variable, Absolute)
    // --- hat switch: low nibble of byte 2 ---
    0x05, 0x01,       //   Usage Page (Generic Desktop)
    0x09, 0x39,       //   Usage (Hat switch)
    0x15, 0x00,       //   Logical Minimum (0)
    0x25, 0x07,       //   Logical Maximum (7)
    0x35, 0x00,       //   Physical Minimum (0 deg)
    0x46, 0x3B, 0x01, //   Physical Maximum (315 deg)
    0x66, 0x14, 0x00, //   Unit (English degrees)
    0x95, 0x01,       //   Report Count (1)
    0x75, 0x04,       //   Report Size (4)
    0x81, 0x42,       //   Input (Data, Variable, Absolute, Null State)
    // --- 4-bit padding: high nibble of byte 2 ---
    0x95, 0x01,       //   Report Count (1)
    0x75, 0x04,       //   Report Size (4)
    0x81, 0x01,       //   Input (Constant, Variable, Absolute)
    // --- signed axes, one byte each: X, Y, Z, Rz (bytes 3-6) ---
    0x09, 0x30,       //   Usage (X)
    0x15, 0x81,       //   Logical Minimum (-127)
    0x25, 0x7F,       //   Logical Maximum (127)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x01,       //   Report Count (1)
    0x81, 0x02,       //   Input (Data, Variable, Absolute)
    0x09, 0x31,       //   Usage (Y)
    0x81, 0x02,       //   Input
    0x09, 0x32,       //   Usage (Z)
    0x81, 0x02,       //   Input
    0x09, 0x35,       //   Usage (Rz)
    0x81, 0x02,       //   Input
    // --- unsigned triggers, one byte each: Rx, Ry (bytes 7-8) ---
    0x09, 0x33,       //   Usage (Rx)
    0x09, 0x34,       //   Usage (Ry)
    0x15, 0x00,       //   Logical Minimum (0)
    0x26, 0xFF, 0x00, //   Logical Maximum (255)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x02,       //   Report Count (2)
    0x81, 0x02,       //   Input (Data, Variable, Absolute)
    0xC0,             // End Collection
)

val GAMEPAD_DESCRIPTOR: ByteArray = ByteArray(DESCRIPTOR_PATTERN.size) { DESCRIPTOR_PATTERN[it].toByte() }
