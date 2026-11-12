# Design: the function-key row, the Windows key, and scroll speed

Date: 2026-11-12
Status: implemented

## Intent

The phone is used as a keyboard and a trackpad for a PC. Three things were missing:

1. There is no **Windows key**, so `Win+R`, `Win+E` and `Win+D` cannot be typed at all.
2. There are no **function keys**, so `F5`, `Alt+F4` and every other F-key shortcut is out of
   reach.
3. **Scrolling is sluggish** — one wheel notch costs 20 dp of two-finger travel, which is
   37.5 px on this phone (density 1.875).

## What already exists, and what it means

The on-screen keyboard has Ctrl / Shift / Alt as press-and-hold keys, so `Ctrl+C` already works
with two hands. The modifier byte in the registered descriptor declares all eight modifier bits
(E0..E7), which includes **Left GUI** — the Windows key — and the key usage range is
`0x00..0x65`, which covers F1..F12.

**Therefore no descriptor byte changes and no re-pairing is needed for any of this.** The
keyboard collection already carries everything; only the UI was missing.

## Goals

- A **Windows key** on the keyboard, usable as a press-and-hold modifier.
- **F1..F12 always visible**, on a row of their own above the number row.
- **Faster scrolling**, and one knob for pointer speed and scroll speed.

## Non-goals

- No change to the mouse report format, the gamepad report format, the button bit positions,
  the keyboard usages or the modifier layout. The report bytes stay as they are.
- No change to the master HID switch, the connection management, the heartbeat or the re-pair
  rule.
- **No second layer and no Fn key.** A permanent function row costs one row of height; a modal
  layer costs the user a keypress and hides F1–F12 behind a toggle.
- No shortcut buttons: any chord is typed by holding a modifier and tapping a key, which the
  main layer already supports.

## The design

### The keyboard

Six rows, no layers:

| Row | Keys |
|---|---|
| 1 | `F1`..`F12` |
| 2 | `Esc` `1`..`0` `Backspace` |
| 3 | `Tab` `Q`..`P` `Enter` |
| 4 | `Shift` `A`..`L` `Shift` |
| 5 | `Ctrl` `Z`..`M` `Alt` |
| 6 | `Win` `Space` `←` `↑` `↓` `→` (arrows narrow) |

`Win` sits at the bottom-left, next to `Space`, and the arrow keys take a smaller share of their
row so `Space` gets most of it.

`Win` is a press-and-hold modifier, exactly like `Ctrl`, so it works alone (Start menu) and in
a chord (`Win+E`). Every usage is inside `0x00..0x65`, so no descriptor change.

The rows share the panel height evenly (`BoxWithConstraints` divides the available height by the
row count), so adding the function row shrinks every row a little rather than overflowing.

**Trade-off, accepted:** with no second layer there is no way to type `. , / - = [ ] ; '` or the
navigation cluster (Ins/Home/PgUp/Del/End/PgDn). The user chose this explicitly over an Fn key.

### Scroll speed

The scroll step was a fixed `SCROLL_STEP_DP = 20f` dp of travel per wheel notch. It becomes:

```
step = SCROLL_STEP_DP * density / sensitivity
```

so the **existing Mouse sensitivity slider** now sets scroll speed as well as pointer speed.
Higher sensitivity means less finger travel per notch, i.e. faster scrolling.

`SCROLL_STEP_DP` drops from `20f` to `10f`, so the default is twice as fast as before even at
×1.00, and the slider scales it from ×0.25 (4× slower than before) to ×2.0 (4× faster). The
step is floored at `1f` so a high sensitivity can never produce a zero-length step. The slider's
label reads **"Pointer & scroll speed"**.

This is the one frozen gesture threshold that changed. The user asked for it explicitly.

### Open problem: the Windows profile

The keyboard works on a host that has the keyboard collection — the Android tablet, and the PC
as it is currently paired (it cached the composite descriptor). It will **not** work on a
Windows host that is re-paired to the gamepad-only descriptor, because that descriptor has no
keyboard collection at all. The app currently points every Windows host at gamepad-only, and
the Settings sheet nudges the user to re-pair — which would quietly turn the keyboard off on the
machine where it is most likely to be wanted.

The trade-off that produced the gamepad-only profile: Windows reads a gamepad's buttons in
*declaration order*, so the gamepad-only descriptor's W3C button order is what a Windows game
expects. The composite descriptor's gamepad collection uses the Android positional order, which
the same Windows game reads wrongly (X and R1 land on the wrong buttons — measured, see the
handoff).

A third profile is the way out: **keyboard + mouse + consumer + a W3C-ordered gamepad**, used
for Windows hosts. It is designed here but not built.

## Approaches considered

**A. Add the missing keys to the main keyboard layer.** Rejected: it makes the common keys
smaller, and the common keys are what the app is used for most.

**B. An Fn layer plus a named shortcut list.** Designed, built, verified on the device, then
removed. The user wanted the function row permanently visible and no Fn key, and preferred the
simpler board over a modal layer. `KeyAction.Chord`, `KeyAction.Layer`, `chordStates()`, the Fn
key and the shortcut list were all deleted.

**C. A permanent function row, no Fn key.** Chosen.

## Constraints

- The descriptor is frozen: no byte changes. Everything above fits the existing keyboard
  collection.
- The reports are frozen: no format changes. Modifier-plus-key chords reuse the existing
  keyboard report, with the modifier held across the key press.

## What shipped

- `KeyUsage`: the F1..F12 usages (`0x3A..0x45`).
- `KeyboardShortcuts.kt`: `functionRow()` and `keyboardRows()`, plus `KeyCap`/`KeyAction`.
- `GamepadScreen`: the function row above the number row, and the `Win` modifier key.
- `TouchpadGestures`: the scroll step follows the sensitivity.
- `Settings`: the slider label.
- Left unbuilt: the third profile for Windows hosts (see "Open problem").
