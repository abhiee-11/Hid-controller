# Touchpad gestures design — 2026-11-10

## Intent

Mouse mode becomes a buttonless MacBook-style trackpad. Today the mouse screen is a
one-finger relative pad plus five on-screen buttons (L / M / R / Scroll up / Scroll down).
The user wants every gesture a normal MacBook trackpad offers, with no buttons, on both
the Windows PC and the Android tablet.

Why: reaching for on-screen buttons defeats the point of a trackpad. Gestures keep the
hand on the pad for navigation, clicking, scrolling, and zooming.

## Goals

- One full-bleed pad; no on-screen mouse buttons.
- Cursor move, tap-to-click, two-finger tap right-click, two-finger scroll (vertical and
  horizontal), pinch-to-zoom, double-tap-drag, three-finger tap middle-click.
- Three-finger swipes mapped per host: Windows Task View / show desktop / back / forward;
  Android Home / Back / Forward.
- No HID descriptor change, therefore **no re-pairing** on any existing host.

## Non-goals (v1)

- Momentum / inertial scrolling. It is the one Mac feel feature that needs hand testing to
  tune; proposed as the immediate follow-up once the discrete gestures are proven.
- Force touch, pressure, rotate, smart zoom (two-finger double tap), Launchpad / spread,
  four-finger gestures. None of them map cleanly to a 5-byte mouse report.
- Any new permission, scan API, or receiver-side app.

## Anti-goals

- A gesture that leaves a mouse button or the Ctrl modifier stuck in the report.
- A gesture that fires while the user meant another (scroll during a tap, tap during a
  swipe).
- Anything that changes the frozen descriptor bytes.

## Constraints

- Descriptor is frozen: report 1 keyboard (8 B), 2 mouse (5 B), 3 gamepad (9 B),
  4 consumer (2 B). Gestures use reports 2 and 1; Android navigation may use report 4,
  which is already in the descriptor and currently unused.
- `sendReport()` gating stays as is: gestures are only delivered when the host is
  authoritatively `CONNECTED`.
- Existing 62 unit tests must stay green.

## Approaches considered

1. **Compose-level gesture detection inside `MouseTouchpad`.** Quickest, but timing and
   state live in UI callbacks, so the regression surface (stuck buttons, misclassified
   gestures) is untestable without instrumented tests.
2. **Pure recognizer + thin Compose adapter** (chosen). A Compose-free state machine takes
   pointer frames and returns actions; the composable only forwards frames. Every rule is
   unit-testable, matching how `pedalValue`, `combineHats`, and the report encoders are
   already tested.
3. **Library trackpad recognizer.** No maintained library targets raw HID mouse output with
   this project's constraints (no new dependency budget, no descriptor change), so it would
   be wrapped rather than reused.

Option 2 wins on testability at the same implementation cost.

## Design

### Recognizer (new `TouchpadGestures.kt`, no Android imports)

```
data class PadPoint(val id: Long, val x: Float, val y: Float)
data class PadFrame(val timeMs: Long, val points: List<PadPoint>)   // currently pressed

sealed interface PadAction {
    data class Move(val dx: Int, val dy: Int) : PadAction
    data class Scroll(val wheel: Int, val hPan: Int) : PadAction
    data class Click(val button: Int) : PadAction                  // press + release
    data class Button(val button: Int, val down: Boolean) : PadAction
    data class Zoom(val steps: Int) : PadAction                    // + = zoom in
    data class Host(val direction: Direction) : PadAction
}

class TouchpadGestureRecognizer(private val densityScale: Float) {
    fun frame(frame: PadFrame): List<PadAction>
    fun tick(nowMs: Long): List<PadAction>   // time-only advance, for holds with no pointer events
    fun reset(): List<PadAction>             // releases what it holds
}
```

`tick` exists because Compose emits no pointer events while a finger rests motionless, so a
hold can only be detected from a clock. The adapter drives it at ~16 ms while any finger is
down.

Thresholds (named constants, dp-scaled by `densityScale`): tap ≤ 220 ms and ≤ 8 dp travel;
double-tap window 300 ms; drag-hold arming 250 ms; scroll start 10 dp; pinch start 12 dp
of finger-distance change; swipe 40 dp; gesture settle 40 ms.

Settle: the family is chosen from the finger count 40 ms after the first touch-down, or
earlier if travel passes the tap threshold. Before settling the family may still be
upgraded by extra fingers landing (two-finger scroll started a few ms apart still reads as
two fingers). Once classified, the family is locked until every finger lifts.

Rules, in precedence order. The finger count at gesture start selects the family, and a
classified gesture holds until every finger lifts:

| Fingers | Gesture | Output |
|---|---|---|
| 1 | drag | `Move(dx, dy)` per frame, sensitivity-scaled, split via `splitIntoReports()` |
| 1 | tap, no movement | `Click(1)` |
| 1 | second tap within 300 ms | `Button(1, down)` armed 250 ms after the second down (tracked by `tick`), released on lift |
| 2 | movement > 10 dp | `Scroll(wheel, hPan)`; natural direction: fingers up = wheel negative (content up) |
| 2 | distance change > 12 dp | `Zoom(steps)`; adapter holds `MOD_LEFT_CTRL` + wheel for the gesture |
| 2 | tap, no movement | `Click(2)` |
| 3 | tap, no movement | `Click(3)` |
| 3 | movement > 40 dp | `Host(direction)`, emitted once per gesture |

One-finger movement is buffered, not emitted, until the drag is classified: a tap therefore
never nudges the cursor, and a drag flushes its accumulated delta as the first `Move` so it
does not lose its first few dp.

Precedence: pinch wins if scroll and pinch thresholds cross on the same frame, and a live
pinch is never superseded by scroll. Scroll may be superseded by pinch, which then releases
the scroll. A tap never emits movement; a scroll never emits a click; a pinch emits no
scroll; a swipe emits no tap. Taps are decided on finger-up from duration + travel.

### Host mapping (report 1 keyboard chords, report 4 consumer usages)

`host_type` already persists as `WINDOWS` or `ANDROID` from setup, so no new UI.

| Direction | Windows | Android |
|---|---|---|
| up | `MOD_LEFT_GUI` + TAB → Task View | consumer `0x0223` AC Home |
| down | `MOD_LEFT_GUI` + D → show desktop | none (no Android equivalent) |
| left | `MOD_LEFT_ALT` + LEFT → back | consumer `0x0224` AC Back |
| right | `MOD_LEFT_ALT` + RIGHT → forward | consumer `0x0225` AC Forward, else `MOD_LEFT_CTRL` + RIGHT |

The Android codes follow HID 1.12 usage IDs (0x0221 Search, 0x0222 Go To, 0x0223 Home,
0x0224 Back, 0x0225 Forward). The report-4 descriptor already declares usages 0x0000–0x03FF
as an array, so no descriptor change. Android has no global Forward action; a forward swipe
therefore sends AC Forward and, only if the tablet is observed to ignore it, the Ctrl+Right
keyboard chord.

Chords are sent as press → release with the modifier released in the same sequence, so no
modifier can leak.

### What each host actually does with the gestures

Android consumes the raw mouse/keyboard reports itself, so behaviour depends on the focused
app, not on the tablet. This is stated in the UI hint and in the README rather than hidden:

| Gesture | Windows | Android |
|---|---|---|
| drag / tap click | works | works (tap = tap, drag = drag on touch-friendly apps) |
| 2-finger scroll | works everywhere | works wherever the app scrolls; vertical reliable, horizontal app-dependent |
| double-tap drag | works | works (host sees a held left button) |
| pinch zoom | Ctrl+wheel; zooms every app that zooms | Ctrl+wheel; only apps that honour Ctrl+wheel (browsers, some editors) — no true pinch |
| 2-finger tap | right click | context menu where the app supports long-press menus; otherwise nothing |
| 3-finger tap | middle click | usually nothing |
| 3-finger swipe | Task View / desktop / back / forward | Home / Back / Forward where consumed |

True pinch (two independent contacts) is impossible on any host with a 5-byte relative mouse
report and no multitouch report, so "pinch" is Ctrl+wheel by design on both.

### UI

`MouseTouchpad` becomes `GestureTouchpad`: full-bleed pad, existing dot-grid, cursor arrow,
one compact hint line (text only, not a button) listing the gesture vocabulary. The L / M /
R and Scroll up / Scroll down buttons are deleted from the mouse panel. Optional local
haptic on each tap click, reusing `HapticBuzz` and the existing haptics preference.

### Lifecycle

Pad teardown calls `reset()`, whose actions release any held button and Ctrl; the existing
`releaseAllInputs()` remains the outer guarantee on backgrounding.

## Testing

- `TouchpadGestureTest` (new, ~20 cases): each table row, tap-vs-drag boundary, tap-vs-swipe
  boundary for three fingers, pinch-vs-scroll disambiguation, double-tap-drag enter and
  release, `tick`-driven drag arming with no pointer events, staggered finger landings inside
  and outside the settle window, `reset()` releasing a held button and Ctrl, no click after a
  scroll.
- `HostGestureMappingTest` (new): chord tables for both hosts, modifier release ordering.
- Existing 62 tests unchanged.
- PC evidence via `tools/pc-hid-probe.ps1`: cursor deltas, click transitions, scroll
  wheel/hPan, zoom as Ctrl+scroll, and the Windows three-finger chords.
- Tablet evidence: cursor, tap, scroll, zoom, and the Android navigation codes.

## Left to the builder

Exact sensitivity constants, the scroll/pinch gain curves, hint-line wording, and whether
the hint collapses after first use.
