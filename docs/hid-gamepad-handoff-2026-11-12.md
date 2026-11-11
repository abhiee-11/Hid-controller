# HID gamepad — continuation handoff (2026-11-12)

Pick this up tomorrow. Everything below is measured, not inferred, unless marked
"open". The tree is clean and committed; nothing is lost if the PC was shut down.

## Where things stand

- Branch `feat/touchpad-gestures`, local only (never pushed), not merged.
- Build + tests: `.\gradlew.bat --console=plain testDebugUnitTest assembleDebug`
  → **135 tests, 0 failures**.
- **A blocker found in review was fixed at the end of the session:** the GAMEPAD_ONLY
  descriptor wrote `0x27` (a 4-byte HID item) for Logical Maximum and swallowed the next item,
  so the report parsed to 44 bits instead of 104. Now `0x26`, with a parse-based regression
  test. See "Review findings".
- The phone app is on `hid_profile=COMPOSITE`, `controller_mode=GAMEPAD`.
- Both hosts have re-paired and decode the current composite descriptor.

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

1. **GAMEPAD_ONLY is still not host-verified.** It was only seen by Chrome *before* the PC
   re-pair. To get a pad with `mapping: "standard"`, set the phone to **Gamepad only**, then
   re-pair the PC again, then re-read `navigator.getGamepads()`.
2. **Latent mismatch in GAMEPAD_ONLY on Android/Linux hosts.** `w3cWireBit` in
   `GamepadProfiles.kt` maps canonical → W3C order (X→wire 2, Y→wire 3, L1→4, R1→5,
   SEL→8, START→9). Browsers index the report positionally, so this is right for a browser,
   but the Linux/Android kernel maps the same positions to `BTN_C`, `BTN_NORTH`, `BTN_WEST`,
   `BTN_Z`, `BTN_TL2`, `BTN_TR2` — i.e. wrong for an Android host. Either restrict
   GAMEPAD_ONLY to browser/DirectInput hosts in the UI, or emit the canonical order and rely
   on the browser heuristic. Not decided.
3. **MODE / L3 / R3 have no on-screen control**, so they cannot be pressed on any host.
   Their bits (12/13/14) are unit-test-only. Either add controls or accept the gap.
4. **Windows games still need XInput** (VID `045E`), which a Bluetooth HID pad can never be.
   Out of scope; needs Steam Input or ViGEm on the PC. User deferred.
5. **Whole-branch review findings** — the review subagent
   (`ses_e3460ba5bffeM8tmO2DwSeGe97`) was still running at shutdown. If it returned nothing,
   re-run the review of `f6f8923..b3942e4`.
6. `feat/touchpad-gestures` is still unmerged; the user deferred that decision.

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
  `expected:<104> but was:<44>`; green after the fix. Suite is now **135 tests, 0 failures**.

### Open — ranked

1. **high — the re-pair warning is defeated, and reports use the wrong profile.**
   `HidGamepadManager.kt:358` sets `_pairedProfile.value = hidProfile` on `STATE_CONNECTED`,
   but the descriptor in force is the one captured at the last `registerApp()`
   (`:402-403`), and a profile switch does not re-register. So after a switch the app claims
   the host understands the new profile and hides the warning while the host still holds the
   old descriptor. Worse, `reportFor(hidProfile, …)` at `:503`, `:610` and `:630` then encodes
   a 13-byte gamepad-only report for a host that registered the 9-byte composite — same report
   ID, so the host mis-parses it as garbage rather than rejecting it.
   Fix: record the profile used at register time, expose *that* to the warning, and encode with
   it. `_pairedProfile` is also never updated on the `setActiveHost`/`refreshConnected` path
   and is initialised to `COMPOSITE` regardless of the stored profile.
2. **high — the `hidProfile` setter never re-registers.** `HidGamepadManager.kt:80-89` only
   logs. The plan required the setter to re-register when the value changes while registered
   (plan lines 449/488/548), and `registerApp()` early-returns while `appRegistered` is true,
   so a live profile change never publishes the new descriptor. Either implement the
   unregister/re-register or change the plan and the UI text to say a restart is required.
3. **medium — GAMEPAD_ONLY wire order is wrong on Android/Linux hosts.**
   `GamepadProfiles.kt:27-40` (`w3cWireBit`) maps X→2, Y→3, L1→4, R1→5, SEL→8, START→9. That
   is right for a browser (positional index), but the kernel maps the same positions to
   `BTN_C, BTN_NORTH, BTN_WEST, BTN_Z, BTN_TL2, BTN_TR2`. Measured proof the kernel is
   positional: the composite (canonical order) produced the correct keycodes for all eight
   buttons on the tablet. Fix: emit canonical order, or restrict the profile to browser hosts.
   Related: `w3cWireBit` returns -1 for unmapped canonical bits and the encoder drops them
   silently — the two profiles' button sets differ with no documentation.
4. **medium — MODE at wire bit 12 collides with the W3C D-pad slot.** In the W3C standard
   layout 12–15 are the D-pad and 16 is Guide/MODE, and the descriptor declares only 16
   buttons, so there is no index 16. The D-pad is also a hat, not buttons 12–15. Consequence:
   GAMEPAD_ONLY can never be `mapping: "standard"`. Fix: declare 17 buttons and put MODE at
   16, or stop calling the profile W3C-standard.
5. **medium — GAMEPAD_ONLY has never been exercised by a real host.** The PC measurement was
   taken on the composite (10 axes = 6 gamepad + 4 mouse). Before claiming the profile works,
   select it, re-pair the PC, and check `axes.length == 6` and `buttons.length == 16`.
6. **medium — `HAT_NEUTRAL` contradicts the spec.** Code ships 15 (`GamepadState.kt:30`,
   `GamepadProfiles.kt:100`), justified by the kernel clamping an out-of-range hat to centred
   — sound for Linux/Android, but the spec's own table (`spec:162`) says "8 = centred", and 8
   is the HID null value for a Null-State hat with Logical Max 7. Write 8 for the gamepad-only
   profile or record the deviation in the spec table.
7. **low — `GAMEPAD_ONLY_REPORT_SIZE` is dead code** (`GamepadProfiles.kt:21`): declared, never
   used, while the encoder hardcodes 13. Use it, or delete it.
8. **low — doc nits.** `REPORT.md` said 133 tests (now 135);
   `docs/hid-gamepad-manual-test-checklist.md` claims PASS for "one pad, 16 buttons, 6 axes"
   but the measured pad had **10** axes — 6 is the unverified gamepad-only target.

### Not defects, for the record

- `axisWord`'s `and 0xFFFF` on a negative `Int` yields the right two's-complement bytes.
- The 100 ms heartbeat resending only the gamepad report is correct — a relative mouse report
  must never be replayed.
- `subclassFor` is correct: `SUBCLASS1_NONE = 0`, `SUBCLASS2_GAMEPAD = 2`.

### Not yet reviewed

- `GamepadScreen.kt` gesture/d-pad wiring beyond the settings row, and `Joystick.kt` hat
  emission (0/2/4/6 + odd diagonals) against the descriptor's declared 0–7 range.
- Test coverage for the setter re-register behaviour and for `reportFor`/`descriptorFor`
  switching mid-session.
- Palm/large-contact filtering in `TouchpadGestures.kt` (only the `maxPoints == 3` guard at
  lines 235 and 274) — identified, not written up.
