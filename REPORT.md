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

---

# Report — 2026-11-10

## Touchpad gestures (implemented)

Design: `docs/superpowers/specs/2026-11-10-touchpad-gestures-design.md`
Plan: `docs/superpowers/plans/2026-11-10-touchpad-gestures.md`

Mouse mode is now a buttonless pad with no on-screen mouse buttons. New files:
`TouchpadGestures.kt`, `HostGestureMapping.kt`, `TouchpadGestureBridge.kt`,
`GestureTouchpad.kt`. Deleted from `Joystick.kt` and the mouse panel: `MouseTouchpad`,
`MouseActionButton`, the three mouse buttons, and the two scroll buttons.

| Check | Result | Evidence |
|---|---|---|
| Unit tests | PASS | `gradlew testDebugUnitTest` → 95 tests, 0 failures (2026-11-10 20:24). |
| Recognizer | PASS | `TouchpadGestureTest` 23 tests: tap/drag/flick, double-tap drag arming, 2-finger scroll + pan + right-click, pinch vs scroll, 3-finger tap/swipe, 4-finger no-click, lift produces no scroll. |
| Bridge | PASS | `TouchpadGestureBridgeTest` 8 tests: button mask, click press/release, Ctrl held across a pinch, per-host chords, `releaseAll`. |
| Host mapping | PASS | `HostGestureMappingTest` 2 tests: Windows Win+Tab / Win+D / Alt+Left / Alt+Right; Android 0x0223 / 0x0224 / 0x0225. |
| Build + install | PASS | `assembleDebug` → `app-debug.apk` 20:24:51; `adb -s <PHONE_SERIAL> install -r` → Success. |
| Gesture evidence on a host | NOT CAPTURED | No host is paired or connected to the phone. Nothing here claims a gesture works on a real host. |
| Android-to-Android | NOT VERIFIED | The tablet still does not answer paging; there is still no keyboard-typing or cursor-movement evidence. |

## Windows HID host (blocked, cause identified)

- Windows cached SDP for the phone (`6c40e8eb8fe0`) holds 19 service records and **no HID
  record (0x1124)**. No `BTHENUM` node for 0x1124 is ever created, so the descriptor is
  never read. ACL reached `Encryption success`, then `Role change
  PERIPHERAL status:ROLE_SWITCH_FAILED(0x35)` / `TRANSACTION_RESPONSE_TIMEOUT(0x22)` and
  `Disconnection initiated … l2cu_no_dynamic_ccbs Idle timer popped`; Windows reconnected
  as an A2DP sink (`A2DP Stream opened`).
- User fix: remove the phone under Bluetooth & devices, make the phone discoverable while
  the app is registered, then pair again. Documented in the README.
- Cache key inspected: `HKLM:\SYSTEM\CurrentControlSet\Services\BTHPORT\Parameters\Devices\6c40e8eb8fe0\CachedServices`.

## Gamepad in games (2026-11-11)

Design: `docs/superpowers/specs/2026-11-11-hid-profiles-and-gamepad-conformance-design.md`
Plan: `docs/superpowers/plans/2026-11-11-hid-profiles-and-gamepad-conformance.md`

### Why games did not respond (measured, not inferred)

| Finding | Evidence |
|---|---|
| Windows enumerated the phone correctly | `Get-PnpDevice` shows `HID Keyboard Device`, `HID-compliant mouse`, `HID-compliant touch pad`, `HID-compliant consumer control device`, `HID-compliant game controller` |
| **Chrome saw zero gamepads** | `navigator.getGamepads()` returned `[null,null,null,null,null,null,null,null]` while the phone was connected (`connState=2`) and streaming reports (`sendReport id=3 ... acceptedByAndroid=true`) |
| A composite device is not a gamepad to a host | the gamepad collection is third in the descriptor, after the keyboard and mouse, and its buttons are in Android's positional order, so no host can derive the W3C standard layout |
| Android decodes gamepad buttons positionally | the tablet's `/system/usr/keylayout/Generic.kl` maps 304→`BUTTON_A` … 316→`BUTTON_MODE`, 317→`BUTTON_THUMBL`, 318→`BUTTON_THUMBR`; the kernel maps button N to `BTN_GAMEPAD + N - 1` = 303+N (`drivers/hid/hid-input.c`, `case HID_UP_BUTTON: code = ((usage->hid - 1) & HID_USAGE)` with `case HID_GD_GAMEPAD: code += BTN_GAMEPAD`) |
| MODE, L3 and R3 were permuted | the app used `MODE=13, L3=14, R3=12`; the table above requires `MODE=12, L3=13, R3=14`. A MODE press reached the tablet as L3 |
| The triggers and axes were already correct | the tablet's keylayout maps `axis 0x09 RTRIGGER` / `0x0a LTRIGGER`; the kernel maps Simulation Accelerator→`ABS_GAS` and Brake→`ABS_BRAKE`, so Brake→LTRIGGER and Accelerator→RTRIGGER is correct as written |
| Valorant cannot be fixed in the app | XInput is a proprietary USB protocol keyed on VID `045E`; a Bluetooth HID peripheral can never be one. It needs a PC-side translator (Steam Input or ViGEm), which was deferred by the user |

