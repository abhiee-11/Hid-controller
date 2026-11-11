# HID Profiles and Gamepad Conformance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the phone's gamepad actually work in games — by fixing the Android button bit
order and by adding a gamepad-only registration profile that Chrome and DirectInput hosts
recognise as a real gamepad.

**Architecture:** The app keeps one canonical button mask (Android positional order). Two
registration profiles each supply their own HID descriptor and their own report encoder, so
the canonical mask maps to a per-profile wire order at encode time. The profile is chosen at
pairing time and persisted, because hosts cache the descriptor at pairing.

**Tech Stack:** Kotlin, Android `BluetoothHidDevice`, Compose, JUnit 4 (no new dependencies).

**Spec:** `docs/superpowers/specs/2026-11-11-hid-profiles-and-gamepad-conformance-design.md`

## Global Constraints

- No new Gradle dependencies (JUnit 4 only). minSdk 28, compileSdk 37, JVM 21.
- No new Android permissions.
- No new third-party code. The composite descriptor stays byte-identical except for the
  button-order fix the spec pins.
- Commit locally only, never push.
- A profile change requires the user to re-pair; the UI must state this.
- Never change a test to make it pass. If a test contradicts the spec, fix the spec or
  escalate — do not weaken the assertion.

## Review Focus

The spec's measurements imply these failure modes, which the tasks' own tests must pin:

1. **A phone button press must reach Android as the right keycode.** MODE, L3 and R3 are
   currently permuted: MODE arrives as L3. A user pressing MODE in a game gets L3.
2. **Chrome must see a gamepad, not an empty list.** `navigator.getGamepads()` returning all
   nulls is the actual symptom. The gamepad-only profile must make it return a pad with 16
   buttons and 6 axes.
3. **The two profiles must not corrupt each other.** Switching profiles must not leave a
   stale button bit set, and the canonical mask must survive a round trip through either
   encoder.
4. **The hat switch must read centred when idle.** Writing 15 into a field whose declared
   Logical Maximum is 7 may make the d-pad read as permanently deflected on the tablet.
5. **Re-pair must actually happen.** The tablet holds the old cached descriptor; the app
   must tell the user this plainly rather than appearing to work.

---

### Task 1: Fix the canonical Android button bit order and the hat's centred value

**Files:**
- Modify: `app/src/main/kotlin/com/hidgamepad/GamepadState.kt` (the `Btn` object and
  `HAT_NEUTRAL`)
- Test: `app/src/test/kotlin/com/hidgamepad/GamepadStateTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: corrected `Btn.MODE = 12`, `Btn.L3 = 13`, `Btn.R3 = 14`, and `HAT_NEUTRAL = 8`.
  Every later task relies on these values being the canonical Android positional bits and
  the HID-conformant centred hat value.

- [ ] **Step 1: Write the failing tests**

Add to `GamepadStateTest.kt`:

```kotlin
@Test
fun `mode l3 and r3 use the android positional bits`() {
    // Android maps gamepad button N (1-based) to BTN_GAMEPAD + N - 1 = 303 + N, and the
    // tablet's Generic.kl maps 304->BUTTON_A .. 316->BUTTON_MODE .. 318->BUTTON_THUMBR.
    // So MODE is report bit 12, L3 bit 13, R3 bit 14.
    assertEquals(12, Btn.MODE)
    assertEquals(13, Btn.L3)
    assertEquals(14, Btn.R3)
}

@Test
fun `the button bits are all distinct`() {
    val used = listOf(
        Btn.A, Btn.B, Btn.X, Btn.Y, Btn.L1, Btn.R1,
        Btn.SELECT, Btn.START, Btn.MODE, Btn.L3, Btn.R3,
    )
    assertEquals(used.size, used.toSet().size)
}

