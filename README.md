# HID Gamepad

Turn an Android phone into a Bluetooth HID gamepad for another device (e.g. a tablet).
The phone registers itself as a real HID gamepad over Bluetooth Classic via the
`BluetoothHidDevice` profile — no companion app needed on the receiving side.

- Standard gamepad descriptor: 16 buttons, D-pad (hat switch), dual analog sticks,
  two trigger buttons
- Multi-touch controls: hold a stick and tap buttons at the same time
- ~8% stick deadzone, stick recenter on release
- On-screen status bar: make discoverable, pick a paired device, connection state
- Optional haptic feedback (persisted)

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

1. Open the app on the phone and grant the three Bluetooth permissions.
2. Tap **Discoverable** (the phone is visible for ~5 minutes).
3. On the tablet: Settings > Bluetooth > pair with **HID Gamepad**.
4. Back in the app, tap **Devices**, pick the tablet — the status line shows
   *Connected*. Reports sync on every input change, with a 100 ms heartbeat.

## Project layout

```
app/src/main/kotlin/com/hidgamepad/
  GamepadState.kt       HID report descriptor + report encoding (unit-tested)
  HidGamepadManager.kt  Bluetooth HID profile: register, connect, send, heartbeat
  GamepadScreen.kt      Fullscreen landscape controller UI + top bar
  Joystick.kt           Multi-touch stick, D-pad and button widgets
  MainActivity.kt       Permissions, immersive mode, app lifecycle
```
