package com.hidgamepad

import android.bluetooth.BluetoothProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HidGamepadManagerTest {

    @Test
    fun `the gamepad only profile registers with the gamepad subclass`() {
        // A dedicated gamepad must announce itself as one, or a host may still treat the
        // device as a generic HID peripheral.
        assertEquals(2, subclassFor(HidProfile.GAMEPAD_ONLY).toInt())
    }

    @Test
    fun `the composite profile registers with the unspecified subclass`() {
        // SUBCLASS1_NONE lets the descriptor speak for itself: Android's InputReader then
        // reads each collection separately instead of collapsing them under GAMEPAD.
        assertEquals(0, subclassFor(HidProfile.COMPOSITE).toInt())
    }

    @Test
    fun `the composite profile uses the composite descriptor`() {
        assertEquals(COMBINED_HID_DESCRIPTOR, descriptorFor(HidProfile.COMPOSITE))
    }

    @Test
    fun `the gamepad only profile uses its own descriptor`() {
        assertEquals(GAMEPAD_ONLY_DESCRIPTOR, descriptorFor(HidProfile.GAMEPAD_ONLY))
    }

    @Test
    fun `the composite profile keeps the nine byte report`() {
        assertEquals(9, reportFor(HidProfile.COMPOSITE, GamepadState()).size)
    }

    @Test
    fun `the gamepad only profile uses the thirteen byte report`() {
        assertEquals(13, reportFor(HidProfile.GAMEPAD_ONLY, GamepadState()).size)
    }

    @Test
    fun `accepted registration waits for the status callback`() {
        assertEquals(RegisterRetryDecision.WaitForStatusCallback, registerRetryDecision(true))
    }

    @Test
    fun `rejected registration takes the same retry path as a thrown failure`() {
        assertEquals(RegisterRetryDecision.ScheduleRetry, registerRetryDecision(false))
    }

    @Test
    fun `host replacement needs neutral only for a different live host`() {
        assertTrue(hostReplacementNeedsNeutral("AA:00", "BB:00"))
        assertFalse(hostReplacementNeedsNeutral("AA:00", "AA:00"))
        assertFalse(hostReplacementNeedsNeutral(null, "AA:00"))
    }

    @Test
    fun `reports are deliverable only in the CONNECTED state`() {
        assertTrue(hostConnectionUsable(BluetoothProfile.STATE_CONNECTED))
        assertFalse(hostConnectionUsable(BluetoothProfile.STATE_CONNECTING))
        assertFalse(hostConnectionUsable(BluetoothProfile.STATE_DISCONNECTED))
        assertFalse(hostConnectionUsable(BluetoothProfile.STATE_DISCONNECTING))
        assertFalse(hostConnectionUsable(-1))
    }

    @Test
    fun `a plugged device is adopted only when registration and connection agree`() {
        assertTrue(shouldAdoptPluggedDevice(true, BluetoothProfile.STATE_CONNECTED))
        assertFalse(shouldAdoptPluggedDevice(false, BluetoothProfile.STATE_CONNECTED))
        assertFalse(shouldAdoptPluggedDevice(true, BluetoothProfile.STATE_CONNECTING))
        assertFalse(shouldAdoptPluggedDevice(true, BluetoothProfile.STATE_DISCONNECTED))
    }

    @Test
    fun `a fresh install has the HID switch on`() {
        // Nothing stored yet: the controller has always started on launch, and it still should.
        assertTrue(hidEnabledFromStorage(null))
        assertTrue(hidEnabledFromStorage(true))
    }

    @Test
    fun `a stored off survives a restart`() {
        // The whole point of persisting the switch: a phone left off must not come back on.
        assertFalse(hidEnabledFromStorage(false))
    }

    @Test
    fun `the HID service runs only when enabled and permitted`() {
        assertTrue(shouldRunHidService(enabled = true, permissionsGranted = true))
        assertFalse(shouldRunHidService(enabled = false, permissionsGranted = true))
        assertFalse(shouldRunHidService(enabled = true, permissionsGranted = false))
        assertFalse(shouldRunHidService(enabled = false, permissionsGranted = false))
    }

    @Test
    fun `a stopped manager never self-heals its HID proxy`() {
        // stop() closes the proxy, which fires onServiceDisconnected; without this guard the
        // 3 s self-heal re-registers the HID device and the master switch turns itself back on.
        assertFalse(shouldSelfHealProxy(stopped = true, bluetoothEnabled = true))
        assertFalse(shouldSelfHealProxy(stopped = true, bluetoothEnabled = false))
        assertTrue(shouldSelfHealProxy(stopped = false, bluetoothEnabled = true))
        assertFalse(shouldSelfHealProxy(stopped = false, bluetoothEnabled = false))
    }
}
