package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Test

class HostGestureMappingTest {

    @Test
    fun `windows swipes use gui and alt chords`() {
        assertEquals(HostAction.Keys(MOD_LEFT_GUI, KeyUsage.TAB), hostActionFor(PadDirection.UP, HostType.WINDOWS))
        assertEquals(HostAction.Keys(MOD_LEFT_GUI, KeyUsage.D), hostActionFor(PadDirection.DOWN, HostType.WINDOWS))
        assertEquals(HostAction.Keys(MOD_LEFT_ALT, KeyUsage.LEFT), hostActionFor(PadDirection.LEFT, HostType.WINDOWS))
        assertEquals(HostAction.Keys(MOD_LEFT_ALT, KeyUsage.RIGHT), hostActionFor(PadDirection.RIGHT, HostType.WINDOWS))
    }

    @Test
    fun `android swipes use consumer navigation usages`() {
        assertEquals(HostAction.Consumer(ConsumerKey.AC_HOME), hostActionFor(PadDirection.UP, HostType.ANDROID))
        assertEquals(HostAction.None, hostActionFor(PadDirection.DOWN, HostType.ANDROID))
        assertEquals(HostAction.Consumer(ConsumerKey.AC_BACK), hostActionFor(PadDirection.LEFT, HostType.ANDROID))
        assertEquals(HostAction.Consumer(ConsumerKey.AC_FORWARD), hostActionFor(PadDirection.RIGHT, HostType.ANDROID))
    }
}
