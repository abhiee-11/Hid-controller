# Report a problem Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Report a problem" button at the top of the Settings sheet that publishes the user's description, plus device and screen details, to the maintainer's ntfy topic.

**Architecture:** A new pure-Kotlin file holds the topic validation, topic resolution, message body, preflight checks, failure-reason mapping and the HTTPS POST — all unit-testable without Android. A second new file collects the device and screen facts from the platform. The UI is a new inline full-screen panel in `GamepadScreen.kt`, following the same pattern as the existing `SetupPanel` and `SettingsPanel`: a boolean state that swaps the screen content.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2026.09.00), Material 3, `java.net.HttpURLConnection`, `kotlinx.coroutines`, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-12-report-a-problem-design.md`

## Global Constraints

- Everything new lives in package `com.hidgamepad`, matching the existing flat package.
- **No new Gradle dependency.** `HttpURLConnection` only. No Compose UI-test dependency either — the panel is verified on the device, not by an instrumented test.
- **Exactly one permission changes:** `android.permission.INTERNET` in `AndroidManifest.xml`. Nothing else in the manifest changes.
- **The HID contract is frozen.** Do not touch: `COMBINED_HID_DESCRIPTOR`, `GAMEPAD_ONLY_DESCRIPTOR`, `GamepadState.encodeReport`, `encodeGamepadOnlyReport`, the `Btn` constants, `KeyUsage`, `MOD_*`, the mouse button masks, `TouchpadGestures` thresholds, `TouchpadGestureBridge` behaviour, `HidGamepadManager` registration/connection/heartbeat, `HidControllerService`, the re-pair rule, or `GamepadProfiles`.
- **The topic string must not appear in any tracked file** — not in source, not in tests, not in
  these documents. It lives in `local.properties` (gitignored) and is compiled into
  `BuildConfig`.
- The topic is the maintainer's own, set in `local.properties` as `ntfy.topic`; the ntfy base URL
  is `https://ntfy.sh`.
- Topic format is `^[A-Za-z0-9_-]{1,64}$`.
- The user must **not** be shown the diagnostics. There is **no** "include diagnostics" checkbox and **no** preview of the details.
- The report panel is opened from a full-width accent button directly under the HID master switch in the Settings sheet.
- Bluetooth MAC addresses and bonded-device names are **never** included in a report.
- Tests are JUnit 4 in `app/src/test/kotlin/com/hidgamepad/`. The existing suite must stay green — 143 tests before this work.
- Build command: `.\gradlew.bat --console=plain testDebugUnitTest assembleDebug`.

## Review Focus

Five input classes or failure modes the spec implies that the tasks' own tests must pin:

1. **A topic containing a path separator, a query character or a newline** must never reach the network — it is a path segment on a remote server. (`Task 1`)
2. **A blank description** must not be sent — the user gets "Describe the problem first", not an empty notification. (`Task 2`)
3. **A build with no topic at all** — a fresh clone — must still work: the app asks the user for a topic rather than dropping the report. (`Task 1`)
4. **A report longer than ntfy accepts** must be reported as such rather than failing opaquely. (`Task 1`)
5. **A device with no internet** must produce a readable message, not a crash or a silent no-op. (`Task 2`, and the real path in `Task 6`)

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/com/hidgamepad/ProblemReport.kt` *(new)* | Pure functions — topic validation, topic resolution, body composition, preflight, failure reasons — plus `ProblemReporter`, the HTTPS POST. No Compose, no Android context. |
| `app/src/main/kotlin/com/hidgamepad/DeviceScreenInfo.kt` *(new)* | Reads the platform for the device and screen facts, and formats them into the details block. The only file that touches `Activity`, `Configuration` or `WindowInsets`. |
| `app/src/main/kotlin/com/hidgamepad/ReportProblemPanel.kt` *(new)* | The Compose panel: text field, conditional topic field, Send button, result line. |
| `app/src/test/kotlin/com/hidgamepad/ProblemReportTest.kt` *(new)* | Unit tests for every pure function in `ProblemReport.kt`, and for the details formatter. |
| `app/src/main/kotlin/com/hidgamepad/GamepadScreen.kt` (modify) | `reportOpen` state, the "Report a problem" button at the top of the Settings sheet, and the panel. |
| `app/src/main/AndroidManifest.xml` (modify) | `INTERNET` permission. |
| `app/build.gradle.kts` (modify) | `buildConfig = true`; a `buildConfigField` carrying the topic from `local.properties`. |
| `local.properties` (create, gitignored) | `ntfy.topic=<the maintainer's ntfy topic>` |

