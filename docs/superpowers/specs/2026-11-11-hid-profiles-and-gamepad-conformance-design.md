# HID Profile Selection and Gamepad Host Conformance — Design

Date: 2026-11-11
Status: approved by user (questions answered 2026-11-11)

## Problem

The phone registers one frozen composite HID descriptor (report ID 1 keyboard, 2 mouse,
3 gamepad, 4 consumer). Hosts enumerate the device, but games do not respond to it.

## Measured facts (2026-11-11, not inference)

Measured on the actual hosts.

1. **Windows sees the phone correctly.** `Get-PnpDevice` shows the composite splitting into
   five HID nodes: `HID Keyboard Device`, `HID-compliant mouse`, `HID-compliant touch pad`,
   `HID-compliant consumer control device`, `HID-compliant game controller`.

2. **Chrome's Gamepad API sees zero gamepads.** `navigator.getGamepads()` returned
   `[null,null,null,null,null,null,null,null]` while the phone was connected
   (`connState=2`) and streaming gamepad reports (`sendReport id=3 ... acceptedByAndroid=true`).
   Browser games therefore have nothing to read.

3. **The tablet currently enumerates no HID device from the phone at all.**
   `/dev/input/` contains only event0–event5 (its own hardware). The tablet bonded the phone
   (`<PHONE_MAC>`) but its HID host link is down. The tablet holds the descriptor cached
   from the earlier pairing, so `SUBCLASS1_NONE` (`0fb3cbc`) cannot take effect until a
   re-pair.

4. **The tablet's `Generic.kl` gives the exact Android button mapping.** Read directly from
   `/system/usr/keylayout/Generic.kl`:

   ```
   304 BUTTON_A      305 BUTTON_B      306 BUTTON_C      307 BUTTON_X
   308 BUTTON_Y      309 BUTTON_Z      310 BUTTON_L1     311 BUTTON_R1
   312 BUTTON_L2     313 BUTTON_R2     314 BUTTON_SELECT 315 BUTTON_START
   316 BUTTON_MODE   317 BUTTON_THUMBL 318 BUTTON_THUMBR
   ```

   Linux `hid-input` maps gamepad button N (1-based) to `BTN_GAMEPAD + N - 1` = 303 + N.
   Therefore the Android-visible bit index for each button is `code - 304`:

   | Button | Android keycode | Required report bit | Current `Btn` constant |
   |---|---|---|---|
   | A | BUTTON_A | 0 | 0 (correct) |
   | B | BUTTON_B | 1 | 1 (correct) |
   | X | BUTTON_X | 3 | 3 (correct) |
   | Y | BUTTON_Y | 4 | 4 (correct) |
   | L1 | BUTTON_L1 | 6 | 6 (correct) |
   | R1 | BUTTON_R1 | 7 | 7 (correct) |
   | SELECT | BUTTON_SELECT | 10 | 10 (correct) |
   | START | BUTTON_START | 11 | 11 (correct) |
   | MODE | BUTTON_MODE | 12 | **13 — wrong** |
   | L3 | BUTTON_THUMBL | 13 | **14 — wrong** |
   | R3 | BUTTON_THUMBR | 14 | **12 — wrong** |

   MODE, L3 and R3 are permuted. A MODE press reaches the tablet as L3, L3 as R3, R3 as MODE.

5. **The tablet's axis mapping confirms the composite axes are already correct.**
   `Generic.kl` maps `axis 0x00 X`, `0x01 Y`, `0x02 Z`, `0x05 RZ`, `0x09 RTRIGGER`,
   `0x0a LTRIGGER`, `0x10 HAT_X`, `0x11 HAT_Y`. The kernel maps Generic Desktop X/Y/Z/Rz to
   ABS_X/ABS_Y/ABS_Z/ABS_RZ and Simulation Brake/Accelerator to ABS_BRAKE/ABS_GAS, so the
   composite collection already lands on Android's AXIS_X/AXIS_Y (left stick),
   AXIS_Z/AXIS_RZ (right stick), AXIS_LTRIGGER/AXIS_RTRIGGER (triggers) and AXIS_HAT_X/Y (d-pad).
   **No axis change is needed for Android.** (An earlier claim that the triggers were on the
   wrong usage page was wrong; the measurement refutes it.)

6. **The hat's centred value is suspect.** The descriptor declares the hat with Logical
   Minimum 0, Logical Maximum 7 and the Null State flag (`0x81, 0x42`). Under HID, the null
   value is Logical Maximum + 1 = 8, but the encoder writes `HAT_NEUTRAL = 15` — outside the
   declared range. A host may read the d-pad as permanently deflected. The fix is a value
   change only: write 8 for centred, keeping the descriptor byte-identical. Verified on the
   tablet in Task 5.

