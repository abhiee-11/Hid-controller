# HID Gamepad — manual test checklist

Phone (HID source): an Android 8.0+ phone (Bluetooth Classic, HID device profile support)
Host (tablet): any tablet that can pair to a Bluetooth HID gamepad

## Setup (one time per tablet)
- [ ] Install app on phone, grant the 3 Bluetooth permissions when prompted.
- [ ] Phone app: tap **Discoverable** (3–5 min window, system dialog).
- [ ] Tablet: Settings → Bluetooth → pair with "HID Gamepad". Expect it to recognize as a gamepad/HID.
- [ ] Phone app: open **Devices** → tap the tablet → status becomes "Connected: <name>".

## Touch / multi-touch behavior
- [ ] Visual layout: all controls remain inside the safe-area + 16dp margin; shoulders are above the 160dp lower control row with no overlap.
- [ ] Hold left stick with left thumb AND tap A (right side) simultaneously — both register.
- [ ] Two-finger D-pad: touch Up + East → diagonal NE (in a game: direction + it moves). One finger per zone works alone.
- [ ] Sticks snap to center on release (no drift; heartbeat keeps last stored value so residual positions persist, center is a valid state).
- [ ] Push stick past ~8% — deadzone means tiny jitters map to 0.

## Analog outputs (verified on the tablet, 2026-11-11)
- [x] Left stick → `ABS_X` (right = +, down = +, the HID/Android gamepad convention).
- [x] Right stick → `ABS_Z` / `ABS_RZ`.
- [x] L2 / R2 (top strips) → `ABS_BRAKE` / `ABS_GAS`, 0 → 255 on a full press.
- [x] Buttons, measured on the tablet: A→`BTN_GAMEPAD` (button 1), B→`BTN_EAST` (2),
      X→`BTN_NORTH` (4), Y→`BTN_WEST` (5), L1→`BTN_TL` (7), R1→`BTN_TR` (8),
      SEL→`BTN_SELECT` (11), START→`BTN_START` (12).

## HID profile and gamepad

The app registers one of two HID profiles. **The host caches the descriptor at pairing
time, so switching profiles requires removing the phone from the host's Bluetooth list and
pairing it again.** A profile change without a re-pair has no effect on the host.

| Profile | What it registers | Use it for |
|---|---|---|
| Keyboard + mouse + gamepad | the 4-in-1 composite descriptor | the Android tablet; mouse + keyboard + gamepad over one link |
| Gamepad only | a dedicated gamepad, W3C standard layout | **not recommended as-is** — measured worse than composite on the tablet's Chrome: X and R1 are dead (see the results table) |

| Profile | Action | Expected | Result |
|---|---|---|---|
| Gamepad only | Chrome **on the tablet**: open a page that reads `navigator.getGamepads()` | one pad, 17 buttons, **6** axes | FAIL (2026-11-12): pad appears but `mapping: ""` and the index map is broken — A→0, B→1, Y→2, L1→3, SEL→6, START→7; **X and R1 produce no index at all** |
| Gamepad only | Press A on the phone | the host sees one button press | PASS (2026-11-12): browser button index 0 |
| Gamepad only | Move the right stick | the right stick moves on the host | PASS (2026-11-12): right stick → `axes[2]/axes[3]` |
| Gamepad only | Press L2 / R2 on the phone | the host sees a trigger axis move | PASS (2026-11-12): L2 → `axes[4]`, R2 → `axes[5]` (rest `-1`, rises with the press) |
| Composite | Re-pair the tablet | the tablet lists the phone as a gamepad | PASS (2026-11-11): `event8` "vivo Y29" with the full button/axis set |
| Composite | Chrome **on the tablet**: open a page that reads `navigator.getGamepads()` | after one real tap, one pad, 17 buttons, 7 axes | PASS (2026-11-12): `mapping: ""`, near-W3C indices (A→0 … d-pad 12–15) |
| Composite | Press A / move the left stick in the tablet's Chrome | button index 0 / axis 0 moves | PASS (2026-11-12) |
| Composite | Press every on-screen control | the tablet reports the expected keycode | PASS (2026-11-11): see the "Analog outputs" list above, plus d-pad → `ABS_HAT0X/Y` |
| Composite | Press MODE on the phone | the tablet reports `BUTTON_MODE` (316) | N/A — the gamepad screen has no MODE button |

Re-pair steps: on the host, remove the phone under Bluetooth settings; on the phone, tap
**Make discoverable**; on the host, add the phone again while the app is open.

## Mouse gestures (buttonless pad)

The mouse mode has no on-screen buttons. Every gesture below goes out through the frozen
descriptor, so no re-pairing is involved. Fill the **Result** column during testing.

| Mode | Action | Expected | Result |
|---|---|---|---|
| Mouse | Tap once on the pad | Host registers one left click | |
| Mouse | Two-finger tap | Host registers a right click (Windows: context menu) | |
| Mouse | Two-finger drag up/down | Content scrolls vertically, natural direction | |
| Mouse | Two-finger drag left/right | Horizontal pan where the app supports it | |
| Mouse | Pinch out / pinch in | Ctrl+wheel zoom in the host's zoomable app | |
| Mouse | Tap, then tap and hold, then drag | Host drags with the left button held; release ends the drag | |
| Mouse | Three-finger tap | Windows: middle click; Android: usually nothing | |
| Mouse | Three-finger swipe left / right / up | Windows: back / forward / Task View; Android: Back / Forward / Home | |
| Mouse | Four-finger tap | Nothing happens | |
| Mouse | Background the app mid-drag or mid-pinch | Host sees the button and Ctrl released | |

Pinch is Ctrl+wheel on both hosts — a 5-byte relative mouse report cannot express two
independent contacts. Android right-click, middle-click, and zoom are app-dependent.

## Haptics toggle
- [ ] **Haptics ON**: pressing any button buzzes.
- [ ] **Haptics OFF**: no buzz; setting persists across app restart.

## Robustness
- [ ] Turn phone Bluetooth off mid-session → app survives, no crash; status resets.
- [ ] Bluetooth back on → app re-registers automatically; tablet reconnect (or re-connect via Devices).
- [ ] Rotate is locked to landscape; screen never sleeps while app is in the foreground.
- [ ] Kill the app (swipe away) → tablet sees HID device disconnect; no stuck pressed buttons after 100 ms heartbeat cut.
- [ ] Background the app while holding A, L2, Start, and a D-pad direction; the host returns every button and axis to neutral.
- [ ] Deny permissions → app shows a "Grant permissions" screen, no crash (retry works).

## Known current limitations (v1)
- Triggers are binary (full pull) — the phone screen cannot sense gradual travel pressure.
- "Connected" devices list shows only already-paired tablets; discovery pairing is via the tablet side.
