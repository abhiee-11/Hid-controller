package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ProblemReportTest {

    @Test
    fun `a topic becomes an ntfy url`() {
        // A generic topic: the maintainer's own topic lives in local.properties and must
        // never be written into a tracked file.
        assertEquals("https://ntfy.sh/my_report_topic", ntfyTopicUrl("my_report_topic"))
    }

    @Test
    fun `a topic is trimmed before it is used`() {
        assertEquals("https://ntfy.sh/abc", ntfyTopicUrl("  abc  "))
    }

    @Test
    fun `an empty topic has no url`() {
        assertNull(ntfyTopicUrl(""))
        assertNull(ntfyTopicUrl("   "))
    }

    @Test
    fun `a topic that could escape the path is rejected`() {
        // The topic is a path segment on a remote server. Anything that could escape it,
        // add a query string or inject a second request line must never reach the network.
        assertNull(ntfyTopicUrl("my topic"))
        assertNull(ntfyTopicUrl("topic/../other"))
        assertNull(ntfyTopicUrl("topic?x=1"))
        assertNull(ntfyTopicUrl("topic#frag"))
        assertNull(ntfyTopicUrl("topic\nInjected: yes"))
        assertNull(ntfyTopicUrl("topic\r\nInjected: yes"))
    }

    @Test
    fun `a trailing newline is trimmed away rather than sent`() {
        // Whitespace at the edges is removed before the topic is validated, so what would
        // actually reach the network is the bare topic — never a topic with a newline on it.
        assertEquals("https://ntfy.sh/topic", ntfyTopicUrl("topic\r\n"))
        assertEquals("https://ntfy.sh/topic", ntfyTopicUrl("topic\n"))
    }

    @Test
    fun `a topic longer than 64 characters is rejected`() {
        assertNull(ntfyTopicUrl("a".repeat(65)))
        assertEquals("https://ntfy.sh/" + "a".repeat(64), ntfyTopicUrl("a".repeat(64)))
    }

    @Test
    fun `topic validity follows the url`() {
        assertTrue(isValidTopic("my_report_topic"))
        assertFalse(isValidTopic("not a topic"))
    }

    @Test
    fun `the build topic wins over the stored one`() {
        assertEquals("build", resolveReportTopic(buildTopic = "build", storedTopic = "stored"))
    }

    @Test
    fun `the stored topic is used when the build has none`() {
        assertEquals("stored", resolveReportTopic(buildTopic = "", storedTopic = "stored"))
        assertEquals("stored", resolveReportTopic(buildTopic = "   ", storedTopic = "stored"))
    }

    @Test
    fun `no topic at all means the user has to be asked`() {
        // A fresh clone has no topic in local.properties. The app must ask rather than
        // silently dropping the report.
        assertNull(resolveReportTopic(buildTopic = "", storedTopic = ""))
        assertNull(resolveReportTopic(buildTopic = "  ", storedTopic = "  "))
    }

    @Test
    fun `the body is the description when there are no details`() {
        assertEquals("The trigger stopped working", buildReportBody("The trigger stopped working", null))
    }

    @Test
    fun `the description is trimmed`() {
        assertEquals("boom", buildReportBody("  boom  ", null))
    }

    @Test
    fun `details are appended below a separator`() {
        val body = buildReportBody("The trigger stopped working", "App: 1.1")
        assertTrue(body.contains("The trigger stopped working"))
        assertTrue(body.contains("App: 1.1"))
        assertTrue(body.contains(REPORT_SEPARATOR))
        assertTrue(body.indexOf("The trigger stopped working") < body.indexOf(REPORT_SEPARATOR))
    }

    @Test
    fun `blank details add no separator`() {
        assertFalse(buildReportBody("boom", "   ").contains(REPORT_SEPARATOR))
    }

    @Test
    fun `a report body is the description followed by the details`() {
        val body = buildReportBody(
            description = "buttons under my notch",
            diagnostics = "Screen\n  Cutout        yes — 67 px top inset",
        )
        assertTrue(body.startsWith("buttons under my notch"))
        assertTrue(body.contains(REPORT_SEPARATOR))
        assertTrue(body.endsWith("top inset"))
    }

    @Test
    fun `a successful status means the report was sent`() {
        assertNull(reportFailureReason(200))
        assertNull(reportFailureReason(201))
        assertNull(reportFailureReason(204))
    }

    @Test
    fun `a rejected topic is reported as such`() {
        val reason = reportFailureReason(403)
        assertTrue(reason != null && reason.contains("topic"))
    }

    @Test
    fun `a missing topic is reported as such`() {
        val reason = reportFailureReason(404)
        assertTrue(reason != null && reason.contains("topic"))
    }

    @Test
    fun `rate limiting and oversized reports get their own messages`() {
        assertTrue(reportFailureReason(429)!!.contains("many"))
        assertTrue(reportFailureReason(413)!!.contains("long"))
    }

    @Test
    fun `any other failure is reported generically`() {
        assertTrue(reportFailureReason(500) != null)
        assertTrue(reportFailureReason(-1) != null)
        assertTrue(reportFailureReason(0) != null)
    }

    @Test
    fun `preflight refuses an invalid topic before any network call`() {
        assertTrue(reportPreflightFailure(topic = "not a topic", body = "boom") != null)
    }

    @Test
    fun `preflight refuses a blank description`() {
        assertTrue(reportPreflightFailure(topic = "abc", body = "   ") != null)
    }

    @Test
    fun `preflight passes a valid report`() {
        assertNull(reportPreflightFailure(topic = "abc", body = "boom"))
    }

    @Test
    fun `an invalid topic is refused without a network call`() = runBlocking {
        val outcome = ProblemReporter.send(topic = "not a topic", title = REPORT_TITLE, body = "boom")
        assertTrue(outcome is ReportOutcome.Failed)
        assertEquals("The ntfy topic name is not valid.", (outcome as ReportOutcome.Failed).reason)
    }

    @Test
    fun `a blank description is refused without a network call`() = runBlocking {
        val outcome = ProblemReporter.send(topic = "abc", title = REPORT_TITLE, body = "   ")
        assertTrue(outcome is ReportOutcome.Failed)
        assertEquals("Describe the problem first.", (outcome as ReportOutcome.Failed).reason)
    }

    @Test
    fun `the details block names the screen the user was on`() {
        val info = DeviceScreenInfo(
            windowWidthDp = 857, windowHeightDp = 384, orientation = "landscape",
            displayWidthPx = 720, displayHeightPx = 1608, densityDpi = 300, densityScale = "1.875",
            smallestWidthDp = 360, refreshRateHz = "60", cutoutInset = "67 px top inset",
            cornerRadiusPx = 47, theme = "dark", manufacturer = "vivo", model = "V2434",
            androidRelease = "16", sdkInt = 36, appVersion = "1.1",
        )
        val block = info.toDetailsBlock(
            screenLabel = "Gamepad",
            mode = ControllerMode.GAMEPAD,
            hostType = HostType.WINDOWS,
            hidStatus = "Registered",
            tiltOn = false,
        )
        assertTrue(block.contains("857 x 384 dp (landscape)"))
        assertTrue(block.contains("720 x 1608 px, 300 dpi (1.875x)"))
        assertTrue(block.contains("360 dp"))
        assertTrue(block.contains("60 Hz"))
        assertTrue(block.contains("yes"))
        assertTrue(block.contains("67 px top inset"))
        assertTrue(block.contains("47 px radius"))
        assertTrue(block.contains("dark"))
        assertTrue(block.contains("vivo V2434"))
        assertTrue(block.contains("Android 16 (SDK 36)"))
        assertTrue(block.contains("HID Controller 1.1"))
        assertTrue(block.contains("Windows PC"))
        assertTrue(block.contains("Registered"))
        assertTrue(block.contains("Width class   expanded"))
    }

    @Test
    fun `a device without a cutout says so`() {
        val info = DeviceScreenInfo(
            windowWidthDp = 411, windowHeightDp = 731, orientation = "portrait",
            displayWidthPx = 1080, displayHeightPx = 2400, densityDpi = 420, densityScale = "2.625",
            smallestWidthDp = 393, refreshRateHz = "60", cutoutInset = null,
            cornerRadiusPx = 0, theme = "light", manufacturer = "Google", model = "Pixel 8",
            androidRelease = "15", sdkInt = 35, appVersion = "1.1",
        )
        val block = info.toDetailsBlock("Keyboard", ControllerMode.KEYBOARD, HostType.ANDROID, "Off", true)
        assertTrue(block.contains("Cutout        no"))
        assertTrue(block.contains("Corners       none"))
        assertTrue(block.contains("light"))
        assertTrue(block.contains("Pixel 8"))
        assertTrue(block.contains("Android device"))
        assertTrue(block.contains("Tilt           on"))
        assertTrue(block.contains("Width class   compact"))
    }
}
