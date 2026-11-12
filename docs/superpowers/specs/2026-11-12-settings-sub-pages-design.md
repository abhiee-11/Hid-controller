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

## Non-goals / anti-goals

- No change to HID, descriptors, report encoding, connection handling, modes,
  settings persistence, input sending, or any control's press/release behaviour.
- No new colours outside `PadColors`; no new screens/activities.
- Not adding the typing-sound feature here (still an open, separate idea).
- Anti-goal: losing a control or its state while moving it. Every row on every
  page must map 1:1 to something that exists today.

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

**Information page**
- HID status; Help / pairing instructions; Android compatibility note; the
  haptics-are-not-rumble note.

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
