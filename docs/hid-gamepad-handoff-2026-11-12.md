# HID gamepad — continuation handoff (2026-11-12)

Pick this up tomorrow. Everything below is measured, not inferred, unless marked
"open". The tree is clean and committed; nothing is lost if the PC was shut down.

## Where things stand

- Branch `feat/touchpad-gestures`, head `b3942e4`, local only (never pushed), not merged.
- Build + tests: `.\gradlew.bat --console=plain testDebugUnitTest assembleDebug`
  → **134 tests, 0 failures**.
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

_To be filled in from the whole-branch review subagent before/after shutdown._
