# 4-zone RGB backlight on the on-screen keyboard - design

**Date:** 2026-11-12

## Intent

The on-screen keyboard gets a per-key RGB backlight, like a gaming laptop or gaming
mechanical keyboard: every key is its own light, and the effects are the ones found in the
major RGB control suites.

## Why

The user asked for per-key RGB ("every key has its own rgb light") with as many effects as
can be found in gaming laptops and keyboards, plus laptop Fn-style shortcuts: Fn+L toggles
the backlight, Fn+Up/Down step its brightness.

## Goals

- Per-key backlight: every key is an independent light.
- Settings section "RGB backlight" at the end of the panel:
  - on/off switch,
  - effect: 16 effects (Static, Breathing, Color cycle, Rainbow, Wave, Reactive, Ripple,
    Aurora, Flicker, Starlight, Comet, Fire, Meteor, Snake, Scanner, Arrows),
  - flow: left to right / right to left,
  - speed slider (0.25x .. 2x),
  - brightness slider (10% .. 100%),
  - colour presets for the non-spectral effects.
- Fn+L toggles the backlight; Fn+Up/Down set the brightness. Consumed, like a laptop -
  these combos never reach the host.
- Off by default; the keyboard renders exactly as it did before.
- One self-contained feature: new file `RgbBacklight.kt`, one added parameter on
  `KeyboardKeyButton`, a Settings section. Removing the feature is one `git revert`.

## Look

- Keys are near-black caps with no outline.
- The legend of each key is drawn in its light colour, like the printed legends on a
  backlit keyboard.
- A soft glow spills around each key into the gaps, like light leaking from under the
  keycaps; the keyboard deck goes near-black so the glow stands out.
- Pressed keys sink slightly (2 dp) instead of shrinking.

## Non-goals

- No change to HID reports, descriptors, gestures, connection handling or state.
- No host-side RGB control: no standard HID output report exists for RGB, and this is a
  phone, not a PC keyboard. The backlight is phone-local and visual only.
- No custom per-key colour editor (presets and effects only).
- No effect on the gamepad, mouse or keyboard layout beyond the backlight rendering.

## Constraints

- Landscape only; dp sizes, no hard-coded pixels.
- `PadColors` holds shared palette values; the RGB colours live in `RgbBacklight.kt`.
- No device serials, host names, MACs or the ntfy topic in tracked files.

## Behaviour

- Every key gets a normalised position (x, y) in the grid, computed from its row and its
  horizontal weight; effects are pure functions of that position and the clock.
- Pressed keys feed a short-lived flash map, used by Reactive and Ripple.
- The grid is clocked at ~12 fps only while the backlight is on; when off, nothing animates
  and the rendering is unchanged from before this feature.
- Settings persist into `hid_gamepad` as `rgb_on`, `rgb_effect`, `rgb_flow`, `rgb_speed`,
  `rgb_brightness`, `rgb_color`.

## Review focus

- **Effects are per-key, deterministic** from (x, y, index, time) and pressing; they must not
  drift or depend on frame history (stateless, no accumulation).
- **Recomposition cost**: the keyboard re-renders ~12 fps while RGB is on. If the phone stutters,
  the clock interval is the knob.
- **Fn+Up/Down are arrows** when Fn is not held; they are only consumed for brightness
  while Fn is held.
