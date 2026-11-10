# LEANREA — Universal Android Bluetooth HID Controller Research

**Date:** 2026-11-09
**Method:** LEANREA (Lean Reverse Engineering) — static source inspection of public repositories, cheap checks first, no APK execution, no Gradle, no build scripts, no dependencies installed.
**Scope:** Bluetooth Classic HID (`BluetoothHidDevice`, API 28+) for turning one Android phone into a composite HID peripheral (keyboard / mouse / gamepad / consumer control) for another Android phone or PC. Wi‑Fi / network remote-control modes found in the investigated apps are explicitly out of scope except where they reveal architecture.
**Workspace:** Clones at `%TEMP%\re\<name>` (Windows: `C:\Users\<user>\AppData\Local\Temp\re\`). No original repositories were modified.

Evidence discipline: every finding is tagged **SEEN** (directly in source/docs, with file references), **INFERRED** (reasoned from the evidence), or **UNKNOWN** (not established). A README claim is recorded as a claim, not as proof of runtime behaviour. Static inspection is not runtime proof. Android‑to‑Android operation was **not** runtime-tested anywhere in this investigation.

---

## 1. Executive summary

- **Architecture feasibility: INFERRED YES.** A single `BluetoothHidDevice` registration with a composite HID descriptor exposing Report ID 1 keyboard, Report ID 2 mouse, Report ID 3 gamepad, Report ID 4 consumer controls is a proven source-code pattern in four of the seven investigated repositories (PhoneDeck, Bluke, GKME, ghost-hid). Android's own API documentation confirms: only one HID Device application can be registered at one time; registering disables the phone's HID Host service until unregistered; the app is automatically unregistered if not in the foreground (hence a foreground service is mandatory) — developer.android.com, fetched 2026-11-09.
- **Android→Android specifically: NOT YET ESTABLISHED.** No investigated repository publishes verified evidence of one ordinary Android phone receiving HID input from another ordinary Android phone. Two repos *claim* Android hosts (GKME README; smttgt/bluetooth-control README "Android 4 and later"), one claims Android TV hosts (Sensat10on/Gamepad), one claims Android games/TV hosts (Bluke) — all are claims, none are published test logs. phonepad documents a real signed-APK verification of **phone → Windows 11 PC** only (Galaxy S23 Ultra, Android 16; `phonepad/README.md:100`), and this workspace's own `REPORT.md` documents **phone → Windows PC** verification (2026-11-09) with a tablet (`<TABLET_SERIAL>`) available over ADB but not used for an Android-receiver campaign. **Biggest unknown: whether an arbitrary Android receiver accepts a classic HID gamepad/mouse/keyboard from another phone.**
- **Recommended path:** implement a clean-room composite descriptor (ID 1 keyboard 6KRO, ID 2 mouse 5‑byte with wheel + AC Pan, ID 3 gamepad Android-tuned 8‑bit axes, ID 4 consumer 16‑bit), one SDP registration (`SUBCLASS1_COMBO`), values-only mode switching for the gamepad encoding (Bluke pattern), never descriptor switching after pairing (Bluke/ghost-hid ADR-002/PhoneDeck comments all warn hosts cache the descriptor at pairing; any change requires unpair/re-pair).
- **Licensing:** none of the strong composite references may be copied into a proprietary codebase. GhostHID (MIT) is the only directly reusable source; everything else is GPL/AGPL/no-license. Treat PhoneDeck, Gamepad, Bluke, GKME, phonepad, bluetooth-control + Atharok BtRemote as **study-only** references. Architecture and HID byte-level facts are not copyrightable; code expression is.
- **Prior art in this workspace:** `com.hidgamepad` (this repo) already ships a working 9‑byte Android-ordered gamepad over classic HID with documented PC-host verification (`REPORT.md`, commit f6f8923). The research below extends that baseline into the required composite controller.

---

## 2. Project explanation

Goal: PHONE A (controller) presents itself over Bluetooth Classic HID as a standard peripheral that PHONE B (receiver) consumes **without any receiver-side app** — mouse moves the real system pointer, keyboard feeds the focused app, gamepad feeds compatible games via Android's input framework. No Wi‑Fi, no cloud, no root, no Shizuku, no screen mirroring.

The central engineering questions, answered in §6–§12:
1. Can one SDP registration carry keyboard+mouse+gamepad+consumer simultaneously? → **Yes (SEEN in source, four repos).**
2. Does that's composite unit work when the *host is another Android phone*? → **UNKNOWN — must be physically verified.**
3. Which repo is the best blueprint? → Best *composite descriptor*: PhoneDeck (structural reference) with GKME's Android-targeted gamepad collection; best *complete app UX*: Bluke (study-only, AGPL); only license-safe reusable source: ghost-hid (MIT); only documented Windows-host runtime evidence: phonepad + this workspace.

---

## 3. Repository inventory (cloned, read-only)

| Repo | Local path (`%TEMP%\re\…`) | Branch | Commit | Last commit | License (file) | Verdict on reuse |
|---|---|---|---|---|---|---|
| GKME — 4zyz4/GKME | `GKME` | master | `f079fef89c84754c00f469f332ec893ee51dc05d` | 2026-11-08 | GPL-3.0 (LICENSE) | Study only |
| Sensat10on/Gamepad | `Gamepad` | main | `571d58d464ba698f5d1ef06dd01dbe58bd282eca` | 2026-09-15 | **None found** (own AUDIT.md says LICENSE missing) | No code reuse (all rights reserved) |
| PhoneDeck — ShubratoDn/PhoneDeck | `PhoneDeck` | main | `8e89eb6dc81092e5d23373856d0a6bfaa00d0bc0` | 2026-10-03 | **None found** | No code reuse |
| Bluke — arnav-kr/Bluke | `Bluke` | main | `bcf4f1492b51bcbb97fc7b792f73ad6bed75c759` | 2026-10-09 | AGPL-3.0 (LICENSE) | Study only |
| bluetooth-control — smttgt/bluetooth-control | `bluetooth-control` (+ submodule `src`, Atharok BtRemote @ `56ea84e`, v1.12.1) | main | `035a3afa420fa7c40bbe5b337752fa76a276ea7e` | 2026-06-20 | GPL-3.0 (both levels) | Study only |
| phonepad — Tygb99/phonepad | `phonepad` | main | `6c864031120baad609cd960b44aa3fc475392647` | 2026-10-30 | GPL-3.0-or-later (LICENSE) | Study only |
| GhostHID — ShubhamJ010/ghost-hid | `ghost-hid` | main | `9b24a65697d028febda66543ccef74a1d590c149` | 2026-11-04 | MIT | Reusable with attribution |

Cheapest-check notes: `file`/magic not needed (all plain source trees); manifests read for all; ripgrep used for all symbol searches; no decompilation performed; no build tooling executed.

---

## 4. Repository comparison (required table)

Legend: ✓ = SEEN in source · ◐ = partial/present-but-unproven · ✗ = absent · ? = UNKNOWN.
"Runtime verified" below means *a verification campaign is documented in the repo*; it never means our target scenario is proven.

| Property | Required | GKME | Gamepad (Sensat10on) | PhoneDeck | Bluke | bluetooth-control | phonepad | ghost-hid |
|---|---|---|---|---|---|---|---|---|
| Android language | Yes | Kotlin ✓ | Kotlin ✓ | Kotlin ✓ | Kotlin ✓ | Kotlin ✓ | Kotlin ✓ | Kotlin ✓ |
| Architecture | Yes | big multi-transport | single-purpose gamepad | monolith service+Compose UI | modular managers + coordinators | Hilt/Compose, fork of Atharok TV remote | monolith MainActivity | service + domain/parser split |
| Bluetooth HID Device (`registerApp`) | Yes | ✓ ClassicHidTransport.kt:417-464 | ✓ HidConnectionManager.kt:70-75 | ✓ BluetoothHidService.kt:412-434 | ✓ HidRegistrationCoordinator / HidLifecycle | ✓ Atharok BluetoothHidCore.kt:44-53 | ✓ MainActivity.kt:925-947 | ✓ HidService.kt:~405-460 |
| Android HID Host claims (README-level) | Yes | ✓ "PC or another phone … Windows/Android/Linux" (README.md:48) | ✓ "Android/Google TV, Windows, compatible Android hosts" (README.md:19) + tester call for "second phone" (store/tester-recruitment.md:85) | ✗ PC only ("host PC", ETS2; BluetoothHidService.kt:319) | ✓ "Android games & Android TV" + profile Native/Android/Web (docs/USAGE.md:76, GamepadReport.kt:17-30) | ✓ "Android 4 and later" host (README.md:33-36) | ✗ Windows/macOS only (README.md:7) | ◐ "Android TV and media players" listed (README.md) |
| Mouse | Yes | ✓ (ID 18, 16-bit, high-res wheel) | ✓ (ID 2, 8-bit, encodeMouse ±64 clamp) | ✓ (ID 2, 8-bit + AC Pan) | ✓ (ID 2, 8-bit) | ✓ (ID 3→renumbered ID 2, 8-bit) | ✓ (ID 1, 8-bit + AC Pan) — **runtime-verified → Win11** | ✓ (ID 2, 8-bit) |
| Touchpad | Yes | ✓ custom touchpad w/ pointer math | ✓ MousePadScreen.kt | ✓ (touch UI) | ✓ (touchpad + strips, 0.25x–2x sensitivity, USAGE.md:60-64) | ✓ (D-pad nav + touchpad) | ✓ (POINTER_SCALE, two-finger scroll, taps, drag mode) | ✓ (trackpad + gestures: tap/right-click/edge scroll) |
| Keyboard | Yes | ✓ (ID 17, 6KRO + LEDs) | ◐ nav-only (Enter + arrows; KeyboardReport.kt:4-18) | ✓ full (ID 1, 6KRO + LED output, US-layout text typer) | ✓ full (ID 1, 6KRO + LEDs, multi-layout) | ◐ modifier + **1 key byte** (Constants.kt:77-89) | ✓ 6KRO (ID 2, no LED output) | ✓ full (ID 1, 6KRO + LEDs) |
| Gamepad | Yes | ✓ (ID 19 Android layout, 9-byte; ID 1 gamepad-only) | ✓ (ID 1, 9-byte Android bit order) | ✓ (ID 3, 12-byte, 32 buttons, 16-bit steering) | ✓ (ID 3, 14-byte, 19 buttons, 16-bit axes) | ✗ | ✗ | ✗ (raw injector only) |
| Composite descriptor | Yes | ✓ combos (Win/Android/Linux) + KM-only + gamepad-only | ✓ Push/Pop-wrapped 3-collection | ✓ 4 collections in one descriptor | ✓ 4 collections in one descriptor | ✓ 3 collections | ✓ 2 collections | ✓ 4 collections |
| Separate report IDs | Yes | ✓ 17/18/19(+20 feature) | ✓ 1/2/3 | ✓ 1/2/3/4 | ✓ 1/2/3/4 | ✓ 1/2/3 (renumbered by smttgt patch 0001) | ✓ 1/2 | ✓ 1/2/3/4 |
| Relative cursor movement | Yes | ✓ 16-bit relative | ✓ 8-bit relative | ✓ 8-bit relative | ✓ 8-bit relative | ✓ 8-bit relative | ✓ 8-bit relative | ✓ 8-bit relative |
| Mouse scrolling | Yes | ✓ 16-bit wheel + AC Pan h-wheel | ✓ wheel only (no AC Pan) | ✓ wheel + AC Pan h-wheel | ✓ wheel only | ✓ wheel only | ✓ wheel (–dy/11) + AC Pan h-scroll | ✓ wheel only |
| Analog gamepad axes | Yes | ✓ (X/Y ±127 8-bit; Slider/Dial pedals; Z/Rz right stick) | ✓ (X/Y/Rx/Ry 0..255 center 128; Z/Rz triggers 0..255) | ✓ (X 16-bit; Y/Rx/Ry/Slider/Dial 8-bit 0..255) | ✓ (5 × 16-bit 0..65535 center 32768) | ✗ | ✗ | ✗ |
| Button mapping | Yes | ✓ Android positional bits; Windows/Linux variants | ✓ STANDARD_BITS vs ANDROID_BITS (HidReportEncoder.kt:41-58) | ✓ 32 raw bits (game-oriented) | ✓ Android BTN_GAMEPAD offsets + modes (GamepadReport.kt) | ✗ | ✗ | ✗ |
| Reconnection | Yes | ✓ watchdog, auto re-register, tryAutoReconnect (ClassicHidTransport.kt:485-560) | ✓ close()/re-register; rejected-connection message | ✓ last-host auto-connect on registration (BluetoothHidService.kt:296-306) | ✓ bounded retry policy + capability diagnosis (HidLifecycle.kt) | ◐ | ✓ auto-reconnect to last host (MainActivity.kt:902-912) | ◐ |
| Foreground service | Yes | ✓ specialUse (Manifest:58-85) | ✓ connectedDevice (Manifest:43-47) | ◐ specialUse + connectedDevice+camera+mediaProjection (Manifest:250-296) — over-privileged vs need | ✓ connectedDevice (Manifest:26-30) | ✓ connectedDevice (submodule Manifest:43-59) | ✓ connectedDevice (Manifest:42-46) | ✓ connectedDevice (Manifest:38-43) |
| Android compatibility | Yes | minSdk 26 (build.gradle.kts:37) | minSdk 28 | minSdk ~28 (check build.gradle.kts) | minSdk 28 (build.gradle.kts:16) | minSdk 26 (upstream) | minSdk 28 | minSdk 28 |
| Licence | Yes | GPL-3.0 | NONE | NONE | AGPL-3.0 | GPL-3.0 | GPL-3.0+ | MIT |
| Confirmed runtime testing | Yes | ◐ README claims; no published HID logs | ✗ none published; closed-testing drive in progress (README.md) | ✗ ETS2/PC orientation; no published Android or PC test logs | ◐ USAGE.md troubleshooting implies field reports; no published campaign | ◐ Play/F-Droid distribution implies usage; no published logs; Android TV origin | ✓ **phone → Win11 PC verified** (README.md:100, Galaxy S23 Ultra / Android 16); **Android receiver: NONE** | ✗ none published |

**Modes vs composite:** GKME exposes *switchable registered descriptors* (UNIVERSAL_KM vs WINDOWS/ANDROID/LINUX combo vs GAMEPAD_ONLY) selected at `registerApp` time — never simultaneous "all descriptors", but one composite per registration. Gamepad, PhoneDeck, Bluke, bluetooth-control, phonepad, ghost-hid all expose *one composite descriptor with report IDs* — simultaneous mouse+keyboard(+gamepad) from one registration. Only GKME's ANDROID combo additionally differentiates byte layout per host target (report IDs 17/18/19 + feature 20) — SEEN.

**Why implementations "move UI boxes" instead of a system cursor:** every investigated repo sends real `sendReport()` HID traffic — none animates fake cursors. The failure mode (view-local motion only, no HID reports) exists only in UI-only clones; it is detectable by `rg 'sendReport'` returning zero hits. Our implementation must treat `MotionEvent → relative delta → `sendReport(2,…)` as the only legal path (see §7).

---

## 5. Licensing analysis (gate for any code reuse)

| Licence | Repos | Obligation if you ship derived code | Practical rule |
|---|---|---|---|
| MIT | ghost-hid | Keep copyright + licence notice | Copy/adapt freely, keep notice |
| Apache-2.0 fragments | gradle wrappers (Gamepad gradlew) | Notice preservation | Irrelevant to app code |
| GPL-3.0 | GKME, bluetooth-control (+ upstream Atharok), phonepad (3.0+) | Whole derivative must be GPL-3.0; source offer required | Study architecture; write your own code; or licence our app GPL-3.0 |
| AGPL-3.0 | Bluke | Same + network-interaction source offer | Do not lift code; architecture/ideas are fine |
| No licence | Sensat10on/Gamepad, PhoneDeck | All rights reserved — copying is infringement | Read for learning; implement independently; do not even port descriptor arrays verbatim without counsel |

**Byte-level HID descriptors** themselves encode public HID-spec facts; functional equivalence is unavoidable and legal, verbatim wholesale copying of a repo's file (with its distinctive values and comments) is not. Clean-room practice: document the needed layouts from the HID spec and public USB-IF/Linux sources, use these repos only to confirm field positions.

**Recommendation:** implement independently; take *only* architectural guidance from GPL/AGPL/no-licence repos; ghost-hid (MIT) may seed the project skeleton with attribution. All external-source decisions must be revisited before any public release. **UNKNOWN (legal):** none of this is legal advice; for a commercial release, have counsel confirm.

---

## 6. Bluetooth HID registration architecture (how PHONE A becomes a peripheral)

**Required APIs (all SEEN in source or official docs):**

- `BluetoothAdapter` → `adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)` (all seven repos).
- `BluetoothProfile.ServiceListener.onServiceConnected` → cast proxy to `BluetoothHidDevice` (all repos).
- `BluetoothHidDevice.registerApp(sdp, inQos, outQos, executor, callback)`:
  - `BluetoothHidDeviceAppSdpSettings(deviceName, description, providerName, subclass, descriptor)` — `SUBCLASS1_COMBO` (combo KB/mouse) in PhoneDeck:418-420, Bluke (GamepadDescriptor), phonepad:941-946, ghost-hid; `SUBCLASS2_GAMEPAD` for gamepad-only in GKME:438-441; `SUBCLASS1_COMBO` + gamepad collections is what real combo apps use.
  - QoS: PhoneDeck uses explicit QoS `SERVICE_BEST_EFFORT, tokenRate 800, bucket 9, latency 11250 (11.25 ms), peak/max` (BluetoothHidService.kt:421-428); GKME passes all-zero QoS (ClassicHidTransport.kt:434-437); phonepad passes nulls. INFERRED: zeros/nulls are acceptable to the stack; explicit 11.25 ms polling is a reasonable default for responsiveness.
- `BluetoothHidDevice.Callback`: `onAppStatusChanged` (registration accepted/rejected/lost), `onConnectionStateChanged`, `onGetReport`, `onSetReport`, `onInterruptData`, `onSetProtocol`, `onVirtualCableUnplug` — implementations in all repos.
- `sendReport(device, reportId, bytes)` — interrupt-channel input report delivery (all repos).
- `unregisterApp()` + `closeProfileProxy()` on teardown (all repos; ghost-hid ADR-002 documents the "one registration per app context" constraint).
- Permissions: `BLUETOOTH_CONNECT` (API 31+ runtime), `BLUETOOTH_ADVERTISE` (discoverable), legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` ≤ API 30, `BLUETOOTH_SCAN` only if needed for pairing flows (phonepad explicitly avoids SCAN; Bluke declares it `neverForLocation`).
- **Android 12+:** runtime `BLUETOOTH_CONNECT`; **Android 14+:** `FOREGROUND_SERVICE_CONNECTED_DEVICE` for connectedDevice FGS type.
- **API level:** `BluetoothHidDevice` exists since API 28 = minimum controller API (phonepad docs call minSdk 28 non-negotiable; README.md:30-33 & docs/HID.md).
- **Official contract** (developer.android.com, fetched): `registerApp()` — "Only one application can be registered at one time. When an application is registered, the HID Host service will be disabled until it is unregistered. … The app will be automatically unregistered if it is not foreground." → **Foreground service is mandatory, not optional.** `connect()` requires the device to be *currently paired*. `sendReport()` targets a connected host. `SUBCLASS1_COMBO` = "combo keyboard and mouse subclass"; there is no SUBCLASS for kb+mouse+gamepad — use COMBO (as GKME/PhoneDeck/Bluke do) and let the descriptor speak.
- **OEM restriction:** HID Device availability is firmware/OEM dependent — Bluke USAGE.md:200 ("Some budget phones or modified manufacturer ROMs disable this feature"), Gamepad AUDIT.md treats it as P-level risk, GKME/phonepad both gate on `getProfileProxy` success and handle rejection. **SEEN as warning; UNKNOWN per model.**
- **Boot protocol:** host may force `PROTOCOL_BOOT_MODE` (BIOS/firmware hosts); boot protocol has no report IDs — PhoneDeck explicitly stays silent in boot protocol (BluetoothHidService.kt:819-823). INFERRED: for Android↔Android, report mode is expected; handle `onSetProtocol` by refusing or sending only ID-0-safe reports.
- **One-way pairing UX:** PHONE B pairs to PHONE A as a normal Bluetooth device; PHONE A then `connect()`s to the bonded PHONE B. All repos implement last-host auto-reconnect.
- **Discoverable window:** pairing usually requires a discoverable/bondable window (phonepad: only "new-PC" action opens discoverable; never on plain foreground).
- **Registration watchdog:** GKME retries registration with backoff + 8 s watchdog (ClassicHidTransport.kt:512-528); Bluke implements `RetryPolicy` + `BluetoothCapabilityRepository` (HidLifecycle.kt) distinguishing *rejection* (likely firmware-incompatible) from *timeout* (inconclusive). This workspace's `REPORT.md` solved reconnect with "twice-empty `getConnectedDevices()`" reconciliation — reuse that lesson.

**Registration lifecycle (canonical, all repos agree):**
`Adapter on → getProfileProxy(HID_DEVICE) → ServiceListener.onServiceConnected → build SDP (name/subclass/descriptor) → registerApp → onAppStatusChanged(registered=true) → [bonded device chosen] → connect(device) → onConnectionStateChanged(STATE_CONNECTED) → sendReport(…) … → teardown: disconnect → release neutral reports → unregisterApp → closeProfileProxy`.

---

## 7. Mouse / touchpad analysis (the system-cursor requirement)

**How a real system cursor happens:** relative motion reports (`Input (Data,Var,Rel)`) on the mouse collection with `Logical Minimum -127 / Maximum 127`, sent via `sendReport(id=MOUSE_ID, …)`. Android (as *host*) injects these into its InputReader the same way as a USB mouse; the pointer is drawn by the Android window manager on the receiver — SEEN pattern in all mouse-capable repos (phonepad:1890-1904, Gamepad: MouseProfile.kt:19-26, PhoneDeck:775-787).

**Report layouts observed:**

| Repo | X/Y/Wheel | Buttons | Payload | Notes |
|---|---|---|---|---|
| phonepad | 8-bit rel ±127 | 3 + 5 pad | 5 B (buttons,dx,dy,wheel,hPan) | wheel = round(-dy/11px), clamp ±12 (MainActivity.kt:1498-1503); POINTER_SCALE multiplier (define ~2.0 — see note*) ; `MOUSE_REPORT_ID=1` |
| PhoneDeck | 8-bit rel ±127 | 5 + 3 pad | 5 B | coalescing `sender` thread, `flushMouse()` splits into ±127 steps, min interval throttle, AC Pan support, double-click serialization (BluetoothHidService.kt:723-787) |
| GKME | **16-bit** rel ±32767 | 8 | 9 B (buttons, dx16, dy16, wheel16, pan16) | Resolution Multiplier Feature report (ID 20) for hi-res wheel per Microsoft spec — only GKME does this |
| Gamepad | 8-bit rel; dx/dy clamp ±64, wheel ±24 | 3 + 5 pad | 4 B | sender-side clamp, descriptor allows ±127 (MouseProfile.kt) |
| Bluke | 8-bit rel ±127 | 3 + 5 pad | 4 B | no AC Pan |
| ghost-hid | 8-bit rel ±127 | 3 + 5 pad | 4 B | `PointerMath.chunks()` splits big deltas into ≤127 chunks; `accumulate()` carries fractional remainder so slow drags aren't rounded to 0; `WheelAccumulator` emits notches at stepPx (PointerMath.kt) — *excellent, MIT-licensed, unit-testable design* |
| Atharok/smttgt | 8-bit rel ±127 | 3 + 5 pad | 4 B | upstream TV-remote lineage |

\* phonepad defines `POINTER_SCALE` as a compile-time sensitivity constant (used at MainActivity.kt:1502-1503); its docs prescribe per-user pointer speed tuning.

**Touch→HID pipeline (mandatory, from phonepad + ghost-hid):**
`onTouchEvent(ACTION_DOWN/MOVE/UP/CANCEL)` → track `lastX/lastY` per pointerId → `dx=x-lastX` (raw px) → apply sensitivity (`dx*scale`, keep fractional remainder) → clamp/chunk to ±127 → `sendReport(mouseId, [buttons, dx, dy, wheel, hPan])` → on up/cancel send zero-dx report with correct button bits; on cancel/teardown send all-buttons-up neutral report. Two-finger vertical drag → wheel notches (phonepad:1495-1497); tap (below slop & duration) → left click; drag mode toggle for held-button drag; double-tap-drag optional.

**MotionEvent specifics to get right:** `event.actionMasked`, `pointerCount >= 2` for scroll gesture, `event.getHistoricalSize()` for batching (optional for MVP), `ACTION_CANCEL` must release held buttons/keys (this workspace's REPORT.md "guaranteed input release" pattern; phonepad `releaseAllMouseButtons` on cancel/lifecycle).

**Acceleration:** none of the repos implement pointer acceleration curves on the sender; sensitivity is a linear scale with fractional carry. INFERRED: keep sender linear; let the receiver apply its own system pointer speed (Android: Settings→Accessibility pointer speed).

**Report pacing:** phonepad sends per `ACTION_MOVE` (uncoalesced, works); PhoneDeck coalesces via a `HandlerThread` + min-interval throttle + explicit ±127 splitting (better under fast flicks). GKME/phonepad/this-workspace use event-driven sends; gamepads additionally use a 100 ms heartbeat in this workspace's app. INFERRED: adopt PhoneDeck-style coalescing with ghost-hid's fractional accumulator; cap ~60–125 Hz.

**Why fake-cursor UIs fail the requirement:** they mutate on-screen `Offset` inside Compose/View and never call `sendReport`. Detection: search for `sendReport` with the mouse report ID — in all seven repos it exists; in a fake implementation it does not. Our design forbids any view-space cursor state; the touchpad surface must be treated as a relative-input digitizer.

**Stuck buttons:** all repos route `disconnect()`/lifecycle/`ACTION_CANCEL` to an explicit all-buttons-zero report. MUST replicate; treat as P0 acceptance criteria (see §13/§15).

---

## 8. Keyboard analysis

**HID Keyboard/Keypad page (0x07), standard 6KRO report** (every full keyboard implementation observed):
`[modifier(1)] [reserved(1, const)] [key1..key6(6)]` — usages 0xE0–0xE7 modifiers (LCtrl..RGUI), key usages 0x00 reserved, 0x04–0x1D A–Z, 0x1E–0x27 1–0, 0x28 Enter, 0x29 Esc, 0x2A Backspace, 0x2B Tab, 0x2C Space, 0x2F/0x30… punctuation, 0x4F–0x52 arrows, 0x3A–0x45 F1–F12, 0xE1 LShift etc.

**Descriptor variants observed:**

| Repo | Reserved byte | LED output (0x91) | Key logical max | 6 slots |
|---|---|---|---|---|
| PhoneDeck | ✓ const | ✓ 5 bit + 3 pad (NumLock..Kana) + `handleLeds()` wires `keyboardLeds` to UI (BluetoothHidService.kt:217, 362-374) | 255 (0x26 FF00) | ✓ |
| GKME (ID 17) | ✗ (modifier then LEDs then keys) | ✓ 5 bit + 3 pad | 101 (0x25 0x65) | ✓ |
| Bluke (ID 1) | ✓ const | ✓ 5 bit + 3 pad | 101 | ✓ |
| phonepad (ID 2) | ✓ const (0x81 0x03) | ✗ | 255 | ✓ |
| ghost-hid (ID 1) | ✓ const (0x81 0x01) | ✓ 5 bit + 3 pad | 101 | ✓ |
| Atharok/smttgt (ID 1) | **✗ none** | ✗ | 255 | **✗ 1 key byte only** |

**Physical usages vs Unicode:** HID reports carry *usages*, not characters. Text typing therefore (a) maps chars → (usage, shift) for the **US layout** only (PhoneDeck `usKeyFor()` BluetoothHidService.kt:680-700; Atharok `VirtualKeyboardLayout` maps per layout incl. AZERTY/QWERTZ in Bluke) and (b) cannot express characters absent from the host layout without host IME composition. PhoneDeck `typeText()` (:617-629) types US-mapped strokes with timing; `untypeable()` counts skipped chars; `typeTextJob()` supports progress/cancel. INFERRED: Android receiver will feed usages through its own keylayout (generic.kl) into the focused app — same mechanism as a USB keyboard.

**Required features:** modifier combos (Ctrl+C etc.), F-keys, navigation cluster, arrows, Tab/Esc/Backspace/Delete, GUI/Meta, Caps Lock (LED handling optional but PhoneDeck/Bluke/ghost-hid wire it), NumPad, media via consumer ID 4.

**Recommended keyboard engine design:**
1. State: `modifiers: Int (8 bits)`, `keys: IntArray (≤6 distinct)`.
2. `press(usage)`/`release(usage)`/`tap(mods, usages…)` with neutral report on release — PhoneDeck `sendKeyboard/tapKeys`; guard against rollover overflow (drop or N-key-conflict policy, 6KRO is a hard limit).
3. Text path: US-layout map only for v1; expose `untypeableCount` in UI; never fake arbitrary Unicode.
4. On disconnect/cancel/teardown → send `[0,0,0,0,0,0,0,0]` (all keys up) — phonepad:1921, PhoneDeck:799-801.
5. Dead-key/composition: INFERRED receiver-side concern; out of scope for us.

---

## 9. Gamepad analysis

**HID collections observed:** Generic Desktop (0x01/0x05) `Game Pad` (0x05) application, buttons (page 0x09, usages 1..N), hat switch (0x39, 4-bit, null state on release, physical max 315°, unit degrees 0x65 0x14), axes X/Y/Z/Rx/Ry/Rz (0x30–0x35), optional Sim Ctrls page (0x02, Brake 0xC4 / Accelerator 0xC5 — GKME pedals), optional Slider (0x36)/Dial (0x37) — PhoneDeck uses Slider/Dial as "games never use those to drive the menu cursor" pedals.

**Axis encodings:**
- phonepad: n/a · PhoneDeck: X 16-bit signed (−32767..32767, steering), Y/Rx/Ry/Slider/Dial 8-bit unsigned 0..255 (center ~128), hat 4-bit + 4 pad, 32×1-bit buttons, 12-byte LE payload (Int16 X + 5 + 1 + 4).
- Gamepad repo: all 8-bit unsigned 0..255, center 128 (X/Y/Rx/Ry + triggers Z(0x32)/Rz(0x35) 0..255), hat 4-bit null-state, 16 buttons, **9-byte payload** (`HidReportEncoder.kt:16-27,60-69`).
- GKME Android combo: buttons 2 B (16), X/Y 8-bit −127..127, hat 4-bit+pad, **Z/Rz 8-bit −127..127 (right stick)**, Brake/Accelerator Sim-Ctrls 8-bit 0..255, 9-byte payload (ClassicHidTransport.kt:848-909).
- Bluke: **five 16-bit axes 0..65535 (center 32768)** X/Y/Z/Rx/Rz, hat 4-bit, 19 buttons + 5 pad, **14-byte** payload, live mode switch (Native/Android/Web) *without* re-registering (GamepadDescriptor.kt:131-136, GamepadReport.kt).

**Android button mapping — the trap (SEEN):** the Linux kernel `hid-input` maps gamepad buttons positionally (`BTN_GAMEPAD + n − 1`), so Android's `Generic.kl` only yields the expected `KEYCODE_BUTTON_*` for specific indices. Both Gamepad repo and Bluke document this: working Android bit order ≈ `[A(1), B(2), X(4), Y(5), L1(7), R1(8), Select(11), Start(12), Mode(13), L3(14), R3(15)]` 1-based → Bluke `androidButtonIndices = [0,1,3,4,6,7,8,9,10,11,13,14]` 0-based (+ guide→bit 12, comment "Offsets 2 and 5 are BUTTON_C/Z, not X/Y"), Gamepad repo `ANDROID_BITS = [0,1,3,4,6,7,10,11,13,14,12]`. Windows/SDL order differs (A B X Y L1 R1 Select Start L3 R3 Home = bits 0..10, `STANDARD_BITS`). **INFERRED (strong, corroborated by two independent codebases): an Android receiver decodes buttons positionally; wrong order → wrong/missing buttons.**

**Axes on Android:** Linux maps X/Y→left stick, Z/Rz→right stick (some keylayouts), triggers as `GAS/BRAKE` or `Z/RZ` ranges (this workspace's verified PC result used winmm: left=X/Y, right=Z/R, triggers=U/V, POV neutral 65535 — Windows quirk noted in REPORT.md). For Android, X/Y = left stick is near-universal; right-stick mapping (Z/Rz vs Rx/Ry) and trigger axes are **device/keylayout dependent — UNKNOWN, must be tested**.

**XInput vs DirectInput vs standard HID gamepad:** XInput is Microsoft's proprietary USB protocol (VID_045E); **never** claim XInput compatibility — no investigated repo does; Windows sees our phone as a generic DirectInput-style HID gamepad ("HID-compliant game controller" — confirmed on this workspace's PC host, REPORT.md CHECK 7). Steam: works via DirectInput or Steam Input remapping (Bluke docs note Steam Input needed for many titles — SEEN, docs/USAGE.md:82).

**Hat switch:** 4-bit null-state (`INPUT Data,Var,Abs,Null` 0x42), Logical 0..7, physical max 315°, `0x46 0x3B 0x01`, unit degrees `0x65 0x14`, neutral = 8/0x0F (Gamepad uses no-null mask? Gamepad descriptor uses `0x81 0x42` null-state with logical max 7 and null handled by encoder's `dpad.hatValue`; Bluke uses 0x0F neutral explicitly, `GAMEPAD_HAT_NEUTRAL = 0x0F`). Both agree Android/Linux expect 8 = centred-null.

**Dead zones:** sender-side ~8% (this workspace's app) or Bluke-level (unknown value); keep configurable 5–15%, recentre on release (this workspace REPORT.md: "stick recenter on release").

**Recommendation for Android receiver gamepad collection (test matrix):** start from GKME's ANDROID combo gamepad collection (8-bit signed −127..127 X/Y/Z/Rz + hat + 16 buttons, Sim-Ctrls Brake/Accelerator 0..255) **or** Gamepad repo's collection (0..255 center 128, triggers Z/Rz); test both against the chosen receiver; freeze the winner. Keep *values-only* Android/Windows mode switching (Bluke pattern) rather than descriptor switching (GKME pattern) if Windows support is later added — descriptor is frozen at pairing time.

---

## 10. Consumer / media controls analysis

Two descriptor families:
1. **Consumer Control 16-bit array** (usage page 0x0C, usage 0x01, logical 0..1023/0x029C, `INPUT Data,Array,Abs`) — PhoneDeck (ID 4), Bluke (ID 4), ghost-hid (ID 4, logical max 0x029C), Atharok (ID 2, 1023). One 16-bit usage per report: e.g. Volume Up `0x00E9`, Volume Down `0x00EA`, Mute `0x00E2`, Play/Pause `0x00CD`, Next `0x00B5`, Previous `0x00B6`. Release = zero report (PhoneDeck `setConsumer(0)`, ghost-hid auto-release after holdMs).
2. **System Control bitmap** (usage page 0x01, usage 0x80 System Control, 3 bits: Power Down 0x81 / Sleep 0x82 / Wake 0x83) — ghost-hid ID 3 only.

**Recommendation:** include collection ID 4 (16-bit consumer array) in the composite; exclude System Control initially (power/wake is risky UX: accidental suspend/wake on the receiver). Map: play/pause, next/prev, vol±, mute, maybe WWW/Back/Forward. Media keys go to the receiver's media session via Android's media key handling — INFERRED; works like a headset button on the receiver.

---

## 11. Android receiver compatibility analysis (the critical question)

**Mechanism (SEEN from AOSP-adjacent sources + API docs):** PHONE B's Bluetooth stack exposes the paired peripheral to the input framework as an external input device (`InputReader` → `InputClassifier`); `Generic.kl` / device keylayout maps key usages → Android key codes; mouse reports produce relative pointer events consumed by the window manager (pointer rendered on-screen on devices with pointer support); gamepad HID descriptors become a `InputDevice` with source `SOURCE_GAMEPAD` (axes `AXIS_X/Y/Z/RZ/HAT_X/HAT_Y/LTRIGGER/RTRIGGER`, buttons `KEYCODE_BUTTON_*`) — only if the keylayout matches.

**Evidence per receiver behaviour class:**

| Behaviour on receiver | Status | Basis |
|---|---|---|
| Keyboard usages → focused app text input | INFERRED (high) | Standard HID keyboard enumeration; generic.kl; PC-host proof of the same bytes (this workspace/phonepad verified PC, not Android) |
| Mouse relative reports → on-screen pointer, clicks | INFERRED (medium-high) | Android external-mouse support exists since 4.x era; pointer visibility varies by OEM/device — **this workspace verified PC, not Android; no repo proves phone→phone cursor** |
| Scroll wheel → vertical scroll in scrollable views | INFERRED (medium) | Mouse wheel maps to `AXIS_VSCROLL`; app-dependent |
| AC Pan horizontal | INFERRED (low-medium) | Maps to `AXIS_HSCROLL` where supported |
| Gamepad generic usages → `KEYCODE_BUTTON_*` per Generic.kl positional mapping | SEEN as code-level mapping knowledge (Gamepad repo + Bluke comments, independently corroborated) | Whether *every* OEM receiver ships a usable gamepad keylayout: UNKNOWN |
| Analog sticks → `AXIS_X/Y`, right stick mapping | UNKNOWN across OEMs | Keylayout-dependent; must test |
| HID Host disabled while PHONE A app registered on PHONE B? No — disabled on the *registering* phone only (PHONE A), per API doc | SEEN (official docs) | PHONE B unaffected |
| PHONE B must have HID Host profile enabled | INFERRED | Standard on modern Android; no UI to toggle; restrictions exist in AOSP builds for some devices (SEEN as Bluke warning) |

**"Android Bluetooth HID Device Profile Compatibility List":** No authoritative public list was retrievable during this research (web search service returned HTTP 402; developer.android.com API pages were reachable, source.android.com 404s on the guessed URLs). Known public signals: Bluke docs ("Some budget phones or modified manufacturer ROMs disable this feature", USAGE.md:200), Gamepad README targets "Android/Google TV and compatible Android hosts", GKME README claims "PC or another phone" with no published logs, bluetooth-control README claims "Android 4 and later", phonepad README documents S23 Ultra→Win11 only. **There is no evidence that any OEM or Android version categorically *fails* as a HID Host — but there is also no proof of success on any specific receiver model. All receiver-side claims are UNKNOWN until physically tested.**

**Minimum bar for PHONE B:** Android 4+ (per Atharok/smttgt claim) — practically test Android 10+ (10, 12, 13, 14, 15, 16) on at least two OEMs (e.g., Samsung + Pixel) plus one TV-class device if available.

**This workspace's situation:** PHONE A = vivo Y29 (<PHONE_SERIAL>), PHONE B candidate = tablet <TABLET_SERIAL> (both visible over ADB in HANDOFF.md inspection), and REPORT.md confirms HID-over-Bluetooth to a Windows laptop today. The Android-receiver campaign has **not** been run.

---

## 12. Physical-device testing plan (do NOT execute during static phase; these require hardware)

**Test matrix** (at minimum: PHONE A = vivo Y29 or current test phone; PHONE B receivers = <TABLET_SERIAL> tablet + one second phone; PC control = current Windows laptop):

### PHONE A (controller) — per device
1. HID Device availability: `getProfileProxy(HID_DEVICE)` non-null.
2. `registerApp()` accepted → `onAppStatusChanged(registered=true)` within 8 s (twice).
3. Composite descriptor with 4 report IDs registers without error; check `adb logcat -s HidGamepad` (or new tag).
4. Pair to receiver; confirm receiver enumerates **one** HID device with descriptor intact (`adb shell dumpsys bluetooth_manager` / receiver logs if accessible).
5. `sendReport` returns true for IDs 1/2/3/4; interrupt traffic visible (btsnoop HCI log if possible).
6. Reconnect after: Bluetooth toggle off/on; app background→foreground; force-stop→relaunch; receiver unpair/re-pair.
7. Descriptor change test: change descriptor → confirm receiver keeps stale mapping until unpair/re-pair (validates caching assumption).

### PHONE B (receiver) — per device/version (API 29/31/33/34/35/36)

| # | Test | Pass criteria |
|---|---|---|
| B1 | Pair PHONE A; open Android Settings → Bluetooth shows HID device | Device present; input device class shown |
| B2 | Keyboard: focus a text field; type A–Z, 0–9, Shift combos, Ctrl+C/V, arrows, Enter, Esc, F1–F12 | All arrive; correct case; combos work in browser/notes |
| B3 | Mouse: cursor appears on screen; move; left/right/middle click; wheel scroll in browser & app drawer; AC Pan horizontal scroll | Cursor moves; clicks land; scroll works |
| B4 | Gamepad in a gamepad-tester app (e.g., Gamepad Tester): axes X/Y/Z/RZ, hat, 16 buttons | All controls register; labels match intent; POV neutral reads centered |
| B5 | Gamepad in a real game (e.g., a racing/platformer title) | Start/select menu navigation works at minimum |
| B6 | Simultaneous composite input: move mouse while a modifier is held; keyboard+mouse interleaved; gamepad+keyboard chord | No dropouts/corruption/stuck states |
| B7 | Disconnect mid-hold (mouse button / key / stick) | Receiver sees buttons/keys release (no stuck keys/sticks) |
| B8 | Re-pair requirement after descriptor change | Re-pair restores new mapping |
| B9 | Receiver Bluetooth off→on while controller connected | Clean reconnect via PHONE A auto-reconnect |

### Combined
- Simultaneous two-finger? N/A cross-device. Combined: type + move + gamepad in sequence; 30-minute soak; battery/thermal sanity on PHONE A; notification shade interference; call interruption.

**Requires physical hardware:** everything in §12. **Not executable in this static phase** by design; this section is the hand-off to the implementation agent's device lab.

---

## 13. Connection reliability & stuck-input elimination

**Failure modes observed or documented across repos:**
- Registration rejected/timing out on OEM-restricted firmware (Bluke HidLifecycle discriminates rejection vs timeout; GKME watchdog+retry; this workspace REPORT.md indefinite retry with backoff 1.5→3→6→10 s cap).
- `getConnectedDevices()` lag after connect (this workspace: trust plugged-device callback first; require two empty queries ~1 s apart to clear host).
- `registerApp()` auto-unregisters when app not foreground (official docs) → connectedDevice foreground service is mandatory (all repos declare one; phonepad/Bluke/ghost-hid/Gamepad use `connectedDevice`).
- Descriptor cached at pairing → any descriptor change needs unpair/re-pair (PhoneDeck comment :65-68, ghost-hid ADR-002, Bluke comment GamepadDescriptor.kt:131-133).
- Stuck buttons/keys on `ACTION_CANCEL`, composition teardown, disconnect → every serious repo sends explicit all-zero reports (phonepad `releaseAllMouseButtons/Keys`, PhoneDeck `releaseAllInput`, this workspace try/finally + neutral-on-teardown, Bluke lifecycle policies). **P0 requirement.**
- Boot-protocol hosts (BIOS/pre-boot) ignore report IDs → report mode only; PhoneDeck stays silent in boot protocol.
- LED output reports (keyboard) are host→device; safe to ignore at controller (we only *send* input reports; GKME/PhoneDeck/Bluke additionally surface LED state — optional).
- Virtual cable unplug (`onVirtualCableUnplug`) → treat as full disconnect: release all + clear state (Gamepad HidConnectionManager.kt:61).
- Permission revocation mid-session → guard every BT call (Gamepad bondedDevices try/runCatching comment :81-87).

**Neutral-report contract (adopt verbatim):** on DISCONNECT, on background, on `ACTION_CANCEL`, on composition teardown: send `[0]*reportLen` for IDs 1 (all keys up), 2 (buttons 0, dx/dy/wheel 0), 3 (hat=8/0x0F or Android null, axes centred, buttons 0), 4 (usage 0). Log each neutral send.

---

## 14. Composite descriptor recommendation

### 14.1 One descriptor vs multiple selectable descriptors — decision

**Decision: ONE composite descriptor, four report IDs, frozen at first release; mode switching changes *values only*.**

Evidence:
- All production-grade repos with >2 functions use one combo SDP record with report IDs (PhoneDeck 1/2/3/4; Bluke 1/2/3/4; ghost-hid 1/2/3/4; Atharok 1/2/3; phonepad 1/2; GKME 17/18/19+20 feature). SEEN.
- ghost-hid ADR-002: Android permits **one** registered HID application per app context; separate profile instances collide (SEEN, ADR-002).
- Host caches descriptor at pairing → switching descriptor post-pair silently desyncs (SEEN comments in 3 repos + this workspace's README states "Mode switching … does not re-pair", achieved via within-descriptor report IDs).
- GKME's multi-descriptor approach exists because it ships *per-host-target byte layouts* (Windows XInput positional vs Android positional vs Linux); it pays for it with unregister/re-register flows, re-pair UX, and a target-platform setting. INFERRED: acceptable for its dual PC/Android scope, unnecessary complexity for us.
- Bluke's SHARED_HID_DESCRIPTOR + values-only Android/Native/Web modes is the strictly better pattern for us (SEEN GamepadDescriptor.kt:131-143) — and directly matches this workspace's app (combined descriptor, values-only gamepad encoding).

**Re-pair ceremony:** only needed if the descriptor bytes change. Therefore: freeze v1 descriptor before first external install; treat any post-release byte change as a breaking release requiring unpair/re-pair instructions.

### 14.2 Frozen descriptor plan (byte-level, intersection of proven layouts)

**ID 1 — Keyboard (8-byte) + LED output** (PhoneDeck/GKME/Bluke/ghost-hid consensus):
`05 01 09 06 A1 01 85 01 | 05 07 19 E0 29 E7 15 00 25 01 75 01 95 08 81 02 | 95 01 75 08 81 01 | 95 05 75 01 05 08 19 01 29 05 91 02 | 95 01 75 03 91 01 | 95 06 75 08 15 00 26 FF 00 05 07 19 00 29 65 81 00 C0`
- 6KRO, modifiers 0xE0–E7, reserved const byte, LED output (NumLock/Caps/Scroll) — optional to consume.

**ID 2 — Mouse (5-byte) with AC Pan** (phonepad/PhoneDeck proven):
`05 01 09 02 A1 01 85 02 09 01 A1 00 05 09 19 01 29 05 15 00 25 01 95 05 75 01 81 02 95 01 75 03 81 01 05 01 09 30 09 31 09 38 15 81 25 7F 75 08 95 03 81 06 05 0C 0A 38 02 15 81 25 7F 75 08 95 01 81 06 C0 C0`
- 5 buttons + 3 pad, 8-bit relative X/Y/Wheel, AC Pan (0x0C/0x38/0x02) horizontal, INPUT Data,Var,Rel.
- (Option: GKME-style 16-bit + Resolution Multiplier Feature (ID 5) only if 120-detent hi-res scroll proves necessary on target hosts — defer; adds a Feature report and host dependence.)

**ID 3 — Gamepad (Android-tuned, 8-bit signed, 10-byte payload)** — start with GKME's Android collection verbatim-in-spirit (not code copy):
`05 01 09 05 A1 01 85 03 | 05 09 19 01 29 10 15 00 25 01 95 10 75 01 81 02 | 05 01 09 30 09 31 15 81 25 7F 75 08 95 02 81 02 | 09 39 15 00 25 07 35 00 46 3B 01 65 14 75 04 95 01 81 42 65 00 75 04 95 01 81 03 | 09 32 09 35 15 81 25 7F 75 08 95 02 81 02 | 05 02 09 C4 09 C5 15 00 25 FF 75 08 95 02 81 02 C0`
- 16 buttons (2 B), X/Y left stick ±127, hat 4-bit null + 4 pad, **Z/Rz right stick ±127**, Brake/Accelerator (Sim Ctrls) 0..255 pedals.
- Fallback candidate B: Gamepad-repo collection (0..255 unsigned, center 128, triggers Z/Rz) if receiver keylayout rejects signed axes.
- Button bit order **Android positional** (see §9): A=bit0, B=bit1, X=bit3, Y=bit4, L1=bit6, R1=bit7, Select=bit10, Start=bit11, Mode=bit13, L3=bit14, R3=bit12 per Gamepad repo `ANDROID_BITS = [0,1,3,4,6,7,10,11,13,14,12]`; Bluke's `androidButtonIndices = [0,1,3,4,6,7,8,9,10,11,13,14]` differs (includes 8,9,10→bits 8..10 and drops 12 differently). **These two encoders disagree slightly on indices 8–12 — SEEN. The exact Android offset table MUST be validated on real receiver hardware in phase 0/1; start with GKME/Gamepad-repo layout, parametrize the table.**

**ID 4 — Consumer Control (2-byte)** (PhoneDeck/Bluke/ghost-hid):
`05 0C 09 01 A1 01 85 04 15 00 26 FF 03 19 00 2A FF 03 75 10 95 01 81 00 C0`
- usages 0x00E9/0x00EA/0x00E2/0x00CD/0x00B5/0x00B6; zero report = release.

**SDP record:** name `HID Controller` (or PHONE A's device name), description `USB/Bluetooth composite input`, provider = app name, `SUBCLASS1_COMBO`, descriptor above; QoS null/zero (phonepad/GKME) or explicit 11.25 ms (PhoneDeck) — INFERRED either acceptable; start with nulls, measure.

---

## 15. Recommended architecture (target design)

```
Phone A app
├── HidForegroundService (connectedDevice)  ← keeps registration alive (official API contract)
│    └── HidController (singleton facade)
│         ├── BluetoothHidManager          ← proxy, registerApp, callbacks, retry/watchdog
│         ├── PairingManager               ← bonded list (computers/phones first), last-host auto-connect, discoverable window on demand only
│         ├── ConnectionStateManager        ← CONNECTED/CONNECTING/... single source of truth; twice-empty reconciliation; poll 5 s; events
│         ├── HidDescriptorRegistry         ← frozen composite bytes (§14); SHA-256 log; no runtime switching
│         ├── HidReportEncoder              ← pure functions: encodeKeyboard/Mouse/Gamepad(mode)/Consumer; unit-tested
│         ├── MouseInputEngine              ← relative deltas, ±127 chunking, fractional accumulator, button bitfield, coalescing sender (HandlerThread), min-interval
│         ├── TouchGestureEngine            ← MotionEvent → MouseInputEngine; tap=click; 2-finger scroll; drag-mode; double-tap drag (opt-in); ACTION_CANCEL neutralization
│         ├── KeyboardInputEngine           ← modifiers+6KRO state; tap/press/release; US-layout text typer (v1); neutral-on-teardown
│         ├── GamepadInputEngine            ← Android positional bits table (parametrized); hat null-state; axes ±127; deadzone; recentre-on-release; 100 ms heartbeat while connected (this workspace pattern)
│         ├── MediaInputEngine              ← consumer usages; auto-release after hold
│         └── NeutralReportReleaser         ← sends all-zero reports on disconnect/background/cancel/teardown (P0)
├── Controller UI (Compose)                 ← touchpad screen · keyboard screen · gamepad screen · media strip · devices/pairing screen · settings
├── Preferences                             ← sensitivity, scroll speed, deadzone, layout, host auto-reconnect, descriptor version
└── Diagnostics                             ← log tag `HidController`, send/fail counters, registration SHA-256, report ring buffer (debug builds)
```

**Per-component strongest reference:**

| Component | Reference repo (licence) | What to take |
|---|---|---|
| Registration + lifecycle/retry | Bluke `HidLifecycle.kt`, `HidRegistrationCoordinator.kt` (AGPL — reimplement) + GKME watchdog (GPL — reimplement) + this workspace `HidGamepadManager.kt` (own) | retry/backoff semantics, rejection-vs-timeout discrimination |
| Descriptor (composite, 4 IDs) | ghost-hid ADR-002 (MIT, doc) + PhoneDeck bytes (no licence, study) + GKME Android collection (GPL, study) | frozen byte layout §14 |
| Report encoders (pure, testable) | Gamepad `HidReportEncoder.kt`/`MouseProfile.kt` (no licence — re-derive) + ghost-hid `PointerMath.kt` (MIT — adapt with notice) | Android bit-table parametrization, fractional accumulation, ±127 chunking |
| Mouse/touchpad UX engine | phonepad `MainActivity.kt` touch block + `docs/HID.md` (GPL — reimplement) + Bluke touchpad UX (AGPL — study) | two-finger scroll, tap/drag, POINTER_SCALE, cancel-release |
| Keyboard | PhoneDeck `typeText/usKeyFor/sendKeyboard` (study) + Bluke layouts (study) + Atharok map table (GPL — re-derive) | 6KRO state machine, US layout v1 |
| Gamepad | Gamepad repo Android bits (no licence — re-derive) + Bluke Android mode switch (study) + GKME Android combo bytes (study) + this workspace `GamepadState.kt` (own) | positional bits, hat null, deadzone, recentring |
| Connection state reconciliation | this workspace `REPORT.md` (own) + Gamepad `HidConnectionManager` (no licence) | twice-empty host clearing, poll loop, heartbeat |
| Pairing UX | phonepad "new-PC" discoverable-on-demand + `PhoneDeck` PC-first bonded sort | computer/phone-first list, no auto-discoverable |
| Foreground service | Gamepad/Bluke/phonepad/ghost-hid `connectedDevice` manifests | FGS type + notification + retry |
| UI patterns | Bluke (study) + this workspace `GamepadScreen.kt` (own) | minimal, mode selector, 48 dp targets |
| Media engine | PhoneDeck/ghost-hid consumer ID 4 | usage map + auto-release |

**Legally reusable:** only ghost-hid (MIT) pieces with notice; this workspace's own code. Everything else: clean-room reimplementation informed by specs. If we later decide to open-source as GPL-3.0, GKME/phonepad/bluetooth-control/Atharok code could be adapted with attribution — decision deferred, see §5.

**Required Android APIs:** `BluetoothAdapter`, `BluetoothProfile`, `BluetoothHidDevice(+Callback/AppSdpSettings/AppQosSettings)`, `BluetoothDevice`, `BluetoothClass` (device filtering), `BLUETOOTH_CONNECT/ADVERTISE` runtime perms, FGS type `connectedDevice`, `MotionEvent`, Compose UI, DataStore preferences. **No root, no Shizuku, no internet permission** (phonepad declares none — SEEN; adopt this).

**Compatibility issues that must be solved:** (1) receiver HID Host acceptance across OEMs; (2) receiver gamepad keylayout bit order; (3) controller-side HID Device profile presence; (4) descriptor caching/re-pair; (5) registration persistence via FGS; (6) stuck-input neutralization; (7) Windows/macOS secondary targets — explicitly gated behind Android-to-Android success.

**Build order (priority):** Phase 0 device spike → HID registration + §14 descriptor + neutral-release skeleton → Phase 1 mouse (touchpad + click + wheel) → Phase 2 keyboard (6KRO + US typer) → Phase 3 gamepad (Android bits, deadzone) → Phase 4 consumer media → Phase 5 hardening (reconnect soak, permission revocation, low battery) → Phase 6 optional: Windows/Linux host support values-mode, hi-res wheel, wake/power keys (exclude), BLE HID (exclude).

---

## 16. Technical unknowns (explicit)

1. Whether any specific Android receiver accepts a classic HID gamepad with our chosen descriptor — UNKNOWN per OEM/version.
2. On-screen mouse pointer visibility on Android receivers (pointer may require external-pointer support flag or be invisible without `PointerIcon`/accessibility) — UNKNOWN.
3. Right-stick / trigger axis mapping on Android (Z/Rz vs Rx/Ry vs Sim-Ctrls brake/accelerator per keylayout) — UNKNOWN; two candidate layouts shipped in the wild.
4. Exact Android positional button-offset table for the chosen receiver kernel (Gamepad repo `[0,1,3,4,6,7,10,11,13,14,12]` vs Bluke `[0,1,3,4,6,7,8,9,10,11,13,14]`) — SEEN disagreement; UNKNOWN which is right per receiver.
5. Whether `BLUETOOTH_SCAN` is ever required (phonepad: no; Bluke: declared `neverForLocation`) — likely no; verify during spike.
6. Whether `SUBCLASS1_COMBO` vs a manual combo subclass byte matters on Android receivers — likely cosmetic; verify.
7. Registration auto-unregister aggressiveness across OEMs (docs say "not foreground ⇒ unregistered"; whether FGS `connectedDevice` always protects registration on all builds) — INFERRED yes; verify on vivo/Samsung/Pixel.
8. QoS zeros vs explicit 11.25 ms latency — no repo measured; measure both.
9. Receiver BT toggle while controller connected — behaviour unknown; test matrix §12 B9.
10. iOS/macOS as receiver — out of scope; do not spend effort here (Bluke explicitly unsupported).

---

## 17. Compatibility risks (severity-ranked)

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| R1 | Receiver HID Host missing/disabled on some OEM (no pairing of HID device class) | **Critical** | Phase-0 spike on 2+ OEMs before any UI work; graceful "HID host not available" UX (Bluke capability diagnosis) |
| R2 | Receiver keylayout maps gamepad buttons positionally to wrong BUTTON_* codes | High | Android bit table as runtime-configurable preference with A/B validation in tester app |
| R3 | Controller firmware lacks HID Device profile (budget ROMs) | High | Capability check + clear error (Bluke HidFailure); list of known-good devices from test matrix |
| R4 | Descriptor cached by receiver; app updates break pairing for existing users | High | Frozen descriptor; versioned SDP description; release notes require unpair/re-pair on descriptor change |
| R5 | Registration dropped when app backgrounded | High | connectedDevice FGS + watchdog re-register (this workspace pattern) |
| R6 | Stuck keys/buttons after disconnect/cancel | High | Neutral reports on all teardown paths; automated unit tests asserting neutral emission |
| R7 | Mouse pointer invisible on receiver (accessibility/pointer settings) | Medium | Document receiver requirement ("Enable pointer in Settings→Accessibility or attach mouse"); verify each test device |
| R8 | 6KRO rollover limits complex chords | Medium | Document; drop-oldest policy; no NKRO promise |
| R9 | Non-US layout text typing garbles | Medium | US layout only in v1; visible untypeable counter; later per-host layout picker |
| R10 | Auto-unregister while FGS "foreground" but process cached | Low-Medium | Health poll every 5 s (this workspace) + onResume re-register |
| R11 | ADVERTISE/DISCOVERABLE friction during pairing | Low | Discoverable only on explicit "Pair new device" action; timeout 5 min |
| R12 | BOM/consumer usages misdelivered as keyboard chords | Low | Consumer collection separate report ID; verify receiver media focus behaviour |

---

## 18. Recommended descriptor freeze & mode strategy

Selected descriptor = §14.2 IDs 1–4. Mode switching (Gamepad-ish/Android vs standard) implemented **inside** the gamepad encoder by swapping the button bit table + axis polarity preferences — never by re-registering or re-pairing. Diagnostic: log `descriptorSha256` at every registration (Bluke pattern) to detect accidental byte drift in CI via a golden-bytes unit test (`HidDescriptorGoldenTest`).

---

## 19. Development roadmap

| Phase | Deliverable | Exit criteria |
|---|---|---|
| **P0 — Device spike (hardware, 1–2 sessions)** | Minimal app: register composite descriptor; connect to PHONE B; send one report per ID; logcat evidence | `registered=true`, receiver lists device, cursor moves on PHONE B (or documented device-specific failure with exact model/build) |
| P1 — Skeleton + registration lifecycle | FGS, retry watchdog, state manager, neutral releaser, on-screen diagnostics | Force-stop/relaunch/Bluetooth-toggle recovery verified on 2 receivers |
| P2 — Mouse engine | Touchpad UI, move/click/wheel/h-wheel, drag mode, tap detection | §12 B3 pass on both receivers; no stuck buttons |
| P3 — Keyboard engine | 6KRO + modifiers + US typer + LED-less MVP | §12 B2 pass; chords work in 2 apps |
| P4 — Gamepad engine | Android positional encoder + deadzone + recentring + heartbeat | §12 B4/B5 pass; neutral on cancel verified |
| P5 — Consumer + settings + polish | Media strip, sensitivity/deadzone/layout prefs, onboarding | §12 B6/B7 pass |
| P6 — Hardening & release | Soak test, permission revocation, docs, store listing | §12 all pass ×matrix; REPORT.md updated |

**This workspace's existing app** (`com.hidgamepad`) covers P4 gamepad-only + PC-host verification (REPORT.md). Reuse its `GamepadState.kt`/`HidGamepadManager.kt`/`Joystick.kt` lessons; extend, don't wholesale rewrite — but note its descriptor is gamepad-only, so the §14 composite replaces it (requires unpair/re-pair once, documented).

**Must be completed BEFORE implementation starts:**
1. §14 descriptor byte-string agreed and frozen (this doc).
2. Licence memo signed off (§5) — confirm clean-room scope.
3. Phase-0 hardware spike executed and PHONE B verdict recorded per receiver (R1/R2 gate).
4. Unit-test harness decision (JUnit; Gamepad-style pure encoders; no instrumented dependence on BT hardware).
5. Stuck-input acceptance criteria added to the manual test checklist (extend `docs/hid-gamepad-manual-test-checklist.md`).

---

## 20. Reading order for the implementation agent

`docs/LEANREA_BLUETOOTH_HID_RESEARCH.md` (this file) →
this workspace `REPORT.md` + `README.md` + `app/src/main/kotlin/com/hidgamepad/*` (proven registration + 9-byte Android gamepad + PC evidence) →
`%TEMP%\re\ghost-hid\docs\decisions\ADR-002-dual-report-descriptor-for-keyboard-and-mouse.md` + `…\app\src\main\java\com\sj010\ghid\domain\pointer\PointerMath.kt` (MIT — reusable) →
`%TEMP%\re\Gamepad\app\src\main\java\io\github\sensat10on\gamepad\domain\*.kt` (structure reference, no licence — do not copy bytes blindly, re-derive) →
`%TEMP%\re\GKME\android\src\main\java\com\zyz4\gkme\service\ClassicHidTransport.kt` (Android combo descriptor, retry watchdog — study) →
`%TEMP%\re\Bluke\app\src\main\java\dev\arnv\bluke\bluetooth\{GamepadDescriptor,HidLifecycle,GamepadReport}.kt` (composite + lifecycle — study) →
`%TEMP%\re\phonepad\Android\app\src\main\java\com\tygb99\phonepad\MainActivity.kt` (touchpad handling block ~1457-1545, sendMouseReport ~1887-1910, release paths ~1906-1931, descriptor ~2386-2444 — study) →
`%TEMP%\re\PhoneDeck\android\app\src\main\java\com\truckcontroller\pro\bluetooth\BluetoothHidService.kt` (composite, coalescing mouse, keyboard typer — study).

---

## 21. Final decision section (answers to the brief)

1. **Is Android-to-Android Bluetooth HID technically feasible?**
   **Yes by design — UNKNOWN on specific receivers.** The mechanism (Classic HID, API 28+, composite descriptor, interrupt reports, receiver's stock input framework) is fully specified and implemented by seven repos; official API docs confirm the registration contract. No investigated repo or this workspace provides verified phone→phone input logs. The risk is *receiver acceptance*, not the protocol. Feasibility = PROBABLE (keyboard/mouse), PLAUSIBLE but least-proven (gamepad). Must be proven by Phase-0 hardware spike before claiming support.
2. **Which repository is the strongest starting reference?**
   **Bluke** for overall app UX/features (keyboard layouts, touchpad, media, Android gamepad profiles, retry diagnostics) — study-only (AGPL). For license-safe material: **ghost-hid (MIT)**. For this workspace, the practical base is our own `com.hidgamepad` (proven on PC) extended with the composite architecture above.
3. **Which repository has the strongest composite HID design?**
   **PhoneDeck** — complete 4-report composite with LED handling, horizontal scroll, coalesced mouse sender, US-layout text typer, auto-reconnect, and explicit host-descriptor-caching warning — but PC/ETS2-oriented, no-licence, and unproven on Android. **Bluke** is the best-engineered Android-aware equivalent (values-only mode switch, capability diagnosis). **GKME** has the only host-target-differentiated descriptors incl. a dedicated Android 9-byte gamepad collection. On balance: **PhoneDeck's structure + GKME's Android gamepad collection + Bluke's mode-switching discipline + ghost-hid's ADR rationale** — re-derived by us.
4. **Can keyboard, mouse and gamepad be exposed simultaneously?**
   **Yes.** One `registerApp`, one SDP record, one descriptor with collections ID 1 (keyboard), ID 2 (mouse), ID 3 (gamepad), ID 4 (consumer); reports interleave on one interrupt L2CAP channel. SEEN in PhoneDeck/Bluke/GKME(17/18/19)/ghost-hid. The Android docs' "only one registered HID app per app context" rule makes composite the *only* viable design anyway.
5. **What needs to be verified on actual devices?**
   See §12 (PHONE A registration, PHONE B pointer/keys/gamepad/AC-Pan, re-pair-after-descriptor-change, disconnect-mid-hold, BT toggle, 2+ receiver OEMs, voluntary Sim-Ctrls pedal recognition, button bit order). Until done, every receiver-facing claim stays UNKNOWN.
6. **What is the biggest engineering risk?**
   **Receiver-side acceptance and mapping variance (R1/R2/R3)** — an arbitrary Android phone acting as a HID Host for a composite gamepad. Secondary: descriptor caching (R4) breaking shipped users. Mitigation: phase-gated roadmap; Android bits table as preference; capability diagnosis UX; frozen descriptor.
7. **What must be completed before implementation starts?**
   §19 items 1–5: descriptor freeze, licence memo, Phase-0 spike verdicts on ≥2 receivers (incl. <TABLET_SERIAL> tablet and one phone), unit-test harness, neutral-release acceptance criteria. **Do not start feature implementation until the spike records "cursor moved / key typed / gamepad button received" on an Android receiver, or documents the exact device/build failure.**

**Explicitly NOT proven anywhere:** "Works on all Android phones" (no evidence), "no root needed ever" (true for API 28+ with HID Device support; fails only on OEM-restricted firmware — capability check handles it), "XInput support" (never; standard DirectInput-class HID only), "Unicode keyboard input" (impossible with plain usages; US-layout only v1).

---

## Appendix A — line-referenced evidence index

**GKME** (`%TEMP%\re\GKME`, master f079fef):
- `android/src/main/java/com/zyz4/gkme/service/ClassicHidTransport.kt` — profile proxy + registerApp 173-199, 417-464; registration watchdog 512-528; recovery/cleanup 365-415; auto-reconnect 531-549; combo/descriptor selection 424-441; ANDROID combo descriptor (keyboard ID 17 / mouse ID 18 / feature ID 20 / gamepad ID 19) 735-910; UNIVERSAL_KM 1091+; ANDROID_GAMEPAD_ONLY 1206+; `sendMouseReport` (16-bit, ID 18) 256-292; `sendKeyboardReport` (ID 17) 296-322; gamepad-report-ID logic 244-253 (`GAMEPAD_REPORT_ID_COMBO=19` @574).
- `README.md:48` ("PC or another phone … Windows / Android / Linux", claim) · `AndroidManifest.xml` (perms 5-23; FGS specialUse 58-85) · `android/build.gradle.kts:37-39` (minSdk 26/targetSdk 36) · `docs/model-data.md:83` (TargetPlatform list).
- Separate mode: Wi‑Fi LAN / USB-ADB / DSU / Shizuku-uinput "controlled device" — NOT HID; unused for this project (README "Connection Methods").

**Gamepad** (`%TEMP%\re\Gamepad`, main 571d58d, no licence):
- `domain/CombinedHidDescriptor.kt:4-11` (Push 0xA4/Pop 0xB4 wrapping to isolate global state) · `domain/HidReportEncoder.kt:16-27` (descriptor), `41-58` (STANDARD_BITS vs ANDROID_BITS), `60-72` (9-byte encode, axes center 128, ±64 mouse-clamp elsewhere) · `domain/MouseProfile.kt:7-27` (ID 2, ±64/±24 clamps) · `domain/KeyboardReport.kt:1-19` (ID 3; Enter/arrows only) · `bluetooth/HidDescriptor.kt` (SUBCLASS1_COMBO) · `bluetooth/HidConnectionManager.kt:38-112` (callbacks, onGetReport replies, bondedDevices, close) · `bluetooth/HidGamepadService.kt` + Manifest (`foregroundServiceType="connectedDevice"`, 43-47) · `README.md:19-44` (Android TV/host claims) · `AUDIT.md` (no LICENSE; notes Android mapping comments) · `store/tester-recruitment.md:85-136` (wants "second phone" testers).

**PhoneDeck** (`%TEMP%\re\PhoneDeck`, main 8e89eb6, no licence):
- `.../bluetooth/BluetoothHidService.kt` — report-ID constants 39-42; GAMEPAD_REPORT_SIZE=12/AXIS_CENTER=128 :48-49; descriptor :69-201 (keyboard ID 1 + LED out; mouse ID 2 + AC Pan; gamepad ID 3 16-bit steering + 32 buttons; consumer ID 4) ; host-caching warning :65-68; `registerApp` :412-434 (SUBCLASS1_COMBO, "TruckPad"); QoS :421-428; `onAppStatusChanged` auto-connect :296-306; sender thread :238-239; coalescing mouse `flushMouse` :775-787; `sendKeyboard/tapKeys/typeText/typeTextJob/usKeyFor` :596-700; `releaseAllInput` :794-803; `keyboardReport` :702-707; connection states/`lastHost` :55-63; bonded-PC-first sort :436-446; LEDs → `keyboardLeds` :217-232, 362-374.
- `android/app/src/main/AndroidManifest.xml` (services :250-296, mixed FGS types incl. over-privileged connectedDevice|camera|mediaProjection — do NOT copy privilege set) · no LICENSE file; `package.json` none.

**Bluke** (`%TEMP%\re\Bluke`, main bcf4f14, AGPL-3.0):
- `.../bluetooth/GamepadDescriptor.kt` — SHARED_HID_DESCRIPTOR (KB ID 1 + LEDs; mouse ID 2 3-btn 8-bit; gamepad ID 3: 19 buttons+5 pad, hat, 5×16-bit axes 0..65535 center 32768; consumer ID 4 0..1023) :1-129; values-only-switch comment :131-133; `hidDescriptorForMode` :134-136 · `HidLifecycle.kt` (HidFailure, RetryPolicy, BluetoothCapabilityRepository, OEM-incompatibility heuristic) · `HidRegistrationCoordinator.kt` · `GamepadReport.kt` (GAMEPAD_* constants, `androidButtonIndices`, `GAMEPAD_HAT_NEUTRAL=0x0F`, `GAMEPAD_REPORT_SIZE_BYTES=14`, Mode Native/Android/Web :17-30) · `BluetoothKeyboardManager.kt` (registration, Sharp/terminal modes, discovery UX — 1,831 lines) · `docs/USAGE.md:74-82` (Android profile, Steam note, "budget phones/ROMs disable HID Device" :200) · `app/build.gradle.kts:16` (minSdk 28) · Manifest (connectedDevice FGS :26-30).

**bluetooth-control** (`%TEMP%\re\bluetooth-control`, main 035a3af, GPL-3.0; `src` submodule = Atharok BtRemote @ 56ea84e, v1.12.1, GPL-3.0):
- `src/app/src/main/java/com/atharok/btremote/common/utils/Constants.kt:45-136` — descriptor: consumer ID 2, keyboard ID 1 (modifier + **1 key byte**, no reserved/LEDs), mouse ID 3; `REMOTE_INPUT_NONE`; `USE_MOUSE_NAVIGATION_BY_DEFAULT` · `…/BluetoothHidCore.kt` — profile start/stop :44-60; interrupt sends; virtualKeyboardLayout `getKeyboardKey` :168-186 (VirtualKeyboardLayout.kt:18-24 — 2-byte reports) · `patches/0001-support-pc.patch` (renumber mouse↔remote IDs; wrap `sendAndroidReport`) · `patches/0002-direct-input.patch` (AndroidKeyMapper, VirtualKeyboard) · `patches/0003-read-keys.patch` (build) · `README.md` — claims: HID device Android 9+, hosts Android 4+/TV/ChromeOS/iOS13+/macOS/Linux/Windows XP SP2+ · `.gitmodules` (upstream gitlab.com/Atharok/BtRemote).
- Net: smttgt's repo = Atharok TV remote + PC patch; Android-host claim traces to upstream TV positioning; no published Android↔Android logs.

**phonepad** (`%TEMP%\re\phonepad`, main 6c86403, GPL-3.0+):
- `Android/app/src/main/java/com/tygb99/phonepad/MainActivity.kt` — `MOUSE_REPORT_ID=1`/`KEYBOARD_REPORT_ID=2` :2331-2332; composite descriptor (mouse 5 B + AC Pan; keyboard 8 B, no LEDs) :2386-2444; `registerApp` SUBCLASS1_COMBO, nulls, `:925-947`; discoverable only via new-host action; auto-reconnect :902-912; touch block `handleTrackpadTouch` :1457-1540 (two-finger scroll wheel `-dy/11f` clamp ±12; `POINTER_SCALE` multiply; tap→click via `isTapGesture`; ACTION_CANCEL releases); `sendMouseReport` :1887-1904 (5-byte payload); `releaseAllMouseButtons`/`releaseAllKeyboardKeys` :1906-1931; `MOUSE_KEYBOARD_DESCRIPTOR` AC Pan `0x0C/0x0A/0x38 0x02` :2444 · `docs/HID.md` (Phase-0 spikes, minSdk 28, FGS connectedDevice, permission rules, release-gates Win11+macOS) · `README.md:100` — **"Real host pairing and mouse report delivery have been verified on a Galaxy S23 Ultra running Android 16 with a Windows 11 PC."** (Windows host; Android receiver untested).

**GhostHID** (`%TEMP%\re\ghost-hid`, main 9b24a65, MIT):
- `app/src/main/java/com/sj010/ghid/service/HidService.kt` — HID_DESCRIPTOR (mouse ID 2 4 B; keyboard ID 1 + LEDs; System Control ID 3; Consumer ID 4 0..0x029C) :151-207; registration :405-460; `onGetReport` :453-454; raw injector auto-release of non-zero reports (`holdMs`, zero re-send) :651-675; `startForeground` :219-231 · `domain/pointer/PointerMath.kt` — `MAX_AXIS_DELTA=127`, `chunks()`, `accumulate()` (fractional carry), `WheelAccumulator` · `docs/decisions/ADR-002-…md` — one registration per app context; combo; re-pair on change; Classic L2CAP PSM 0x11/0x13 vs BLE rejection rationale · `docs/protocol-spec.md`, `docs/architecture.md` (diagnostic logging, Combo/Payload/QuickSend architecture) · Manifest (`connectedDevice` FGS :38-43).

**Official Android API facts** (developer.android.com, fetched 2026-11-09): `BluetoothHidDevice` since API 28; `registerApp` one-app-at-a-time; app auto-unregisters off-foreground; HID Host disabled while HID Device app registered; `SUBCLASS1_COMBO/_KEYBOARD/_MOUSE`, `SUBCLASS2_GAMEPAD`; all methods require `BLUETOOTH_CONNECT` on S+; `sendReport` interrupt channel; `connect` requires prior pairing.

**This workspace (prior art, own):** `REPORT.md` (2026-11-09 PC-host campaign: phone `<PHONE_SERIAL>` vivo Y29 ↔ Windows laptop WORKSTATION-01; registration/connection/input/lifecycle checks PASS; winmm mapping notes; twice-empty rule; SIG 100 ms heartbeat) · `README.md` (combined descriptor IDs 1 gamepad / 2 keyboard / 3 mouse; re-pair-free mode switching) · `docs/hid-gamepad-manual-test-checklist.md` · `app/src/main/kotlin/com/hidgamepad/*` · tablet `<TABLET_SERIAL>` seen over ADB in HANDOFF.md but unused for Android-receiver testing.

---

## Appendix B — unresolved verification commands (for the hardware phase, not executed now)

```
# Controller side
adb logcat -s HidController
adb shell dumpsys bluetooth_manager | findstr /i "hid"
# Receiver side (if controllable)
adb shell dumpsys input | findstr /i "gamepad\|keyboard\|mouse"
# HCI capture (if btsnoop enabled on controller)
adb shell setprop persist.bluetooth.btsnoop_enable true
```

---

*End of research document. No production code was written; no repositories were modified; no Gradle/build scripts were run; no APKs were executed; no dependencies were installed. Only this file was added to the project.*
