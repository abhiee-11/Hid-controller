# HID gamepad — continuation handoff (2026-11-12)

Pick this up tomorrow. Everything below is measured, not inferred, unless marked
"open". The tree is clean and committed; nothing is lost if the PC was shut down.

## Where things stand

- `main` is at the merge commit `1dafd3c`, which folds in `feat/touchpad-gestures`
  (identical tree). Everything is local only — **never pushed**: `origin/main` is still at
  `06d552e`, `origin/feat/touchpad-gestures` at `3e72aef`.
- Build + tests: `.\gradlew.bat --console=plain testDebugUnitTest assembleDebug`
  → **143 tests, 0 failures**.
- **The L2/R2 triggers are digital again** (commit `d7ca8a2`): a press anywhere on the strip
  is a full pull (`ABS_GAS`/`ABS_BRAKE` 255), not a position ramp. They had been analog
  position-ramped since `c1a1ece`, which put the centred "R2" label at half travel and made
  the trigger read as dead. See "Trigger fix" below.
- **The HID profile now follows the host type** (approach A, commit `3b24642`): the separate
  "HID profile" picker is gone. `profileForHostType` maps `ANDROID → COMPOSITE`,
  `WINDOWS → GAMEPAD_ONLY`; `HidGamepadManager.loadStoredProfile()` derives from `host_type`
  and the `hid_profile` pref is ignored (still present in old installs, harmless). Changing
  the host type pushes the derived profile into the manager and re-registers; the re-pair
  warning compares it with `paired_profile`. See
  `docs/superpowers/specs/2026-11-12-host-driven-hid-profile-design.md`.
- **Master HID on/off switch added** (Settings, top). Off releases every input, unregisters
  the HID device, stops `HidControllerService` and removes its notification; on starts it
  again and shows the notification only after registration succeeds. Persisted under
  `hid_enabled` (default on); a phone left off stays off across a restart. See
  `docs/superpowers/specs/2026-11-12-master-hid-switch-design.md`.
- **Two review findings are fixed:** the GAMEPAD_ONLY descriptor wrote `0x27` (a 4-byte HID
  item) for Logical Maximum and swallowed the next item, so the report parsed to 44 bits
  instead of 104 (now `0x26`, with a parse-based regression test); and the re-pair warning was
  defeated because reports used the selected profile, not the host's (now a persisted
  `pairedProfile` drives both, cleared only by the new "I've re-paired the host" button). See
  "Review findings".
- The phone app is on `host_type=ANDROID` (so it derives COMPOSITE), `controller_mode=GAMEPAD`,
  `hid_enabled=true`, `paired_profile=COMPOSITE`.
- Both hosts have re-paired and decode the current composite descriptor.

## Host-driven HID profile (approach A, commit `3b24642`)

