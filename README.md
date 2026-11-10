# HID Controller

Turn an Android phone into a Bluetooth HID controller for another device (Windows PC,
Android phone or tablet, or other HID host). The phone registers one combined HID
device over Bluetooth Classic via the `BluetoothHidDevice` profile — no companion app
needed on the receiving side. Mode switching happens in the app and does not re-pair.

- Selected host type: Windows PC or Android phone/tablet (affects instructions only)
- Modes: Gamepad, Mouse, and Keyboard
- Combined HID descriptor: report ID 1 keyboard (8 bytes), report ID 2 mouse (5 bytes,
  including pan), report ID 3 gamepad (9 bytes), report ID 4 consumer control (2 bytes,
  reserved). Bytes are frozen — changing them requires re-pairing the host.
- Gamepad: 16 buttons, D-pad (hat switch), dual analog sticks, analog L2/R2 triggers, tilt
- Mouse: buttonless trackpad — tap click, two-finger tap right-click, two-finger scroll
  (vertical and horizontal), pinch zoom, double-tap then hold to drag, three-finger tap
  middle-click, three-finger swipes per host
- Keyboard: A–Z, 0–9, Space, Enter, Escape, Backspace, Tab, arrows, Ctrl/Shift/Alt
- Multi-touch controls: hold a stick and tap buttons at the same time
- ~8% stick deadzone, stick recenter on release
- On-screen status bar: mode selector, Devices, Settings, connection state
- Optional phone-local haptic feedback (persisted); this is not host-controlled rumble

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

## Project layout

```
app/src/main/kotlin/com/hidgamepad/
  GamepadState.kt       Combined HID descriptor + gamepad/keyboard/mouse encoders (unit-tested)
  HidGamepadManager.kt  Bluetooth HID profile: register, connect, send, heartbeat
  TouchpadGestures.kt   Pure pointer-frame recognizer (tap, scroll, pinch, swipe) — unit-tested
  TouchpadGestureBridge.kt  Gestures -> mouse/keyboard/consumer reports with release timing
  HostGestureMapping.kt Three-finger swipe table per host type
  GestureTouchpad.kt    Compose pad: pointer frames, ticker, virtual cursor
  GamepadScreen.kt      Setup wizard, mode selector, settings, gamepad/mouse/keyboard UI
  Joystick.kt           Multi-touch stick, D-pad, buttons, pedals, and keyboard keys
  MainActivity.kt       Permissions, immersive mode, app lifecycle
```

## Unsupported / intentionally not implemented

- Home/Guide, Share/Menu, stick-click L3/R3
- Host-controlled rumble / HID output reports (phone-side button haptics are press
  feedback only, not game rumble)
- Custom or repositionable layouts
- Low-battery warning
- In-app controller tester
- Formal Steam Input / game / emulator compatibility test coverage