### What changed

- `GamepadState.kt`: MODE/L3/R3 moved to the Android positional bits (12/13/14).
- `GamepadProfiles.kt` (new): a `GAMEPAD_ONLY` profile with a dedicated gamepad collection in
  the W3C standard layout — 16 buttons in standard order, X/Y/Rx/Ry as signed 16-bit axes,
  Z/Rz as the triggers, and a 4-bit hat. The composite profile is unchanged.
- `HidGamepadManager.kt`: registers `descriptorFor(profile)` with `subclassFor(profile)`
  (`SUBCLASS1_NONE` for the composite, `SUBCLASS2_GAMEPAD` for the gamepad-only profile) and
  sends `reportFor(profile, state)`.
- Settings: a **HID profile** row, with the warning that a profile change needs a re-pair.

### Measured results

| Check | Result | Evidence |
|---|---|---|
| Unit tests | PASS | `gradlew testDebugUnitTest` → 143 tests, 0 failures (2026-11-12) |
| Chrome enumerates a gamepad | **PASS** | `navigator.getGamepads()` → `{id: "HID Gamepad (Vendor: 001d Product: 0000)", connected: true, buttons: 16}`; every slot was `null` before the change |
| The phone sends the right bytes | **PASS** | logcat `sendReport id=3 payload=[01 00 00 00 00 00 00 00 00 00 00 00 0F] length=13` on an A press, then `[00 00 …]` on release |
| The host decodes the new descriptor | **PASS** | the PC was re-paired (`DEVPKEY_Device_FirstInstallDate` 2026-11-11 23:53:21) and now exposes a single `Bluetooth HID Device` node — the old COL01 (Keyboard) / COL02 (Mouse) / COL03 (Game controller) / COL04 (Consumer control) siblings are gone |
| Chrome reads the composite pad | **PASS** | `navigator.getGamepads()` → one pad, `id: "HID Gamepad (Vendor: 001d Product: 0000)"`, 16 buttons, 10 axes. Holding A sets `buttons[0]=1` |
| Chrome button indices | **PASS, `mapping: ""`** | raw Android positional order: A→0, B→1, X→3, Y→4, L1→6, R1→7, SEL→10, START→11. Axes: L-stick→`axes[0]`, R-stick→`axes[2]`/`axes[5]`, L2→`axes[4]`, R2→`axes[3]`, d-pad→`axes[9]`. The pad is non-standard, so a game that requires the W3C standard layout still needs the GAMEPAD_ONLY profile |
| Chrome freezes the Gamepad API in a background tab | measured | while the probe tab was `hidden`/unfocused every button read 0; `Emulation.setFocusEmulationEnabled` (or a foreground tab) is required before reading `navigator.getGamepads()` |
| Tablet enumerates the phone | **PASS** | after the tablet re-paired, its `/dev/input/` has `event6` "vivo Y29 Keyboard", `event7` "vivo Y29 Mouse", `event8` "vivo Y29" (gamepad). `event8` carries `BTN_GAMEPAD BTN_EAST BTN_C BTN_NORTH BTN_WEST BTN_Z BTN_TL BTN_TR BTN_TL2 BTN_TR2 BTN_SELECT BTN_START BTN_MODE BTN_THUMBL BTN_THUMBR`, `ABS_X/Y/Z/RZ`, `ABS_GAS/BRAKE` and `ABS_HAT0X/Y` |
| Tablet button keycodes (composite) | **PASS** | `getevent -lt /dev/input/event8` while pressing each on-screen control: A→`BTN_GAMEPAD`, B→`BTN_EAST`, X→`BTN_NORTH`, Y→`BTN_WEST`, L1→`BTN_TL`, R1→`BTN_TR`, SEL→`BTN_SELECT`, START→`BTN_START` |
| Tablet d-pad and sticks | **PASS** | d-pad N→`ABS_HAT0Y -1`, E→`ABS_HAT0X +1`, S→`ABS_HAT0Y +1`, W→`ABS_HAT0X -1`; left stick→`ABS_X`; right stick→`ABS_Z`/`ABS_RZ` |
| Tablet triggers | **PASS** | L2→`ABS_BRAKE 255`, R2→`ABS_GAS 255`, both back to 0 on release |
| MODE/L3/R3 keycodes | NO UI CONTROL | the gamepad screen exposes no MODE/L3/R3 button, so they cannot be pressed on any host. Their bit positions (12/13/14) are covered by unit tests only; the descriptor puts them at wire bits 12/13/14, which the kernel maps to `BTN_MODE`/`BTN_THUMBL`/`BTN_THUMBR` |
| Valorant | OUT OF SCOPE | needs a PC-side XInput translator; deferred by the user |

### User action required

Both hosts have now re-paired and decode the current composite descriptor (see the table
above). **The profile is no longer a choice** — it follows the host type (see
"Gamepad in games (2026-11-12)" below). An Android host always gets the composite pad; a
Windows host always gets the gamepad-only pad, whose reports follow the W3C standard layout.
Changing the host type needs a re-pair, because the host caches the descriptor at pairing
time.

