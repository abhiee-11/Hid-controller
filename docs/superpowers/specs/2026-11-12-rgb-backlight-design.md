# Per-key RGB backlight on the on-screen keyboard - design

**Date:** 2026-11-12

## Intent

The on-screen keyboard gets a per-key RGB backlight, like a gaming laptop or gaming
mechanical keyboard: every key is its own light, and the effects are the ones found in the
major RGB control suites.

## Look

- Keycaps are near-black (#14161C) with a 1 dp #04050A border, 8 dp corner radius and a faint
  white 5% top highlight. No coloured outlines, in any mode.
- The legend of each key glows in the key's colour, like the print on a backlit key (Shadow
  blur 6 dp, the key colour at 65% lightness).
- Unlit keys (backlight off, or reactive mode with nothing pressed) fill #14161C, legend
  #7A8196, no glow.
- A soft glow leaks around each key into the gaps (native Paint + BlurMaskFilter; stacked
  translucent rounded rects as a fallback), in the key colour at 55% lightness, alpha =
  intensity x 0.5, blur radius = (8 + 8 x intensity) dp, spread = (1 + intensity) dp.
- The keyboard deck is #06070A with 14 dp rounded corners and 10 dp padding.
- Pressed keys sink 2 dp; they do not shrink.
- Caps key shows its LED dot always: green #4CC38A with a glow when the host has Caps Lock
  on, #3A4156 when off.

## Layout

Bottom row: Fn, Caps, Win, Space, arrows. The keyboard carries no lighting keys.

- Fn: laptop-style local layer; the number row emits F1-F10 while held. Never sent on the wire.
- All lighting controls live in Settings, or on combos consumed like a laptop's: Fn+L toggles
  the backlight, Fn+Up/Down steps the brightness, Fn+Left/Right steps the effect.
  Fn+Up/Down and Fn+Left/Right are arrows when Fn is not held.

## Hard rules

- No change to HID reports, descriptors, gestures, connection handling or state.
- The backlight is phone-local and visual only.
- Off by default; the keyboard renders the unlit look when off.
- No device serials, host names, MACs or the ntfy topic in tracked files.
- Landscape only; dp sizes, no hard-coded pixels.

## Behaviour

- Every key gets a normalised position (x, y) in the grid from its row and its horizontal
  weight; effects are pure functions of position and clock.
- Pressed keys feed a short-lived flash map, used by Reactive and Ripple.
- The board is clocked ~12 fps only while the backlight is on; when off, nothing animates.
- Settings persist into hid_gamepad as rgb_on, rgb_effect, rgb_flow, rgb_speed,
  rgb_brightness, rgb_color.

## Review focus

- Effects are deterministic from (x, y, index, time); no frame-history accumulation.
- Recomposition cost: ~12 fps while RGB is on. If the phone stutters, the clock is the knob.
- Fn+Up/Down and Fn+Left/Right are arrows when Fn is not held; they are only consumed for
  lighting while Fn is held.
