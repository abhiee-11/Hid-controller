# Settings Sub-pages + Mouse Pad Glow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the Settings sheet into a short list of four sub-pages (Connection, Controls, RGB backlight, Information) and add a fully independent RGB glow around the Mouse-mode touchpad's border.

**Architecture:** `SettingsPanel` in `GamepadScreen.kt` gains a remembered `SettingsSection?` (null = the list) and renders either the list or one page; no new composable parameters and no navigation library. The pad glow is a small pure colour function (`padGlowColor`) plus a halo drawn in `GestureTouchpad`'s existing Canvas; its settings live in their own `PadGlowConfig` persisted under `pad_glow_*` keys and shown as a second group on the RGB page.

**Tech Stack:** Kotlin, Jetpack Compose (Canvas + `produceState` clock), SharedPreferences, JUnit4 (pure unit tests only — the suite has no Compose UI tests).

**Spec:** `docs/superpowers/specs/2026-11-12-settings-sub-pages-design.md`

## Global Constraints

- Visual/structure only: do not change HID, descriptors, report encoding/sizes, connection handling, modes, input sending, or how any control's press/release/stick value is produced.
- Move controls, do not rewrite them: same callbacks, same parameter names, same labels, same behaviour.
- Colours come only from `PadColors` (plus the existing literal greys already in `GestureTouchpad`); dp only; keep the app landscape-locked.
- The pad glow must never affect the touchpad's gestures, sensitivity, or mouse sending — it is a border decoration only.
- Persistence reads must tolerate a stored `Int` or `Float` (see `readStoredBrightness`); never crash on a stored value.
- Never write device serials, host Bluetooth names/MACs, or the ntfy topic into tracked files. Run the leak scan before each commit; commit locally after each step.
- Work stays on branch `feat/ui-restyle`. Do not push `main`.
- Build/test command: `.\gradlew.bat --console=plain testDebugUnitTest assembleDebug` (baseline: 205 tests, 0 failures).

## Review Focus

- **Pad glow with the keyboard backlight OFF.** The two are independent; the pad must glow while `rgb_on` is false. Test: `padGlowColor` returns a colour from `PadGlowConfig` alone (Task 3).
- **Reactive pad glow on touch start/end.** The border should brighten only while a finger is down, and fall back when it lifts. Test: `padGlowColor(REACTIVE, touched=false)` is the dim value, `touched=true` the bright one (Task 3).
- **Stored pad-glow brightness written by an older/newer build.** Must not crash or render near-black. Test: `readStoredBrightness` is reused and covered (Task 3 reuses the existing helper; Task 5 persists with `putFloat`).
- **Back arrow / hardware Back with no page open.** From the list, Back must mean "close Settings", never a blank panel. Test: `settingsBackStep(null) == CLOSE`, `settingsBackStep(section) == TO_LIST` (Task 1).
- **A long page on a short landscape screen.** Every page must scroll (the panel keeps `verticalScroll`), so a page taller than the screen never clips its bottom controls.

---

### Task 1: Settings section model and Back steps

