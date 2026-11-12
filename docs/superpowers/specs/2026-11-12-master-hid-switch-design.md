# Master HID on/off switch — design (2026-11-12)

## Goal

A single switch in Settings that turns the phone's Bluetooth HID controller on and off,
using the "stop advertising entirely" approach: off really unregisters the device, so the
phone stops looking like a gamepad/keyboard/mouse to every host.

## Behaviour

OFF:

1. Release every held input (keyboard, mouse, gamepad, consumer).
2. Unregister the HID app (the descriptor disappears from the stack).
3. Stop `HidControllerService` and remove its persistent notification.
4. No report can be transmitted afterwards.

ON:

1. Start `HidControllerService`.
2. Register the HID app and its descriptor for the current profile.
3. Show the persistent notification only after registration succeeds.
4. Normal HID behaviour resumes.

The choice is persisted under `hid_enabled` (Boolean, default `true`). A phone left OFF
stays OFF across an app restart: the activity does not start the service on launch.

Normal phone Bluetooth, other Bluetooth connections, the HID profile choice and the
re-pair warning are all untouched. The switch only controls this app's HID registration.

## Shape

- **Pure policy** (unit-tested, no Android):
  - `hidEnabledFromStorage(value: Boolean?): Boolean` — default `true` for a fresh install.
  - `shouldRunHidService(enabled: Boolean, permissionsGranted: Boolean): Boolean` — the
    single gate every start path consults.
- **`MainActivity`**: `hidEnabled` Compose state read from prefs at `onCreate`;
  `ensureHidService()` and `onResume()` both gated on `shouldRunHidService(...)`;
  `setHidEnabled(...)` persists the pref and either starts the service or
  releases + `manager.stop()` + `stopService(...)`.
- **`HidGamepadManager`**: new `onRegistrationChanged: ((Boolean) -> Unit)?` callback fired
  from `onAppStatusChanged`.
- **`HidControllerService`**: no longer calls `startForeground` unconditionally in
  `onCreate`. It shows the notification when the manager reports registered, and removes it
  in `onDestroy`. `onDestroy` stops the manager but keeps the process-wide instance so the
  UI's status/profile flows stay bound across an OFF→ON cycle.

## Deliberate deviation

The platform requires a service started with `startForegroundService` to call
`startForeground` within ~5 s or it is killed. Waiting for registration to finish could
exceed that, so the service arms a ~4 s fallback that shows the notification anyway. The
normal path still shows it only after successful registration; the fallback exists purely
to satisfy the platform deadline.

## Out of scope

- Disabling the on-screen gamepad while OFF (the controls stay visible; they simply cannot
  send anything because no host is connected).
- Rumble, Wi-Fi, cloud, root, Shizuku.
