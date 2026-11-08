# HID Gamepad takeover report

Date: 2026-11-08

## What changed

### Phase 0 — takeover safety

- Inspected the repository, devices, installed package, and untracked work before editing.
- Preserved unrequested keyboard/mouse/mode-switching and unrelated website work in `stash@{0}: On main: unrequested-modes`.
- Kept the implementation gamepad-only.

### Phase 1 — layout and input fixes

- Rebuilt the gamepad screen for the 858 × 384dp landscape target using safe-area/display-cutout padding followed by a 16dp margin.
- Made the header 48dp tall and its three action buttons 48dp tall.
- Positioned 54dp shoulders at the top, 48dp Select/Start in the center, and the D-pad/sticks/ABXY as a single non-overlapping lower row. The visual screenshot confirms the controls are on screen and separated.
- Corrected joystick Y to the Android/HID convention: down is positive.
- Hardened button release/cancellation and send a neutral report when the activity backgrounds or stops.
- Added `*.png` and `*.log` to `.gitignore`.

### Phase 2 — build and install

- Ran `gradlew clean assembleDebug testDebugUnitTest` successfully.
- Installed the debug APK on phone `<PHONE_SERIAL>`, launched `com.hidgamepad/.MainActivity`, and reviewed logcat for crashes/ANRs.

### Phase 3 — HID checks

- Both ADB devices were online and Bluetooth was enabled. The phone and tablet were already bonded.
- Granted the three Bluetooth runtime permissions. The app status displayed `Connected: My Lenovo Tab M11`.
- The tablet lists the phone as an external `GAMEPAD | JOYSTICK` device at `/dev/input/event6`; its capabilities include gamepad buttons, X/Y/Z/Rz/Rx/Ry axes, and HAT0X/HAT0Y.
- Injected screen touches reached the app visually. Tablet input-state inspection showed report delivery, but stale trigger/select/start assertions remained from an earlier injected sequence. The final neutral-state check did not clear them. This remains open and requires physical-host verification before release.

## Evidence and checks

| Check | Result | Evidence |
|---|---|---|
| Layout/no overlaps | PASS | `shot.png`; visual review shows all clusters separated and fully visible. |
| 48dp minimum controls | PASS | Smallest controls are 48dp Select/Start; ABXY/shoulders are 54dp. |
| Safe area + 16dp margin | PASS | `safeDrawingPadding()` precedes `padding(16.dp)`. |
| Clean build | PASS | `gradlew clean assembleDebug` completed successfully. |
| Unit tests | PASS | `gradlew testDebugUnitTest` completed successfully. |
| Install and launch | PASS | ADB streamed install succeeded; `MainActivity` displayed. |
| Crash/ANR scan | PASS | No `FATAL EXCEPTION` or app ANR in final logcat scan. |
| Required permissions only | PASS | Manifest declares CONNECT, ADVERTISE, and SCAN with `neverForLocation`; package dump confirmed grants. |
| Permission-denial UI | PASS (code review) | Existing activity gates manager startup and displays the retry/settings path. |
| Bluetooth off/on retry | PASS (code review) | Existing receiver restarts the manager when Bluetooth returns. |
| HID registration/retry | PASS (device + code) | Tablet exposes the gamepad input node; manager retains registration retry. |
| Descriptor/report | PASS | 16 buttons, hat, X/Y/Z/Rz/Rx/Ry and 9-byte report verified in source/unit tests; tablet exposes matching capabilities. |
| Change send + 100ms heartbeat | PASS (code review) | `update` diffs reports and heartbeat resends every 100ms while connected. |
| Clean shutdown | PASS (code review) | Manager releases inputs, unregisters the app, and closes the HID proxy. |
| Deadzone/stick center | PASS (code review/tests) | ~8% deadzone; controls emit neutral on release. |
| Stick Y down positive | PASS | Corrected in `Joystick.kt`; checklist updated. |
| D-pad diagonals | PASS (code review) | Eight-way hat mapping plus cardinal merge exists. |
| Distinct mapping/haptics saved/keep awake/landscape | PASS (code review) | Existing implementation maps unique button bits, stores haptics, keeps screen on, and locks landscape. |
| Live button/axis event capture | FAIL — needs manual test | Tablet input node exists, but stale `BTN_TL2`, `BTN_TR2`, `BTN_SELECT`, and `BTN_START` remained after injected tests. Do not release until verified with physical touches and a fresh host connection. |
| No stuck inputs after backgrounding | NEEDS MANUAL TEST | Neutral-on-pause/stop code was added, but tablet retained stale input state during automated checks. |
| `.gitignore` / private files | PASS | PNG/log ignores added; `local.properties`, APKs, and build output remain untracked. |
| README/manual checklist | PASS | README exists; checklist was updated for layout, Y direction, and background neutralization. |

## Screenshots

- Phone: `shot.png`
- Tablet: `shot-tablet.png`

## Remaining manual tests

- Perform real two-thumb multi-touch and play a game/controller tester on the tablet.
- Reconnect or re-pair only if needed, then verify every button/axis and that backgrounding clears all host input state.
- Verify haptic feel and permission-denial/Bluetooth-off interaction on the phone UI.
