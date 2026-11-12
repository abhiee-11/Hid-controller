# Back navigation, host chooser and CapsLock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Back navigate instead of exiting, replace the first-run tutorial with a host-type chooser, and add a CapsLock key with a glowing indicator.

**Architecture:** Three independent changes. A pure `backAction()` function decides what Back does so the navigation rules are unit-testable. The tutorial is deleted and replaced by a small `HostTypeChooser` composable gated on whether `host_type` has ever been stored. CapsLock is a latched `KeyAction` that adds Left Shift to the report only while a letter usage is down, plus an `indicator` slot on `KeyboardKeyButton`.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2026.09.00), Material 3, `androidx.activity:activity-compose:1.13.0` (`BackHandler`), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-12-back-navigation-host-chooser-caps-lock-design.md`

## Global Constraints

- HID contract frozen: no change to descriptors, report encoders/sizes, `Btn` bits or keyboard usages.
- The ntfy topic must never appear in a tracked file; it lives in git-ignored `local.properties`.
- Never leak device serials or host Bluetooth names/MACs into tracked files.
- Landscape only; no hard-coded pixels in layout (dp and fractions only).
- Build and test: `.\gradlew.bat --console=plain testDebugUnitTest assembleDebug`.
- Baseline before this plan: 190 unit tests, 0 failures. `TutorialTest.kt` holds 9 of them and is deleted in Task 2, so the expected count after Task 2 is 181 plus whatever Task 1 adds.

## Review Focus

- **Back with the Devices dropdown open** - the Material3 `DropdownMenu` is a focusable popup and dismisses itself on Back before our handler runs. Expected: the dropdown closes and the app stays open. Device-verify.
- **Back with the mode dropdown open** - same as above. Device-verify.
- **Back on the host chooser** - no handler is registered there, so Android's default applies. Expected: the app exits. Device-verify.
- **Back on the Report panel** - expected: returns to Settings, does not exit. Covered by the `backAction` unit test.
- **CapsLock with another modifier already held** - e.g. Ctrl held, caps on, letter pressed. Expected: Ctrl survives and Shift is added. Covered by the `keyboardModifiersFor` unit test.
- **CapsLock with Shift already held by the user** - expected: still one Shift bit (the host gives lowercase, exactly like a real keyboard). Covered by the unit test.

---

### Task 1: CapsLock key

**Files:**
- Modify: `app/src/main/kotlin/com/hidgamepad/GamepadState.kt` (add `keyboardModifiersFor`)
- Modify: `app/src/main/kotlin/com/hidgamepad/KeyboardShortcuts.kt` (add `KeyAction.ToggleCapsLock`, add the Caps key)
- Modify: `app/src/main/kotlin/com/hidgamepad/Joystick.kt` (`KeyboardKeyButton` gains `indicator`)
- Modify: `app/src/main/kotlin/com/hidgamepad/GamepadScreen.kt` (`capsOn` state, `KeyboardStage` params, report modifiers)
- Test: `app/src/test/kotlin/com/hidgamepad/KeyboardMouseModeTest.kt`

**Interfaces:**
- Consumes: `KeyUsage.A` (0x04), `KeyUsage.Z` (0x1D), `MOD_LEFT_SHIFT` (0x02), all in `GamepadState.kt`.
- Produces:
  - `KeyAction.ToggleCapsLock` (a `data object`, not a `Modifier` or `Usage`).
  - `fun keyboardModifiersFor(capsOn: Boolean, modifiers: Int, pressedKeys: Set<Int>): Int` in `GamepadState.kt`.
  - `KeyboardKeyButton(..., indicator: Boolean = false)` in `Joystick.kt`.
  - `KeyboardStage(modifiers, pressedKeys, capsOn, hapticsEnabled, hapticsStrength, onModifier, onKey, onCapsChange)` in `GamepadScreen.kt`.

- [ ] **Step 1: Write the failing test**

In `app/src/test/kotlin/com/hidgamepad/KeyboardMouseModeTest.kt`, add:

```kotlin
@Test
fun `caps lock adds shift to letters only`() {
    assertEquals(
        MOD_LEFT_SHIFT,
        keyboardModifiersFor(capsOn = true, modifiers = 0, pressedKeys = setOf(KeyUsage.A)),
    )
    // Digits and punctuation are not shifted by a real CapsLock either.
    assertEquals(
        0,
        keyboardModifiersFor(capsOn = true, modifiers = 0, pressedKeys = setOf(KeyUsage.NUM_1)),
    )
    // Off means no shift, and a modifier the user is already holding survives.
    assertEquals(0, keyboardModifiersFor(capsOn = false, modifiers = 0, pressedKeys = setOf(KeyUsage.A)))
    assertEquals(
        MOD_LEFT_CTRL or MOD_LEFT_SHIFT,
        keyboardModifiersFor(capsOn = true, modifiers = MOD_LEFT_CTRL, pressedKeys = setOf(KeyUsage.Z)),
    )
    // Shift held by the user and caps on is still a single Shift bit.
    assertEquals(
        MOD_LEFT_SHIFT,
        keyboardModifiersFor(capsOn = true, modifiers = MOD_LEFT_SHIFT, pressedKeys = setOf(KeyUsage.A)),
    )
}

