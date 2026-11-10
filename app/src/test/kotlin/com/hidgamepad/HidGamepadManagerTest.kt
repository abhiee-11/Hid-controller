package com.hidgamepad

import android.bluetooth.BluetoothProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HidGamepadManagerTest {

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
}