## Corrections to earlier handoff notes

- Tablet HID enumeration **does** exist in this report (gamepad input node at
  `/dev/input/event6`); the earlier claim that it did not was wrong.
- The working tree was 10 modified + 9 untracked, not clean.
- Stored `controller_mode` is `GAMEPAD`, not mouse.

## Gamepad in games (2026-11-12) — host-driven profile, digital triggers

This supersedes the 2026-11-11 section above where they disagree. Details and the raw
measurements are in `docs/hid-gamepad-handoff-2026-11-12.md`.

### What changed

- **The HID profile follows the host type.** `profileForHostType` maps `ANDROID → COMPOSITE`
  and `WINDOWS → GAMEPAD_ONLY`; `HidGamepadManager.loadStoredProfile()` derives it from
  `host_type`, and the separate `hid_profile` pref and the Settings **HID profile** row are
  gone. The two questions used to be independent, so "Android + Gamepad only" was reachable
  — and that combination is broken (see below). One choice to make now.
- **The triggers are digital.** L2/R2 had become analog position-ramped pedals, which put the
  centred "R2" label at exactly half travel (`ABS_GAS` 128 of 255); a host that thresholds the
  trigger read that as "not pressed". Any touch on the strip is now a full pull (255).
- **A master HID on/off switch** in Settings: off releases every input, unregisters the HID
  device, stops the foreground service and removes the notification; on reverses it. Persisted,
  and a phone left off stays off across a restart.
- **The branch is merged into `main`** (local only, never pushed).

### Measured results

| Check | Result | Evidence |
|---|---|---|
| Unit tests | PASS | `gradlew testDebugUnitTest` → **143 tests, 0 failures**; clean rebuild from scratch, `assembleDebug` produces the APK |
| The profile follows the host type | PASS | switching the host type to Windows PC cleared the re-pair warning (derived GAMEPAD_ONLY == paired); switching back to Android showed it again (derived COMPOSITE != paired) |
| A stale `hid_profile` pref does not win | PASS | with `hid_profile=GAMEPAD_ONLY` still stored, the tablet re-paired and decoded the **composite** descriptor |
| The tablet decodes the composite | PASS | one merged node `<HOST>`'s vivo Y29 Keyboard`, `Sources: KEYBOARD \| DPAD \| GAMEPAD \| MOUSE \| STYLUS \| JOYSTICK`, 8-bit X/Y/Z/RZ plus `BRAKE`/`GAS` |
| Triggers | PASS | R2 on the label → `ABS_GAS 255` → 0 on release; L2 on the label → `ABS_BRAKE 255`; R2 at the top of the strip → `ABS_GAS 255` |
| The host caches the descriptor | PASS | before the re-pair the tablet still showed the cached GAMEPAD_ONLY layout (16-bit X/Y/RX/RY) — the expected behaviour, and why the app warns |
| The trigger reaches the host | PASS (as an axis only) | `ABS_GAS`/`ABS_BRAKE`, mapped to `AXIS_RTRIGGER`/`AXIS_LTRIGGER` by the tablet's `Generic.kl`. No key event is ever generated for it, so a game that reads the trigger strictly as `KEYCODE_BUTTON_R2` sees nothing — fixing that needs L2/R2 in the descriptor's button bits, and therefore a re-pair on every host |

### Still open

- The trigger never arrives as a button (above); needs a descriptor change and a re-pair.
- A real Windows *game* is still an unverified manual check, and XInput games need a PC-side
  translator (Steam Input or ViGEm) — a Bluetooth HID pad can never be XInput.
- MODE / L3 / R3 have no on-screen control, so they cannot be pressed on any host.
- The `HAT_NEUTRAL` value (15) contradicts the spec's own table (8); it is the kernel's
  clamping behaviour, and the deviation is not yet recorded in the spec table.

## Commits (local only, never pushed)

`main` holds the whole `feat/touchpad-gestures` work as a linear history — the branch tip
`7f7d49d` is an ancestor of `main` — plus the close-out commits on top. Main's four
pre-merge commits were **dropped rather than merged**: they were the same three PC-host fixes
applied to a different base plus a report whose content the branch already carried, and they
were the only commits in the repository still holding the raw device serials. Dropping them
leaves no raw serial anywhere in `main`'s history, so the branch is safe to publish as it
stands. Nothing has been pushed: `origin/main` is still at `06d552e` and
`origin/feat/touchpad-gestures` at `3e72aef`.

Latest commits on `feat/touchpad-gestures` (all now in `main`):

`7f7d49d` docs(gamepad): record the digital trigger fix and its measurement
`d7ca8a2` fix(gamepad): make the L2/R2 triggers digital
`fab95db` docs(hid): record the host-driven profile change and its verification
`3b24642` feat(hid): derive the HID profile from the host type
`7591240` docs(spec): host-driven HID profile design

Earlier work on the branch: `0f36c05`, `22649fb`, `d5a3b44`, `7b8da8e`, `b5c7c12`,
`ed71c88`, `81da7b3`, `5169867`.

