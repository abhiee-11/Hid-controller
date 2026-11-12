# Back navigation, host chooser and CapsLock - design

**Date:** 2026-11-12

## Intent

Three things were asked for:

1. Back must not shut the app down. Back closes whatever panel is open; on a controller
   screen it returns to the device-type chooser.
2. The first-run tutorial is removed. The app opens on a "choose device type" screen
   instead, and that screen is where Back lands.
3. A CapsLock key, left of Win, with a glowing white dot while it is on.

## Why

- Back finishing the activity is stock Android behaviour the app never overrode. There is
  no `BackHandler` anywhere in the project, so every Back press ends `MainActivity`.
- The tutorial is five steps across 714 lines in three files, and it is in the way.
- The keyboard has no caps lock, so uppercase is impossible without holding Shift.

## Approaches

**Where Back lands on a controller screen.** Three options were put to the user:

- **A. Chooser shown only until a host type is picked; Back from a controller returns to it.**
  **(picked)**
- B. Chooser on every launch.
- C. No chooser after the first pick; Back only closes overlays and otherwise exits.

Option A was picked: it keeps the chooser reachable without making the user pick a host on
every launch, and gives Back a landing place at every level of the app.

**How CapsLock works.** Two options:

- Send HID usage `0x39` (CapsLock). The host toggles its own caps state and lights its own
  LED. The app's dot would be a local mirror that can drift from the host's real state.
- Keep a local `capsOn` latch and send Left Shift with letter keys. Deterministic,
  host-independent, and the dot always matches what the app is doing. **(picked)**

A real keyboard's CapsLock does not shift digits or punctuation, so the latch adds Shift
only when a letter usage (`KeyUsage.A`..`KeyUsage.Z`) is down.

## Goals

- Back closes Report, then Settings, then the Devices dropdown, then the mode dropdown; with
  nothing open it returns to the host chooser; on the chooser it exits the app.
- First launch shows the host chooser. Picking a type stores `host_type` and registers the
  matching HID profile.
- CapsLock toggles a latch. Letters arrive uppercase while it is on. The key shows a glowing
  white dot.
- The chooser is reachable from Settings, so the choice is never trapped.

## Non-goals

- No new HID usages, descriptors, report sizes or report encoders.
- No pairing page. Pairing stays where it already works: the top-bar Devices dropdown and
  Settings (Change device / Make discoverable / Reconnect).
- No change to the mode dropdown, or to any control's press/release behaviour.

## Anti-goals

- Do not let Back exit from a controller screen.
- Do not leave the tutorial behind as dead code.

## Constraints

- HID contract frozen: descriptors, report encoders and sizes, `Btn` bits, keyboard usages.
- The ntfy topic never appears in a tracked file.
- No device serials or host Bluetooth names/MACs in tracked files.
- Landscape only.
- Compose BOM 2026.09.00, Material 3, minSdk 28, targetSdk 37.

## Left to the builder

- The chooser's exact copy.
- The dot's radius and glow radius.
- Whether the Settings button keeps the label "Getting started" (it does).
