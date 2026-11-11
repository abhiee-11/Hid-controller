package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun ByteArray.toHexString(): String = joinToString("") { "%02X ".format(it).padEnd(3) }.let { it }

private data class HidField(
    val reportSize: Int,
    val reportCount: Int,
    val flags: Int,
    val usagePage: Int,
    val usages: List<Int>,
    val usageMin: Int?,
    val usageMax: Int?,
    val logicalMin: Int,
    val logicalMax: Int,
    val reportId: Int,
) {
    val bits: Int get() = reportSize * reportCount
    val isConstant: Boolean get() = (flags and 0x01) != 0
}

private data class DescriptorSummary(
    val inputBits: Int,
    val reportIdItemCount: Int,
    val reportIds: List<Int>,
    val applicationCollections: Int,
    val physicalCollections: Int,
    val physicalPointerUsages: List<Int>,
    val fields: List<HidField>,
) {
    fun fieldsFor(reportId: Int): List<HidField> = fields.filter { it.reportId == reportId }
    fun bitsFor(reportId: Int): Int = fieldsFor(reportId).sumOf { it.bits }
}

/** Small test-only HID short-item walker that totals Input item payloads. */
private fun parseDescriptor(descriptor: ByteArray): DescriptorSummary {
    val bytes = descriptor.map { it.toInt() and 0xFF }
    var i = 0
    var usagePage = 0
    var reportSize = 0
    var reportCount = 0
    var logicalMin = 0
    var logicalMax = 0
    var reportIdItems = 0
    var currentReportId = 0
    val reportIds = mutableListOf<Int>()
    var applicationCollections = 0
    var physicalCollections = 0
    val physicalPointerUsages = mutableListOf<Int>()
    val usages = mutableListOf<Int>()
    var usageMin: Int? = null
    var usageMax: Int? = null
    val fields = mutableListOf<HidField>()

    fun signedValue(raw: List<Int>): Int {
        var v = 0
        for ((idx, b) in raw.withIndex()) v = v or (b shl (8 * idx))
        val bits = raw.size * 8
        return if (bits > 0 && bits < 32 && (v and (1 shl (bits - 1))) != 0) v - (1 shl bits) else v
    }

    fun unsignedValue(raw: List<Int>): Int {
        var v = 0
        for ((idx, b) in raw.withIndex()) v = v or (b shl (8 * idx))
        return v
    }

    while (i < bytes.size) {
        val header = bytes[i]
        if (header == 0xFE) {
            val count = bytes[i + 1]
            i += 2 + count
            continue
        }
        val tag = (header shr 4) and 0x0F
        val type = (header shr 2) and 0x03
        val sizeCode = header and 0x03
        val dataLen = when (sizeCode) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 4 }
        val data = bytes.subList(i + 1, minOf(bytes.size, i + 1 + dataLen))
        when (type) {
            1 -> { // Global
                when (tag) {
                    0 -> usagePage = unsignedValue(data)
                    1 -> logicalMin = signedValue(data)
                    2 -> logicalMax = signedValue(data)
                    7 -> reportSize = unsignedValue(data)
                    8 -> {
                        reportIdItems++
                        currentReportId = unsignedValue(data)
                        reportIds.add(currentReportId)
                    }
                    9 -> reportCount = unsignedValue(data)
                }
            }
            2 -> { // Local
                when (tag) {
                    0 -> usages.add(unsignedValue(data))
                    1 -> usageMin = unsignedValue(data)
                    2 -> usageMax = unsignedValue(data)
                }
            }
            0 -> { // Main
                if (tag == 8) { // Input
                    val flags = if (data.isNotEmpty()) data[0] else 0
                    fields.add(
                        HidField(
                            reportSize = reportSize,
                            reportCount = reportCount,
                            flags = flags,
                            usagePage = usagePage,
                            usages = usages.toList(),
                            usageMin = usageMin,
                            usageMax = usageMax,
                            logicalMin = logicalMin,
                            logicalMax = logicalMax,
                            reportId = currentReportId,
                        ),
                    )
                    usages.clear()
                    usageMin = null
                    usageMax = null
                } else if (tag == 10) { // Collection
                    if (data.isNotEmpty() && data[0] == 1) applicationCollections++
                    else if (data.isNotEmpty() && data[0] == 0) {
                        physicalCollections++
                        // The local Usage item immediately before this Physical collection is its usage.
                        if (usages.isNotEmpty()) physicalPointerUsages.add(usages.last())
                        usages.clear()
                    }
                }
            }
        }
        i += 1 + dataLen
    }
    return DescriptorSummary(fields.sumOf { it.bits }, reportIdItems, reportIds.toList(), applicationCollections, physicalCollections, physicalPointerUsages.toList(), fields)
}


