# Bluetooth Controller — portfolio write-up

*A Bluetooth HID controller for Android: turn a phone into a gamepad, mouse or keyboard for a PC or tablet — over Bluetooth Classic, with no companion app on the host.*

---

## The problem

Every "use your phone as a gamepad" app on Android has the same structural problem: it presents
a keyboard and a mouse and a gamepad, and picks *one* descriptor. Bluetooth HID hosts, however,
do not agree on how to read a controller.

The decisive discovery came from reading the Linux kernel's `hid-input.c` and then confirming it
on a real device: **Android maps a Gamepad collection's button usages positionally** — usage 1
becomes `BTN_A`, usage 2 `BTN_B`, usage 3 `BTN_C`, usage 4 `BTN_X` and so on. It is not a
lookup table; it is the *order of the buttons in the descriptor*.

That single fact explains most of the weird behaviour:

- A descriptor that puts the buttons in the **W3C standard order** (A, B, X, Y) lands X on
  `BTN_C` and Y on `BTN_X` — and the buttons are then *read back by name*, so a host that only
  knows `BTN_X` and `BTN_Y` sees a controller with no X and no Y. Measured on a Lenovo tablet
  running Chrome: **X and R1 produced no button index at all**, while A, B, Y and L1 worked
  perfectly.
- A descriptor that puts the buttons in **Android's positional order** (`BTN_A, BTN_B, BTN_X,
  BTN_Y, BTN_TL, BTN_TR, …`) maps cleanly onto every Android host, because it *is* the order
  the kernel assumes.

So there is no single "correct" gamepad descriptor. There is only the right descriptor **for
that host**.

## What it does

- **Three controller modes** on one frozen descriptor: gamepad, mouse and keyboard.
- **A host-driven HID profile.** The app asks one question — *Windows PC or Android device?* —
  and derives the descriptor from the answer: Android gets a 4-in-1 composite (keyboard +
  mouse + gamepad + consumer control), Windows gets a dedicated gamepad with the W3C button
  order. The two hosts want different orderings, and asking once removes the possibility of the
  broken combination.
- **A touchpad that is a touchpad**, not a set of buttons: tap, two-finger tap, two-finger
  scroll, pinch-to-zoom, double-tap-and-drag, three-finger tap and three-finger swipe, all
  recognised by a pure state machine with no Android dependencies in it.
- **Three-finger swipes that mean something on the host**: Windows Task View / show-desktop /
  back / forward, versus Android Home / Back / Forward.
- **A master switch.** One control that unregisters the HID device, releases every held input
  and stops the foreground service — without touching the phone's Bluetooth or any other
  Bluetooth device.
- **In-app problem reporting.** Type what went wrong; it is delivered with the device and
  screen details (window size in dp, display density, cutout and corner geometry, refresh rate,
  current screen, HID state) so the report is actionable without anyone describing their own
  setup.
- **A first-run tutorial** that explains the pairing flow in five steps, with illustrations
  drawn on a `Canvas` — no image assets, no emoji, nothing that can render as a missing glyph.
- **A mark and a launcher icon** drawn as a single vector: a gamepad silhouette with the d-pad
  and the two face buttons punched out of it, so it still reads as a controller at 24 dp. The
  launcher icon reuses the same path in an adaptive icon with a monochrome layer, and the app is
  called **Bluetooth Controller** on the home screen — "HID" is jargon.

## How it works

```
Compose UI  →  state model  →  report encoders  →  BluetoothHidDevice  →  host
 (one activity)   (data class)     (9 / 13 bytes)     (platform HID profile)
