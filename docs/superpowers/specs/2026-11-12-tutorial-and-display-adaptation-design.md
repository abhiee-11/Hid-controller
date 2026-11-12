# First-use tutorial and display adaptation — design

Date: 2026-11-12
Status: awaiting approval (proceeding on the human partner's "do as you like")
Size: project (two related changes)

## Intent

Two things the human partner asked for after the report feature landed:

1. **"add get started and tutorial for the first use"** — a first-launch tutorial that teaches
   what the app is and how to get a host connected.
2. **"optimize the screen for all display type"** — the app should work on every kind of
   screen: compact phone, large phone, tablet, portrait and landscape.

## Why

The app already has a five-step setup wizard, but it is a functional list of buttons, not a
tutorial: it has no progress, no way back, and no way out. A user who lands on it can
neither undo a wrong answer nor leave and come back. And the whole activity is locked to
landscape, so a tablet held in portrait is letterboxed and a phone in portrait cannot use the
mouse or keyboard modes at all.

## Goals

- A first-launch tutorial that can be skipped, gone back through, and replayed later.
- Every screen usable in portrait and landscape, on a compact phone and on a tablet.
- The gamepad stays landscape, because a controller laid out for one hand is a gamepad in
  portrait.

## Non-goals

- **No second onboarding system.** The existing setup wizard is *redesigned*, not replaced by
  something new: it already asks the right questions in the right order, and it already owns
  the `setup_complete` / `last_host_address` persistence.
- No new dependencies, no navigation framework.
- No change to the HID layer.

## Anti-goals

- **The tutorial must never trap the user.** Skip is available on every step, and skipping
  leaves the app usable.
- **No screen may clip.** A short screen (a phone in landscape, a small tablet) must scroll
  rather than cut content off.

## Approaches

### Tutorial

| | How | Trade-off |
|---|---|---|
| **A. Redesign the existing wizard** | Same five steps, same persistence, new presentation and controls | **Chosen.** No duplicate onboarding, no migration, and the questions are already the right ones |
| B. A new tutorial system alongside the wizard | Two onboarding paths | Two things to keep in step, and a user could be pulled into either |
| C. Keep the wizard and add a help screen | Cheapest | The wizard stays unfriendly, which is what was complained about |

### Orientation

| | How | Trade-off |
|---|---|---|
| **A. Activity unlocked; the gamepad asks for landscape** | The manifest drops the lock; the gamepad screen requests landscape while it is shown | **Chosen.** Every other screen can rotate; the gamepad stays a one-hand layout |
| B. Keep the activity locked to landscape | No change | Portrait is unusable for the mouse and keyboard — the opposite of the request |
| C. Two activities | A landscape one for the gamepad, a free one for the rest | A second activity to keep in sync for no benefit the runtime request cannot give |

### Layout adaptation

| | How | Trade-off |
|---|---|---|
| **A. Width cap + centred column on wide screens** | A column wider than ~640 dp is capped and centred; narrow screens get everything | **Chosen.** One rule, no breakpoint tables, no duplicated layouts |
| B. Breakpoint-specific layouts | A compact and an expanded layout per screen | Twice the code, twice the ways to get it wrong |
| C. Scale the whole UI up on tablets | Uniform scale | Blurs text, breaks touch-target maths, and fights the system font scale |

## The design

### Tutorial

Five steps, unchanged in content and order:

| # | Step | What it does |
|---|---|---|
| 1 | Welcome | What the app is, and a **Get started** button |
| 2 | Host type | Windows PC or Android device — this picks the HID descriptor |
| 3 | Mode | Gamepad, mouse or keyboard |
| 4 | Pairing | How to pair, and the list of bonded hosts |
| 5 | Ready | Confirmation, and **Start using the app** |

Controls, on every step:

- **Skip** — top right, always available. Finishes the tutorial without changing any answer.
  The app stays usable, and the tutorial can be replayed from Settings.
- **Back** — bottom left, from step 2 onwards.
- **Next** — bottom right, until the last step, where it becomes **Start using**.

A progress indicator shows "Step 2 of 5" with a bar, so the user knows the tutorial ends.

Each step has an illustration drawn on a `Canvas` — no image assets, no emoji — so it scales
and never renders as a tofu box.

**Adaptive layout:** on a window 600 dp or wider, the illustration and the text sit side by
side with the illustration on the left; below that they stack. The content column is capped at
640 dp and centred on wide screens.

**Replay:** Settings → **Getting started** re-runs the tutorial from step 1. The existing
"Run setup again" entry becomes this, so there is one way in, not two.

### Orientation and adaptation

- The app **stays locked to landscape** — it is a one-hand controller. (An earlier draft unlocked
  the activity; the human partner asked for the lock back, so it stays.)
- What the manifest *does* change is the configuration handling: it now absorbs **every**
  configuration change (`orientation|screenSize|smallestScreenSize|screenLayout|keyboard|keyboardHidden|navigation|uiMode|density|fontScale|locale|layoutDirection`).
  **This matters beyond tidiness:** recreating the activity on a layout change would release
  every held input (onPause) and re-register the HID device mid-game.
- The Settings sheet, the tutorial and the report panel all cap their content column at
  640 dp and centre it on wide screens.
- All three surfaces respect the display cutout and system gesture areas with
  `safeDrawingPadding()`; the tutorial and the report panel also consume the touches that
  reach them, so the controller underneath cannot receive a stray tap.
- The tutorial and the report panel scroll, so a short window never clips content.

## What does not change

The HID descriptors, report encoders, report sizes, button bits, keyboard usages, mouse
masks, gesture thresholds, the bridge's held-button ownership, the foreground service and its
notification, connection management, the heartbeat, the re-pair rule, and the permission set.

## Files

| File | Change |
|---|---|
| `app/src/main/kotlin/com/hidgamepad/Tutorial.kt` *(new)* | The step model and its transitions — pure, unit-tested. |
| `app/src/main/kotlin/com/hidgamepad/TutorialPanel.kt` *(new)* | The redesigned tutorial screen. |
| `app/src/main/kotlin/com/hidgamepad/GamepadScreen.kt` | Use the new tutorial panel; the Settings entry becomes "Getting started"; the Settings sheet gets the width cap and the insets. |
| `app/src/main/AndroidManifest.xml` | Absorb every configuration change; the orientation lock stays. |
| `app/src/test/kotlin/com/hidgamepad/TutorialTest.kt` *(new)* | The step transitions. |

## Verification

1. `.\gradlew.bat --console=plain testDebugUnitTest assembleDebug`.
2. On the phone: clear `setup_complete`, launch, and walk the tutorial — including **Skip**
   from the first step and **Back** from the last.
3. Rotate the phone to portrait: the mouse and keyboard screens must work; the gamepad must
   ask to be rotated.
4. Check no screen clips at the phone's smallest window.