@Test
fun `caps lock key sits left of the win key`() {
    val bottom = keyboardRows().last()
    assertEquals("Caps", bottom[0].label)
    assertTrue(bottom[0].action is KeyAction.ToggleCapsLock)
    assertEquals("Win", bottom[1].label)
    assertTrue(bottom[1].action is KeyAction.Modifier)
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat --console=plain testDebugUnitTest`
Expected: FAIL - unresolved references `keyboardModifiersFor` and `KeyAction.ToggleCapsLock`.

- [ ] **Step 3: Add the toggle action and the key**

In `KeyboardShortcuts.kt`, add to the `sealed interface KeyAction`:

```kotlin
    /** A latch: tapping toggles it, it is not held. */
    data object ToggleCapsLock : KeyAction
```

In `keyboardRows()`, change the bottom row to:

```kotlin
    // Caps sits left of Win, next to Space; the arrow keys are narrow so Space gets most
    // of the row.
    listOf(
        KeyCap("Caps", KeyAction.ToggleCapsLock),
        cap("Win", KeyAction.Modifier(MOD_LEFT_GUI)),
        cap("Space", KeyUsage.SPACE, 2f),
        cap("\u2190", KeyUsage.LEFT, 0.5f), cap("\u2191", KeyUsage.UP, 0.5f),
        cap("\u2193", KeyUsage.DOWN, 0.5f), cap("\u2192", KeyUsage.RIGHT, 0.5f),
    ),
```

Write the arrow labels as `\u2190` etc. escapes so no non-ASCII byte enters the file (the build mangles raw UTF-8 in edited files).

- [ ] **Step 4: Add the pure modifier rule**

In `GamepadState.kt`, next to `KeyboardState`:

```kotlin
/**
 * The modifier byte for a keyboard report.
 *
 * CapsLock is a latch rather than a HID usage: sending usage 0x39 would make the host toggle
 * its own caps state, and then the app's indicator could drift from the host's real state.
 * Instead, while caps is on, letters are sent with Left Shift so they arrive uppercase.
 * Digits and punctuation are deliberately left alone - a real keyboard's CapsLock does not
 * shift them.
 */
fun keyboardModifiersFor(capsOn: Boolean, modifiers: Int, pressedKeys: Set<Int>): Int =
    if (capsOn && pressedKeys.any { it in KeyUsage.A..KeyUsage.Z }) modifiers or MOD_LEFT_SHIFT
    else modifiers
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `.\gradlew.bat --console=plain testDebugUnitTest`
Expected: PASS, and the two new tests are in the run.

- [ ] **Step 6: Add the glowing indicator to `KeyboardKeyButton`**

In `Joystick.kt`, add `indicator: Boolean = false` as the last parameter of `KeyboardKeyButton`, and inside the Canvas draw, after the border `drawRoundRect`:

```kotlin
            if (indicator && active) {
                val r = 2.5f.dp.toPx()
                val inset = r + 3f.dp.toPx()
                val dot = Offset(size.width - inset, inset)
                drawCircle(Color.White.copy(alpha = 0.22f), radius = r * 2.6f, center = dot)
                drawCircle(Color.White.copy(alpha = 0.45f), radius = r * 1.7f, center = dot)
                drawCircle(Color.White, radius = r, center = dot)
            }
```

`Color` is already imported in `Joystick.kt`.

- [ ] **Step 7: Wire the latch into `GamepadScreen`**

In `GamepadScreen`, next to `keyboardModifiers` / `keyboardKeys`:

```kotlin
    var capsOn by remember { mutableStateOf(false) }
```

Change the report construction from `val keyboardState = KeyboardState(keyboardModifiers, keyboardKeys)` to:

```kotlin
    val keyboardState = KeyboardState(
        keyboardModifiersFor(capsOn, keyboardModifiers, keyboardKeys),
        keyboardKeys,
    )
```

In the `ControllerMode.KEYBOARD` branch, pass the two new arguments to `KeyboardStage`:

```kotlin
                KeyboardStage(
                    modifiers = keyboardModifiers,
                    pressedKeys = keyboardKeys,
                    capsOn = capsOn,
                    hapticsEnabled = hapticsEnabled,
                    hapticsStrength = hapticsStrength,
                    onModifier = { bit, down ->
                        keyboardModifiers = if (down) keyboardModifiers or bit else keyboardModifiers and bit.inv()
                    },
                    onKey = { usage, down ->
                        keyboardKeys = if (down) keyboardKeys + usage else keyboardKeys - usage
                    },
                    onCapsChange = { capsOn = it },
                )
```

In `KeyboardStage`, add `capsOn: Boolean` after `pressedKeys` and `onCapsChange: (Boolean) -> Unit` after `onKey`, and replace the per-key `active` / `onDown` / `onUp` computation with:

```kotlin
                        val action = cap.action
                        val isCaps = action is KeyAction.ToggleCapsLock
                        val active = when (action) {
                            is KeyAction.Modifier -> (modifiers and action.bit) != 0
                            is KeyAction.ToggleCapsLock -> capsOn
                            is KeyAction.Usage -> action.usage in pressedKeys
                        }
                        KeyboardKeyButton(
                            label = cap.label,
                            active = active,
                            hapticsEnabled = hapticsEnabled,
                            hapticsStrength = hapticsStrength,
                            onDown = {
                                when (action) {
                                    is KeyAction.Modifier -> onModifier(action.bit, true)
                                    is KeyAction.ToggleCapsLock -> onCapsChange(!capsOn)
                                    is KeyAction.Usage -> onKey(action.usage, true)
                                }
                            },
                            onUp = {
                                when (action) {
                                    is KeyAction.Modifier -> onModifier(action.bit, false)
                                    is KeyAction.ToggleCapsLock -> Unit
                                    is KeyAction.Usage -> onKey(action.usage, false)
                                }
                            },
                            indicator = isCaps,
                            modifier = Modifier.weight(cap.weight).fillMaxHeight(),
                        )
```

- [ ] **Step 8: Build, test, and device-verify**

Run: `.\gradlew.bat --console=plain testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL, 192 tests, 0 failures.

Device-verify (the connected phone, app is landscape): Settings -> mode Keyboard. Tap **Caps** - the white dot appears. Tap **A** - the host receives uppercase A. Tap **1** - the host receives `1`, not `!`. Tap **Caps** again - the dot goes away.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/GamepadState.kt \
        app/src/main/kotlin/com/hidgamepad/KeyboardShortcuts.kt \
        app/src/main/kotlin/com/hidgamepad/Joystick.kt \
        app/src/main/kotlin/com/hidgamepad/GamepadScreen.kt \
        app/src/test/kotlin/com/hidgamepad/KeyboardMouseModeTest.kt
git commit -m "feat: add a CapsLock key with a glowing indicator"
```

---

### Task 2: Host chooser replaces the tutorial

**Files:**
- Delete: `app/src/main/kotlin/com/hidgamepad/Tutorial.kt`
- Delete: `app/src/main/kotlin/com/hidgamepad/TutorialPanel.kt`
- Delete: `app/src/test/kotlin/com/hidgamepad/TutorialTest.kt`
- Modify: `app/src/main/kotlin/com/hidgamepad/GamepadState.kt` (remove `needsOnboarding`)
- Create: `app/src/main/kotlin/com/hidgamepad/HostTypeChooser.kt`
- Modify: `app/src/main/kotlin/com/hidgamepad/GamepadScreen.kt`
- Modify: `app/src/test/kotlin/com/hidgamepad/KeyboardMouseModeTest.kt` (drop the two `needsOnboarding` assertions)
- Modify: `docs/PORTFOLIO.md` (the bullet that describes the first-run tutorial)

**Interfaces:**
- Consumes: `HostType`, `hostTitle(HostType)`, `profileForHostType(HostType)` - all existing.
- Produces:
  - `HostTypeChooser(hostType: HostType, onHostType: (HostType) -> Unit, modifier: Modifier = Modifier)` in `HostTypeChooser.kt`.
  - `GamepadScreen` no longer takes or uses `TutorialStep`, `needsSetup` or `onboardingStep`.

- [ ] **Step 1: Delete the tutorial**

```bash
git rm app/src/main/kotlin/com/hidgamepad/Tutorial.kt \
       app/src/main/kotlin/com/hidgamepad/TutorialPanel.kt \
       app/src/test/kotlin/com/hidgamepad/TutorialTest.kt
```

- [ ] **Step 2: Remove `needsOnboarding`**

In `GamepadState.kt`, delete the function `needsOnboarding` and its KDoc comment.

In `KeyboardMouseModeTest.kt`, inside `onboarding storage restores mode host and setup flag semantics`, delete the two lines that call `needsOnboarding` and the two-line comment above them. Rename the test to `storage restores mode and host semantics`.

- [ ] **Step 3: Create the chooser**

Create `app/src/main/kotlin/com/hidgamepad/HostTypeChooser.kt`:

```kotlin
package com.hidgamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The screen the app opens on until a host type has been chosen.
 *
 * This is the one question the tutorial used to spend five steps on: which kind of device
 * will be controlled. That choice also picks the HID descriptor the phone registers, so it
 * has to be answered before the controller is useful.
 *
 * Tapping an option chooses it and enters the app. Back on this screen exits, because there
 * is nothing behind it.
 */
@Composable
fun HostTypeChooser(
    hostType: HostType,
    onHostType: (HostType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(PadColors.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("HID Controller", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = PadColors.text)
        Spacer(Modifier.height(8.dp))
        Text("What will you control?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PadColors.text)
        Spacer(Modifier.height(6.dp))
        Text(
            "This also chooses the HID descriptor the phone registers. You can change it later in Settings.",
            fontSize = 13.sp,
            color = PadColors.secondary,
        )
        Spacer(Modifier.height(20.dp))
        HostOption(
            title = "Windows PC",
            detail = "PC games and desktop control.",
            selected = hostType == HostType.WINDOWS,
            onClick = { onHostType(HostType.WINDOWS) },
        )
        Spacer(Modifier.height(10.dp))
        HostOption(
            title = "Android phone or tablet",
            detail = "Games and app control on another Android device.",
            selected = hostType == HostType.ANDROID,
            onClick = { onHostType(HostType.ANDROID) },
        )
    }
}

@Composable
private fun HostOption(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) PadColors.accent.copy(alpha = 0.18f) else PadColors.surface)
            .border(1.dp, if (selected) PadColors.accent else PadColors.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = PadColors.text)
            Text(detail, fontSize = 12.sp, color = PadColors.secondary)
        }
        if (selected) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(PadColors.accent),
            )
        }
    }
}
```

- [ ] **Step 4: Wire the chooser into `GamepadScreen`**

Replace the whole `if (needsSetup) { TutorialPanel(...) return }` block (currently lines 152-194) with:

```kotlin
    var showHostChooser by remember {
        mutableStateOf(prefs.getString("host_type", null) == null)
    }
```

placed with the other `remember` states, and this block where the tutorial call was:

```kotlin
    if (showHostChooser) {
        HostTypeChooser(
            hostType = hostType,
            onHostType = { newType ->
                hostType = newType
                prefs.edit().putString("host_type", newType.name).apply()
                // The connection choice also picks the descriptor the host will pair with.
                onProfileChanged(profileForHostType(newType))
                showHostChooser = false
            },
        )
        return
    }
```

- [ ] **Step 5: Repoint Settings at the chooser**

In the `SettingsPanel(...)` call, replace the `onRunSetup` argument with:

```kotlin
                onShowHostChooser = {
                    settingsOpen = false
                    showHostChooser = true
                },
```

In `SettingsPanel`, rename the parameter `onRunSetup: () -> Unit` to `onShowHostChooser: () -> Unit` and change the button to `Button(onClick = onShowHostChooser, ...)`. Keep the label "Getting started".

- [ ] **Step 6: Update the portfolio bullet**

In `docs/PORTFOLIO.md`, replace the bullet that describes the five-step first-run tutorial with one that describes the host-type chooser.

- [ ] **Step 7: Build and test**

Run: `.\gradlew.bat --console=plain testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL, **183 tests**, 0 failures (190 - 9 deleted + 2 added in Task 1).

- [ ] **Step 8: Device-verify the chooser**

Install and launch. Expected: the chooser appears first (the phone already has `host_type` stored, so first clear it with `adb shell pm clear com.hidgamepad` to see it). Tap **Windows PC** - the gamepad screen appears. Settings -> Getting started returns to the chooser. The tutorial's five steps are gone; no pairing page exists.

- [ ] **Step 9: Commit**

```bash
git add -A -- app docs/PORTFOLIO.md
git commit -m "feat: replace the tutorial with a host-type chooser"
```

---

### Task 3: Back navigation

**Files:**
- Create: `app/src/main/kotlin/com/hidgamepad/BackNavigation.kt`
- Modify: `app/src/main/kotlin/com/hidgamepad/GamepadScreen.kt`
- Test: `app/src/test/kotlin/com/hidgamepad/BackNavigationTest.kt`

**Interfaces:**
- Consumes: `showHostChooser` from Task 2.
- Produces:
  - `sealed interface BackAction` with `CloseReport`, `CloseSettings`, `CloseDevices`, `ShowHostChooser` (all `data object`).
  - `fun backAction(reportOpen: Boolean, settingsOpen: Boolean, devicesOpen: Boolean): BackAction`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/hidgamepad/BackNavigationTest.kt`:

```kotlin
package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Test

class BackNavigationTest {

    @Test
    fun `back closes the panel that was opened last`() {
        assertEquals(
            BackAction.CloseReport,
            backAction(reportOpen = true, settingsOpen = true, devicesOpen = true),
        )
        assertEquals(
            BackAction.CloseSettings,
            backAction(reportOpen = false, settingsOpen = true, devicesOpen = true),
        )
        assertEquals(
            BackAction.CloseDevices,
            backAction(reportOpen = false, settingsOpen = false, devicesOpen = true),
        )
        assertEquals(
            BackAction.ShowHostChooser,
            backAction(reportOpen = false, settingsOpen = false, devicesOpen = false),
        )
    }

    @Test
    fun `report always beats settings and devices`() {
        // The report is filed from Settings, so it is always the newest panel.
        assertEquals(
            BackAction.CloseReport,
            backAction(reportOpen = true, settingsOpen = false, devicesOpen = false),
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat --console=plain testDebugUnitTest`
Expected: FAIL - unresolved reference `backAction`.

- [ ] **Step 3: Implement the decision**

Create `app/src/main/kotlin/com/hidgamepad/BackNavigation.kt`:

```kotlin
package com.hidgamepad

/** What the system Back button should do, given which panels are currently open. */
sealed interface BackAction {
    /** Close the report; it was filed from Settings, so that is where Back returns. */
    data object CloseReport : BackAction

    data object CloseSettings : BackAction

    data object CloseDevices : BackAction

    /** Nothing is open: walk out to the host-type chooser. */
    data object ShowHostChooser : BackAction
}

/**
 * Decide what Back does.
 *
 * The newest panel closes first: the report sits on top of Settings, and Settings sits on top
 * of the controller. Only when nothing is open does Back leave the controller for the chooser.
 * The chooser registers no handler of its own, so Back there exits the app.
 */
fun backAction(
    reportOpen: Boolean,
    settingsOpen: Boolean,
    devicesOpen: Boolean,
): BackAction = when {
    reportOpen -> BackAction.CloseReport
    settingsOpen -> BackAction.CloseSettings
    devicesOpen -> BackAction.CloseDevices
    else -> BackAction.ShowHostChooser
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\gradlew.bat --console=plain testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Register the handler in `GamepadScreen`**

Add the import `androidx.activity.compose.BackHandler` to `GamepadScreen.kt`.

Register the handler after the `devicesOpen` and `deviceList` declarations (they are declared after the tutorial block that used to `return` early, so the handler cannot go above them - Kotlin locals cannot be referenced before they are declared):

```kotlin
    // Back must never close the app from a controller screen: it closes the panel that is
    // open, and with nothing open it returns to the host chooser. On the chooser itself no
    // handler is registered, so Back there exits normally.
    BackHandler(enabled = !showHostChooser) {
        when (backAction(reportOpen, settingsOpen, devicesOpen)) {
            BackAction.CloseReport -> {
                reportOpen = false
                settingsOpen = true
            }
            BackAction.CloseSettings -> settingsOpen = false
            BackAction.CloseDevices -> devicesOpen = false
            BackAction.ShowHostChooser -> showHostChooser = true
        }
    }
```

- [ ] **Step 6: Build and test**

Run: `.\gradlew.bat --console=plain testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL, **185 tests**, 0 failures.

- [ ] **Step 7: Device-verify the back order**

Install and launch, then walk this sequence and check the screen after each press:

1. Controller visible -> Back -> host chooser. (Back on the chooser -> app exits.)
2. Enter the controller -> Settings -> Back -> controller.
3. Controller -> Settings -> Report a problem -> Back -> Settings (not the controller).
4. Controller -> Devices -> Back -> the dropdown closes, the controller stays.
5. Controller -> mode dropdown -> Back -> the dropdown closes, the controller stays.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/BackNavigation.kt \
        app/src/main/kotlin/com/hidgamepad/GamepadScreen.kt \
        app/src/test/kotlin/com/hidgamepad/BackNavigationTest.kt
git commit -m "fix: make Back navigate instead of closing the app"
```