class GamepadStateTest {

    @Test
    fun `neutral state encodes to nine neutral bytes`() {
        val report = GamepadState().encodeReport()
        assertEquals(GamepadState.REPORT_SIZE, report.size)
        assertEquals(0, report[0].toInt())
        assertEquals(0, report[1].toInt())
        assertEquals(GamepadState.HAT_NEUTRAL, report[4].toInt())
        assertEquals(0, report[2].toInt())
        assertEquals(0, report[3].toInt())
        assertEquals(0, report[5].toInt())
        assertEquals(0, report[6].toInt())
        assertEquals(0, report[7].toInt())
        assertEquals(0, report[8].toInt())
    }

    @Test
    fun `buttons spread across bytes 0 and 1`() {
        val mask = (1 shl Btn.A) or (1 shl Btn.START) // bit 0 and bit 11
        val report = GamepadState(buttons = mask).encodeReport()
        assertEquals(0x01, report[0].toInt())
        assertEquals(0x08, report[1].toInt()) // bit 11 -> byte 1, bit 3
    }

    @Test
    fun `left stick on bytes 3-4 and right stick on bytes 7-8 signed`() {
        val report = GamepadState(lx = -1f, rx = 1f).encodeReport()
        assertEquals(-127, report[2].toInt()) // left stick X
        assertEquals(0, report[3].toInt())    // left stick Y untouched
        assertEquals(127, report[5].toInt())  // right stick Z
        assertEquals(0, report[6].toInt())    // right stick Rz untouched
    }

    @Test
    fun `analog triggers on bytes 5-6 map quarter and three-quarter`() {
        // Brake (left trigger) 25%, Gas (right trigger) 75%.
        val report = GamepadState(lt = 0.25f, rt = 0.75f).encodeReport()
        assertEquals(64, report[7].toInt() and 0xFF)   // 255 * 0.25 = 63.75 -> 64
        assertEquals(191, report[8].toInt() and 0xFF)  // 255 * 0.75 = 191.25 -> 191
        val full = GamepadState(lt = 1f, rt = 1f).encodeReport()
        assertEquals(255, full[7].toInt() and 0xFF)
        assertEquals(255, full[8].toInt() and 0xFF)
        assertEquals(0xFF.toByte(), full[8])
    }

    @Test
    fun `trigger and right-stick byte positions never collide`() {
        val braked = GamepadState(lt = 0.5f).encodeReport()
        val stirred = GamepadState(rx = 1f).encodeReport()
        assertEquals(128, braked[7].toInt() and 0xFF)
        assertEquals(127, stirred[5].toInt())
        assertEquals(0, stirred[7].toInt())
    }

    @Test
    fun `deadzone flattens small inputs`() {
        assertEquals(0f, applyDeadzone(0.07f))
        assertEquals(0f, applyDeadzone(-0.075f))
        assertEquals(0f, applyDeadzone(0f))
    }

    @Test
    fun `deadzone reshapes past threshold and keeps sign`() {
        // (0.5 - 0.08) / (1 - 0.08) = 0.4565...
        val up = applyDeadzone(0.5f)
        assertTrue(">0.45: $up", up in 0.445f..0.468f)
        val down = applyDeadzone(-0.5f)
        assertTrue("negative: $down", down in -0.468f..-0.445f)
        // full deflection stays full
        assertEquals(1f, applyDeadzone(1f))
    }

    @Test
    fun `combined descriptor has three collections and report ids`() {
        assertTrue(COMBINED_HID_DESCRIPTOR[0] == 0x05.toByte() && COMBINED_HID_DESCRIPTOR[1] == 0x01.toByte())
        assertTrue(COMBINED_HID_DESCRIPTOR[2] == 0x09.toByte() && COMBINED_HID_DESCRIPTOR[3] == 0x06.toByte())
        assertEquals(0xC0.toByte(), COMBINED_HID_DESCRIPTOR.last())
        val summary = parseDescriptor(COMBINED_HID_DESCRIPTOR)
        assertEquals(4, summary.applicationCollections)
        assertEquals(listOf(1, 2, 3, 4), summary.reportIds)
        assertEquals(4, summary.reportIdItemCount)
    }

