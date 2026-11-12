# Report a problem — design

Date: 2026-11-12
Status: awaiting approval
Size: small change

## Intent

Let the user send a problem report from inside the app and have it arrive as an ntfy push
notification, together with the device and screen details needed to diagnose a layout or
HID problem. The user should not have to think about the technical details at all: they type
what went wrong and press **Send**.

## Why

The app's failure modes are mostly visual — a control hidden under a display cutout, a trigger
that reads as dead on one host, a keyboard layout that is wrong on a specific tablet. A text
message alone ("the gamepad does not work") is not enough to act on. The device and screen
facts — window size in dp, display density, whether there is a cutout, the current screen — are
what turn it into something fixable.

## Goals

- A "Report a problem" entry point in the app that reaches the maintainer's phone.
- The user's own words, plus device and screen details, in one notification.
- The user fills in a text field and presses one button. Nothing else to understand or set.

## Non-goals

- No crash reporting, no automatic uploads. Reports are sent only when the user asks.
- No analytics, no telemetry, no background reporting of any kind.
- No changes to the HID layer, Bluetooth behaviour, or the permission set beyond `INTERNET`.

## Anti-goals

- **The user must not have to configure anything to send a report.** A topic field that is
  always visible and always empty is a failure, even if it is only one field.
- **The technical details must not become the user's problem.** They are attached; they are not
  shown, explained, or asked about.

## Constraints

- The repository is public. A topic hard-coded in the source would let anyone who reads the
  repository read the reports and publish fake notifications to the maintainer's phone.
- The app currently has no `INTERNET` permission and no HTTP code.
- The app has one user-facing navigation pattern today: inline full-screen panels
  (`SetupPanel`, `SettingsPanel`) swapped in by a boolean. The report panel follows it rather
  than introducing a navigation framework.

## Approaches considered

### Where the topic lives

| | How | Trade-off |
|---|---|---|
| **A. Typed into the app** | A topic field on the report screen, saved to prefs | One-time setup; nothing to build. But the field is always in the user's way, which contradicts the anti-goal. |
| **B. Baked into the source** | `const val TOPIC = "<the topic>"` | Zero friction, but the topic is published in a public repository. |
| **C. Build-time property** | Topic read from `local.properties` (already gitignored) and compiled into the APK | **Chosen.** Zero friction *and* the repository stays clean. A fresh clone simply has no topic and the app asks for one. |

### How the details are attached

| | How | Trade-off |
|---|---|---|
| **A. Shown, with a checkbox** | The user reviews and opts into the details | Transparent, but it puts the technical content in front of the user on every report. |
| **B. Always attached, never shown** | The details go with every report automatically | **Chosen.** The user types a problem and presses Send; nothing else. |
| **C. Always attached, one line disclosed** | As B, plus a static "device details are attached automatically" | Slightly more transparent. |

Chosen: **B**, on the grounds that this app has a single user — the maintainer — so there is
no consent gap to close inside the app. The one-line disclosure (C) is worth having in the
README instead, where it belongs: a note that reports carry device and screen details.

## The design

### Flow

```
Settings → "Report a problem" (top of the sheet)
   └─ panel opens (inline, like the setup wizard)
        ├─ [TextField]  what went wrong
        ├─ [TextField]  ntfy topic     ← only shown when no topic is known
        └─ [Send report]
              └─ POST https://ntfy.sh/<topic>
                    Title:  HID Controller — problem report
                    Tags:   warning
                    Body:   <the user's text>
                            ───
                            <device and screen details>
              └─ inline result: "Sent", or the actual reason it failed
```

### Entry point

**Placement: near the top of the Settings sheet, directly under the HID master switch.**

```
Settings                            [Close]
HID controller: ON/OFF         [Switch]
[ Report a problem ]            ← full-width accent button
Off releases every input, unregisters the HID device…
Connection
Connected host: …
```

A report is filed when something is not working, and the person filing it is most likely
already in Settings looking for the answer. Putting the button directly under the master
switch — the first control they can see — makes it reachable without scrolling past haptics,
tilt, sensitivities and the help text. It is deliberately *not* at the end of the sheet with
"Information", which is where a support action conventionally sits but is far down a long
scroll.

It is a visible accent button rather than another line of text: the sheet is a wall of
`Text` calls, and this is the one row on it that is an action.

Pressing it closes the Settings sheet and opens the report panel.

### Not in this change

The Settings sheet's "Close" button scrolls off the top of the list, which leaves a user at
the bottom of the sheet with no visible way out except the system back button (which exits
the app). That is pre-existing behaviour and unrelated to reporting, so it is left alone
rather than folded into this change; it is worth a separate, contained fix.

### The panel

- Title "Report a problem" with a **Close** button, matching the setup wizard's header.
- One multi-line text field: "What went wrong?".
- The ntfy topic field **only if** there is no known topic. Known means: the build-time topic
  is set, or a previous report stored one. A topic typed here is saved.
- A **Send report** button.
- A result line underneath: "Sent — check your phone", or the real reason it failed.
- **No diagnostics preview and no checkbox.** The details are attached and are not shown.

