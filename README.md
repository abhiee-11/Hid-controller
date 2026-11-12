# Bluetooth Controller

Turn an Android phone into a Bluetooth HID controller for another device (Windows PC,
Android phone or tablet, or other HID host). The phone registers one combined HID
device over Bluetooth Classic via the `BluetoothHidDevice` profile — no companion app
needed on the receiving side. Mode switching happens in the app and does not re-pair.

- Selected host type: Windows PC or Android phone/tablet. It also picks the HID profile (below)
- Modes: Gamepad, Mouse, and Keyboard
- The **host type** picks the HID profile, so there is no separate profile to choose:
  - **Android phone/tablet** → **Keyboard + mouse + gamepad** — one composite device: report ID 1
    keyboard (8 bytes), report ID 2 mouse (5 bytes, including pan), report ID 3 gamepad (9 bytes),
    report ID 4 consumer control (2 bytes, reserved). Android's kernel maps gamepad buttons
    positionally, which is the order this descriptor emits.
  - **Windows PC** → **Gamepad only** — a dedicated gamepad: 16 buttons, X/Y/Rx/Ry as signed
    16-bit axes, Z/Rz as the triggers, and a 4-bit hat (13-byte report). Windows/DirectInput
    reads the buttons by declaration order, which is the order this descriptor emits. A Bluetooth
    HID pad can never be XInput, so XInput-only games still need a remapper (e.g. Steam Input).
  - Bytes are frozen per profile. The host caches the descriptor at pairing time, so **changing
    the host type requires re-pairing the host**; the Settings sheet says so and offers
    "I've re-paired the host".
- Gamepad: 16 buttons, D-pad (hat switch), dual analog sticks, digital L2/R2 triggers, tilt
- Mouse: buttonless trackpad — tap click, two-finger tap right-click, two-finger scroll
  (vertical and horizontal), pinch zoom, double-tap then hold to drag, three-finger tap
  middle-click, three-finger swipes per host
- Keyboard: A–Z, 0–9, Space, Enter, Escape, Backspace, Tab, arrows, Ctrl/Shift/Alt
- Multi-touch controls: hold a stick and tap buttons at the same time
- ~8% stick deadzone, stick recenter on release
- On-screen status bar: mode selector, Devices, Settings, connection state
- Master HID on/off switch in Settings (see below)
- Optional phone-local haptic feedback (persisted); this is not host-controlled rumble
- A first-run tutorial, and a "Report a problem" button (see below)

## First run

The first time the app is launched it walks through five short steps: welcome, which host you
will control, which controller you want, how to pair, and a summary. You can go **Back**, you
can **Skip** at any point, and the tutorial never comes back on its own once it is finished —
but you can replay it any time from **Settings → Getting started**.

Picking the host type is the one answer that matters: it chooses the HID descriptor the phone
registers, and it decides what the three-finger swipes send (Windows chords versus Android
Home/Back/Forward).

## Requirements

- A phone running Android 8.0+ (minSdk 28) for the controller side
- A tablet or PC that accepts Bluetooth HID gamepads on the host side
- Build machine: JDK 17+ (tested with 25), Android SDK with platform 37

## Build

1. Install the Android SDK (platform 37 / build-tools 36) and set up a JDK.
2. Create `local.properties` (this file is machine-local and git-ignored):

   ```properties
   sdk.dir=C:/path/to/Android/Sdk
   ```

