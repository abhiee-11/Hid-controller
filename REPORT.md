# HID Gamepad takeover report

Date: 2026-11-08

---

# Report — 2026-11-09

Host: this Windows laptop ("WORKSTATION-01") — there is no tablet in the test environment.

## What changed (2026-11-09)

### Problem A — connection status & reliability (`HidGamepadManager.kt`)

- `getConnectedDevices()` reconciles every status transition (registration, connection callbacks, `onResume`, Bluetooth state change, and a 5 s poll while registered).
- The stack's list can lag a just-arrived host connection for under a second: the plugged-device SDP callback is trusted first, and clearing a host requires **two empty queries spaced ≥1 s apart** ("twice-empty" rule).
- `registerApp` retries with backoff (1.5 s → 3 s → 6 s → capped at 10 s, retries forever while Bluetooth is on).
- `connect()` gets a 10 s timeout; new status `ConnectTimedOut(deviceName)` surfaces in the top bar as "Couldn't reach … — open Devices to retry".
- `onResume()` re-checks truth and re-registers when the stack dropped the HID record; `onServiceDisconnected` self-heals via `start()`.
- Logging with tag `HidGamepad`: registration attempts/results, every connection state change, connected-device queries with the reason, connect results, heartbeat send counter (every 100).
- Host-neutral wording: SDP description "host computers", status "waiting for a host", menu "No paired hosts yet".

### Problem B — stuck inputs (`Joystick.kt`)

- Joystick: per-gesture `try/finally` emits a neutral stick report on pointer up **and** cancel; outer `finally` covers composition teardown (stick can never leave a stale axis).
- D-pad: zone map cleared + `HAT_NEUTRAL` emitted in `finally` (per-gesture and teardown); lived via `rememberUpdatedState`.
- Buttons: `wasPressed` flag + `finally` around `waitForUpOrCancellation()` — a press and release in the same frame (or teardown mid-press) can never stay logically pressed.

### Tests

- `GamepadStateTest` expanded to 10 tests: same-frame press/release returns byte-identical neutral report; named controls map to distinct 16-bit button bits. 10/10 pass, 0 skipped, 0 compiler warnings.

## Checks table (2026-11-09)

| # | Check | Result | Evidence |
|---|---|---|---|
| 1 | Phone reachable | PASS | `adb devices` → `<PHONE_SERIAL> device` |
| 2 | App installed + permissions | PASS | package listed; CONNECT/ADVERTISE/SCAN all `granted=true` |
| 3 | Phone Bluetooth on | PASS | `settings get global bluetooth_on` → `1` (restored after CHECK 9a) |
| 4 | HID registration | PASS | logcat `onAppStatusChanged registered=true plugged=WORKSTATION-01` at 19:10:47.602 |
| 5 | PC Bluetooth working | PASS | `bthserv` Running; radio PAVO PL031 / MSBT Enumerator Status OK |
| 6 | Phone↔PC bonded | PASS | PC lists "My vivo Y29"; phone bonded list has the PC |
| 7 | HID profile connected | PASS | app status `Connected: WORKSTATION-01`; PC lists **HID-compliant game controller, Status OK** (re-verified after every CHECK 9 scenario) |
| 8 | Input reaches PC, press→release→neutral | PASS | see per-control table below; winmm `joyGetPosEx` sampler at 50 ms |
| 9 | Connection survives real events | PASS | see scenarios below |

### CHECK 8 — per-control results (PC-side winmm sampler)

Coordinates measured from the on-screen layout screenshot (left display-cutout inset ≈35 dp shifts left-side controls; naive dp math missed them — first campaign's L1/L2/D-pad-W/NE "misses" were test-artifact taps next to the controls, re-run with corrected coordinates).

| Control (holds via `input swipe … 400`, one at a time) | PC saw | Returns to neutral |
|---|---|---|
| A | `B=0x0001` | ✔ |
| B | `B=0x0002` | ✔ |
| X | `B=0x0004` | ✔ |
| Y | `B=0x0008` | ✔ |
| L1 | `B=0x0100` (bit 8) | ✔ |
| R1 | `B=0x0200` | ✔ |
| L2 | trigger axis `V=65535` full → `0` | ✔ |
| R2 | trigger axis `U=65535` full → `0` | ✔ |
| Select | `B=0x0400` | ✔ |
| Start | `B=0x0800` | ✔ |
| D-pad E/S | `POV=9000` / `22500` | ✔ |
| D-pad N/W/NE | `POV=0` / `27000` / `4500` | ✔ |
| Left stick ↓/→ | Y 35889→64235 / X up-ramp (down & right positive, HUD convention) | ✔ centered |
| Right stick ↓/→ | R 36929→65275 / Z→61634 | ✔ centered |

Empirical winmm mapping (Windows naming quirk): left stick = X/Y, right stick = Z/R, triggers = U/V full-scale, POV = degrees×100 with neutral 65535. No control stuck in any campaign; after each hold the next neutral sample had all axes centered, `B=0x0000`, `POV=65535`.

### CHECK 9 — lifecycle scenarios (each followed by a CHECK 7 re-run + no-stuck-input check)

| Scenario | Result | Evidence |
|---|---|---|
| a. Bluetooth off → on | PASS | off: adapter state 13, proxy disconnected cleanly; on: re-registered attempt 1 → plugged=WORKSTATION-01 → CONNECTED → poll confirms; heartbeat resumed (7100+) |
| b. HOME (background) → relaunch | PASS | background: registered=false, retry scheduled; return: re-register → CONNECTED → reports flowing |
| c. force-stop → relaunch | PASS | fresh process (PID 21575): registered → CONNECTED in ~1.2 s → poll confirms → counter restarted |

### Clean startup sequence, reproducible every launch

`start()` → proxy → `registerApp requested (attempt 1)` → `registered=true plugged=WORKSTATION-01` → (list lags, host kept) → `state=2 CONNECTED` → `[WORKSTATION-01]` confirmed by poll → heartbeat.

### Layout

- Layout from the previous fix untouched; screenshot re-taken and checked: no overlaps, 48 dp minimums (Select/Start), 16 dp margins after the (~35 dp) left display-cutout inset, status bar shows `Connected: WORKSTATION-01`. Screenshot saved outside the repo (`C:\Users\<user>\AppData\Local\Temp\opencode\layout-phoneshot.png`), never committed.

## OWNER TODO

- None required — pairing, bonding and the HID profile all work today. If the connection is ever lost on your side, open the app's **Devices** menu and tap **WORKSTATION-01**; on failure the retry message appears and another tap reconnects.

## Needs manual test

- Multi-touch with real fingers (two controls at once: code handles pointer handover, but only a single injected pointer was testable).
- Real games / controller-tester apps on this PC.
- Haptic feedback feel and the Bluetooth-off permission UI on a real hand.

## Open problems

- None in the gamepad scope. (2026-11-08's tablet stale-trigger issue could not be reproduced on the PC host; the try/finally release guarantees and neutral-on-lifecycle now cover the mechanisms that produced it.)

---

## Phase 0 of the 2026-11-08 section

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
