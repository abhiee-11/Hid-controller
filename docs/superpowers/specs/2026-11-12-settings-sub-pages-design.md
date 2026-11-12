# Settings as sub-pages — short design

Date: 2026-11-12
Branch: feat/ui-restyle

## Intent

The Settings sheet is one long flat column. Split it so each group of controls
lives on its own page, reached from a short list. The aim is a Settings screen a
person can scan: four rows, tap the one you want, and only that group's controls
are on screen.

## What exists today

- `SettingsPanel` in `GamepadScreen.kt` is a single `Column` in a
  `verticalScroll`, with bold text lines ("Connection", "Controls",
  "Information", "RGB backlight") acting as headings. Everything is on one page.
- The sheet opens from the top bar (`settingsOpen = true`) and closes with a
  "Close" button. There is **no** `BackHandler` yet — hardware Back is not wired
  (that is the separate, still-pending Back-navigation task).

## Goals

- Four sub-pages: **Connection**, **Controls**, **RGB backlight**, **Information**.
- A short main list with those four rows plus "Report a problem".
- Every existing control stays, with its exact same behaviour, callbacks and
  parameters — this is a move, not a change.
- Works with the pending Back-navigation work: Back from a sub-page returns to
  the list; Back from the list closes Settings.

This change also adds the **mouse pad RGB glow** (see its own section below),
which lives on the RGB backlight page.

## Non-goals / anti-goals

- No change to HID, descriptors, report encoding, connection handling, modes,
  input sending, or any control's press/release behaviour.
- No new colours outside `PadColors`; no new screens/activities.
- Not adding the typing-sound feature here (still an open, separate idea).
- Anti-goal: losing a control or its state while moving it. Every row on every
  page must map 1:1 to something that exists today.
- Anti-goal: the pad glow must never affect the touchpad's gestures, sensitivity
  or mouse sending. It is a border decoration only.

## Approaches considered

1. **Sub-pages as in-panel state (chosen).** One remembered
   `settingsSection: Section?` inside the panel; `null` = the list, otherwise the
   chosen page. No navigation library, no new composable parameters threaded
   through `GamepadScreen`.
2. **Separate full-screen composables per page.** More moving parts (each page
   needs the panel's whole parameter list threaded in) for no benefit at four
   pages.
3. **Keep one page, just add dividers/cards.** Smallest, but rejected by the
   user, who wants real sub-pages.

## The design (as decided)

**Main list**
- Header: "Settings" + "Close".
- Button: "Report a problem" (kept prominent at the top, as today).
- Four rows, each: title, a one-line summary, and a chevron. Rows use
  `PadColors` surface/border, rounded, tall enough for touch.

**Connection page** (back arrow in the header)
- HID controller ON/OFF master switch + its explanation text.
- Connected host status; Reconnect / Change device / Make discoverable.
- Getting started.

**Controls page**
- Mode buttons; Host type buttons + the three-finger note, the descriptor note,
  and the re-pair warning with "I've re-paired the host".
- Haptics ON/OFF + strength.
- Tilt ON/OFF + sensitivity + deadzone + calibrate + sensor line.
- Pointer & scroll speed.
- Clear all held inputs.

**RGB backlight page**
- Everything currently under the "RGB backlight" heading, verbatim: on/off,
  effect pills, flow, speed, brightness, colour swatches, the shortcut hint.
- Plus the **Mouse pad glow** group (below).

**Information page**
- HID status; Help / pairing instructions; Android compatibility note; the
  haptics-are-not-rumble note.

## Mouse pad glow (fully independent settings)

The touchpad shown in Mouse mode (`GestureTouchpad`) has a static grey 2px
border today. The user wants that border to glow with RGB, controlled from the
RGB backlight page, with **settings fully independent** of the keyboard's
backlight.

**Settings** (own group on the RGB backlight page, own prefs keys `pad_glow_*`):
- ON/OFF
- Effect, from a reduced set that makes sense on a loop: **Static**,
  **Breathing**, **Color cycle**, **Rainbow**, **Reactive** (border brightens
  while a finger is down on the pad).
- Speed
- Brightness
- Colour swatches

These are stored and applied separately from `rgb_*`, so the pad and the
keyboard can run different effects, colours and brightnesses, and either can be
on while the other is off.

**Look**
- The existing 2px border stays; a soft halo is drawn on both sides of it (light
  spilling in and out), in the pad glow's colour and brightness.
- Only in Mouse mode, since that is where the pad is.
- Pad glow off ⇒ the border is exactly the grey it is today.

**Engine**
- Reuse the existing light engine's colour/brightness math; the loop effects map
  to a position around the border's perimeter rather than to a key.
- New `PadGlowConfig` data class + `pad_glow_*` persistence, mirroring
  `RgbConfig`/`readStoredBrightness` (including the defensive Int-or-Float read).

**Boundary**
- The pad glow draws in `GestureTouchpad`'s Canvas; the pointer/gesture handling
  in that composable is not touched. The glow needs only the current animation
  colour, passed in as a parameter.

**Gesture**
- A 4-finger double-tap anywhere on the Mouse-mode touchpad toggles the pad
  glow on and off. It is a phone-local action (`PadAction.PadGlowToggle`) that
  is consumed on the phone before the HID bridge — the host receives nothing,
  and a 4-finger tap is a no-op on the wire today anyway.

**Navigation**
- `settingsSection` lives inside `SettingsPanel`; the panel decides list vs page.
- Each page header has a back arrow that sets the section back to `null`.
- The pending Back-navigation task will make hardware Back call the same thing:
  a section open → go to the list; list → close Settings.

## Left to the builder

- Exact row heights, spacing and chevron glyph, kept inside the existing
  restyle metrics and `PadColors`.
- Whether to add a `@Preview` per page (preferred, matching the restyle).
- Ordering within a page: keep the current top-to-bottom order of the controls
  being moved.

## Size

A small-to-medium change: one composable in `GamepadScreen.kt` is reorganised
into a list plus four page bodies, plus a small navigation state. No behaviour
changes.