3. Build and install:

   ```sh
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

## Pair and connect

1. Open the app on the phone and grant the runtime Bluetooth permissions
   (Connect and Advertise; VIBRATE is declared in the manifest only, no runtime prompt).
2. First run asks whether you control a Windows PC or an Android phone/tablet, then
   the mode you want to use.
3. Tap **Make discoverable** during setup, or in Settings (the phone is visible for ~5 minutes).
4. On Windows: Settings > Bluetooth & devices > Add device > choose this phone.
   On Android: Bluetooth settings > pair with this phone, if the host supports HID input.
5. Back in the app's paired-device list, choose the host — the status line shows
   *Connected*. Reports sync on state change; gamepad keeps a 100 ms heartbeat,
   while keyboard and mouse reports are event-driven and mouse movement is never repeated by heartbeat.

Windows caches a device's SDP service list at pairing time. If the phone was paired
with Windows before this app ever registered its HID service, Windows keeps treating
it as a phone/audio device and never opens the HID channels. Remove the phone under
Bluetooth & devices and pair it again while the app is open and registered.

The same caching applies to the **HID profile**: a host reads the report descriptor once,
at pairing. Changing the host type (which picks the profile) therefore does nothing until
the host removes the phone and pairs it again; the Settings sheet warns you and offers
"I've re-paired the host".

## Turning HID off

Settings opens with a master switch, **HID controller: ON/OFF**. It controls whether the
phone advertises itself as a Bluetooth HID device at all.

- **Off** releases every held input, unregisters the HID device, stops the foreground
  service and removes its persistent notification. The phone stops looking like a
  gamepad/keyboard/mouse to every host until you turn it back on. Use it to save battery or
  when you do not want the phone to appear as a controller.
- **On** starts the service and registers the HID device again; the notification appears
  only after registration succeeds.

The choice is remembered: a phone left off stays off after the app is restarted, so it will
not silently start advertising again. Turning HID off does not touch normal phone Bluetooth
or any other Bluetooth connection, and it does not change the selected profile or the
re-pair state — those are handled separately.

## Mouse gestures

Mouse mode is one full-bleed pad with no on-screen buttons. Every gesture is sent through
the existing frozen descriptor, so nothing re-pairs.

| Gesture | Windows | Android |
|---|---|---|
| Drag / tap | Cursor moves, tap clicks | Same; drag is a drag on touch-friendly apps |
| Two-finger drag | Vertical and horizontal scroll everywhere | Vertical reliable; horizontal depends on the app |
| Double-tap then hold and drag | Left button held, release ends the drag | Same (the host sees a held left button) |
| Pinch | Ctrl+wheel zoom in any zoomable app | Ctrl+wheel; only apps that honour it (browsers, some editors) |
| Two-finger tap | Right click | Context menu where the app supports long-press menus; often nothing |
| Three-finger tap | Middle click | Usually nothing |
| Three-finger swipe up / down / left / right | Task View / show desktop / back / forward | Home / nothing / Back / Forward where consumed |

True pinch is not possible over a 5-byte relative mouse report, so "pinch" is Ctrl+wheel on
both hosts. A 40 ms hold separates each press from its release so a host cannot coalesce
the pair.

## Reporting a problem

The Settings sheet has a **Report a problem** button, directly under the HID master switch.

Type what went wrong and press **Send report**. The report is delivered to the maintainer as
a push notification, together with the device and screen details needed to diagnose it:

```
Screen
  Window        858 x 384 dp (landscape)
  Display       1608 x 720 px, 300 dpi (1.875x)
  Smallest w    384 dp
  Width class   expanded
  Refresh rate  60 Hz
  Cutout        yes — 67 px left inset
  Corners       47 px radius
  Theme         dark

Device
  vivo V2434
  Android 16 (SDK 36)

App
  HID Controller 1.1
  Screen         Settings
  Mode           Gamepad
  Host type      Windows PC
  HID            registered
  Tilt           off
```

Those details are attached to every report automatically and are never shown to the person
reporting — they are there so the report is actionable without anyone having to describe
their own screen. **No Bluetooth addresses, no paired-device names and no host names are
included** — the HID state is reported as a plain word (`registered`, `connected`, …).

The delivery topic is the maintainer's own and is compiled into the app from a
machine-local, git-ignored property, so it never enters the repository. A build without that
property asks the user for a topic instead.

## Project layout

```
app/src/main/kotlin/com/hidgamepad/
  GamepadState.kt       Combined HID descriptor + gamepad/keyboard/mouse encoders (unit-tested)
  GamepadProfiles.kt    The two HID profiles: descriptors, encoders, host-type -> profile
  HidGamepadManager.kt  Bluetooth HID profile: register, connect, send, heartbeat
  HidControllerService.kt  Foreground service that keeps the HID registration alive
  TouchpadGestures.kt   Pure pointer-frame recognizer (tap, scroll, pinch, swipe) — unit-tested
  TouchpadGestureBridge.kt  Gestures -> mouse/keyboard/consumer reports with release timing
  HostGestureMapping.kt Three-finger swipe table per host type
  GestureTouchpad.kt    Compose pad: pointer frames, ticker, virtual cursor
  GamepadScreen.kt      Setup wizard, mode selector, settings, gamepad/mouse/keyboard UI
  Joystick.kt           Multi-touch stick, D-pad, buttons, pedals, and keyboard keys
  ProblemReport.kt      ntfy report: topic validation, message body, HTTPS publish
  DeviceScreenInfo.kt   Device and screen facts attached to a report
  ReportProblemPanel.kt The report-a-problem screen
  Tutorial.kt           The five tutorial steps (unit-tested)
  TutorialPanel.kt      The tutorial screen
  MainActivity.kt       Permissions, immersive mode, app lifecycle
app/src/main/res/
  drawable/ic_logo.xml        The controller mark, drawn on a 24-unit grid
  drawable/ic_launcher_foreground.xml  The same mark, scaled into the adaptive-icon safe zone
  mipmap-anydpi-v26/          Adaptive launcher icon (background, foreground, monochrome)
  values/themes.xml           Theme.HidController — the window background before the first frame
```

## Unsupported / intentionally not implemented

- Home/Guide, Share/Menu, stick-click L3/R3
- Host-controlled rumble / HID output reports (phone-side button haptics are press
  feedback only, not game rumble)
- Custom or repositionable layouts
- Low-battery warning
- In-app controller tester
- Formal Steam Input / game / emulator compatibility test coverage