## Why Chrome sees nothing

Chrome enumerates gamepads through DirectInput and applies the W3C standard layout, which
assumes a *dedicated* gamepad whose first buttons are A, B, X, Y and whose first axes are
LX, LY, RX, RY, LT, RT. Our composite puts the keyboard first, the mouse second and the
gamepad third, with the gamepad's own buttons in Android's positional order (A, B, _C, X, Y,
_Z_, L1, R1, _L2_, _R2_, Select, Start, ...). Both the collection order and the button order
are wrong for Chrome, so no standard layout can be derived and no gamepad is exposed.

Two independent reference implementations in this project's own research
(`docs/LEANREA_BLUETOOTH_HID_RESEARCH.md`) ship exactly this remedy: the Sensat10n/Gamepad
repo has a "ID 1 gamepad-only" descriptor and GKME exposes a "GAMEPAD_ONLY" descriptor mode
selected at `registerApp` time, versus its "combo" modes.

## Measured facts (continued, after the profile shipped)

7. **Chrome now enumerates the phone as a gamepad.** With the `GAMEPAD_ONLY` profile
   registered and the host connected, `navigator.getGamepads()` returns
   `{id: "HID Gamepad (Vendor: 001d Product: 0000)", connected: true, buttons: 16}` — where
   before the change every slot was `null`. The browser path is fixed.

8. **The host still decodes the OLD descriptor until it re-pairs.** The Windows device node
   is `HID\{00001124-...-COL03}\8&1623005&1&0002` with siblings `COL01` (Keyboard),
   `COL02` (Mouse), `COL03` (Game controller) and `COL04` (Consumer control) — four
   top-level collections, which is the *composite* descriptor. The gamepad-only descriptor
   has exactly one collection, so a re-parsed node would have no COL01/COL02/COL04 siblings.
   Meanwhile the PC's SDP cache (`BTHPORT\...\Devices\6C40E8EB8FE0\CachedServices\0001000F`)
   already contains the gamepad-only descriptor bytes, so the SDP record updated and the
   *parsed* device node did not.

   Consequence: the phone sends the new 13-byte report, but the host parses it with the old
   9-byte layout, so the axes decode to nonsense and a button press does not land on the
   button the host expects. **A re-pair on the host is mandatory** — this is the frozen
   descriptor's cost, and the app says so in Settings.

   Measured proof the phone side is correct: the logcat shows
   `sendReport id=3 payload=[01 00 00 00 00 00 00 00 00 00 00 00 0F] length=13` on an A-button
   press — button bit 0 set, then released. The app sends what it should; the host is
   reading it with a stale descriptor.

   Further proof that the host, not the app, holds the old descriptor: the HID node's
   `DEVPKEY_Device_FirstInstallDate` is **2026-11-10 19:35:08**, hours before the profile
   existed. Windows parsed the report descriptor once, when the device was paired, and has
   reused that parse ever since. A freshly launched Chrome still reports the gamepad with
   **10 axes** — the old composite's axis set (6 gamepad axes + the mouse collection's
   axes) — even though the gamepad-only descriptor declares 6. Chrome is reading the old
   composite descriptor, and the phone's 13-byte report does not match it.

   Consequence for the tablet: the same cache rule applies, and its HID host link to the
   phone does not form at all (`HID_HOST` connection policy 100, no `/dev/input` node for
   the phone). It needs the same remove-and-pair-again.

## Decision

Ship **two registration profiles**, chosen by the user at pairing time:

- `COMPOSITE` — the existing 4-collection descriptor, with the MODE/L3/R3 bit order fixed.
  This is the Android/tablet profile: keyboard, mouse, gamepad and consumer over one link.
- `GAMEPAD_ONLY` — a dedicated gamepad collection whose buttons and axes follow the W3C
  standard layout, so Chrome and DirectInput hosts expose a real gamepad.

The profile is fixed at pairing time because the host caches the descriptor. Switching
profiles requires a re-pair. The app states this in the UI rather than pretending a
mode switch is free.

### Canonical button semantics vs wire bits

The app keeps one canonical button mask (`Btn` constants, Android positional order). Each
profile maps canonical bits to its own wire order at encode time, so the on-screen controls
never change meaning and only the encoder differs per profile.

- Canonical (Android) bit order: A=0, B=1, X=3, Y=4, L1=6, R1=7, SELECT=10, START=11,
  MODE=12, L3=13, R3=14.
