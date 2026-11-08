# Takeover handoff

## Initial inspection (2026-11-08)

- Branch: `main`, initially at `06d552e feat: Bluetooth HID gamepad Android app`.
- The working tree contained uncommitted keyboard, mouse, and mode-switching work plus unrelated website files. It was preserved without modification in `stash@{0}: On main: unrequested-modes`, as required by the takeover brief.
- The committed baseline is gamepad-only. It includes the Android Bluetooth permissions, a 9-byte gamepad report, and the manual checklist.
- Both requested devices were visible to ADB: phone `<PHONE_SERIAL>` and tablet `<TABLET_SERIAL>`. The phone already had `com.hidgamepad` installed, with all three Bluetooth runtime permissions granted.
- Initial issue to address: the old screen arranged control clusters vertically, causing overlaps and content beyond the bottom edge on the 858 × 384dp landscape target.

## Work in progress

- Replacing only the gamepad layout with an inset-aware 16dp-margin geometry; keyboard, mouse, and mode-switching features remain excluded.