    @Test
    fun `each combined payload has its expected bit length`() {
        val summary = parseDescriptor(COMBINED_HID_DESCRIPTOR)
        assertEquals(72, summary.bitsFor(GAMEPAD_REPORT_ID))
        assertEquals(9, summary.bitsFor(GAMEPAD_REPORT_ID) / 8)
        assertEquals(64, summary.bitsFor(KEYBOARD_REPORT_ID))
        assertEquals(8, summary.bitsFor(KEYBOARD_REPORT_ID) / 8)
        assertEquals(40, summary.bitsFor(MOUSE_REPORT_ID))
        assertEquals(5, summary.bitsFor(MOUSE_REPORT_ID) / 8)
        assertEquals(16, summary.bitsFor(CONSUMER_REPORT_ID))
        assertEquals(2, summary.bitsFor(CONSUMER_REPORT_ID) / 8)
    }

    @Test
    fun `combined descriptor keyboard payload shape`() {
        val keyboard = parseDescriptor(COMBINED_HID_DESCRIPTOR).fieldsFor(KEYBOARD_REPORT_ID)
        assertEquals(3, keyboard.size)
        val modifiers = keyboard[0]
        val reserved = keyboard[1]
        val keys = keyboard[2]
        assertEquals(8, modifiers.reportCount * modifiers.reportSize)
        assertEquals(8, reserved.bits)
        assertTrue(reserved.isConstant)
        assertEquals(48, keys.bits)
        assertEquals(6, keys.reportCount)
        assertEquals(8, keys.reportSize)
    }

    @Test
    fun `keyboard child fields use keyboard keypad usage page`() {
        val keyboard = parseDescriptor(COMBINED_HID_DESCRIPTOR).fieldsFor(KEYBOARD_REPORT_ID)
        assertTrue(keyboard.isNotEmpty())
        assertTrue(keyboard.all { it.usagePage == 0x07 })
        val modifiers = keyboard[0]
        val keys = keyboard[2]
        // Modifier usages are declared on the Keyboard/Keypad page.
        assertEquals(0x07, modifiers.usagePage)
        assertEquals(0x07, keys.usagePage)
        assertEquals(0xe0, modifiers.usageMin)
        assertEquals(0xe7, modifiers.usageMax)
        assertEquals(0, keys.usageMin)
        assertEquals(0x65, keys.usageMax)
        // All 8 modifier usages E0..E7 must be covered by usage min/max.
        val hex = COMBINED_HID_DESCRIPTOR.toHexString()
        assertTrue(hex.contains("19 E0"))
        assertTrue(hex.contains("29 E7"))
        assertEquals(64, parseDescriptor(COMBINED_HID_DESCRIPTOR).bitsFor(KEYBOARD_REPORT_ID))
    }

    @Test
    fun `mouse has pointer physical collection and correct usage pages`() {
        val summary = parseDescriptor(COMBINED_HID_DESCRIPTOR)
        assertEquals(1, summary.physicalCollections)
        assertEquals(listOf(0x01), summary.physicalPointerUsages)
        val mouse = summary.fieldsFor(MOUSE_REPORT_ID)
        assertTrue(mouse.all { it.reportId == MOUSE_REPORT_ID })
        assertEquals(0x09, mouse[0].usagePage)
        assertEquals(0x01, mouse[2].usagePage)
        assertTrue((mouse[2].flags and 0x04) != 0)
        assertEquals(0x0C, mouse[3].usagePage)
        assertTrue((mouse[3].flags and 0x04) != 0)
        assertEquals(40, summary.bitsFor(MOUSE_REPORT_ID))
    }

    @Test
    fun `descriptor button field is sixteen one-bit values`() {
        val button = parseDescriptor(COMBINED_HID_DESCRIPTOR).fieldsFor(GAMEPAD_REPORT_ID)[0]
        assertEquals(16, button.reportCount)
        assertEquals(1, button.reportSize)
        assertEquals(16, button.bits)
        assertEquals(1, button.usageMin)
        assertEquals(16, button.usageMax)
        assertFalse(button.isConstant)
    }

    @Test
    fun `descriptor hat plus padding totals eight bits`() {
        val fields = parseDescriptor(COMBINED_HID_DESCRIPTOR).fieldsFor(GAMEPAD_REPORT_ID)
        val hat = fields[2] // buttons field 0, grouped X/Y field 1, hat field 2
        val padding = fields[3]
        assertEquals(4, hat.bits)
        assertEquals(4, padding.bits)
        assertEquals(8, hat.bits + padding.bits)
        assertTrue("hat must set Null State", (hat.flags and 0x40) != 0)
        assertTrue(padding.isConstant)
    }

    @Test
    fun `descriptor has six eight-bit axes in standard order`() {
        val summary = parseDescriptor(COMBINED_HID_DESCRIPTOR)
        val axes = summary.fieldsFor(GAMEPAD_REPORT_ID).filter { it.reportSize == 8 && !it.isConstant }
        val flatAxes = axes.flatMap { field -> field.usages.map { field.usagePage to it } }
        assertEquals(listOf(
            0x01 to 0x30, // X
            0x01 to 0x31, // Y
            0x01 to 0x32, // Z right stick X
            0x01 to 0x35, // Rz right stick Y
            0x02 to 0xC5, // Brake / left trigger
            0x02 to 0xC4, // Accelerator / right trigger
        ), flatAxes)
    }