@Test
fun `the hat centred value is the null state of the declared range`() {
    // The descriptor declares the hat Logical Max 7 with the Null State flag, so HID
    // defines the null value as Logical Max + 1 = 8. Writing 15 is out of range and can
    // make a host read the d-pad as permanently deflected.
    assertEquals(8, HAT_NEUTRAL)
    assertTrue(HAT_NEUTRAL > 7)
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.GamepadStateTest"`
Expected: FAIL — `Btn.MODE` is 13, not 12, and `HAT_NEUTRAL` is 15, not 8.

- [ ] **Step 3: Fix the constants in `GamepadState.kt`**

In the `Btn` object, change:

```kotlin
    const val MODE = 12
    const val L3 = 13
    const val R3 = 14
```

And change the hat's neutral value:

```kotlin
    const val HAT_NEUTRAL = 8
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.GamepadStateTest"`
Expected: PASS.

- [ ] **Step 5: Run the whole suite**

Run: `.\gradlew.bat --console=plain testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures. If any existing test asserted the old permutation
or the old hat value, that test encoded the bug — report it rather than editing it, and
check the spec's tables.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/GamepadState.kt app/src/test/kotlin/com/hidgamepad/GamepadStateTest.kt
git commit -m "fix(gamepad): correct MODE, L3, R3 bits and the hat's centred value"
```

---

### Task 2: Add the gamepad-only profile's descriptor and encoder

**Files:**
- Create: `app/src/main/kotlin/com/hidgamepad/GamepadProfiles.kt`
- Test: `app/src/test/kotlin/com/hidgamepad/GamepadProfileTest.kt`

**Interfaces:**
- Consumes: `Btn` constants from `GamepadState.kt` (canonical mask, Task 1), `GamepadState`
  and its `encodeReport()`.
- Produces:
  - `enum class HidProfile { COMPOSITE, GAMEPAD_ONLY }`
  - `const val GAMEPAD_ONLY_REPORT_SIZE = 13`
  - `val GAMEPAD_ONLY_DESCRIPTOR: ByteArray`
  - `fun encodeGamepadOnlyReport(state: GamepadState): ByteArray`
  - `fun hidProfileFromStorage(name: String?): HidProfile`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/hidgamepad/GamepadProfileTest.kt`:

```kotlin
package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadProfileTest {

    @Test
    fun `gamepad only report is thirteen bytes`() {
        assertEquals(13, encodeGamepadOnlyReport(GamepadState()).size)
    }

    @Test
    fun `gamepad only report puts the buttons in the w3c standard order`() {
        // W3C standard layout: 0=A 1=B 2=X 3=Y 4=L1 5=R1 8=SELECT 9=START 10=L3 11=R3 12=MODE
        val state = GamepadState(buttons = 1 shl Btn.X)
        val report = encodeGamepadOnlyReport(state)
        assertEquals(0b0000_0100, report[0].toInt() and 0xFF)
    }

    @Test
    fun `every canonical button reaches a distinct w3c wire bit`() {
        val canonical = listOf(
            Btn.A, Btn.B, Btn.X, Btn.Y, Btn.L1, Btn.R1,
            Btn.SELECT, Btn.START, Btn.L3, Btn.R3, Btn.MODE,
        )
        val wires = canonical.map { canonical -> w3cWireBit(canonical) }
        assertEquals(wires.size, wires.toSet().size)
        assertEquals(0, w3cWireBit(Btn.A))
        assertEquals(1, w3cWireBit(Btn.B))
        assertEquals(2, w3cWireBit(Btn.X))
        assertEquals(3, w3cWireBit(Btn.Y))
        assertEquals(4, w3cWireBit(Btn.L1))
        assertEquals(5, w3cWireBit(Btn.R1))
        assertEquals(8, w3cWireBit(Btn.SELECT))
        assertEquals(9, w3cWireBit(Btn.START))
        assertEquals(10, w3cWireBit(Btn.L3))
        assertEquals(11, w3cWireBit(Btn.R3))
        assertEquals(12, w3cWireBit(Btn.MODE))
    }

    @Test
    fun `sticks are signed sixteen bit and centred at zero`() {
        val report = encodeGamepadOnlyReport(GamepadState(lx = 0f, ly = 0f))
        assertEquals(0, report[2].toInt())
        assertEquals(0, report[3].toInt())
        assertEquals(0, report[4].toInt())
        assertEquals(0, report[5].toInt())
    }

    @Test
    fun `right stick is at axes two and three`() {
        val report = encodeGamepadOnlyReport(GamepadState(rx = 1f, ry = -1f))
        assertEquals(32767, axisWord(report, 6))
        assertEquals(-32767, axisWord(report, 8))
    }

    @Test
    fun `triggers are unsigned bytes at bytes ten and eleven`() {
        val report = encodeGamepadOnlyReport(GamepadState(lt = 1f, rt = 0f))
        assertEquals(255, report[10].toInt() and 0xFF)
        assertEquals(0, report[11].toInt() and 0xFF)
    }

    @Test
    fun `the descriptor declares a gamepad collection and no keyboard`() {
        val d = GAMEPAD_ONLY_DESCRIPTOR
        val text = d.joinToString(" ") { "%02X".format(it) }
        assertTrue("must declare Usage Page Generic Desktop", text.contains("05 01"))
        assertTrue("must declare Usage Gamepad", text.contains("09 05"))
        assertTrue("must not declare a keyboard collection", !text.contains("05 07"))
        assertTrue("must not declare a mouse collection", !text.contains("09 02"))
    }

    @Test
    fun `the descriptor axes are X Y Rx Ry then Z and Rz`() {
        val text = GAMEPAD_ONLY_DESCRIPTOR.joinToString(" ") { "%02X".format(it) }
        assertTrue(text.contains("09 30"))
        assertTrue(text.contains("09 31"))
        assertTrue(text.contains("09 33"))
        assertTrue(text.contains("09 34"))
        assertTrue(text.contains("09 32"))
        assertTrue(text.contains("09 35"))
    }

    @Test
    fun `profile round trips through storage`() {
        assertEquals(HidProfile.COMPOSITE, hidProfileFromStorage(null))
        assertEquals(HidProfile.COMPOSITE, hidProfileFromStorage("COMPOSITE"))
        assertEquals(HidProfile.GAMEPAD_ONLY, hidProfileFromStorage("GAMEPAD_ONLY"))
        assertEquals(HidProfile.GAMEPAD_ONLY, hidProfileFromStorage("nonsense"))
    }

    @Test
    fun `the canonical mask survives both encoders without stray bits`() {
        // The two profiles must not corrupt each other: a canonical button must land on
        // exactly one wire bit in each profile, and no unused canonical bit may leak.
        val canonical = Btn.X
        val composite = reportFor(HidProfile.COMPOSITE, GamepadState(buttons = 1 shl canonical))
        assertEquals(0x08, composite[0].toInt() and 0xFF)

        val only = reportFor(HidProfile.GAMEPAD_ONLY, GamepadState(buttons = 1 shl canonical))
        assertEquals(0x04, only[0].toInt() and 0xFF)
        assertEquals(0, only[1].toInt() and 0xFF)
    }

    @Test
    fun `an empty mask produces no button bits in either profile`() {
        for (profile in HidProfile.entries) {
            val report = reportFor(profile, GamepadState())
            assertEquals(0, report[0].toInt() and 0xFF)
            assertEquals(0, report[1].toInt() and 0xFF)
        }
    }
}

private fun axisWord(report: ByteArray, offset: Int): Int {
    val lo = report[offset].toInt() and 0xFF
    val hi = report[offset + 1].toInt()
    return (hi shl 8) or lo
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.GamepadProfileTest"`
Expected: FAILED to compile — "Unresolved reference 'GamepadProfileTest'" / missing symbols.

- [ ] **Step 3: Create `GamepadProfiles.kt`**

`app/src/main/kotlin/com/hidgamepad/GamepadProfiles.kt`:

```kotlin
package com.hidgamepad

/**
 * Which HID descriptor the phone registers. The host caches the descriptor at pairing
 * time, so a profile is fixed until the user re-pairs.
 */
enum class HidProfile {
    /** Keyboard + mouse + gamepad + consumer over one link. The Android/tablet profile. */
    COMPOSITE,

    /** A dedicated gamepad whose buttons and axes follow the W3C standard layout, so
     *  DirectInput and browser hosts expose a real gamepad. */
    GAMEPAD_ONLY,
}

const val GAMEPAD_ONLY_REPORT_SIZE = 13

fun hidProfileFromStorage(name: String?): HidProfile =
    runCatching { HidProfile.valueOf(name ?: "") }.getOrDefault(HidProfile.COMPOSITE)

/** Maps a canonical button bit to its W3C standard-layout wire bit. */
fun w3cWireBit(canonicalBit: Int): Int = when (canonicalBit) {
    Btn.A -> 0
    Btn.B -> 1
    Btn.X -> 2
    Btn.Y -> 3
    Btn.L1 -> 4
    Btn.R1 -> 5
    Btn.SELECT -> 8
    Btn.START -> 9
    Btn.L3 -> 10
    Btn.R3 -> 11
    Btn.MODE -> 12
    else -> -1
}
```

plus `encodeGamepadOnlyReport(state: GamepadState): ByteArray` that:

1. Builds a 16-bit wire mask by walking bits 0..15 of `state.buttons` and, for each set
   bit, looking up `w3cWireBit` and setting that wire bit.
2. Writes bytes 0–1 as the wire mask, low byte then high byte.
3. Writes LX, LY, RX, RY as signed 16-bit words at offsets 2, 4, 6, 8, scaled by 32767.
4. Writes LT and RT as unsigned bytes at offsets 10 and 11, scaled by 255.
5. Writes the hat byte at offset 12 as `state.hat and 0x0F`.

and `GAMEPAD_ONLY_DESCRIPTOR: ByteArray` — a single gamepad collection:

```
Usage Page (Generic Desktop), Usage (Gamepad), Collection (Application)
  Report ID 3
  Usage Page (Button), Usage Minimum 1, Usage Maximum 16, Report Count 16, Report Size 1,
    Logical Min 0, Logical Max 1, Input (Data, Variable, Absolute)
  Usage Page (Generic Desktop)
    Usage X, Usage Y, Usage Rx, Usage Ry
      Logical Min -32768, Logical Max 32767, Report Size 16, Report Count 1,
      Input (Data, Variable, Absolute)   x4
    Usage Z, Usage Rz
      Logical Min 0, Logical Max 255, Report Size 8, Report Count 1,
      Input (Data, Variable, Absolute)   x2
    Usage Hat switch
      Logical Min 0, Logical Max 7, Report Size 4, Report Count 1,
      Input (Data, Variable, Absolute, Null State)
      Report Size 4, Report Count 1, Input (Constant)   x1  (padding)
End Collection
```

Take the byte values from the existing composite descriptor in `GamepadState.kt` and adjust
the axis usages to `09 33` (Rx) and `09 34` (Ry) instead of `09 32` (Z) and `09 35` (Rz) for
the right stick, and put the two trigger axes on `09 32` (Z) and `09 35` (Rz).

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.GamepadProfileTest"`
Expected: PASS, 9 tests.

- [ ] **Step 5: Run the whole suite**

Run: `.\gradlew.bat --console=plain testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/GamepadProfiles.kt app/src/test/kotlin/com/hidgamepad/GamepadProfileTest.kt
git commit -m "feat(gamepad): add gamepad-only profile with a W3C standard layout"
```

---

### Task 3: Register the selected profile and report by profile

**Files:**
- Modify: `app/src/main/kotlin/com/hidgamepad/HidGamepadManager.kt`
- Test: `app/src/test/kotlin/com/hidgamepad/HidGamepadManagerTest.kt`

**Interfaces:**
- Consumes: `HidProfile`, `GAMEPAD_ONLY_DESCRIPTOR`, `encodeGamepadOnlyReport` (Task 2).
- Produces on `HidGamepadManager`:
  - `var hidProfile: HidProfile` — settable; changing it while registered triggers a
    re-register.
  - `fun descriptorFor(profile: HidProfile): ByteArray`
  - `fun reportFor(profile: HidProfile, state: GamepadState): ByteArray`
  - `fun updateGamepad(state: GamepadState)` now encodes by the active profile.

- [ ] **Step 1: Write the failing tests**

Add to `HidGamepadManagerTest.kt`:

```kotlin
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
```

These call **top-level pure functions** in `GamepadProfiles.kt`, not manager methods, so the
tests need no Android runtime.

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.HidGamepadManagerTest"`
Expected: FAIL — unresolved `descriptorFor` / `reportFor`.

- [ ] **Step 3: Implement the pure functions and wire them into the manager**

Add to `GamepadProfiles.kt`:

```kotlin
fun descriptorFor(profile: HidProfile): ByteArray = when (profile) {
    HidProfile.COMPOSITE -> COMBINED_HID_DESCRIPTOR
    HidProfile.GAMEPAD_ONLY -> GAMEPAD_ONLY_DESCRIPTOR
}

fun reportFor(profile: HidProfile, state: GamepadState): ByteArray = when (profile) {
    HidProfile.COMPOSITE -> state.encodeReport()
    HidProfile.GAMEPAD_ONLY -> encodeGamepadOnlyReport(state)
}
```

Then in `HidGamepadManager.kt`:

- Add a public `var hidProfile: HidProfile = HidProfile.COMPOSITE` whose setter re-registers
  the app when the value changes while registered.
- In `registerApp()`, pass `descriptorFor(hidProfile)` instead of `COMBINED_HID_DESCRIPTOR`.
- In `updateGamepad(state)`, encode with `reportFor(hidProfile, state)` instead of
  `state.encodeReport()`.
- In the heartbeat, re-encode the last state with `reportFor(hidProfile, ...)` — keep the
  existing "gamepad-only heartbeat, the mouse is never repeated" behaviour.

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.HidGamepadManagerTest"`
Expected: PASS.

- [ ] **Step 5: Run the whole suite**

Run: `.\gradlew.bat --console=plain testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/GamepadProfiles.kt app/src/main/kotlin/com/hidgamepad/HidGamepadManager.kt app/src/test/kotlin/com/hidgamepad/HidGamepadManagerTest.kt
git commit -m "feat(hid): register and report according to the selected profile"
```

---

### Task 4: Let the user choose the profile, and say that a re-pair is required

**Files:**
- Modify: `app/src/main/kotlin/com/hidgamepad/GamepadScreen.kt`
- Modify: `app/src/main/kotlin/com/hidgamepad/MainActivity.kt`
- Test: `app/src/test/kotlin/com/hidgamepad/GamepadProfileTest.kt` (storage round-trip only;
  this task's UI is not unit-testable without Compose test deps, which are forbidden)

**Interfaces:**
- Consumes: `HidProfile`, `hidProfileFromStorage` (Task 2), `HidGamepadManager.hidProfile`
  (Task 3).
- Produces: a `Settings` row that switches profile and persists it under the
  `hid_profile` preference key; the app re-registers on change.

- [ ] **Step 1: Write the failing test**

Extend `GamepadProfileTest.kt`:

```kotlin
@Test
fun `profile names are stable across versions`() {
    // The storage key is what a user's paired host was registered with; renaming an enum
    // entry would silently re-pair them onto the wrong descriptor.
    assertEquals("COMPOSITE", HidProfile.COMPOSITE.name)
    assertEquals("GAMEPAD_ONLY", HidProfile.GAMEPAD_ONLY.name)
}

@Test
fun `profile labels name the collections the profile carries`() {
    assertEquals("Keyboard + mouse + gamepad", profileLabel(HidProfile.COMPOSITE))
    assertEquals("Gamepad only", profileLabel(HidProfile.GAMEPAD_ONLY))
}
```

`profileLabel` is a top-level function in `GamepadScreen.kt`, so it is unit-testable
without Compose test dependencies.

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.GamepadProfileTest"`
Expected: FAIL — unresolved `profileLabel`.

- [ ] **Step 3: Add the Settings row in `GamepadScreen.kt`**

In `SettingsPanel`, below the "Mode" row, add:

- A label `Text("HID profile: ${profileLabel(profile)}", ...)`.
- A row of buttons, one per `HidProfile.entries`, calling `onProfile(type)`.
- A hint line: `Text("Changing the profile needs a re-pair: the host caches the HID descriptor at pairing time.", fontSize = 11.sp, color = Color(0xFF9AA1B2))`.

Add a **top-level** `profileLabel(profile: HidProfile): String` helper next to `modeLabel`
(outside the composable, so the unit test can reach it), returning
"Keyboard + mouse + gamepad" for `COMPOSITE` and "Gamepad only" for `GAMEPAD_ONLY`.

Add the `onProfile: (HidProfile) -> Unit` parameter to `SettingsPanel`.

- [ ] **Step 4: Wire the parameter in `GamepadScreen.kt`'s call site**

```kotlin
onProfile = { newProfile ->
    profile = newProfile
    prefs.edit().putString("hid_profile", newProfile.name).apply()
},
```

Read the initial value with
`var profile by remember { mutableStateOf(hidProfileFromStorage(prefs.getString("hid_profile", null))) }`
next to the existing `hostType` declaration.

- [ ] **Step 5: Push the profile into the manager in `MainActivity.kt`**

Where `GamepadScreen` is constructed, pass `onProfile` through to
`manager.hidProfile = it`. The manager re-registers when the value changes.

- [ ] **Step 6: Build and run the whole suite**

Run: `.\gradlew.bat --console=plain testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/GamepadScreen.kt app/src/main/kotlin/com/hidgamepad/MainActivity.kt app/src/test/kotlin/com/hidgamepad/GamepadProfileTest.kt
git commit -m "feat(settings): choose the HID profile and warn that a re-pair is needed"
```

---

### Task 5: Verify on the real hosts

**Files:**
- Modify: `docs/hid-gamepad-manual-test-checklist.md` (add the profile rows)
- Modify: `REPORT.md` (record what was actually measured)

**Interfaces:**
- Consumes: everything from Tasks 1–4.
- Produces: measured evidence on the Windows host (Chrome Gamepad API) and the Android
  tablet (`getevent` keycodes).

- [ ] **Step 1: Install the APK on the phone**

Run:
`adb -s <PHONE_SERIAL> install -r app\build\outputs\apk\debug\app-debug.apk`
Expected: `Success`.

- [ ] **Step 2: Measure Chrome with the COMPOSITE profile (the baseline)**

With the phone connected to the PC and Chrome running the probe page from
`C:\Users\<user>\AppData\Local\Temp\opencode\gamepad-probe.html`, evaluate over the DevTools
protocol (`http://127.0.0.1:9222/json`, `Runtime.evaluate`):

```
JSON.stringify(Array.from(navigator.getGamepads()).map(g => g ? {id: g.id, axes: g.axes.length, buttons: g.buttons.length} : null))
```

Expected: all null — this is the already-measured baseline that Task 2 exists to fix.

- [ ] **Step 3: Switch the profile to GAMEPAD_ONLY, re-pair the PC, and measure again**

Switch the profile in Settings on the phone, then remove the phone under Windows
Bluetooth settings and pair it again while the app is open and registered. Then re-run the
Step 2 expression.

Expected: one entry with a non-empty `id`, `axes` = 6 and `buttons` = 16.

**If Chrome still reports zero gamepads, stop and record that fact** — do not claim success
and do not start rewriting the descriptor speculatively. The next step is a Windows-side
DirectInput enumeration dump, not a guess.

- [ ] **Step 4: Measure the Android tablet**

Re-pair the tablet: remove the phone under the tablet's Bluetooth settings, make the phone
discoverable from the app, and pair again.

Then:

```
adb -s <TABLET_SERIAL> shell dumpsys input | grep -A 12 GAMEPAD
```

Expected: the phone appears as an external gamepad input device.

Then read the raw keycodes while pressing each app button on the phone:

```
adb -s <TABLET_SERIAL> shell getevent -lt /dev/input/event6
```

Expected for the MODE button: a key event with code 316 (`BUTTON_MODE`), not 317.

**If the tablet still enumerates nothing, record that fact and stop.**

- [ ] **Step 5: Update the checklist and REPORT with only what was observed**

Add profile rows to `docs/hid-gamepad-manual-test-checklist.md`:

| Profile | Action | Expected | Result |
|---|---|---|---|
| Gamepad only | Chrome `navigator.getGamepads()` | one pad, 16 buttons, 6 axes | |
| Gamepad only | Press MODE in a game | the game's MODE action fires | |
| Composite | Re-pair the tablet | the tablet lists the phone as a gamepad | |

In `REPORT.md`, add a 2026-11-11 section with the measured values and the evidence commands.
Record a step as NOT VERIFIED if it was not measured — never as a pass.

- [ ] **Step 6: Commit**

```bash
git add docs/hid-gamepad-manual-test-checklist.md REPORT.md
git commit -m "docs(gamepad): record the measured profile behaviour on both hosts"
```