---

## Task 1: The pure reporting logic

**Files:**
- Create: `app/src/main/kotlin/com/hidgamepad/ProblemReport.kt`
- Test: `app/src/test/kotlin/com/hidgamepad/ProblemReportTest.kt`

**Interfaces:**
- Consumes: nothing (first task).
- Produces, all in package `com.hidgamepad`:
  - `const val NTFY_BASE_URL = "https://ntfy.sh"`
  - `const val REPORT_SEPARATOR = "---"`
  - `const val REPORT_TITLE = "HID Controller — problem report"`
  - `fun ntfyTopicUrl(topic: String): String?`
  - `fun isValidTopic(topic: String): Boolean`
  - `fun resolveReportTopic(buildTopic: String, storedTopic: String): String?`
  - `fun buildReportBody(description: String, diagnostics: String?): String`
  - `fun reportFailureReason(code: Int): String?`
  - `fun reportPreflightFailure(topic: String, body: String): String?`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/hidgamepad/ProblemReportTest.kt`:

```kotlin
package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemReportTest {

    @Test
    fun `a topic becomes an ntfy url`() {
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
        assertNull(ntfyTopicUrl("topic\r"))
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
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.ProblemReportTest"`

Expected: FAIL — `Unresolved reference 'ntfyTopicUrl'` and the other new symbols.

- [ ] **Step 3: Implement the pure functions in `app/src/main/kotlin/com/hidgamepad/ProblemReport.kt`**

Create the file with exactly these top-level declarations.

Constants: `NTFY_BASE_URL`, `REPORT_SEPARATOR`, `REPORT_TITLE` as above.

`private val TOPIC_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")`.

`ntfyTopicUrl(topic)`: trim the topic; if it does not match `TOPIC_PATTERN` return `null`; otherwise return `"$NTFY_BASE_URL/$trimmed"`.

`isValidTopic(topic)`: `ntfyTopicUrl(topic) != null`.

`resolveReportTopic(buildTopic, storedTopic)`: return the trimmed `buildTopic` when it is not blank; otherwise the trimmed `storedTopic` when it is not blank; otherwise `null`.

`buildReportBody(description, diagnostics)`: the trimmed description; when the details are non-blank, `"$text\n\n$REPORT_SEPARATOR\n${details.trim()}"`.

`reportFailureReason(code)`: `null` for 200..299. Otherwise exactly these strings — 403 "The ntfy server rejected the topic. Check the topic name is correct.", 404 "The ntfy topic does not exist. Subscribe to it in the ntfy app first.", 413 "The report is too long. Shorten the description.", 429 "Too many reports sent in a short time. Wait a moment and try again.", -1 "No connection to the ntfy server. Check the phone's internet connection.", and for every other code "The ntfy server returned an error ($code). Try again later."

`reportPreflightFailure(topic, body)`: return "The ntfy topic name is not valid." when `ntfyTopicUrl(topic)` is null; return "Describe the problem first." when the body is blank; otherwise `null`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.ProblemReportTest"`

Expected: PASS — 23 tests, 0 failures.

- [ ] **Step 5: Run the whole suite to confirm nothing regressed**

Run: `.\gradlew.bat --console=plain testDebugUnitTest`

Expected: PASS — 143 pre-existing tests plus the 23 new ones, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/ProblemReport.kt app/src/test/kotlin/com/hidgamepad/ProblemReportTest.kt
git commit -m "feat(report): validate the ntfy topic and build the report body"
```

---

## Task 2: The HTTPS POST

**Files:**
- Modify: `app/src/main/kotlin/com/hidgamepad/ProblemReport.kt`

**Interfaces:**
- Consumes: `ntfyTopicUrl`, `reportFailureReason`, `reportPreflightFailure` from Task 1.
- Produces:
  - `sealed interface ReportOutcome { data object Sent : ReportOutcome; data class Failed(val reason: String) : ReportOutcome }`
  - `object ProblemReporter { suspend fun send(topic: String, title: String, body: String): ReportOutcome }`

- [ ] **Step 1: Implement `ReportOutcome` and `ProblemReporter` in `ProblemReport.kt`**

Add to the same file:

```kotlin
/** The outcome of a report attempt. */
sealed interface ReportOutcome {
    data object Sent : ReportOutcome
    data class Failed(val reason: String) : ReportOutcome
}
```

and an `object ProblemReporter` with `suspend fun send(topic: String, title: String, body: String): ReportOutcome` that:

- calls `reportPreflightFailure(topic, body)` first; when it returns a reason, returns `ReportOutcome.Failed(reason)` **without any network call**;
- otherwise opens `URL(url).openConnection() as HttpURLConnection` with `requestMethod = "POST"`, `doOutput = true`, `connectTimeout = 10_000`, `readTimeout = 10_000`, and request properties `Title` = title, `Priority` = `default`, `Tags` = `warning`, `Content-Type` = `text/plain; charset=utf-8`;
- writes the body bytes to `outputStream` and closes it;
- reads `responseCode` and maps it through `reportFailureReason`: `null` → `ReportOutcome.Sent`, otherwise `ReportOutcome.Failed(reason)`;
- catches `IOException` → `ReportOutcome.Failed(reportFailureReason(-1) ?: "Could not reach the ntfy server.")` and `SecurityException` → `ReportOutcome.Failed("The app is not allowed to use the internet.")`;
- runs on `Dispatchers.IO` via `withContext(Dispatchers.IO)`;
- disconnects the connection in a `finally` block.

- [ ] **Step 2: Run the tests to verify they still pass**

Run: `.\gradlew.bat --console=plain testDebugUnitTest`

Expected: PASS — 166 tests, 0 failures.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/ProblemReport.kt
git commit -m "feat(report): publish the report to the ntfy topic over https"
```

---

## Task 3: The build-time topic and the internet permission

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Create (gitignored): `local.properties`

**Interfaces:**
- Consumes: nothing.
- Produces: `BuildConfig.NTFY_TOPIC` — a `String`: the topic from `local.properties`, or `""` when the property is absent. Read as `com.hidgamepad.BuildConfig.NTFY_TOPIC`.

- [ ] **Step 1: Enable BuildConfig and add the field in `app/build.gradle.kts`**

Add at the top of the file, after the `plugins` block:

```kotlin
import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
```

Inside `buildFeatures { }`, add `buildConfig = true`.

Inside `defaultConfig { }`, add:

```kotlin
buildConfigField(
    "String",
    "NTFY_TOPIC",
    "\"${localProperties.getProperty("ntfy.topic", "").replace("\"", "")}\"",
)
```

The `.replace("\"", "")` keeps a stray quote in the value from breaking the generated Java string.

- [ ] **Step 2: Add the INTERNET permission to `app/src/main/AndroidManifest.xml`**

Add one line inside `<manifest>`, with the other runtime-requested permissions:

```xml
    <!-- Needed only to publish a problem report to ntfy; the controller itself is Bluetooth-only. -->
    <uses-permission android:name="android.permission.INTERNET" />
```

Change nothing else in the manifest.

- [ ] **Step 3: Put the topic in `local.properties`**

If `local.properties` exists, append this line to it; if it does not, create the file containing this line (Gradle will add the SDK path it needs):

```
ntfy.topic=<the maintainer's ntfy topic>
```

Confirm it is ignored:

Run: `git check-ignore -v local.properties`

Expected: a line naming the `.gitignore` rule that matched it.

- [ ] **Step 4: Build to confirm the field exists**

Run: `.\gradlew.bat --console=plain assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Confirm the topic is not in any tracked file**

Run: `git grep -nF "$(grep '^ntfy.topic=' local.properties | cut -d= -f2)" -- .`

Expected: no output. If it finds the string anywhere tracked, remove it from that file and repeat this step.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml
git commit -m "feat(report): carry the ntfy topic in BuildConfig and allow internet"
```

`local.properties` is **not** added — it is gitignored.

---

## Task 4: Collecting the device and screen facts

**Files:**
- Create: `app/src/main/kotlin/com/hidgamepad/DeviceScreenInfo.kt`
- Test: `app/src/test/kotlin/com/hidgamepad/ProblemReportTest.kt` (append)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class DeviceScreenInfo(val windowWidthDp: Int, val windowHeightDp: Int, val orientation: String, val displayWidthPx: Int, val displayHeightPx: Int, val densityDpi: Int, val densityScale: String, val smallestWidthDp: Int, val refreshRateHz: String, val hasCutout: Boolean, val cutoutTopPx: Int, val cornerRadiusPx: Int, val theme: String, val manufacturer: String, val model: String, val androidRelease: String, val sdkInt: Int, val appVersion: String)`
  - `fun collectDeviceScreenInfo(activity: Activity): DeviceScreenInfo`
  - `fun DeviceScreenInfo.toDetailsBlock(screenLabel: String, mode: ControllerMode, hostType: HostType, hidStatus: String, tiltOn: Boolean): String`

- [ ] **Step 1: Write the failing test for the details formatter**

Append to `ProblemReportTest.kt`:

```kotlin
    @Test
    fun `the details block names the screen the user was on`() {
        val info = DeviceScreenInfo(
            windowWidthDp = 857, windowHeightDp = 384, orientation = "landscape",
            displayWidthPx = 720, displayHeightPx = 1608, densityDpi = 300, densityScale = "1.875",
            smallestWidthDp = 360, refreshRateHz = "60", hasCutout = true, cutoutTopPx = 67,
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
    }

    @Test
    fun `a device without a cutout says so`() {
        val info = DeviceScreenInfo(
            windowWidthDp = 411, windowHeightDp = 731, orientation = "portrait",
            displayWidthPx = 1080, displayHeightPx = 2400, densityDpi = 420, densityScale = "2.625",
            smallestWidthDp = 393, refreshRateHz = "60", hasCutout = false, cutoutTopPx = 0,
            cornerRadiusPx = 0, theme = "light", manufacturer = "Google", model = "Pixel 8",
            androidRelease = "15", sdkInt = 35, appVersion = "1.1",
        )
        val block = info.toDetailsBlock("Keyboard", ControllerMode.KEYBOARD, HostType.ANDROID, "Off", true)
        assertTrue(block.contains("Cutout        no"))
        assertTrue(block.contains("Corners       none"))
        assertTrue(block.contains("light"))
        assertTrue(block.contains("Pixel 8"))
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.ProblemReportTest"`

Expected: FAIL — `Unresolved reference 'DeviceScreenInfo'`.

- [ ] **Step 3: Implement `DeviceScreenInfo.kt`**

Create the file with the data class exactly as declared above, `collectDeviceScreenInfo(activity)`, and `toDetailsBlock`.

`collectDeviceScreenInfo(activity)` reads:

- `activity.resources.configuration` → `screenWidthDp`, `screenHeightDp`, `smallestScreenWidthDp`; `orientation` is `"landscape"` for `Configuration.ORIENTATION_LANDSCAPE`, `"portrait"` for `Configuration.ORIENTATION_PORTRAIT`, `"other"` otherwise; `theme` is `"dark"` when `uiMode and Configuration.UI_MODE_NIGHT_MASK` equals `Configuration.UI_MODE_NIGHT_YES`, otherwise `"light"`.
- `activity.resources.displayMetrics` → `widthPixels`, `heightPixels`, `densityDpi`; `densityScale` is `density` formatted with `Locale.US` to one decimal place, for example `"1.875"`.
- `activity.display?.refreshRate` (API 30 and above) → the rate as an integer string; `"unknown"` when the display is null or the API is lower.
- The cutout from `activity.window.decorView.rootWindowInsets?.displayCutout` → `hasCutout` is `cutout != null && cutout.boundingRect.height() > 0`; `cutoutTopPx` is `cutout.safeInsetTop`.
- The corner radius from `activity.window.decorView.rootWindowInsets?.getRoundedCorner(0)?.radius` (API 31 and above, guarded by `Build.VERSION.SDK_INT`) → `cornerRadiusPx`, `0` when unavailable.
- `Build.MANUFACTURER`, `Build.MODEL`, `Build.VERSION.RELEASE`, `Build.VERSION.SDK_INT`.
- The app version from `activity.packageManager.getPackageInfo(activity.packageName, 0).versionName`, falling back to `"unknown"` if the lookup throws.

`toDetailsBlock(screenLabel, mode, hostType, hidStatus, tiltOn)` returns exactly this shape, with the values substituted:

```
Screen
  Window        <windowWidthDp> x <windowHeightDp> dp (<orientation>)
  Display       <displayWidthPx> x <displayHeightPx> px, <densityDpi> dpi (<densityScale>x)
  Smallest w    <smallestWidthDp> dp
  Refresh rate  <refreshRateHz> Hz
  Cutout        <yes — <cutoutTopPx> px top inset | no>
  Corners       <cornerRadiusPx> px radius | none>
  Theme         <theme>

Device
  <manufacturer> <model>
  Android <androidRelease> (SDK <sdkInt>)

App
  HID Controller <appVersion>
  Screen         <screenLabel>
  Mode           <Gamepad|Mouse|Keyboard>
  Host type      <Windows PC|Android device>
  HID            <hidStatus>
  Tilt           <on|off>
```

The mode name is `Gamepad`/`Mouse`/`Keyboard`; the host type name is `Windows PC`/`Android device`; the cutout is `no` when `hasCutout` is false; the corners are `none` when `cornerRadiusPx` is 0.

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.ProblemReportTest"`

Expected: PASS.

- [ ] **Step 5: Run the whole suite**

Run: `.\gradlew.bat --console=plain testDebugUnitTest`

Expected: PASS — 168 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/DeviceScreenInfo.kt app/src/test/kotlin/com/hidgamepad/ProblemReportTest.kt
git commit -m "feat(report): collect and format the device and screen details"
```

---

## Task 5: The report panel and the settings entry point

**Files:**
- Create: `app/src/main/kotlin/com/hidgamepad/ReportProblemPanel.kt`
- Modify: `app/src/main/kotlin/com/hidgamepad/GamepadScreen.kt`

**Interfaces:**
- Consumes: `ProblemReporter.send`, `ReportOutcome`, `buildReportBody`, `collectDeviceScreenInfo`, `DeviceScreenInfo.toDetailsBlock`, `resolveReportTopic`, `REPORT_TITLE`, `BuildConfig.NTFY_TOPIC`, `ControllerMode`, `HostType`.
- Produces:
  - `@Composable fun ReportProblemPanel(onClose: () -> Unit, controllerMode: ControllerMode, hostType: HostType, hidStatus: String, currentScreenLabel: String, knownTopic: String?, onTopicSaved: (String) -> Unit)`
  - In `GamepadScreen.kt`: `var reportOpen by remember { mutableStateOf(false) }`; a `Button` labelled `"Report a problem"` directly under the HID master-switch row in `SettingsPanel`, whose click sets `settingsOpen = false` and `reportOpen = true`; and a `if (reportOpen) { ReportProblemPanel(...) }` block that replaces the settings sheet.

**Note on testing:** this task adds no testable pure logic — the pieces it composes were tested in Tasks 1, 2 and 4 — and the project has no Compose UI-test infrastructure (adding it would mean a new dependency, which the global constraints forbid). The verification for this task is the compile in Step 3 plus the device check in Task 6.

- [ ] **Step 1: Create `ReportProblemPanel.kt`**

Create `app/src/main/kotlin/com/hidgamepad/ReportProblemPanel.kt` with the public composable and its private helpers.

The panel is a full-screen `Column` with `Modifier.fillMaxSize().background(Color(0xFF1A1B21)).padding(16.dp).verticalScroll(rememberScrollState())`, matching the Settings sheet's colours.

State held in the panel:

- `description: String` — the multi-line text field, `Modifier.fillMaxWidth().heightIn(min = 120.dp)`, a Material 3 `TextField` with the label `"What went wrong?"`.
- `topic: String` — initialised from `knownTopic` when there is one, otherwise empty.
- `sending: Boolean` — true while the POST is in flight.
- `result: String` — the result line, empty until a send completes.

`knownTopic` is the topic the app already knows (build-time or stored), or `null` when there is none. **The topic field is rendered only when `knownTopic` is `null`** — a user with a known topic never sees it.

The header is a `Row` with `Text("Report a problem", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE8EAF0))` and a `Button(onClick = onClose)` labelled `"Close"`, `Modifier.height(48.dp)` — the same shape as the Settings sheet header.

Below the header, in order:

1. The description `TextField`.
2. The topic `TextField`, only when `knownTopic` is `null`, labelled `"ntfy topic"`, prefilled from `topic`.
3. A `Button(onClick = { send() }, enabled = !sending && description.isNotBlank())` labelled `"Send report"`, `Modifier.fillMaxWidth().height(48.dp)`.
4. The result `Text`, `fontSize = 12.sp`, coloured `Color(0xFFE06A5A)` for a failure and `Color(0xFF9BB08D)` for a success; empty when there is no result.

There is **no** diagnostics preview and **no** "include details" checkbox.

The send action:

- resolves the topic as `knownTopic ?: topic.trim()`;
- builds the details with `collectDeviceScreenInfo(activity).toDetailsBlock(currentScreenLabel, controllerMode, hostType, hidStatus, tiltOn)` — the activity comes from a private helper that unwraps `ContextWrapper` to find the `Activity`; when there is no activity the details are `null` and only the user's text is sent;
- builds the body with `buildReportBody(description, details)`;
- calls `ProblemReporter.send(topic, REPORT_TITLE, body)` inside a `rememberCoroutineScope()`;
- on `ReportOutcome.Sent` sets the result to `"Sent — check your phone."` and calls `onTopicSaved(topic)`;
- on `ReportOutcome.Failed(reason)` sets the result to the reason.

- [ ] **Step 2: Wire it into `GamepadScreen.kt`**

In `GamepadScreen`:

- Add `var reportOpen by remember { mutableStateOf(false) }` next to `var settingsOpen by remember { mutableStateOf(false) }`.
- Change `if (settingsOpen) { SettingsPanel(...) }` so the sheet renders only when `!reportOpen`, and add an `if (reportOpen) { ReportProblemPanel(...) }` block.
- Add an `onReportProblem: () -> Unit` parameter to `SettingsPanel`, and inside it, directly after the `Row` that holds `Text("HID controller: ...")` and the `Switch`, add:

```kotlin
Button(onClick = onReportProblem, modifier = Modifier.fillMaxWidth().height(48.dp)) {
    Text("Report a problem")
}
```

- Pass to `ReportProblemPanel`: `onClose = { reportOpen = false }`; `controllerMode`; `hostType`; `hidStatus = statusText`; `currentScreenLabel = modeLabel(controllerMode)`; `knownTopic = resolveReportTopic(BuildConfig.NTFY_TOPIC, prefs.getString("report_topic", "") ?: "")`; `onTopicSaved = { topic -> prefs.edit().putString("report_topic", topic).apply() }`.

`BuildConfig` and `resolveReportTopic` are in the same package, so no import is needed for either.

- [ ] **Step 3: Compile**

Run: `.\gradlew.bat --console=plain :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL, with no new warnings.

- [ ] **Step 4: Run the whole suite**

Run: `.\gradlew.bat --console=plain testDebugUnitTest`

Expected: PASS — 168 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/ReportProblemPanel.kt app/src/main/kotlin/com/hidgamepad/GamepadScreen.kt
git commit -m "feat(report): add the report panel and the settings entry point"
```

---

## Task 6: Verify on the phone, then document

**Files:**
- Modify: `README.md`
- Modify: `docs/hid-gamepad-handoff-2026-11-12.md`
- Create: `docs/screenshots/16-report-panel.png`

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Produces: the documentation and the screenshot.

- [ ] **Step 1: Build and install**

Run: `.\gradlew.bat --console=plain assembleDebug`

Then: `adb -s <phone> install -r app\build\outputs\apk\debug\app-debug.apk`

Expected: `Success`.

- [ ] **Step 2: Open the panel and screenshot it**

Launch the app, open **Settings**, and confirm the **Report a problem** button sits directly under the HID master switch with no scrolling needed.

Capture the screen: `adb -s <phone> shell screencap -p /sdcard/s.png` then `adb -s <phone> pull /sdcard/s.png docs/screenshots/16-report-panel.png`

- [ ] **Step 3: Send a real report**

Type a short neutral description into the field and press **Send report**.

Confirm on the phone that the result line reads "Sent — check your phone." and not an error.

Then read the message back from the topic, using the topic from `local.properties` so it is
never typed into a tracked file:

`curl -s "https://ntfy.sh/$(grep '^ntfy.topic=' local.properties | cut -d= -f2)/json?poll=1&since=1m"`

Expected: JSON containing the typed description, then `---`, then the Screen / Device / App block, with the cutout line present on this device.

- [ ] **Step 4: Confirm the failure path**

Turn the phone's Wi-Fi and mobile data off, send another report, and confirm the result line reads the connection message rather than "Sent". Restore the phone's network afterwards.

- [ ] **Step 5: Confirm the topic is still not tracked**

Run: `git grep -nF "$(grep '^ntfy.topic=' local.properties | cut -d= -f2)" -- .`

Expected: no output. The screenshot is an image, so `git grep` will not match it; if the typed description appears in it, retake the screenshot with a neutral description.

- [ ] **Step 6: Update the README**

Add a short "Reporting a problem" section stating that the Settings sheet has a **Report a problem** button, that a report carries the device and screen details (app version, Android version, device model, screen size, density, cutout, refresh rate and the current screen) along with the user's description, that it is delivered as an ntfy push notification, and that **no Bluetooth addresses or paired-device names are included**.

- [ ] **Step 7: Update the handoff doc**

Add a section recording: the feature, the placement, the payload format, the topic's build-time origin (`local.properties` → `BuildConfig.NTFY_TOPIC`), the permission added (`INTERNET`), and the device verification result from Steps 3 and 4 — including the exact HTTP status observed. Note that the ntfy topic must be set in `local.properties` before building, and that without it the app asks the user for a topic.

- [ ] **Step 8: Commit**

```bash
git add README.md docs/hid-gamepad-handoff-2026-11-12.md
git commit -m "docs(report): document the report-a-problem feature and its verification"
```

The repository's `.gitignore` ignores `*.png`, so `docs/screenshots/16-report-panel.png` stays untracked unless it is forced with `git add -f`. Leave it untracked and say so in the commit message.