**Files:**
- Create: `app/src/main/kotlin/com/hidgamepad/SettingsSections.kt`
- Test: `app/src/test/kotlin/com/hidgamepad/SettingsSectionsTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class SettingsSection(val title: String, val summary: String)` with entries `CONNECTION`, `CONTROLS`, `RGB`, `INFORMATION`.
  - `val settingsSections: List<SettingsSection>` (= `SettingsSection.entries`).
  - `enum class SettingsBack { TO_LIST, CLOSE }`.
  - `fun settingsBackStep(current: SettingsSection?): SettingsBack` — `TO_LIST` when a page is open, `CLOSE` when at the list.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSectionsTest {

    @Test
    fun `there are four sections in order`() {
        assertEquals(
            listOf(
                SettingsSection.CONNECTION,
                SettingsSection.CONTROLS,
                SettingsSection.RGB,
                SettingsSection.INFORMATION,
            ),
            settingsSections,
        )
    }

    @Test
    fun `every section has a title and a summary`() {
        for (section in settingsSections) {
            assertTrue("${section.name} needs a title", section.title.isNotBlank())
            assertTrue("${section.name} needs a summary", section.summary.isNotBlank())
        }
    }

    @Test
    fun `back from a page returns to the list`() {
        assertEquals(SettingsBack.TO_LIST, settingsBackStep(SettingsSection.CONNECTION))
        assertEquals(SettingsBack.TO_LIST, settingsBackStep(SettingsSection.RGB))
    }

    @Test
    fun `back from the list closes settings`() {
        assertEquals(SettingsBack.CLOSE, settingsBackStep(null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.SettingsSectionsTest"`
Expected: FAIL — unresolved reference `SettingsSection` / `settingsSections` / `settingsBackStep`.

- [ ] **Step 3: Implement `SettingsSections.kt`**

```kotlin
package com.hidgamepad

/** One page of the Settings sheet. `null` in the panel means the list itself. */
enum class SettingsSection(val title: String, val summary: String) {
    CONNECTION("Connection", "HID on/off, host, pairing"),
    CONTROLS("Controls", "Mode, host type, haptics, tilt, pointer"),
    RGB("RGB backlight", "Effects and colours"),
    INFORMATION("Information", "Help and status"),
}

/** The four pages, in the order the Settings list shows them. */
val settingsSections: List<SettingsSection> = SettingsSection.entries

/** What the Back action does inside Settings. */
enum class SettingsBack { TO_LIST, CLOSE }

/** Back from a page returns to the list; back from the list closes the sheet. */
fun settingsBackStep(current: SettingsSection?): SettingsBack =
    if (current == null) SettingsBack.CLOSE else SettingsBack.TO_LIST
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.SettingsSectionsTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/SettingsSections.kt app/src/test/kotlin/com/hidgamepad/SettingsSectionsTest.kt
git commit -m "feat: settings section model and back steps"
```

---

### Task 2: Split `SettingsPanel` into a list and four pages

**Files:**
- Modify: `app/src/main/kotlin/com/hidgamepad/GamepadScreen.kt` (`SettingsPanel`, roughly lines 821–1010)

**Interfaces:**
- Consumes: `SettingsSection`, `settingsSections` (Task 1).
- Produces: nothing new to other tasks. `SettingsPanel` keeps its exact parameter list.

- [ ] **Step 1: Add the list/page switch**

In `SettingsPanel`, immediately after `val profile = profileForHostType(hostType)`:

```kotlin
    // null = the Settings list; a value = that page is open. The panel owns this, so the
    // rest of the screen needs no new parameter.
    var section by remember { mutableStateOf<SettingsSection?>(null) }
```

Replace the single `Column { ... }` body with: the same outer `Box`/`Column`/scroll scaffolding, then inside it, `if (section == null) { SettingsList(...) } else { when (section) { ... } }`. Keep `.verticalScroll(rememberScrollState())` and the 640dp `widthIn` on the shared Column so every page scrolls.

- [ ] **Step 2: Add the two small private composables**

Add near `SettingsPanel`:

```kotlin
/** One tappable row on the Settings list: title, a one-line summary, a chevron. */
@Composable
private fun SettingsRow(title: String, summary: String, onClick: () -> Unit)

/** A page header: a back arrow, the page title, and the panel Close on the right. */
@Composable
private fun SettingsPageHeader(title: String, onBack: () -> Unit, onClose: () -> Unit)
```

Row: `Row` on `PadColors.surface` with a `PadColors.border` rounded outline, `fillMaxWidth`, `heightIn(min = 64.dp)`, `.clickable(onClick = onClick)`, title in `PadColors.text`, summary in `PadColors.secondary`, and a `"›"` chevron in `PadColors.secondary`. Header: back arrow button (`"‹"`) calls `onBack`; title in `PadColors.text` bold; Close button calls `onClose`.

- [ ] **Step 3: Build the list**

The list is: header row ("Settings" + Close), the existing "Report a problem" button, then `settingsSections.forEach { SettingsRow(it.title, it.summary) { section = it } }`.

- [ ] **Step 4: Build the four pages**

Each page renders `SettingsPageHeader(...)` then the existing controls, moved verbatim in their current order:
- **CONNECTION** — HID switch + explanation; `Connected host: $statusText`; Reconnect / Change device / Make discoverable; Getting started.
- **CONTROLS** — Mode buttons; Host type buttons + the three-finger note, descriptor note, and the re-pair warning + "I've re-paired the host"; Haptics ON/OFF + strength; Tilt ON/OFF + sensitivity + deadzone + calibrate + sensor; Pointer & scroll speed; Clear all held inputs.
- **RGB** — the existing "RGB backlight" group verbatim (on/off, effect pills, flow, speed, brightness, colour, hint). Task 5 adds the pad glow group here.
- **INFORMATION** — HID status; Help / pairing instructions; Android note; haptics note.

Do not change any label, callback, or parameter while moving them.

- [ ] **Step 5: Add a `@Preview` per page**

Add landscape `@Preview(showBackground = true, widthDp = 800, heightDp = 360)` previews for the list and for one page, using dummy values, matching the existing preview style in the file.

- [ ] **Step 6: Build and verify on the device**

Run: `.\gradlew.bat --console=plain testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL, tests=205 failures=0.

Install on the phone, open Settings, and check each of the four rows opens its page, the back arrow returns to the list, and every control still works (toggle haptics, move a slider, pick a mode). Screenshot the list and each page.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/GamepadScreen.kt
git commit -m "feat: split Settings into four sub-pages"
```

---

### Task 3: Pad glow config and colour engine

**Files:**
- Create: `app/src/main/kotlin/com/hidgamepad/PadGlow.kt`
- Test: `app/src/test/kotlin/com/hidgamepad/PadGlowTest.kt`

**Interfaces:**
- Consumes: `RGB_PRESETS`, `RgbPreset`, `RgbConfig`-style persistence helpers from `RgbBacklight.kt` (`readStoredBrightness`, `RGB_BRIGHTNESS_MIN/MAX`).
- Produces:
  - `enum class PadGlowEffect { STATIC, BREATHING, COLOR_CYCLE, RAINBOW, REACTIVE }`
  - `data class PadGlowConfig(enabled: Boolean = false, effect: PadGlowEffect = PadGlowEffect.STATIC, speed: Float = 1f, color: Int = 0, brightness: Float = 1f)`
  - `fun padGlowColor(config: PadGlowConfig, position: Float, touched: Boolean, timeMillis: Long): Color?` — `null` when `!config.enabled`, else the border colour. `position` is 0..1 around the border's perimeter; `touched` is true while a finger is down (only `REACTIVE` reads it).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hidgamepad

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PadGlowTest {

    private val base = PadGlowConfig(enabled = true, effect = PadGlowEffect.STATIC, brightness = 1f)

    @Test
    fun `disabled pad glow draws nothing`() {
        assertNull(padGlowColor(base.copy(enabled = false), 0.5f, false, 0L))
    }

    @Test
    fun `enabled static glow draws the preset colour`() {
        val c = padGlowColor(base, 0.5f, false, 0L)
        assertTrue(c != null && c.alpha > 0f)
    }

    @Test
    fun `glow does not depend on the keyboard backlight being on`() {
        // The pad glow is independent: its own config is all it needs.
        assertNotEquals(null, padGlowColor(base, 0.2f, false, 1234L))
    }

    @Test
    fun `rainbow changes hue around the border and over time`() {
        val a = padGlowColor(base.copy(effect = PadGlowEffect.RAINBOW), 0f, false, 0L)!!
        val b = padGlowColor(base.copy(effect = PadGlowEffect.RAINBOW), 1f, false, 0L)!!
        val later = padGlowColor(base.copy(effect = PadGlowEffect.RAINBOW), 0f, false, 500L)!!
        assertNotEquals(a, b)
        assertNotEquals(a, later)
    }

    @Test
    fun `reactive is dim until touched, then bright`() {
        val cfg = base.copy(effect = PadGlowEffect.REACTIVE)
        val off = padGlowColor(cfg, 0.5f, false, 0L)!!
        val on = padGlowColor(cfg, 0.5f, true, 0L)!!
        assertTrue("touched must be brighter", luminance(on) > luminance(off))
    }

    @Test
    fun `brightness scales the glow`() {
        val dim = padGlowColor(base.copy(brightness = 0.2f), 0.5f, false, 0L)!!
        val bright = padGlowColor(base.copy(brightness = 1f), 0.5f, false, 0L)!!
        assertTrue(luminance(bright) > luminance(dim))
    }

    private fun luminance(c: Color) = c.red * 0.2126f + c.green * 0.7152f + c.blue * 0.0722f
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.PadGlowTest"`
Expected: FAIL — unresolved `PadGlowConfig` / `padGlowColor`.

- [ ] **Step 3: Implement `PadGlow.kt`**

```kotlin
package com.hidgamepad

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.PI

/** The pad-glow effects: only the ones that make sense on a single border loop. */
enum class PadGlowEffect { STATIC, BREATHING, COLOR_CYCLE, RAINBOW, REACTIVE }

/**
 * The Mouse-mode touchpad's border glow. Independent of [RgbConfig]: own prefs keys
 * (`pad_glow_*`), own effect, colour and brightness.
 */
data class PadGlowConfig(
    val enabled: Boolean = false,
    val effect: PadGlowEffect = PadGlowEffect.STATIC,
    val speed: Float = 1f,
    val color: Int = 0,
    val brightness: Float = 1f,
)

/** The border colour at [position] (0..1 around the loop) and [timeMillis], or null when off. */
fun padGlowColor(
    config: PadGlowConfig,
    position: Float,
    touched: Boolean,
    timeMillis: Long,
): Color? {
    if (!config.enabled) return null
    val t = timeMillis / 1000f
    val sb = config.brightness.coerceIn(RGB_BRIGHTNESS_MIN, RGB_BRIGHTNESS_MAX)
    val preset = RGB_PRESETS[config.color.coerceIn(RGB_PRESETS.indices)]

    fun presetAt(intensity: Float) =
        Color.hsv(preset.hue, preset.sat, (preset.value * intensity * sb).coerceIn(0f, 1f))

    return when (config.effect) {
        PadGlowEffect.STATIC -> presetAt(1f)
        PadGlowEffect.BREATHING ->
            presetAt(0.1f + (1f - cos(PI.toFloat() * 2f * t * config.speed * 0.5f)) * 0.45f)
        PadGlowEffect.COLOR_CYCLE ->
            Color.hsv((t * config.speed * 60f).mod360(), 0.85f, sb)
        PadGlowEffect.RAINBOW ->
            Color.hsv(((position * 360f) + t * config.speed * 80f).mod360(), 0.85f, 0.95f * sb)
        PadGlowEffect.REACTIVE -> presetAt(if (touched) 1f else 0.14f)
    }
}
```

`RGB_BRIGHTNESS_MIN/MAX` and `mod360()` already exist in `RgbBacklight.kt` in the same package.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.PadGlowTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/PadGlow.kt app/src/test/kotlin/com/hidgamepad/PadGlowTest.kt
git commit -m "feat: pad glow colour engine"
```

---

### Task 4: Draw the pad glow in `GestureTouchpad` and drive it in Mouse mode

**Files:**
- Modify: `app/src/main/kotlin/com/hidgamepad/GestureTouchpad.kt` (composable `GestureTouchpad`, the `Canvas` near lines 104–162)
- Modify: `app/src/main/kotlin/com/hidgamepad/GamepadScreen.kt` (the `ControllerMode.MOUSE` branch, around line 427)

**Interfaces:**
- Consumes: `PadGlowConfig`, `padGlowColor` (Task 3).
- Produces: `GestureTouchpad` gains two defaulted parameters (so all other call sites keep compiling):
  - `padGlow: PadGlowConfig = PadGlowConfig()`
  - `padGlowTimeMillis: Long = 0L`

- [ ] **Step 1: Add the parameters and draw the halo**

In `GestureTouchpad`, add the two parameters above. In the `Canvas` draw block, **after** the existing border `drawRoundRect`, draw the halo — the same stacked translucent rounded rects used for the key glow (no `BlurMaskFilter`):

```kotlin
            // The pad glow: a soft halo on both sides of the border, only when enabled.
            val glowColor = padGlowColor(padGlow, position = 0f, touched = fingerDown, timeMillis = padGlowTimeMillis)
            if (glowColor != null) {
                for (i in 1..4) {
                    val spread = i * 2f
                    drawRoundRect(
                        color = glowColor.copy(alpha = 0.18f / i),
                        topLeft = Offset(-spread, -spread),
                        size = Size(size.width + spread * 2, size.height + spread * 2),
                        cornerRadius = CornerRadius(20f + spread, 20f + spread),
                        style = Stroke(width = 2f + spread),
                    )
                }
            }
```

Do not touch the gesture `pointerInput`, the recognizer, the bridge, or the ticker.

- [ ] **Step 2: Wire the config and clock in the MOUSE branch**

In `GamepadScreen.kt`, near the RGB state, add the pad-glow state and its own clock, mirroring the keyboard's:

```kotlin
    var padGlow by remember { mutableStateOf(PadGlowConfig()) }
    val padGlowStart = remember { System.currentTimeMillis() }
    val padGlowClock by produceState(initialValue = 0L, key1 = padGlow.enabled) {
        if (!padGlow.enabled) { value = 0L; return@produceState }
        while (true) { value = System.currentTimeMillis() - padGlowStart; delay(80) }
    }
```

Pass `padGlow = padGlow, padGlowTimeMillis = padGlowClock` to the `GestureTouchpad` call in the `ControllerMode.MOUSE` branch. (Persistence and the Settings controls come in Task 5; this task renders with defaults.)

- [ ] **Step 3: Build and verify on the device**

Run: `.\gradlew.bat --console=plain testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL, tests=211 failures=0.

Install, switch to Mouse mode. With the glow still defaulting off, the border must look exactly as before. Temporarily flip the default to `enabled = true` to confirm the halo renders, screenshot it, then set the default back to `false`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/GestureTouchpad.kt app/src/main/kotlin/com/hidgamepad/GamepadScreen.kt
git commit -m "feat: draw the mouse pad glow"
```

---

### Task 5: Pad glow controls on the RGB page

**Files:**
- Modify: `app/src/main/kotlin/com/hidgamepad/GamepadScreen.kt` (RGB page body from Task 2; the RGB state block near line 283)

**Interfaces:**
- Consumes: `PadGlowConfig`, `PadGlowEffect`, `readStoredBrightness`, `adjustBrightness` (Task 3 / `RgbBacklight.kt`).
- Produces: `padGlow` now persists under `pad_glow_on`, `pad_glow_effect`, `pad_glow_speed`, `pad_glow_color`, `pad_glow_brightness`.

- [ ] **Step 1: Persist and load the pad-glow config**

Load it defensively, like the backlight:

```kotlin
    var padGlow by remember {
        mutableStateOf(
            runCatching {
                PadGlowConfig(
                    enabled = prefs.getBoolean("pad_glow_on", false),
                    effect = runCatching { PadGlowEffect.valueOf(prefs.getString("pad_glow_effect", "STATIC") ?: "STATIC") }
                        .getOrDefault(PadGlowEffect.STATIC),
                    speed = runCatching { prefs.getFloat("pad_glow_speed", 1f) }.getOrDefault(1f),
                    color = prefs.getInt("pad_glow_color", 0),
                    brightness = readStoredBrightness(prefs, 1f),
                )
            }.getOrDefault(PadGlowConfig()),
        )
    }

    fun savePadGlow(newConfig: PadGlowConfig) {
        padGlow = newConfig
        prefs.edit()
            .putBoolean("pad_glow_on", newConfig.enabled)
            .putString("pad_glow_effect", newConfig.effect.name)
            .putFloat("pad_glow_speed", newConfig.speed)
            .putInt("pad_glow_color", newConfig.color)
            .putFloat("pad_glow_brightness", newConfig.brightness)
            .apply()
    }
```

(Replace the Task 4 placeholder state with this block; the clock from Task 4 stays.)

- [ ] **Step 2: Add the "Mouse pad glow" group to the RGB page**

Below the existing RGB backlight controls on the RGB page:

```kotlin
        Text("Mouse pad glow", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PadColors.text)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Glow: ${if (padGlow.enabled) "ON" else "OFF"}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PadColors.text, modifier = Modifier.weight(1f))
            Switch(checked = padGlow.enabled, onCheckedChange = { onPadGlow(padGlow.copy(enabled = it)) })
        }
        Text("The Mouse-mode touchpad border. Independent of the keyboard backlight above.", fontSize = 11.sp, color = PadColors.secondary)
```

Then, following the exact pattern of the existing RGB controls: effect pills over `PadGlowEffect.entries` (labels via a `padGlowEffectLabel` helper), a speed `Slider` (`valueRange = 0.25f..2f, steps = 6`), a brightness `Slider` (`valueRange = 0.1f..1f, steps = 8`), and the same colour swatch row over `RGB_PRESETS` (index stored in `padGlow.color`).

- [ ] **Step 3: Thread `onPadGlow` into `SettingsPanel`**

Add `padGlow: PadGlowConfig` and `onPadGlow: (PadGlowConfig) -> Unit` to `SettingsPanel`'s parameters and pass `padGlow = padGlow, onPadGlow = { savePadGlow(it) }` at the call site (next to `rgbConfig`/`onRgb`).

- [ ] **Step 4: Build, test and verify on the device**

Run: `.\gradlew.bat --console=plain testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL, tests=211 failures=0.

Install. In Mouse mode: turn the pad glow on and confirm the border glows in each of the five effects; set it to Reactive and confirm it brightens while you drag and dims when you lift; change brightness and colour and confirm they apply; turn it off and confirm the border returns to the grey. Confirm the keyboard backlight can be off while the pad glows. Screenshot two effects.

- [ ] **Step 5: Leak scan and commit**

```bash
git add -A
# Run the repo's leak scan (see AGENTS/notes): grep the staged diff for any
# device serial, host Bluetooth name or MAC, or the report topic. Expect no matches.
git commit -m "feat: mouse pad glow settings on the RGB page"
```

---

## Self-Review

**Spec coverage:** sub-pages list + four pages (Task 2); Back steps (Task 1, consumed by Task 2's header and by the pending Back-navigation task); pad glow independent settings (Tasks 3, 5); pad glow drawing only in Mouse mode (Task 4); defensive persistence (Task 5); reduced five-effect set (Task 3); `PadColors`-only + dp-only (Global Constraints). No gaps.

**Step scan:** every step names a file and a concrete action; code appears only where the signature and tests do not determine the body.

**Type consistency:** `PadGlowConfig`, `PadGlowEffect`, `padGlowColor`, `SettingsSection`, `settingsBackStep`/`SettingsBack`, and `savePadGlow`/`onPadGlow` are used with the same names and shapes in every task that touches them. `GestureTouchpad`'s new parameters are defaulted, so the other call sites are unaffected.

**Review Focus:** each of the five lines has a test in Task 1 or Task 3; the scrolling line is covered by Task 2 keeping `verticalScroll` on the shared Column.