- `GAMEPAD_ONLY` wire order (W3C standard layout): A=0, B=1, X=2, Y=3, L1=4, R1=5,
  SELECT=8, START=9, L3=10, R3=11, MODE=12. A bit permutation at encode time.

### GAMEPAD_ONLY report layout (13 bytes)

| Bytes | Content | Usage | Range |
|---|---|---|---|
| 0–1 | 16 buttons, W3C standard order | Button page | bit per button |
| 2–3 | LX | Generic Desktop X | signed 16-bit |
| 4–5 | LY | Generic Desktop Y | signed 16-bit |
| 6–7 | RX | Generic Desktop Rx | signed 16-bit |
| 8–9 | RY | Generic Desktop Ry | signed 16-bit |
| 10 | LT | Generic Desktop Z (Chrome maps ABS_Z to the left trigger) | 0–255 |
| 11 | RT | Generic Desktop Rz (Chrome maps ABS_RZ to the right trigger) | 0–255 |
| 12 | hat switch, low nibble | Hat switch | 0–7, 8 = centred |

Axis order X, Y, Rx, Ry, Z, Rz puts the six axes at indices 0–5, which is what the W3C
standard layout expects.

## Non-goals

- XInput compatibility for Valorant. A Bluetooth HID peripheral cannot be an XInput device;
  that needs a PC-side translator and the user chose to defer it.
- Rumble, force feedback, and host-controlled output reports.
- Changing the composite descriptor's axis assignments (measurement says they are correct).

## Constraints

- No new Gradle dependencies (JUnit 4 only). minSdk 28, compileSdk 37, JVM 21.
- No new Android permissions.
- The descriptor is still frozen per profile: a profile change requires a re-pair, and the UI
  must say so.
- Commit locally only, never push.

## Verification

Two measurements decide success, both on real hosts:

- **Windows/Chrome:** `navigator.getGamepads()` must return a pad whose `id` is non-empty,
  whose `axes.length` is 6 and whose `buttons.length` is 16, while a phone button press
  changes `buttons[n]`.
- **Android tablet:** after a re-pair, `getevent` on the tablet must show the phone's input
  node, and each app button must produce the keycode the table in "Measured facts" demands —
  in particular MODE must produce `BUTTON_MODE` (316), not `BUTTON_THUMBL`.

### Status as of 2026-11-11

| Check | Result | Evidence |
|---|---|---|
| Chrome enumerates a gamepad | PASS | `navigator.getGamepads()` → `{id: "HID Gamepad (Vendor: 001d Product: 0000)", connected: true, buttons: 16}`; before the change every slot was `null`. |
| Phone sends the right bytes | PASS | logcat `sendReport id=3 payload=[01 00 ... 0F] length=13` on an A press, then `[00 00 ...]` on release. |
| Host decodes the new descriptor | PASS | The tablet and the PC both decode the current descriptor. The PC was re-paired (`DEVPKEY_Device_FirstInstallDate` 2026-11-11 23:53:21) and now exposes a single `Bluetooth HID Device` node — the old COL01–COL04 siblings are gone. |
| PC button indices (Chrome) | PASS, mapping `""` | A held A press sets `buttons[0]=1`. Indices are the raw Android positional order: A→0, B→1, X→3, Y→4, L1→6, R1→7, SEL→10, START→11; L-stick→`axes[0]`, R-stick→`axes[2]`/`axes[5]`, L2→`axes[4]`, R2→`axes[3]`, d-pad→`axes[9]`. `mapping` is `""` (non-standard), so a game that demands the W3C standard layout still needs the GAMEPAD_ONLY profile. |
| Tablet enumerates the phone | PASS | After the tablet re-paired, `/dev/input/event8` "vivo Y29" carries the gamepad buttons, `ABS_X/Y/Z/RZ`, `ABS_GAS/BRAKE` and `ABS_HAT0X/Y`; `event6`/`event7` are the keyboard and mouse. |
| Button keycodes on the tablet | PASS | A→`BTN_GAMEPAD`, B→`BTN_EAST`, X→`BTN_NORTH`, Y→`BTN_WEST`, L1→`BTN_TL`, R1→`BTN_TR`, SEL→`BTN_SELECT`, START→`BTN_START`; d-pad → `ABS_HAT0X/Y`; sticks → `ABS_X`/`ABS_Z`/`ABS_RZ`; L2→`ABS_BRAKE 255`, R2→`ABS_GAS 255`. |
| MODE/L3/R3 bit order | FIXED, no UI control | Correct per the tablet's `Generic.kl` and the kernel's `hid-input` mapping. The gamepad screen exposes no MODE/L3/R3 button, so the mapping cannot be pressed on a host; unit tests cover the bits. |
