# Host-driven HID profile

Date: 2026-11-12
Status: implemented (commit `3b24642`); device-verified on the tablet, see the handoff

## Intent

Make the phone work as a gamepad in games on both host types the app already asks about,
by deriving the HID descriptor from the connection choice instead of a separate "HID
profile" picker. Stop treating the browser as a target.

## Why

- The app already asks Android vs Windows PC (`host_type`), and *also* asks a separate HID
  profile (`hid_profile`). The two are independent, so "Android + Gamepad only" is
  reachable — and that combination is broken.
- GAMEPAD_ONLY reorders the buttons into W3C order, which lands X on wire bit 2 and R1 on
  wire bit 5. The Android kernel maps Gamepad button usages positionally to
  `BTN_A, BTN_B, BTN_C, BTN_X, BTN_Y, BTN_Z, …`, so those two slots become `BTN_C`/`BTN_Z`,
  which Android and Chrome drop. Measured on the tablet's Chrome, 2026-11-12: X and R1
  produce no button at all, and `mapping` stays `""`.
- The two hosts want *different* orderings: Android maps buttons by position (kernel
  gamepad map), Windows maps them by declaration order (DirectInput). So the profile should
  follow the host, not be chosen separately.

## Goal

- **Android connection** → COMPOSITE descriptor, positional (`Btn`) button order → works in
  Android games and in Android Chrome.
- **Windows PC connection** → GAMEPAD_ONLY descriptor, W3C button order → works in Windows
  games (DirectInput) and in Windows Chrome.
- One choice to make. Switching connection still needs a re-pair, because the descriptor
  changes; the existing warning already covers that.

## Non-goals

- XInput for Windows. A Bluetooth HID pad cannot be XInput; unchanged, still deferred.
- Changing the descriptors or the report encoders. They are already correct for their host.
- Browser-specific work. The browser is no longer a target; it keeps working by virtue of
  the host-correct ordering, but nothing is built for it.

## Anti-goals

- Two independent pickers that can be set to a broken combination.

## Design

- Add `profileForHostType(HostType): HidProfile` in `GamepadProfiles.kt`:
  `ANDROID → COMPOSITE`, `WINDOWS → GAMEPAD_ONLY`.
- Remove the "HID profile" selector and its copy from the Settings sheet, and drop the
  `profile` / `onProfile` parameters from `SettingsPanel`.
- Derive the profile from the host type wherever it is needed:
  - `HidGamepadManager.loadStoredProfile()` reads `host_type` and derives the profile. The
    `hid_profile` pref is dropped.
  - The Settings sheet derives `profile = profileForHostType(hostType)` so the re-pair
    warning keeps working.
  - Changing the host type (Settings, or the wizard's auto-detect from the device name)
    pushes the derived profile into the manager, as `onProfile` does today.
- Re-pair warning logic is unchanged: compare the derived profile with `paired_profile`.
- Remove `hid_profileFromStorage` once nothing reads it. Keep `paired_profile`.

## What's left to the builder

- Exact placement of the derived-profile refresh in the manager.
- Test names and file layout.

## Verification

- Unit: `profileForHostType`; a host-type change produces a repair-needed state; the rest of
  the suite stays green.
- Device: Android/tablet (gamepad screen + browser), Windows Chrome if a PC is available.
  A real Windows *game* stays a manual check.