### Payload

The body is the user's text, then a separator, then the details:

```
The gamepad buttons are hidden under my notch

---
Screen
  Window        857 x 384 dp (landscape)
  Display       720 x 1608 px, 300 dpi (1.875x)
  Smallest w    360 dp
  Refresh rate  60 Hz
  Cutout        yes — 67 px top inset
  Corners       47 px radius
  Theme         dark

Device
  vivo V2434
  Android 16 (SDK 36)

App
  HID Controller 1.1
  Screen         Gamepad
  Mode           Gamepad
  Host type      Windows PC
  HID            Registered
  Tilt           off
```

Collected from:

| Fact | Source |
|---|---|
| Window size in dp, orientation, theme | `Configuration` |
| Display px, dpi, density scale | `activity.resources.displayMetrics` |
| Smallest width dp | `Configuration.smallestScreenWidthDp` |
| Refresh rate | `activity.display.refreshRate` |
| Display cutout | `rootWindowInsets.displayCutout` |
| Rounded corner radius | `rootWindowInsets.getRoundedCorner()` (API 31+) |
| Device | `Build.MANUFACTURER`, `Build.MODEL`, `Build.VERSION.RELEASE`, `Build.VERSION.SDK_INT` |
| App version | `packageManager.getPackageInfo().versionName` |
| Current screen, mode, host type, HID status, tilt | already in scope on the screen |

Deliberately excluded: Bluetooth MAC addresses and bonded-device names. They identify other
people's devices and have no business on a public server.

### Transport

A single HTTPS `POST` with `HttpURLConnection`, on `Dispatchers.IO`. No new dependency — the
app has no HTTP code today and one POST does not justify adding a library. HTTPS only; the
app already forbids cleartext by default.

Request:

```
POST https://ntfy.sh/<topic>
Title: HID Controller — problem report
Tags: warning
Content-Type: text/plain; charset=utf-8

<body>
```

### Failure handling

Failures are reported in plain language, because the person who pressed the button is not
debugging an HTTP client:

| Status | Message |
|---|---|
| 2xx | Sent |
| 403 | The ntfy server rejected the topic. Check the topic name is correct. |
| 404 | The ntfy topic does not exist. Subscribe to it in the ntfy app first. |
| 413 | The report is too long. Shorten the description. |
| 429 | Too many reports sent in a short time. Wait a moment and try again. |
| connection error | No connection to the ntfy server. Check the phone's internet connection. |

### Topic validation

A topic is a path segment on a remote server, so it is validated against
`^[A-Za-z0-9_-]{1,64}$` before anything reaches the network. Empty, whitespace, a slash, a
question mark and an embedded newline are all rejected.

### Files

| File | Change |
|---|---|
| `app/src/main/AndroidManifest.xml` | `+ INTERNET` permission. Nothing else. |
| `app/src/main/kotlin/com/hidgamepad/ProblemReport.kt` *(new)* | Topic validation, body and diagnostics formatting, failure reasons, the POST. |
| `app/src/main/kotlin/com/hidgamepad/DeviceScreenInfo.kt` *(new)* | Collects the device and screen facts. |
| `app/src/main/kotlin/com/hidgamepad/GamepadScreen.kt` | `reportOpen` state, the report panel, and the "Report a problem" button at the top of the Settings sheet. |
| `app/src/test/kotlin/com/hidgamepad/ProblemReportTest.kt` *(new)* | Unit tests for the pure logic. |
| `app/build.gradle.kts` | `buildConfig = true`; a `buildConfigField` carrying the topic from `local.properties`. |
| `local.properties` *(not in the repository)* | `ntfy.topic=<the maintainer's ntfy topic>` on the maintainer's machine only. |

### Tests

JUnit 4, matching the existing suite (no new test framework):

- topic → URL, and rejection of empty, whitespace, path-traversal, query and newline topics;
- body composition with and without diagnostics;
- diagnostics formatting;
- every HTTP status → message.

The topic tests matter most: they are the difference between "sends a message" and "lets a
stranger inject a request line".

## What does not change

Descriptors, report encoders, report sizes, `Btn` bit positions, keyboard usages, mouse
button masks, host gesture mappings, the gesture thresholds, the bridge's held-button
ownership, the foreground service and its notification, connection management, the heartbeat,
the re-pair rule, and the permission set apart from `INTERNET`. The feature reads state and
writes one HTTP request; it touches none of the HID contract.

## Verification

1. `.\gradlew.bat --console=plain testDebugUnitTest assembleDebug` — the whole suite, plus the
   new tests.
2. Install on the phone, open Settings, and confirm the "Report a problem" button sits under
   the HID switch without scrolling.
3. **Send a real report** and confirm the HTTP status, that the body arrived shaped as
   intended, and that the notification lands.
4. Screenshot the panel.
5. Confirm `git status` shows no topic string anywhere in the tracked files.

## Left to the builder

- The exact wording of the strings.
- Whether the panel shows a topic field before a topic is known (the design says: only when
  no topic is known).