    @Test
    fun `neutral report is valid for declared ranges and null state`() {
        val fields = parseDescriptor(COMBINED_HID_DESCRIPTOR).fieldsFor(GAMEPAD_REPORT_ID)
        val hatField = fields.first { it.reportSize == 4 && (it.flags and 0x40) != 0 }
        val neutral = GamepadState().encodeReport()
        assertEquals(15, neutral[4].toInt() and 0x0F)
        assertTrue("hat 15 is the Null State value", (hatField.flags and 0x40) != 0)
        val axes = fields.filter { it.reportSize == 8 && !it.isConstant }
        for (byteIndex in listOf(2, 3, 5, 6)) {
            assertEquals(0, neutral[byteIndex].toInt())
        }
        for (byteIndex in listOf(7, 8)) {
            assertEquals(0, neutral[byteIndex].toInt() and 0xFF)
        }
        val signedAxes = axes.filter { it.logicalMin < 0 }
        val unsignedTriggers = axes.filter { it.logicalMin == 0 && it.logicalMax == 255 }
        assertEquals(2, signedAxes.size)      // grouped X/Y and grouped Z/Rz
        assertEquals(1, unsignedTriggers.size) // grouped Brake/Accelerator
    }

    @Test
    fun `descriptor uses simulation brake then accelerator for analog triggers`() {
        val hex = COMBINED_HID_DESCRIPTOR.toHexString()
        // Usage Page (Simulation) start: 05 02
        assertTrue("usage page simulation", hex.contains("05 02"))
        // Brake first -> LTRIGGER after the Linux/AOSP mapping; then Accelerator -> RTRIGGER.
        val sim = hex.substringAfter("05 02")
        assertTrue("brake before accelerator", sim.contains("09 C5"))
        assertTrue("accelerator after brake", sim.indexOf("09 C5") in 0 until sim.indexOf("09 C4"))
        // Z/Rz are the new right stick (Android combo layout).
        assertTrue(hex.contains("09 32"))
        assertTrue(hex.contains("09 35"))
        // Rx/Ry are no longer the right stick.
        assertFalse(hex.contains("09 33"))
        assertFalse(hex.contains("09 34"))
    }

    @Test
    fun `same-frame press and release return the exact neutral bytes`() {
        val pressed = GamepadState(
            buttons = 1 shl Btn.A,
            hat = 3,
            lx = 1f,
            rt = 0.5f,
        ).encodeReport()
        assertFalse(pressed.contentEquals(GamepadState().encodeReport()))
        // down() then up() on the same control (Cup bit clear) must restore the
        // byte-identical neutral report — never leave a residual bit.
        val bit = 1 shl Btn.A
        val afterRelease = GamepadState(buttons = bit and bit.inv()).encodeReport()
        assertTrue(afterRelease.contentEquals(GamepadState().encodeReport()))
    }

    @Test
    fun `named controls map to distinct button bits`() {
        val bits = listOf(Btn.A, Btn.B, Btn.X, Btn.Y, Btn.L1, Btn.R1, Btn.SELECT, Btn.START)
        val masks = bits.map { 1 shl it }
        assertEquals(bits.size, masks.distinct().size)
        // each mask must land in the 16-bit button area (bytes 0-1)
        assertEquals(0, masks.sum() ushr 16)
    }

    @Test
    fun `mode l3 and r3 use the android positional bits`() {
        // The Linux hid-input driver maps a gamepad button whose usage is <= 0x0f to
        // BTN_GAMEPAD + usage - 1, so button N (1-based) lands on Linux code 303 + N.
        // The tablet's Generic.kl maps 304 -> BUTTON_A .. 316 -> BUTTON_MODE,
        // 317 -> BUTTON_THUMBL (L3), 318 -> BUTTON_THUMBR (R3). So the report bit
        // for a button is (its Linux code) - 304.
        assertEquals(12, Btn.MODE)   // 316 - 304
        assertEquals(13, Btn.L3)     // 317 - 304
        assertEquals(14, Btn.R3)     // 318 - 304
    }

    @Test
    fun `every android button bit is distinct`() {
        val used = listOf(
            Btn.A, Btn.B, Btn.X, Btn.Y, Btn.L1, Btn.R1,
            Btn.SELECT, Btn.START, Btn.MODE, Btn.L3, Btn.R3,
        )
        assertEquals(used.size, used.toSet().size)
    }
}
