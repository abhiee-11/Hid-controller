# Design: a "WASD" controller mode (keyboard keys in a gamepad shape)

Date: 2026-11-13
Status: proposed

## Intent

Some games on the host read only the keyboard: `W A S D` to move, `Space` to jump, `Shift`
to sprint, `E`/`R`/`F`/`Q` for actions. The app's Gamepad page cannot drive them — it sends a
gamepad report — and the Keyboard page is a full QWERTY board, not a controller.

The user wants a **fourth mode, alongside Gamepad / Mouse / Keyboard**: a page shaped like a
controller whose buttons *are keyboard keys*. The phone shows a controller; the host sees a
keyboard.

## What already exists, and what it means

- `GamepadScreen` already owns the keyboard state (`keyboardKeys`, `keyboardModifiers`) and
  fires a keyboard report (`KEYBOARD_REPORT_ID = 1`) through `LaunchedEffect(keyboardState)`,
  regardless of mode. Today only the Keyboard page writes to that state.
- The keyboard collection is part of the **composite** descriptor, which is what the user's PC
  is currently paired with (see `2026-11-12-keyboard-function-row-design.md`).

**Therefore no descriptor byte changes and no re-pairing are needed.** This mode only reuses
the existing keyboard report; it is new UI over plumbing that is already live.

## Goals

- A fourth `ControllerMode`, labelled **WASD**, in the mode dropdown and the Settings mode list.
- A controller-shaped layout of keyboard keys, **no analog stick**.
- Left thumb: `W A S D` as a cross. Right thumb: the action keys a game needs.
- Presses send keyboard reports; multi-touch so keys combine (`W` + `Shift` while `Space`).
- Haptics on press, matching the other modes.

## Non-goals

- **No new HID descriptor and no re-pair.** This mode works exactly where the Keyboard page
  works today.
- **No gamepad report** from this mode; nothing on the wire changes.
- **No analog input** and no tilt; keyboard games are digital.
- No change to the frozen descriptors, report formats, button bits, keyboard usages, connection
  management, heartbeat or re-pair rule.
- No RGB backlight in this mode (keep it simple; the Keyboard page owns the backlight).

## Anti-goals

- Must not disturb Gamepad / Mouse / Keyboard. Those pages and their reports stay exactly as
  they are.
- Must not add a mode that looks like a gamepad but sends nothing usable.

## Approaches considered

**A. A new mode in the dropdown (chosen).** Additive: the analog gamepad stays intact, and the
mode dropdown and Settings mode list already iterate `ControllerMode.entries`, so the new entry
appears with no extra wiring.

**B. A switch on the Gamepad page that swaps it to a key layout.** Rejected: it removes the
analog sticks whenever it is on, and the user asked for a separate device, "like mouse,
keyboard, gamepad".

**C. A key strip added to the Gamepad page.** Rejected: the user explicitly does not want the
stick alongside it — "no stick, only WASD and other controls a game needs".

## The design

### Mode

`ControllerMode` gains `WASD`. `modeLabel` returns `"WASD"`. Because the dropdown
(`GamepadScreen`) and the Settings mode list iterate `ControllerMode.entries`, both pick it up
automatically. `switchMode` already releases all inputs and resets local state on change.

### Layout

A controller shape, keys only:

```
  [Tab]            [Enter]            [Esc]
  [Shift]                             [Ctrl]
     [W]                    [E] [R]
  [A][S][D]      [  Space  ]  [Q] [F]
```

- **Left thumb:** `W` above, `A` `S` `D` below, in a d-pad-shaped cross, sized like the D-pad
  so it is thumb-reachable in landscape.
- **Right thumb:** `Space` as a wide bar, with `E R Q F` around it and `Shift`/`Ctrl` on the
  right shoulder row.
- **Centre:** `Enter` (chat / confirm). **Shoulders:** `Tab` (left), `Esc` (right).

Every key is a press-and-hold key that reports its usage while held, using the same
`onKey`/`onModifier` handlers the Keyboard page uses.

**Builder call (changeable):** this exact key set. It is the common FPS/TPS set; each key is
one usage, so trimming or adding (`1..4`, `C`, `V`, `Tab`) later is a small edit.

### Rollover

The boot keyboard report carries six non-modifier keys plus the modifier byte, so
`W A S D` + `Space` + one more is the ceiling; `Shift`/`Ctrl` are modifiers and do not count
against it. That covers the intended combinations. (The same 6-key limit applies to the
Keyboard page.)

### Where it appears

- Mode dropdown and Settings mode list: automatic via `ControllerMode.entries`.
- **Builder call:** not added to the first-run onboarding MODE step, to keep first-run to the
  three primary modes. Reachable from the dropdown afterwards.

## Constraints

- Descriptor and reports are frozen; this mode sends only the existing keyboard report.
- Works only on a host paired with the composite descriptor (Android host, or a PC paired
  before the profile split) — the same constraint the Keyboard page already has. A Windows host
  re-paired to gamepad-only would not receive it; that is the existing "open problem", not a
  new one.

## What is left to the builder

- The exact key set and their sizes/positions.
- Whether the WASD mode gets a one-line hint like the Mouse page has.
- Tests: the mode's presence in the enum/labels, and that a key press produces the expected
  keyboard usage (pure logic, no device).