```

- **One activity, one foreground service, one report descriptor per profile.** The service
  exists because Android unregisters a HID Device app that leaves the foreground; keeping the
  registration alive is part of the contract, not a convenience.
- **The gesture recogniser is pure Kotlin.** Pointer frames go in, actions come out, with no
  Android types in between — so every rule (what counts as a tap, a fling, a pinch) is unit
  tested without a device.
- **The input-release guarantees are structural.** Every pointer-driven control reports its
  release in a `finally` block, so a cancelled gesture, a lost touch or a recomposition can
  never leave a button held down on the host. That is why the code has the shape it has.

## The engineering I'm most pleased with

**1. Measuring instead of guessing.** Almost every decision in this project came from a
measurement, not from a specification:

| Question | How it was answered |
|---|---|
| Which buttons actually work on a tablet? | `getevent` on the host's input node, one control at a time |
| What does the browser see? | The Gamepad API read back from the page |
| Is the descriptor even parseable? | A byte-level HID descriptor parser used as a test |
| Does the host cache the descriptor? | Remove the device, pair again, watch the reported axes change |

That last one produced the app's central UX rule: because a host caches the report descriptor
at pairing time, changing the profile needs a **re-pair**, and the app says so plainly instead
of appearing broken.

**2. A descriptor bug that no amount of reading would have caught.** The gamepad-only
descriptor used `0x27` for a Logical Maximum. In the HID short-item encoding that means *four
data bytes*, so the parser swallowed the next item and the report parsed to 44 bits instead of
104 — the descriptor was unparseable and no host could have read it. The fix was to parse the
descriptor in a test and assert the bit count, because substring checks cannot see a short-item
size error.

**3. Letting the platform decide, not the UI.** The app never sends an input it did not mean
to. Each pointer is tracked individually, each control releases in a `finally`, and the master
switch releases *everything* before unregistering — so a host can never be left with a phantom
button held down because the phone went to sleep.

**4. Privacy in a diagnostic feature.** The report carries the device and screen details
automatically, so the person filing it does not have to understand them — but the *host's*
Bluetooth name (and its MAC address, when it has no friendly name) is somebody else's device
and is never included. The HID state is reported as a plain word (`registered`, `connected`).
Getting that wrong was a real review finding, and it is the kind of thing that is easy to miss
in a feature that "only goes to me".

**5. A mark that survives being small.** The logo is one vector path — a gamepad silhouette with
the d-pad and the two face buttons punched out of it as negative space — reused for the in-app
top bar and for the adaptive launcher icon. Nothing is a bitmap, so it is sharp at every density
and it can be recoloured (the monochrome layer for themed icons is the same path). Getting the
holes to read at 24 dp took two rounds of pixel-counting the screenshot on the device: the first
version was drawn in a 0..1 space against a 24-unit viewport, so it was rendering at about one
pixel, and the second was invisible for a different reason — Material's `Icon` tints its painter
with the content colour, which is black on a near-black bar. Both failures looked identical
from the UI ("nothing is there"), and only measuring the pixels told them apart.

## Numbers

| | |
|---|---|
| Main source | 18 Kotlin files, ~4 750 lines |
| Tests | 14 files, ~1 820 lines, **182 tests, 0 failures** |
| Documentation | 12 documents: design specs, plans, a measurement handoff, a device test checklist |
| Dependencies added | **none** — Bluetooth, HTTP and UI are all platform or Compose. Navigation Compose was evaluated for the redesign and rejected as unnecessary |

## What it is not

Honesty about the edges is part of the work:

- **A Bluetooth HID peripheral can never be XInput**, so a Windows game that insists on an Xbox
  controller needs a PC-side translator (Steam Input, ViGEm). That is a platform limit, not
  something the app can fix.
- **The gamepad is landscape-only**, deliberately: it is a one-hand layout.
- **The keyboard is a US layout with no second layer, and both Shift keys send the *left* Shift
  modifier.** A symbols layer and right-hand modifiers were designed — the HID usages and
  modifier bits are known — but have not landed. Non-US layouts are future work too.
- **The trigger reaches an Android host as an axis, never as `KEYCODE_BUTTON_R2`.** A game that
  reads the trigger strictly as a button will not see it. Fixing that means changing the
  descriptor's button bits, which changes the frozen descriptor and forces a re-pair on every
  host — a deliberate trade, not an oversight.

## What I would do next

1. Ship the trigger-as-button descriptor behind a profile the user opts into, and measure it.
2. Land the keyboard's symbols layer and right-hand modifiers, then make the layout switchable
   (US / UK / DE).
3. Move the report feature's topic configuration into the app for a store build, and add
   instrumented Compose tests — the UI currently has no automated regression protection.
4. Profile the touchpad's dot grid, which is redrawn every frame and should be cached.

---

## Short version

> **Bluetooth Controller** — an Android app that turns a phone into a Bluetooth gamepad, mouse or
> keyboard for a PC or tablet.
>
> The interesting part was that Android reads a gamepad's buttons *positionally* from the HID
> descriptor, so the "standard" button order silently breaks X and R1 on Android hosts while
> working on Windows. The app derives the descriptor from the host type instead of offering a
> choice, which makes the broken combination impossible to reach.
>
> Built in Kotlin and Jetpack Compose on the platform Bluetooth HID profile, with a pure
> gesture state machine, a byte-level descriptor parser in the test suite, and 182 unit tests.
> Measured on real hardware rather than inferred from the specification.

*(Screenshots: `docs/screenshots/` — gamepad, mouse, keyboard, settings, report panel and the
five tutorial steps.)*
