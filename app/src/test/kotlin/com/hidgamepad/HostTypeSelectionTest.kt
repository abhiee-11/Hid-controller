package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The host type must follow the host that is actually connected, otherwise a swipe meant
 * for a tablet sends Windows chords (or the reverse) and the gesture looks broken.
 */
class HostTypeSelectionTest {

    @Test
    fun `android tablet names select the android host type`() {
        assertEquals(HostType.ANDROID, hostTypeForDeviceName("My Lenovo Tab M11"))
        assertEquals(HostType.ANDROID, hostTypeForDeviceName("Pixel 8"))
        assertEquals(HostType.ANDROID, hostTypeForDeviceName("Galaxy Tab S9"))
    }

    @Test
    fun `pc names select the windows host type`() {
        assertEquals(HostType.WINDOWS, hostTypeForDeviceName("WORKSTATION-01"))
        assertEquals(HostType.WINDOWS, hostTypeForDeviceName("DESKTOP-ABC123"))
    }

    @Test
    fun `unknown names keep the current host type`() {
        assertEquals(HostType.WINDOWS, hostTypeForDeviceName("Unknown Device", HostType.WINDOWS))
        assertEquals(HostType.ANDROID, hostTypeForDeviceName("Unknown Device", HostType.ANDROID))
    }

    @Test
    fun `a pc hiding inside another word does not claim a windows host`() {
        // Bluetooth names are frequently one packed token; "pc" needs to stand alone.
        assertEquals(HostType.ANDROID, hostTypeForDeviceName("AbhiesPc", HostType.ANDROID))
        assertEquals(HostType.WINDOWS, hostTypeForDeviceName("Abhies-PC"))
        assertEquals(HostType.WINDOWS, hostTypeForDeviceName("My PC"))
        assertEquals(HostType.WINDOWS, hostTypeForDeviceName("ThinkPad T480s"))
        assertEquals(HostType.ANDROID, hostTypeForDeviceName("Mi 10"))
        assertEquals(HostType.ANDROID, hostTypeForDeviceName("Redmi Note 10"))
    }
}