The app used to ask two independent questions — Android vs Windows, and a HID profile — so
"Android + Gamepad only" was reachable, and that combination is broken (see open item #1).
Now the host type picks the profile: `profileForHostType` → `ANDROID = COMPOSITE`,
`WINDOWS = GAMEPAD_ONLY`. `HidGamepadManager.loadStoredProfile()` derives from `host_type`;
the `hid_profile` pref is ignored (left in place on old installs, harmless). The Settings
"HID profile" picker is gone; changing the host type pushes the derived profile into the
manager and re-registers, and the re-pair warning still compares it with `paired_profile`.

Verified on the phone `<PHONE_SERIAL>` + tablet `<TABLET_SERIAL>` (2026-11-12):

- Settings no longer shows a "HID profile" row; it shows the derived descriptor in the host-type
  copy. Prefs showed `host_type=ANDROID` with a stale `hid_profile=GAMEPAD_ONLY`.
- Switching host type to Windows PC cleared the re-pair warning (derived GAMEPAD_ONLY == paired
  GAMEPAD_ONLY); switching back to Android showed it again (derived COMPOSITE != paired
  GAMEPAD_ONLY). So the warning follows the derivation, not a stored picker.
- The stale `hid_profile=GAMEPAD_ONLY` did **not** win: after re-pairing (tablet forgot the
  phone, phone made discoverable, paired again) the tablet's `dumpsys input` showed one merged
  node `Abhiskar's vivo Y29 Keyboard`, `Sources: KEYBOARD | DPAD | GAMEPAD | MOUSE | STYLUS |
  JOYSTICK`, X/Y/Z/RZ 8-bit joystick axes plus `BRAKE`/`GAS` — i.e. the **composite** descriptor.
  Before the re-pair the tablet still showed the cached GAMEPAD_ONLY layout (16-bit X/Y/RX/RY),
  which is the expected "host caches the descriptor" behaviour.
- Tapping **I've re-paired the host** cleared the warning and persisted `paired_profile=COMPOSITE`.

Note: this phone's ROM suppresses the app's `Log.i` output entirely (even after
`setprop log.tag.HidGamepad VERBOSE`), so logcat is not a usable evidence channel on it; the
tablet's `dumpsys input` is.

## Trigger fix: L2 / R2 are digital (commit `d7ca8a2`)

**Reported as:** "R2 not working".

**Root cause (measured, not inferred).** L2/R2 were *analog position-ramped* pedals, not
buttons. `pedalValue(yNorm)` mapped the vertical touch position inside the strip to 0..1
(ramp from 10% to 90%), and the "R2" label was drawn with `Alignment.Center` in that strip —
so the only visible affordance sat at exactly mid-travel. Measured on the tablet
(2026-11-12, `getevent` on the gamepad node):

| Touch on the R2 strip | `ABS_GAS` before the fix |
|---|---|
| y=180 (top) | 19 |
| **y=221 (the "R2" label)** | **128** |
| y=262 | 236 |
| y=275 (bottom) | 255 |

The host delivers the trigger as an **axis only** — `ABS_GAS`/`ABS_BRAKE`, mapped to
`AXIS_RTRIGGER`/`AXIS_LTRIGGER` by the tablet's `Generic.kl` — and **never** emits a key
event for it, so the value has to cross the game's own threshold. At 128/255 ≈ **0.47** a
typical `axis > 0.5` check fails, which is what "not working" looked like. Full travel needed
a touch in the bottom ~10% of the strip, which nothing on screen indicated.

**Why it regressed:** L2/R2 were binary buttons (`model.rt = 1f` → 255) until commit
`c1a1ece` ("feat(touchpad): buttonless gesture pad in mouse mode") replaced them with the
ramp. The checklist still documented "Triggers are binary (full pull)" and
"L2 / R2 → ABS_BRAKE / ABS_GAS, 0 → 255", so the code and the documented contract disagreed.

**Fix (user chose digital triggers).** `pedalValue` now takes the pressed state instead of the
touch position: any touch on the strip is a full pull, lifting is release. `PEDAL_RAMP_START`,
`PEDAL_RAMP_END` and the position mapping are deleted. The per-pointer map is kept, so
lifting one of two fingers on the same pedal does not drop the trigger while the other is
still down.

**Verified on the tablet after the fix** (`getevent`, clean capture):

| Press | `ABS_GAS` / `ABS_BRAKE` after the fix |
|---|---|
| R2 on the label | `ABS_GAS 255` → 0 on release |
| L2 on the label | `ABS_BRAKE 255` → 0 on release |
| R2 at the top of the strip | `ABS_GAS 255` → 0 on release |

The descriptor is unchanged, so no host needs to re-pair. Suite: **143 tests, 0 failures**.

## Verified on the tablet (composite profile)

Tablet `<TABLET_SERIAL>`, gamepad node `/dev/input/event8` ("vivo Y29"). `event6` = keyboard,
`event7` = mouse. `event8` carries `BTN_GAMEPAD BTN_EAST BTN_C BTN_NORTH BTN_WEST BTN_Z
BTN_TL BTN_TR BTN_TL2 BTN_TR2 BTN_SELECT BTN_START BTN_MODE BTN_THUMBL BTN_THUMBR`,
`ABS_X/Y/Z/RZ`, `ABS_GAS/BRAKE`, `ABS_HAT0X/Y`.

| On-screen control | Tablet keycode / axis |
|---|---|
| A / B / X / Y | `BTN_GAMEPAD` / `BTN_EAST` / `BTN_NORTH` / `BTN_WEST` |
| L1 / R1 | `BTN_TL` / `BTN_TR` |
| SEL / START | `BTN_SELECT` / `BTN_START` |
| D-pad N / E / S / W | `ABS_HAT0Y -1` / `ABS_HAT0X +1` / `ABS_HAT0Y +1` / `ABS_HAT0X -1` |
| Left stick | `ABS_X` |
| Right stick | `ABS_Z` + `ABS_RZ` |
| L2 / R2 | `ABS_BRAKE 255` / `ABS_GAS 255`, both return to 0 on release |

## Verified in the tablet's browser (composite profile, Chrome 157)

Chrome 157 on the tablet (`<TABLET_SERIAL>`) **does** expose the phone to web pages.

- The Gamepad API is gated behind a **real user gesture**. Before a genuine tap,
  `navigator.getGamepads()` returns 8 null slots (`count: 8, pads: []`). An `adb`-injected
  tap satisfies the gate; once it fires, `gamepadconnected` fires and the pad stays live.
- `gamepadconnected` payload: `id: "Abhiskar's vivo Y29 Keyboard (Vendor: 001d Product: 0000)"`,
  **`mapping: ""`**, `axes: 7`, `buttons: 17`.
- Unlike the PC, the tablet's browser maps the composite pad to a **near-W3C layout**
  (measured control-by-control, not inferred):

| Phone control | button index | axis |
|---|---|---|
| A / B / X / Y | 0 / 1 / 2 / 3 | |
| L1 / R1 | 4 / 5 | |
| L2 / R2 | 6 / 7 | 4 / 5 |
| SELECT / START | 8 / 9 | |
| D-pad N / S / W / E | 12 / 13 / 14 / 15 | |
| Left stick | | 0 (X), 1 (Y) |
| Right stick | | 2 (X), 3 (Y) |

- `mapping` is still `""`, so a game that hard-requires `mapping === "standard"` ignores the
  pad; games that read indices directly work.
- Recipe: `adb reverse tcp:8099 tcp:8099`, serve `gp-probe.html` on `127.0.0.1:8099`, open it
  in the tablet's Chrome, tap once, then read `gp-capture.txt`. The Android Chrome DevTools
  socket answers only on the **browser** endpoint with flattened `Target.attachToTarget`
  sessions; per-page sockets and session-scoped commands time out (see `cdp2.mjs`).

## Verified on the PC (composite profile, Chrome)

- Re-pair took effect: single `Bluetooth HID Device` node, no COL01–COL04 siblings;
  `DEVPKEY_Device_FirstInstallDate` = 2026-11-11 23:53:21.
- `navigator.getGamepads()` → one pad, `id: "HID Gamepad (Vendor: 001d Product: 0000)"`,
  16 buttons, 10 axes, **`mapping: ""`**.
- Button indices are the **raw Android positional order** (not the W3C order):
  A→0, B→1, X→3, Y→4, L1→6, R1→7, SEL→10, START→11. Unused: 2, 5, 8, 9, 12–15.
- Axes: left stick→`axes[0]`, right stick→`axes[2]`/`axes[5]`, L2→`axes[4]`, R2→`axes[3]`,
  d-pad→`axes[9]`. Held A sets `buttons[0]=1`.
- **Chrome freezes the Gamepad API in a hidden/unfocused tab** — every button read 0 until the
  tab was made visible. Fix used: CDP `Emulation.setFocusEmulationEnabled {enabled:true}`
  (see below). Any future measurement must do this or it will read all-zero.

## Open items, in priority order

1. **RESOLVED (2026-11-12) — the Android-host mismatch is gone by construction.** GAMEPAD_ONLY
   measured worse than COMPOSITE on the tablet's Chrome: `id` had no "Keyboard" suffix,
   `mapping: ""`, `axes: 6`, `buttons: 17`, and X and R1 produced no button index at all. The
   kernel maps those wire bits to `BTN_C`/`BTN_Z`, which Chrome drops (root cause below). Instead
   of reordering the buttons, the profile now follows the host: an Android host always gets
   COMPOSITE, so the broken "Android + Gamepad only" combination is unreachable. See
   "Host-driven HID profile" below.

   *Root cause, kept for the record:* the Android kernel's `hid-input` maps the 16 HID Button
   usages of a Gamepad collection positionally to Linux keycodes `BTN_A, BTN_B, BTN_C, BTN_X,
   BTN_Y, BTN_Z, BTN_TL, BTN_TR, …`. Chrome on Android builds its button list from those
   keycodes and has no entry for `BTN_C` or `BTN_Z`, so wire bit 2 (Button 3) and wire bit 5
   (Button 6) vanish. `w3cWireBit`'s reordering (X→bit 2, R1→bit 5) is what pushed buttons onto
   those two slots; COMPOSITE emits Android's positional order and maps cleanly.
2. **RESOLVED (2026-11-12) — same root cause as #1, addressed the same way.** `w3cWireBit` and
   the GAMEPAD_ONLY encoder are unchanged; they are simply no longer used on an Android host.
3. **MODE / L3 / R3 have no on-screen control**, so they cannot be pressed on any host.
   Their bits (12/13/14) are unit-test-only. Either add controls or accept the gap.
4. **Windows games still need XInput** (VID `045E`), which a Bluetooth HID pad can never be.
   Out of scope; needs Steam Input or ViGEm on the PC. User deferred.
5. **RESOLVED — the whole-branch review findings are recorded.** The review subagent
   (`ses_e3460ba5bffeM8tmO2DwSeGe97`) delivered after all; its findings are de-duplicated and
   ranked in "Review findings" below, with the blocker and the two high findings fixed.
6. **RESOLVED (2026-11-12) — the branch is merged.** `main` is at `1dafd3c`, a merge of
   `feat/touchpad-gestures` whose tree is identical to the branch tip. Main's four commits
   were duplicated history (the same three PC-host fixes plus the 2026-11-09 report), and
   main's copy of the report still carried the real device serials — folding the branch in
   redacted them. Still local only: nothing pushed.
7. **The trigger never reaches an Android host as a button.** `ABS_GAS`/`ABS_BRAKE` reach the
   host as axes (`AXIS_RTRIGGER`/`AXIS_LTRIGGER` via `Generic.kl`) and no key event is ever
   generated, so a game that reads the trigger strictly as `KEYCODE_BUTTON_R2` sees nothing.
   Fixing it means adding L2/R2 to the descriptor's button bits, which changes the frozen
   descriptor and forces a re-pair on every host. **Needs the tablet back to verify.**
8. **A real Windows *game* is still an unverified manual check**, and XInput games need a
   PC-side translator regardless. The Windows profile (GAMEPAD_ONLY) has never been exercised
   by a real host. **Needs the PC and the tablet back.**

## How to reproduce the measurements

Tooling used lives in `C:\Users\<user>\AppData\Local\Temp\opencode\` (`cdp.ps1`, `cdp2.ps1`,
`raise-chrome.ps1`, `pc-map.ps1`, `gamepad-server.ps1`, `gamepad-probe.html`). Temp may be
cleaned — the recipes below are what matter.

- adb: `C:\Users\<user>\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
  Phone `<PHONE_SERIAL>` (HID source, always pass `-s`), tablet `<TABLET_SERIAL>`.
- Phone BT MAC `<PHONE_MAC>`, PC MAC `<PC_MAC>`, tablet MAC `<TABLET_MAC>`.
- **Tablet capture:** `adb -s <TABLET_SERIAL> shell "rm -f /data/local/tmp/gp.txt; (getevent -lt /dev/input/event8 > /data/local/tmp/gp.txt 2>&1 &)"`,
  then press on the phone, then `cat /data/local/tmp/gp.txt`. `getevent -t` alone blocks;
  `cat /proc/bus/input/devices` is permission-denied.
- **Phone button press via adb:** `input swipe <x> <y> <x> <y> 800` (same coords = hold).
  Plain `input tap` samples after release and misses. Phone UI is landscape, 1608×720.
  A `1418,582`, B `1527,473`, X `1310,473`, Y `1419,364`, L1 `147,246`, R1 `1528,246`,
  SEL `785,240`, START `890,240`, d-pad N/E/S/W `265,361` / `385,481` / `265,601` / `145,481`,
  L-stick right `615,481`, R-stick up `1124,361`, L2 `258,305`, R2 `1417,305`.
- **Chrome probe:** Chrome with `--remote-debugging-port=9222`, page served at
  `http://127.0.0.1:8099/`. Connect over the CDP WebSocket, send `Page.bringToFront`, then
  `Emulation.setFocusEmulationEnabled {enabled:true}`, then
  `Runtime.evaluate` with `JSON.stringify(Array.from(navigator.getGamepads())...)`.
  Verify `document.visibilityState === "visible"` before trusting any reading.
- **Phone logcat:** `adb -s <PHONE_SERIAL> logcat -d -s HidGamepad:I`. A report looks like
  `sendReport id=3 payload=[01 00 00 00 0F 00 00 00 00] length=9 ... acceptedByAndroid=true`
  (composite, 9 bytes) or `length=13` (gamepad-only).
- **Prefs:** `adb -s <PHONE_SERIAL> shell "run-as com.hidgamepad cat /data/data/com.hidgamepad/shared_prefs/hid_gamepad.xml"`.

## Review findings

Two reviews fed this: my own read of `f6f8923..b3942e4`, and the whole-branch review subagent
(which delivered after all). De-duplicated and ranked. The subagent found the blocker; my
review missed it.

### Fixed in this session

- **blocker — `GamepadProfiles.kt:144` — the GAMEPAD_ONLY descriptor was unparseable.**
  It wrote `0x27, 0xFF, 0x7F` for Logical Maximum. HID short-item size code 3 means **four**
  data bytes, so the parser swallowed `FF 7F 75 10` and ate the following `Report Size (16)`
  item. The report parsed to 16+4+16+4+4 = **44 bits (5.5 bytes, not byte-aligned)** instead
  of 104 bits (13 bytes), so no host could parse the advertised 13-byte report.
  Fixed to `0x26, 0xFF, 0x7F` (2-byte size, matching the composite's convention at
  `GamepadState.kt:388`/`:400`).
- **high — the descriptor was never parsed by a test.** `GamepadProfileTest` only did
  substring checks (`contains("75 10")`), which cannot catch a short-item size error — which
  is why the blocker shipped. Added
  `the gamepad-only descriptor parses to exactly a thirteen byte report`, reusing the existing
  short-item walker (now `internal` in `GamepadStateTest.kt`). Red first:
  `expected:<104> but was:<44>`; green after the fix.
- **high — the re-pair warning was defeated and reports used the wrong profile.**
  `_pairedProfile` was overwritten with the selected profile on `STATE_CONNECTED`, and every
  `reportFor(...)` call encoded with the selected profile, so after a switch to GAMEPAD_ONLY
  the phone sent a 13-byte report to a host still decoding the 9-byte composite — same report
  ID, so the host mis-parsed it as garbage. Now a persisted `pairedProfile` is the profile the
  host decodes: reports follow it (`reportForHost`), the warning reflects it, and the connect
  callback no longer touches it. The new `confirmRepaired()` action is the only thing that
  switches it, backed by the **"I've re-paired the host"** button in Settings. Verified on the
  phone: selecting GAMEPAD_ONLY logged `HID profile changed to GAMEPAD_ONLY - re-registering`
  and showed "Re-pair needed: the host is still using the Keyboard + mouse + gamepad
  descriptor"; tapping the button logged `user confirmed a re-pair; reports now follow
  GAMEPAD_ONLY` and persisted `paired_profile=GAMEPAD_ONLY`. The reverse direction behaves the
  same. Commit `71b299c`.
- **high — the `hidProfile` setter never re-registered.** It only logged, and `registerApp()`
  early-returns while registered, so a live change never published the new descriptor. The
  setter now unregisters and re-registers (the unregister callback triggers the re-register,
  with a timed fallback), matching plan lines 449/488/548. Verified in the same logcat run:
  `onAppStatusChanged registered=false` immediately followed by `registerApp requested`.

### Master HID switch (new, commit `2b6ad95`)

- **The switch itself.** Settings now opens with `HID controller: ON/OFF` and a `Switch`.
  Off: `manager.releaseAllInputs()` → `manager.stop()` → `stopService(...)`; the service's
  `onDestroy` removes the notification. On: `startForegroundService` + `manager.start()`.
  Persisted as `hid_enabled` (default on); `onResume` and every `ensureHidService()` path are
  gated on `shouldRunHidService(hidEnabled, permissionsGranted)`, so a phone left off never
  reactivates on restart.
- **Bug found and fixed while verifying — the switch turned itself back on.** `stop()` closes
  the profile proxy, which fires `onServiceDisconnected`, whose 3 s self-heal then re-fetched
  the proxy and re-registered the HID device — the log showed `registerApp requested` three
  seconds after `HID switch OFF`. Fixed with a `stopped` flag consulted through the new
  `shouldSelfHealProxy(stopped, bluetoothEnabled)` predicate; the 8 s post-off logcat run now
  shows no re-registration.
- **Notification timing.** `HidControllerService` no longer calls `startForeground` in
  `onCreate`. It shows the notification from a new `HidGamepadManager.onRegistrationChanged`
  callback when `registered=true`. The platform kills a service that misses the ~5 s
  `startForeground` deadline, so a 4 s fallback is armed; the normal path wins
  (`service: HID registered — showing the persistent notification`, 44 ms after
  `onAppStatusChanged registered=true`).
- **Manager instance kept across off/on.** `HidRuntime.stop()` used to null the manager; that
  left the activity's `collectAsState` bound to a dead manager's flows after an off→on cycle.
  It now stops the manager but keeps the instance (the manager is fully restartable).

Verified on the phone `<PHONE_SERIAL>`: launch with `hid_enabled=false` starts no service and
no notification; the top bar reads "HID off — turn the switch on to enable"; toggling on
registers and notifies; toggling off releases, unregisters and removes the notification; a
force-stop + relaunch with the pref false starts nothing. Bluetooth stayed on
(`settings get global bluetooth_on` = 1) with the same bonded devices, and
`hid_profile`/`paired_profile`/`controller_mode` were untouched.

Suite is now **143 tests, 0 failures**.

### Open — ranked

1. **RESOLVED — GAMEPAD_ONLY is no longer used on an Android/Linux host.** The wire order
   (`GamepadProfiles.kt` `w3cWireBit` maps X→2, Y→3, L1→4, R1→5, SEL→8, START→9) is still
   W3C order, and the kernel still maps those positions to `BTN_C, BTN_NORTH, BTN_WEST, BTN_Z,
   BTN_TL2, BTN_TR2` — but the profile now goes only to Windows hosts, and the Windows host
   reads buttons by declaration order. *Still true and undocumented:* `w3cWireBit` returns -1
   for unmapped canonical bits and the encoder drops them silently, so the two profiles' button
   sets differ with no documentation.
2. **medium — MODE at wire bit 12 collides with the W3C D-pad slot.** In the W3C standard
   layout 12–15 are the D-pad and 16 is Guide/MODE, and the descriptor declares only 16
   buttons, so there is no index 16. The D-pad is also a hat, not buttons 12–15. Consequence:
   GAMEPAD_ONLY can never be `mapping: "standard"`. Fix: declare 17 buttons and put MODE at
   16, or stop calling the profile W3C-standard. **Needs a descriptor change, hence a re-pair
   on every host.**
3. **medium — GAMEPAD_ONLY has never been exercised by a real host.** The PC measurement was
   taken on the composite (10 axes = 6 gamepad + 4 mouse). Before claiming the profile works,
   select the Windows host type, re-pair the PC, and check `axes.length == 6` and
   `buttons.length == 16`. **Needs the PC and the tablet back.**
4. **RESOLVED (doc) — `HAT_NEUTRAL` contradicts the spec.** The code ships 15
   (`GamepadState.kt:30`), justified by the kernel clamping an out-of-range hat to centred
   — sound for Linux/Android, but the spec's own table (`spec:162`) says "8 = centred", and 8
   is the HID null value for a Null-State hat with Logical Max 7. The deviation is now
   **recorded in that spec table** rather than changed in code, because writing 8 changes a
   byte the host sees and the Windows host — which now gets this profile — has never been
   exercised.
5. **RESOLVED (the finding was wrong) — `GAMEPAD_ONLY_REPORT_SIZE` is not dead code.** It is
   the size contract used by the descriptor-parse regression test
   (`GAMEPAD_ONLY_REPORT_SIZE * 8` == the parsed bit count), which is the test that catches the
   `0x27` blocker. The encoder's size is now tied to it in the test as well, so the two cannot
   drift apart.
6. **RESOLVED — doc nits.** `REPORT.md` now says 143 tests and carries a dated 2026-11-12
   section; `docs/hid-gamepad-manual-test-checklist.md` no longer claims "16 buttons, 6 axes"
   for the measured pad (the measured composite pad had 10 axes; 6 is the unverified
   gamepad-only target).

### Not defects, for the record

- `axisWord`'s `and 0xFFFF` on a negative `Int` yields the right two's-complement bytes.
- The 100 ms heartbeat resending only the gamepad report is correct — a relative mouse report
  must never be replayed.
- `subclassFor` is correct: `SUBCLASS1_NONE = 0`, `SUBCLASS2_GAMEPAD = 2`.

### Not yet reviewed

- `GamepadScreen.kt` gesture/d-pad wiring beyond the settings row, and `Joystick.kt` hat
  emission (0/2/4/6 + odd diagonals) against the descriptor's declared 0–7 range.
- Test coverage for the manager's setter re-register behaviour (the pure policy is covered;
  the Android-coupled unregister/re-register path is verified on-device only).
- Palm/large-contact filtering in `TouchpadGestures.kt` (only the `maxPoints == 3` guard at
  lines 235 and 274) — identified, not written up.
