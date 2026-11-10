# Touchpad Gestures Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the mouse mode's one-finger pad plus five buttons with a buttonless
MacBook-style trackpad: cursor, tap click, two-finger tap right-click, two-finger scroll
(V+H), pinch zoom, double-tap drag, three-finger tap middle-click, and three-finger host
swipes — on Windows and Android, without changing the frozen HID descriptor.

**Architecture:** A Compose-free `TouchpadGestureRecognizer` turns pointer frames into
`PadAction`s; a Compose-free `TouchpadGestureBridge` turns actions into `MouseState` /
`KeyboardState` / `ConsumerState` sends with a small injected scheduler; a thin
`GestureTouchpad` composable feeds frames from `pointerInput` and a 16 ms ticker.

**Tech Stack:** Kotlin, Jetpack Compose, Android Bluetooth HID profile, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-10-touchpad-gestures-design.md`

## Global Constraints

- Frozen descriptor: report 1 keyboard 8 B, 2 mouse 5 B, 3 gamepad 9 B, 4 consumer 2 B.
  No byte, report ID, or collection order may change. No re-pairing may be required.
- No new permission, no `BLUETOOTH_SCAN`, no receiver-side app, no new Gradle dependency.
- `minSdk = 28`, `compileSdk = 37`, JVM target 21, JUnit 4 only (`junit:junit:4.13.2`).
- Mouse buttons: 1 = left (`0x01`), 2 = right (`0x02`), 3 = middle (`0x04`).
- All thresholds are dp values, converted with the pad's `density` (px per dp).
- Reports are only delivered when the manager says the host is `CONNECTED`; never bypass
  `sendReportNow`'s gate, never call `HidDevice.sendReport` from this feature.
- Existing 62 unit tests must stay green: `.\gradlew.bat --console=plain testDebugUnitTest`.
- Commit locally after every task. **Never push.**
- Do not describe Android-to-Android as working until the tablet shows keyboard typing and
  cursor movement.

## Review Focus

1. **Finger lifting or a new finger landing mid-gesture.** A scroll that gains a third
   finger, or a drag that loses its finger for one frame, must not emit a click, a swipe,
   or leave a button held.
2. **Backgrounding, mode switch, or composition disposal mid-gesture.** A drag-held left
   button or a held Ctrl from a pinch must be released, not left latched in the host.
3. **A fast flick across the pad in one frame.** Large deltas must split (existing
   `splitIntoReports`), must not be misread as a tap or swipe, and must never exceed the
   int8 range.
4. **Rapid gesture alternation** (scroll, lift, immediately tap). The tap must not inherit
   scroll state, and the scroll must not emit a stray click on lift.
5. **Host disconnect and reconnect mid-gesture.** The bridge must not replay stale held
   buttons to a freshly connected host.
6. **Four or more fingers / palm contact.** Must not fire a random middle-click or swipe.

## File Structure

- `app/src/main/kotlin/com/hidgamepad/TouchpadGestures.kt` (create) — gesture vocabulary
  types, thresholds, pure recognizer.
- `app/src/main/kotlin/com/hidgamepad/HostGestureMapping.kt` (create) — `HostAction` and
  the per-host three-finger mapping table.
- `app/src/main/kotlin/com/hidgamepad/TouchpadGestureBridge.kt` (create) — action → HID
  send state machine, injectable scheduler, `releaseAll`.
- `app/src/main/kotlin/com/hidgamepad/GestureTouchpad.kt` (create) — Compose pad, pointer
  frames, ticker, virtual cursor drawing.
- `app/src/main/kotlin/com/hidgamepad/Joystick.kt` (modify) — delete `MouseTouchpad` and
  `MouseActionButton`; keep every other control untouched.
- `app/src/main/kotlin/com/hidgamepad/GamepadState.kt` (modify) — add three `ConsumerKey`
  usage constants.
- `app/src/main/kotlin/com/hidgamepad/GamepadScreen.kt` (modify) — mouse panel becomes the
  gesture pad plus a text hint; add an `onConsumerState` parameter.
- `app/src/main/kotlin/com/hidgamepad/MainActivity.kt` (modify) — pass `manager.sendConsumer`.
- `app/src/test/kotlin/com/hidgamepad/TouchpadGestureTest.kt` (create)
- `app/src/test/kotlin/com/hidgamepad/HostGestureMappingTest.kt` (create)
- `app/src/test/kotlin/com/hidgamepad/TouchpadGestureBridgeTest.kt` (create)

---

### Task 1: Recognizer core — one-finger tap and drag

**Files:**
- Create: `app/src/main/kotlin/com/hidgamepad/TouchpadGestures.kt`
- Test: `app/src/test/kotlin/com/hidgamepad/TouchpadGestureTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class PadPoint(val id: Long, val x: Float, val y: Float)`
  - `data class PadFrame(val timeMs: Long, val points: List<PadPoint>)`
  - `sealed interface PadAction` with `Move(dx: Int, dy: Int)`, `Scroll(wheel: Int, hPan: Int)`,
    `Click(button: Int)`, `Button(button: Int, down: Boolean)`, `ZoomStart`, `Zoom(steps: Int)`,
    `ZoomEnd`, `Host(direction: PadDirection)`
  - `enum class PadDirection { UP, DOWN, LEFT, RIGHT }`
  - `class TouchpadGestureRecognizer(density: Float, sensitivity: Float)` with
    `fun frame(frame: PadFrame): List<PadAction>`, `fun tick(nowMs: Long): List<PadAction>`,
    `fun reset(): List<PadAction>`
  - Top-level constants: `TAP_TIMEOUT_MS = 220L`, `TAP_SLOP_DP = 8f`,
    `DOUBLE_TAP_WINDOW_MS = 300L`, `DRAG_ARM_MS = 250L`, `SCROLL_START_DP = 10f`,
    `SCROLL_STEP_DP = 20f`, `PINCH_START_DP = 12f`, `PINCH_STEP_DP = 15f`,
    `SWIPE_DISTANCE_DP = 40f`, `SETTLE_MS = 40L`

- [ ] **Step 1: Write the failing test**

Create the test file with a shared helper and five cases. `density = 1f` makes dp equal px,
so the numbers read as both.

```kotlin
package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Test

private fun pt(id: Long, x: Float, y: Float) = PadPoint(id, x, y)
private fun fr(t: Long, vararg pts: PadPoint) = PadFrame(t, pts.toList())
private fun recog() = TouchpadGestureRecognizer(density = 1f, sensitivity = 1f)

class TouchpadGestureTest {

    @Test fun `one finger below slop emits nothing`() {
        val r = recog()
        assertEquals(emptyList<PadAction>(), r.frame(fr(0, pt(1, 100f, 100f))))
        assertEquals(emptyList<PadAction>(), r.frame(fr(16, pt(1, 104f, 100f))))
    }

    @Test fun `drag past slop flushes buffered delta then moves per frame`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 100f)))
        r.frame(fr(16, pt(1, 104f, 100f)))
        assertEquals(listOf(PadAction.Move(9, 0)), r.frame(fr(32, pt(1, 109f, 100f))))
        assertEquals(listOf(PadAction.Move(5, 0)), r.frame(fr(48, pt(1, 114f, 100f))))
    }

    @Test fun `tap emits one left click on lift`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 100f)))
        assertEquals(listOf(PadAction.Click(1)), r.frame(fr(120)))
    }

    @Test fun `jitter inside slop is discarded and still taps`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 100f)))
        assertEquals(emptyList<PadAction>(), r.frame(fr(16, pt(1, 103f, 100f))))
        assertEquals(listOf(PadAction.Click(1)), r.frame(fr(60)))
    }

    @Test fun `hold longer than tap timeout emits nothing`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 100f)))
        assertEquals(emptyList<PadAction>(), r.frame(fr(400)))
    }

    @Test fun `one frame flick emits move and never a click`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 100f)))
        assertEquals(listOf(PadAction.Move(300, -40)), r.frame(fr(16, pt(1, 400f, 60f))))
        assertEquals(emptyList<PadAction>(), r.frame(fr(24)))
    }
}
```

The flick delta is intentionally larger than one int8 report; `Move` stays an unclamped
`Int` and `HidGamepadManager.sendMouse` splits it. Nothing in the recognizer may clamp.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.TouchpadGestureTest"`
Expected: FAIL — compile error, unresolved references `PadPoint`, `PadFrame`, `PadAction`,
`TouchpadGestureRecognizer`.

- [ ] **Step 3: Create `TouchpadGestures.kt`**

Declare the types verbatim from the Interfaces block. The recognizer is a state machine;
implement it to this algorithm, which is the ordering the spec leaves open:

```
state: active, startMs, family (ONE|TWO|THREE), familyLocked, maxPoints,
       startCentroid, lastCentroid, travel,
       bufferedDx, bufferedDy, dragClassified,
       pendingTapMs, pendingTapPos, doubleTapCandidate, dragHeld

frame(f):
  out = []
  if (!active && f.points.isEmpty()) return out
  if (!active) start(f.timeMs, f.points)        // sets family, cent., clears flags,
                                                // doubleTapCandidate from pendingTap*
  else update(f.timeMs, f.points, out)
  if (f.points.isEmpty()) end(f.timeMs, out)
  return out

update:
  maxPoints = max(maxPoints, points.size)
  if (!familyLocked) family = familyFor(maxPoints)      // 1,2,3 -> ONE,TWO,THREE; >3 -> THREE
  if (!familyLocked && now - startMs >= SETTLE_MS) familyLocked = true
  travel = distance(centroid(points), startCentroid)
  delta = centroid(points) - lastCentroid
  lastCentroid = centroid(points)
  when (family):
    ONE:
      bufferedDx += delta.x; bufferedDy += delta.y
      if (!dragClassified && travel > TAP_SLOP_DP * density):
          dragClassified = true; familyLocked = true
      if (dragClassified) emitBuffered(out)

end:
  if (family == ONE && !dragClassified && now - startMs <= TAP_TIMEOUT_MS)
      out += Click(1); pendingTapMs = now; pendingTapPos = startCentroid
  resetGestureState()

emitBuffered(out):
  dx = round(bufferedDx * sensitivity); dy = round(bufferedDy * sensitivity)
  if (dx != 0 || dy != 0) { out += Move(dx, dy); bufferedDx = 0; bufferedDy = 0 }
```

`tick(nowMs)` returns `emptyList()` in this task (it gains behaviour in Task 2) and
`reset()` releases nothing yet.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.TouchpadGestureTest"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Run the whole suite**

Run: `.\gradlew.bat --console=plain testDebugUnitTest`
Expected: PASS, 68 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/TouchpadGestures.kt app/src/test/kotlin/com/hidgamepad/TouchpadGestureTest.kt
git commit -m "feat(touchpad): add gesture vocabulary and one-finger tap/drag recognizer"
```

---

### Task 2: Double-tap drag

**Files:**
- Modify: `app/src/main/kotlin/com/hidgamepad/TouchpadGestures.kt`
- Test: `app/src/test/kotlin/com/hidgamepad/TouchpadGestureTest.kt`

**Interfaces:**
- Consumes: Task 1's types and `pendingTapMs` / `pendingTapPos` / `doubleTapCandidate`.
- Produces: `tick(nowMs)` emitting `Button(1, true)` once arming elapses; `end` emitting
  `Button(1, false)`; `reset()` returning `Button(1, false)` when a drag button is held.

- [ ] **Step 1: Write the failing tests**

Add these methods inside the existing `TouchpadGestureTest` class.

```kotlin
    @Test fun `second tap then hold arms a drag button`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 100f)))
        assertEquals(listOf(PadAction.Click(1)), r.frame(fr(100)))
        assertEquals(emptyList<PadAction>(), r.frame(fr(150, pt(1, 101f, 100f))))
        assertEquals(emptyList<PadAction>(), r.tick(300))                   // 150 ms held
        assertEquals(listOf(PadAction.Button(1, true)), r.tick(400))        // 250 ms held
        assertEquals(listOf(PadAction.Move(20, 0)), r.frame(fr(416, pt(1, 121f, 100f))))
        assertEquals(listOf(PadAction.Button(1, false)), r.frame(fr(500)))
    }

    @Test fun `second tap released before arming is a normal click`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 100f)))
        r.frame(fr(90))
        r.frame(fr(150, pt(1, 101f, 100f)))
        assertEquals(listOf(PadAction.Click(1)), r.frame(fr(250)))
    }

    @Test fun `reset releases a held drag button`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 100f)))
        r.frame(fr(90))
        r.frame(fr(150, pt(1, 101f, 100f)))
        r.tick(400)
        assertEquals(listOf(PadAction.Button(1, false)), r.reset())
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.TouchpadGestureTest"`
Expected: FAIL — `tick` returns empty, no `Button` actions.

- [ ] **Step 3: Implement arming in `tick` and release in `end`/`reset`**

Rules to add:

- `start` sets `doubleTapCandidate = pendingTapMs != null && now - pendingTapMs <= DOUBLE_TAP_WINDOW_MS && distance(centroid, pendingTapPos) <= TAP_SLOP_DP * density`, then clears `pendingTapMs`/`pendingTapPos`.
- `tick(now)`: if `active && family == ONE && doubleTapCandidate && !dragHeld && now - startMs >= DRAG_ARM_MS && travel <= TAP_SLOP_DP * density`, emit `Button(1, true)`, set `dragHeld = true`, `dragClassified = true`, `familyLocked = true`. Otherwise return `emptyList()`.
- In `update` for `ONE`, when `dragHeld` is true, buffer and emit movement as normal (the drag moves the window).
- `end`: if `dragHeld`, emit `Button(1, false)` and skip the tap branch entirely (no `Click`).
- `reset()`: if `dragHeld`, emit `Button(1, false)`; clear all gesture state including `pendingTap*`; return the emitted actions.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.TouchpadGestureTest"`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/TouchpadGestures.kt app/src/test/kotlin/com/hidgamepad/TouchpadGestureTest.kt
git commit -m "feat(touchpad): double-tap hold arms a mouse drag"
```

---

### Task 3: Two-finger scroll and two-finger tap

**Files:**
- Modify: `app/src/main/kotlin/com/hidgamepad/TouchpadGestures.kt`
- Test: `app/src/test/kotlin/com/hidgamepad/TouchpadGestureTest.kt`

**Interfaces:**
- Consumes: Task 1 family/settle logic, `familyLocked`, `travel`.
- Produces: `Scroll(wheel, hPan)` emissions in notches, `Click(2)` on a two-finger tap, and
  the tested settle/upgrade rule other tasks rely on.

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test fun `two finger vertical drag scrolls in notches`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f)))          // settle
        assertEquals(emptyList<PadAction>(), r.frame(fr(66, pt(1, 100f, 195f), pt(2, 140f, 195f))))
        assertEquals(
            listOf(PadAction.Scroll(wheel = -1, hPan = 0)),
            r.frame(fr(82, pt(1, 100f, 180f), pt(2, 140f, 180f))),
        )
    }

    @Test fun `two finger sideways drag pans horizontally`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 100f, 240f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 100f, 240f)))
        assertEquals(
            listOf(PadAction.Scroll(wheel = 0, hPan = 1)),
            r.frame(fr(70, pt(1, 120f, 200f), pt(2, 120f, 240f))),
        )
    }

    @Test fun `two finger tap is a right click`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        assertEquals(listOf(PadAction.Click(2)), r.frame(fr(120)))
    }

    @Test fun `staggered second finger landing stays a two finger gesture`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f)))
        r.frame(fr(20, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(60, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        assertEquals(
            listOf(PadAction.Scroll(wheel = -1, hPan = 0)),
            r.frame(fr(80, pt(1, 100f, 180f), pt(2, 140f, 180f))),
        )
    }

    @Test fun `lifting one of two fingers does not click or scroll`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(70, pt(1, 100f, 180f), pt(2, 140f, 180f)))          // scrolls -1
        assertEquals(emptyList<PadAction>(), r.frame(fr(90, pt(1, 100f, 180f))))
        assertEquals(emptyList<PadAction>(), r.frame(fr(100)))
    }

    @Test fun `a tap right after a scroll is a fresh right click`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(70, pt(1, 100f, 180f), pt(2, 140f, 180f)))
        r.frame(fr(90))
        r.frame(fr(300, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        assertEquals(listOf(PadAction.Click(2)), r.frame(fr(360)))
    }
```

The `lifting one of two fingers` case is why the recognizer needs the point-count rule in
Step 3: a finger leaving changes the centroid, and that jump must not be read as scroll.

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.TouchpadGestureTest"`
Expected: FAIL — no `Scroll` actions, no `Click(2)`.

- [ ] **Step 3: Implement the TWO branch**

Rules to add to `update` for `family == TWO`:

- `scrollAccumX += delta.x`, `scrollAccumY += delta.y`.
- If `!scrollClassified && travel > SCROLL_START_DP * density`: `scrollClassified = true`, `familyLocked = true`.
- If `scrollClassified`: `wheel = (scrollAccumY / (SCROLL_STEP_DP * density)).toInt()`,
  `hPan = (scrollAccumX / (SCROLL_STEP_DP * density)).toInt()`; if either is non-zero,
  subtract `wheel * step` / `hPan * step` from the accumulators and emit one
  `Scroll(wheel, hPan)`. Sign: finger up (negative dy) gives negative wheel.
- `end` for `TWO`: when `scrollClassified` is false, `now - startMs <= TAP_TIMEOUT_MS` and
  `travel <= TAP_SLOP_DP * density`, emit `Click(2)`.
- Reset `scrollAccum*` and `scrollClassified` in `resetGestureState()`.

Do not downgrade `family` when a finger lifts; only all-fingers-up ends the gesture.

Point-count rule (required by the lift test): when the number of pressed points differs from
the previous frame, set `lastCentroid` to the new centroid and emit nothing for that frame —
no `Move`, no `Scroll`, no pinch step. A finger joining or leaving is a discontinuity, not
user movement. Also reset `pinchRefDistance` on that frame.

`end` for `TWO` fires `Click(2)` only when `!scrollClassified && !pinchLive`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.TouchpadGestureTest"`
Expected: PASS, 15 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/TouchpadGestures.kt app/src/test/kotlin/com/hidgamepad/TouchpadGestureTest.kt
git commit -m "feat(touchpad): two-finger scroll and right-click tap"
```

---

### Task 4: Pinch zoom and its precedence over scroll

**Files:**
- Modify: `app/src/main/kotlin/com/hidgamepad/TouchpadGestures.kt`
- Test: `app/src/test/kotlin/com/hidgamepad/TouchpadGestureTest.kt`

**Interfaces:**
- Consumes: the TWO branch from Task 3.
- Produces: `ZoomStart`, `Zoom(steps)`, `ZoomEnd`; a live pinch that scroll cannot
  supersede; `reset()` returning `ZoomEnd` when a pinch is live.

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test fun `two fingers moving apart zoom and frame the gesture`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))           // distance 40
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        assertEquals(
            listOf(PadAction.ZoomStart, PadAction.Zoom(2)),
            r.frame(fr(70, pt(1, 85f, 200f), pt(2, 155f, 200f))),      // distance 70
        )
        assertEquals(listOf(PadAction.ZoomEnd), r.frame(fr(90)))
    }

    @Test fun `pinch wins when scroll and pinch cross together`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        val actions = r.frame(fr(70, pt(1, 85f, 180f), pt(2, 155f, 180f)))
        assertEquals(listOf(PadAction.ZoomStart, PadAction.Zoom(2)), actions)
    }

    @Test fun `a live pinch is not replaced by scroll`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(70, pt(1, 85f, 200f), pt(2, 155f, 200f)))
        assertEquals(
            emptyList<PadAction>(),
            r.frame(fr(90, pt(1, 85f, 170f), pt(2, 155f, 170f))),      // both fingers up 30, same distance
        )
    }

    @Test fun `reset ends a live pinch`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f)))
        r.frame(fr(70, pt(1, 85f, 200f), pt(2, 155f, 200f)))
        assertEquals(listOf(PadAction.ZoomEnd), r.reset())
    }
```

Note the `-0.0` pitfall: assert `Zoom(2)`, not `Zoom(-2)`. Distance is 70 - 40 = 30, and
`PINCH_STEP_DP = 15f`, so 30 / 15 = 2 with truncation.

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.TouchpadGestureTest"`
Expected: FAIL — no `Zoom*` actions.

- [ ] **Step 3: Implement pinch**

Rules to add to `update` for `TWO`, evaluated **before** the scroll rules:

- Track `pinchRefDistance`: the two-finger distance on the first TWO frame.
- `pinchAccum += abs(currentDistance - pinchRefDistance)` signed by growth/shrink:
  `pinchAccum += (currentDistance - pinchRefDistance)`; set `pinchRefDistance = currentDistance` each frame.
- If `!pinchLive && abs(pinchAccum) > PINCH_START_DP * density`: `pinchLive = true`,
  `familyLocked = true`, `scrollClassified = false`, emit `ZoomStart`, then emit steps.
- Steps: `steps = (pinchAccum / (PINCH_STEP_DP * density)).toInt()`; if non-zero, subtract
  `steps * step` from `pinchAccum` and emit `Zoom(steps)`.
- Scroll runs only when `!pinchLive` and `!pinchLive` stays true for the rest of the
  gesture (no un-pinching back to scroll).
- `end` and `reset()`: if `pinchLive`, emit `ZoomEnd`, clear `pinchLive`, `pinchAccum`,
  `pinchRefDistance`.
- A two-finger tap must not fire when `pinchLive` is true.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.TouchpadGestureTest"`
Expected: PASS, 19 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/TouchpadGestures.kt app/src/test/kotlin/com/hidgamepad/TouchpadGestureTest.kt
git commit -m "feat(touchpad): pinch zoom with precedence over scroll"
```

---

### Task 5: Three-finger tap and swipe

**Files:**
- Modify: `app/src/main/kotlin/com/hidgamepad/TouchpadGestures.kt`
- Test: `app/src/test/kotlin/com/hidgamepad/TouchpadGestureTest.kt`

**Interfaces:**
- Consumes: family classification and settle from Tasks 1 and 3.
- Produces: `Click(3)` and exactly one `Host(direction)` per gesture.

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test fun `three finger tap is a middle click`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f), pt(3, 180f, 200f)))
        assertEquals(listOf(PadAction.Click(3)), r.frame(fr(140)))
    }

    @Test fun `three finger swipe emits one host action`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f), pt(3, 180f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f), pt(3, 180f, 200f)))
        assertEquals(
            listOf(PadAction.Host(PadDirection.LEFT)),
            r.frame(fr(70, pt(1, 50f, 200f), pt(2, 90f, 200f), pt(3, 130f, 200f))),
        )
        assertEquals(
            emptyList<PadAction>(),
            r.frame(fr(90, pt(1, 0f, 200f), pt(2, 40f, 200f), pt(3, 80f, 200f))),
        )
        assertEquals(emptyList<PadAction>(), r.frame(fr(120)))
    }

    @Test fun `three finger short drag is neither tap nor swipe`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f), pt(3, 180f, 200f)))
        r.frame(fr(50, pt(1, 100f, 200f), pt(2, 140f, 200f), pt(3, 180f, 200f)))
        assertEquals(emptyList<PadAction>(), r.frame(fr(70, pt(1, 90f, 200f), pt(2, 130f, 200f), pt(3, 170f, 200f))))
        assertEquals(emptyList<PadAction>(), r.frame(fr(100)))
    }

    @Test fun `four fingers do not click`() {
        val r = recog()
        r.frame(fr(0, pt(1, 100f, 200f), pt(2, 140f, 200f), pt(3, 180f, 200f), pt(4, 220f, 200f)))
        assertEquals(emptyList<PadAction>(), r.frame(fr(120)))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.TouchpadGestureTest"`
Expected: FAIL — no three-finger actions.

- [ ] **Step 3: Implement the THREE branch**

Rules:

- `update` for `THREE`: if `!swipeEmitted && travel > SWIPE_DISTANCE_DP * density`, pick the
  dominant axis of `delta` accumulated since `startCentroid` and emit exactly one
  `Host(direction)`; set `swipeEmitted = true`, `familyLocked = true`. Dominant axis: larger
  of `|dx|`, `|dy|`; `dx > 0` = RIGHT, `dx < 0` = LEFT, `dy > 0` = DOWN, `dy < 0` = UP.
- `end` for `THREE`: `Click(3)` only when `maxPoints == 3 && !swipeEmitted`,
  `now - startMs <= TAP_TIMEOUT_MS` and `travel <= TAP_SLOP_DP * density`. Four or more
  points share the THREE branch for swipes but must never produce a click.
- Reset `swipeEmitted` in `resetGestureState()`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.TouchpadGestureTest"`
Expected: PASS, 23 tests.

- [ ] **Step 5: Run the whole suite**

Run: `.\gradlew.bat --console=plain testDebugUnitTest`
Expected: PASS, 87 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/TouchpadGestures.kt app/src/test/kotlin/com/hidgamepad/TouchpadGestureTest.kt
git commit -m "feat(touchpad): three-finger tap and host swipe"
```

---

### Task 6: Host mapping table

**Files:**
- Create: `app/src/main/kotlin/com/hidgamepad/HostGestureMapping.kt`
- Modify: `app/src/main/kotlin/com/hidgamepad/GamepadState.kt` (add three `ConsumerKey` constants)
- Test: `app/src/test/kotlin/com/hidgamepad/HostGestureMappingTest.kt`

**Interfaces:**
- Consumes: `PadDirection` (Task 1), `HostType`, `KeyUsage`, `MOD_LEFT_*`, `ConsumerKey`.
- Produces:
  - `sealed interface HostAction` with `data class Keys(val modifiers: Int, val key: Int)`,
    `data class Consumer(val usage: Int)`, `data object None`
  - `fun hostActionFor(direction: PadDirection, hostType: HostType): HostAction`
  - `ConsumerKey.AC_HOME = 0x0223`, `ConsumerKey.AC_BACK = 0x0224`,
    `ConsumerKey.AC_FORWARD = 0x0225`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Test

class HostGestureMappingTest {

    @Test fun `windows swipes use gui and alt chords`() {
        assertEquals(HostAction.Keys(MOD_LEFT_GUI, KeyUsage.TAB), hostActionFor(PadDirection.UP, HostType.WINDOWS))
        assertEquals(HostAction.Keys(MOD_LEFT_GUI, KeyUsage.D), hostActionFor(PadDirection.DOWN, HostType.WINDOWS))
        assertEquals(HostAction.Keys(MOD_LEFT_ALT, KeyUsage.LEFT), hostActionFor(PadDirection.LEFT, HostType.WINDOWS))
        assertEquals(HostAction.Keys(MOD_LEFT_ALT, KeyUsage.RIGHT), hostActionFor(PadDirection.RIGHT, HostType.WINDOWS))
    }

    @Test fun `android swipes use consumer navigation usages`() {
        assertEquals(HostAction.Consumer(0x0223), hostActionFor(PadDirection.UP, HostType.ANDROID))
        assertEquals(HostAction.None, hostActionFor(PadDirection.DOWN, HostType.ANDROID))
        assertEquals(HostAction.Consumer(0x0224), hostActionFor(PadDirection.LEFT, HostType.ANDROID))
        assertEquals(HostAction.Consumer(0x0225), hostActionFor(PadDirection.RIGHT, HostType.ANDROID))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.HostGestureMappingTest"`
Expected: FAIL — unresolved `HostAction`, `hostActionFor`.

- [ ] **Step 3: Add the constants and the mapping**

Append to `object ConsumerKey` in `GamepadState.kt`:

```kotlin
    /** HID 1.12 consumer usages used by Android three-finger navigation. */
    const val AC_HOME = 0x0223
    const val AC_BACK = 0x0224
    const val AC_FORWARD = 0x0225
```

Implement `hostActionFor` as the spec's table. Windows: UP `Keys(MOD_LEFT_GUI, KeyUsage.TAB)`,
DOWN `Keys(MOD_LEFT_GUI, KeyUsage.D)`, LEFT `Keys(MOD_LEFT_ALT, KeyUsage.LEFT)`,
RIGHT `Keys(MOD_LEFT_ALT, KeyUsage.RIGHT)`. Android: UP `Consumer(ConsumerKey.AC_HOME)`,
DOWN `None`, LEFT `Consumer(ConsumerKey.AC_BACK)`, RIGHT `Consumer(ConsumerKey.AC_FORWARD)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.HostGestureMappingTest"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/HostGestureMapping.kt app/src/main/kotlin/com/hidgamepad/GamepadState.kt app/src/test/kotlin/com/hidgamepad/HostGestureMappingTest.kt
git commit -m "feat(touchpad): map three-finger swipes per host"
```

---

### Task 7: Action → HID bridge

**Files:**
- Create: `app/src/main/kotlin/com/hidgamepad/TouchpadGestureBridge.kt`
- Test: `app/src/test/kotlin/com/hidgamepad/TouchpadGestureBridgeTest.kt`

**Interfaces:**
- Consumes: `PadAction`, `HostAction`, `hostActionFor`, `MouseState`, `KeyboardState`,
  `ConsumerState`.
- Produces:
  ```kotlin
  class TouchpadGestureBridge(
      private val hostType: HostType,
      private val sendMouse: (MouseState) -> Unit,
      private val sendKeyboard: (KeyboardState) -> Unit,
      private val sendConsumer: (ConsumerState) -> Unit,
      private val schedule: (delayMs: Long, action: () -> Unit) -> Unit,
      private val hapticClick: () -> Unit = {},
  ) {
      fun dispatch(actions: List<PadAction>)
      fun releaseAll()
  }
  ```
  plus `const val CHORD_HOLD_MS = 40L`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeScheduler {
    val pending = mutableListOf<Pair<Long, () -> Unit>>()
    fun schedule(ms: Long, action: () -> Unit) { pending += ms to action }
    fun flush() {
        val copy = pending.toList()
        pending.clear()
        for ((_, action) in copy) action()
    }
}

private class Recorder {
    val mouse = mutableListOf<MouseState>()
    val keyboard = mutableListOf<KeyboardState>()
    val consumer = mutableListOf<ConsumerState>()
    var haptics = 0
    val scheduler = FakeScheduler()

    fun bridge(host: HostType) = TouchpadGestureBridge(
        hostType = host,
        sendMouse = { mouse += it },
        sendKeyboard = { keyboard += it },
        sendConsumer = { consumer += it },
        schedule = scheduler::schedule,
        hapticClick = { haptics++ },
    )
}

class TouchpadGestureBridgeTest {

    @Test fun `move carries the current button mask`() {
        val r = Recorder()
        val b = r.bridge(HostType.WINDOWS)
        b.dispatch(listOf(PadAction.Button(1, true)))
        b.dispatch(listOf(PadAction.Move(5, -3)))
        assertEquals(listOf(MouseState(buttons = 0), MouseState(buttons = 1, dx = 5, dy = -3)), r.mouse)
    }

    @Test fun `click presses then releases after the hold`() {
        val r = Recorder()
        val b = r.bridge(HostType.WINDOWS)
        b.dispatch(listOf(PadAction.Click(2)))
        assertEquals(listOf(MouseState(buttons = 0x02)), r.mouse)
        assertEquals(1, r.haptics)
        r.scheduler.flush()
        assertEquals(MouseState(buttons = 0), r.mouse.last())
    }

    @Test fun `zoom holds ctrl across the gesture`() {
        val r = Recorder()
        val b = r.bridge(HostType.WINDOWS)
        b.dispatch(listOf(PadAction.ZoomStart, PadAction.Zoom(2), PadAction.ZoomEnd))
        assertEquals(listOf(KeyboardState(MOD_LEFT_CTRL), KeyboardState(0)), r.keyboard)
        assertEquals(listOf(MouseState(buttons = 0, wheel = 2)), r.mouse)
        assertEquals(emptyList<ConsumerState>(), r.consumer)
    }

    @Test fun `windows swipe sends a chord and releases it`() {
        val r = Recorder()
        val b = r.bridge(HostType.WINDOWS)
        b.dispatch(listOf(PadAction.Host(PadDirection.UP)))
        assertEquals(listOf(KeyboardState(MOD_LEFT_GUI, setOf(KeyUsage.TAB))), r.keyboard)
        r.scheduler.flush()
        assertEquals(KeyboardState(0), r.keyboard.last())
    }

    @Test fun `android swipe sends a consumer tap and releases it`() {
        val r = Recorder()
        val b = r.bridge(HostType.ANDROID)
        b.dispatch(listOf(PadAction.Host(PadDirection.LEFT)))
        assertEquals(listOf(ConsumerState(0x0224)), r.consumer)
        r.scheduler.flush()
        assertEquals(ConsumerState(0), r.consumer.last())
    }

    @Test fun `android downward swipe sends nothing`() {
        val r = Recorder()
        val b = r.bridge(HostType.ANDROID)
        b.dispatch(listOf(PadAction.Host(PadDirection.DOWN)))
        assertEquals(emptyList<ConsumerState>(), r.consumer)
        assertEquals(emptyList<KeyboardState>(), r.keyboard)
    }

    @Test fun `releaseAll clears held buttons and ctrl`() {
        val r = Recorder()
        val b = r.bridge(HostType.WINDOWS)
        b.dispatch(listOf(PadAction.Button(1, true), PadAction.ZoomStart))
        b.releaseAll()
        assertEquals(MouseState(buttons = 0), r.mouse.last())
        assertEquals(KeyboardState(0), r.keyboard.last())
        assertEquals(ConsumerState(0), r.consumer.last())
    }

    @Test fun `gesture end does not leak state into the next move`() {
        val r = Recorder()
        val b = r.bridge(HostType.WINDOWS)
        b.dispatch(listOf(PadAction.Button(1, true)))
        b.dispatch(listOf(PadAction.Button(1, false)))
        b.dispatch(listOf(PadAction.Move(1, 1)))
        assertEquals(MouseState(buttons = 0, dx = 1, dy = 1), r.mouse.last())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.TouchpadGestureBridgeTest"`
Expected: FAIL — unresolved `TouchpadGestureBridge`.

- [ ] **Step 3: Implement the bridge**

State: `buttons: Int = 0`, `ctrlHeld: Boolean = false`.

- `Move` / `Scroll`: `sendMouse(MouseState(buttons, dx, dy, wheel, hPan))` with the fields
  from the action.
- `Click(button)`: `buttons = buttons or (1 shl (button - 1))`; send; `hapticClick()`;
  `schedule(CHORD_HOLD_MS) { buttons = buttons and (1 shl (button - 1)).inv(); send() }`.
- `Button(button, down)`: set or clear the mask bit, send immediately.
- `ZoomStart`: `ctrlHeld = true`; `sendKeyboard(KeyboardState(MOD_LEFT_CTRL))`.
- `Zoom(steps)`: `sendMouse(MouseState(buttons, wheel = steps))`.
- `ZoomEnd`: `ctrlHeld = false`; `sendKeyboard(KeyboardState(0))`.
- `Host(direction)`: `when (val a = hostActionFor(direction, hostType))`:
  - `Keys` → `sendKeyboard(KeyboardState(a.modifiers, setOf(a.key)))`, then
    `schedule(CHORD_HOLD_MS) { sendKeyboard(KeyboardState(if (ctrlHeld) MOD_LEFT_CTRL else 0)) }`
  - `Consumer` → `sendConsumer(ConsumerState(a.usage))`, then `schedule(CHORD_HOLD_MS) { sendConsumer(ConsumerState(0)) }`
  - `None` → nothing
- `releaseAll()`: `buttons = 0`; `ctrlHeld = false`; `sendMouse(MouseState(0))`;
  `sendKeyboard(KeyboardState(0))`; `sendConsumer(ConsumerState(0))`.

No cancellation of scheduled releases: a late release only re-sends neutral state, and a
human cannot tap twice inside 40 ms.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat --console=plain testDebugUnitTest --tests "com.hidgamepad.TouchpadGestureBridgeTest"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/TouchpadGestureBridge.kt app/src/test/kotlin/com/hidgamepad/TouchpadGestureBridgeTest.kt
git commit -m "feat(touchpad): bridge gestures to mouse, keyboard, and consumer reports"
```

---

### Task 8: Compose pad and screen wiring

**Files:**
- Create: `app/src/main/kotlin/com/hidgamepad/GestureTouchpad.kt`
- Modify: `app/src/main/kotlin/com/hidgamepad/Joystick.kt` (delete `MouseTouchpad` L387-494
  and `MouseActionButton` L496-539)
- Modify: `app/src/main/kotlin/com/hidgamepad/GamepadScreen.kt` (mouse panel L393-446, add
  `onConsumerState` parameter, drop the `mouseButtons` field and its reset)
- Modify: `app/src/main/kotlin/com/hidgamepad/MainActivity.kt` (pass `onConsumerState`)

**Interfaces:**
- Consumes: `TouchpadGestureRecognizer`, `TouchpadGestureBridge`, `PadFrame`, `PadPoint`.
- Produces:
  ```kotlin
  @Composable
  fun GestureTouchpad(
      sensitivity: Float,
      hostType: HostType,
      hapticsEnabled: Boolean,
      hapticsStrength: Float,
      onMouseState: (MouseState) -> Unit,
      onKeyboardState: (KeyboardState) -> Unit,
      onConsumerState: (ConsumerState) -> Unit,
      modifier: Modifier = Modifier,
  )
  ```
  and `GamepadScreen(..., onConsumerState: (ConsumerState) -> Unit)`.

- [ ] **Step 1: Write the failing test (compile-level)**

There is no Compose test dependency and none may be added. Prove the wiring by compiling:

Run: `.\gradlew.bat --console=plain testDebugUnitTest`
Expected: PASS — the existing suite still compiles and passes; no `GestureTouchpad` exists yet.

Then change `GamepadScreen`'s mouse branch to call `GestureTouchpad(...)` first and run

Run: `.\gradlew.bat --console=plain assembleDebug`
Expected: FAIL — unresolved `GestureTouchpad`.

- [ ] **Step 2: Implement `GestureTouchpad.kt`**

Requirements:

- `density = LocalDensity.current.density` (px per dp) passed to the recognizer, so
  thresholds stay device-independent.
- One `remember(sensitivity, density)` recognizer and one `remember(hostType, ...)` bridge.
  Wrap the three send callbacks and the haptic in `rememberUpdatedState` so recomposition
  swaps them without rebuilding the bridge.
- `schedule = { ms, action -> Handler(Looper.getMainLooper()).postDelayed(action, ms) }`,
  created once with `remember`.
- Pointer loop: `Modifier.fillMaxSize().pointerInput(Unit) { awaitEachGesture { ... } }`:
  - `awaitFirstDown(requireUnconsumed = false)`, then `setFingerDown(true)`.
  - On every `awaitPointerEvent()`, `for (c in event.changes) c.consume()`, build
    `PadFrame(SystemClock.uptimeMillis(), event.changes.filter { it.pressed }.map { PadPoint(it.id.value, it.position.x, it.position.y) })`,
    dispatch `recognizer.frame(frame)` through the bridge, and update the virtual cursor from
    the `Move` actions.
  - Break when no change is pressed, but still send that final empty frame (it carries the
    tap/lift actions), then `setFingerDown(false)`.
- Ticker: `var fingerDown by remember { mutableStateOf(false) }` and
  `LaunchedEffect(fingerDown) { while (fingerDown) { delay(16); bridge.dispatch(recognizer.tick(SystemClock.uptimeMillis())) } }`.
- `DisposableEffect(Unit) { onDispose { bridge.dispatch(recognizer.reset()); bridge.releaseAll(); } }`.
- Drawing: reuse the current `MouseTouchpad` visuals — rounded dark rect, 2 px border,
  36 px dot grid, white arrow path translated to a clamped virtual cursor — plus a small
  "gesture active" affordance is not needed. Keep the arrow, drop `hasTouch`.
- Haptics: pass `{ if (hapticsEnabled) HapticBuzz.buzz(context, hapticsStrength) }` as
  `hapticClick`; read both through `rememberUpdatedState`.

- [ ] **Step 3: Rewrite the mouse panel and delete the old controls**

In `GamepadScreen`:

```kotlin
ControllerMode.MOUSE -> {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GestureTouchpad(
            sensitivity = mouseSensitivity,
            hostType = hostType,
            hapticsEnabled = hapticsEnabled,
            hapticsStrength = hapticsStrength,
            onMouseState = onMouseState,
            onKeyboardState = onKeyboardState,
            onConsumerState = onConsumerState,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Tap = click · 2-finger tap = right click · 2-finger drag = scroll · " +
                "pinch = zoom · double-tap then drag = drag · 3-finger tap/swipe",
            fontSize = 10.sp,
            color = Color(0xFF9AA1B2),
            maxLines = 2,
        )
    }
}
```

Delete the three `MouseActionButton` rows and the two scroll rows, the `mouseButtons` state
variable and its line in `resetLocalInputs()`. Add `onConsumerState: (ConsumerState) -> Unit`
to the `GamepadScreen` signature and thread it from `MainActivity` as
`{ state -> manager.sendConsumer(state) }`.

In `Joystick.kt`, delete `MouseTouchpad` and `MouseActionButton` and any import that only
they used (`fillMaxHeight`, `Path` if the arrow moves to the new file, `translate`).

- [ ] **Step 4: Build and run the suite**

Run: `.\gradlew.bat --console=plain testDebugUnitTest assembleDebug`
Expected: PASS, 95 tests, 0 failures; `app\build\outputs\apk\debug\app-debug.apk` written.

- [ ] **Step 5: Install and capture device evidence**

Run: `C:\Users\<user>\AppData\Local\Android\Sdk\platform-tools\adb.exe -s <PHONE_SERIAL> install -r app\build\outputs\apk\debug\app-debug.apk`
Expected: `Success`. Then with a connected host, exercise each gesture and confirm from
logcat (`adb.exe -s <PHONE_SERIAL> logcat -d -s HidGamepad:V`) that the expected report
IDs appear: `id=2` for move/scroll, `id=1` for zoom/swipe chords, `id=4` for Android swipes.
Record the actual observed lines; do not claim a gesture works without them.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/hidgamepad/GestureTouchpad.kt app/src/main/kotlin/com/hidgamepad/Joystick.kt app/src/main/kotlin/com/hidgamepad/GamepadScreen.kt app/src/main/kotlin/com/hidgamepad/MainActivity.kt
git commit -m "feat(touchpad): buttonless gesture pad in mouse mode"
```

---

### Task 9: Documentation and manual test rows

**Files:**
- Modify: `README.md`
- Modify: `docs/hid-gamepad-manual-test-checklist.md`
- Modify: `REPORT.md` (append a 2026-11-10 section)

**Interfaces:**
- Consumes: nothing; documents what Tasks 1–8 built and what has been observed.

- [ ] **Step 1: Update `README.md`**

Replace the mouse-mode description with the gesture vocabulary and add the host behaviour
table from the spec (`docs/superpowers/specs/2026-11-10-touchpad-gestures-design.md`,
section "What each host actually does with the gestures"). State plainly that pinch is
Ctrl+wheel on both hosts and that Android right/middle-click and zoom are app-dependent.

- [ ] **Step 2: Extend the manual checklist**

Append one row per gesture with this shape:

```markdown
| Mouse | Tap once on the pad | Host registers one left click |  |
| Mouse | Two-finger tap | Host registers a right click (Windows: context menu) |  |
| Mouse | Two-finger drag up/down | Content scrolls vertically, natural direction |  |
| Mouse | Two-finger drag left/right | Horizontal pan where the app supports it |  |
| Mouse | Pinch out/in | Ctrl+wheel zoom in the host's zoomable app |  |
| Mouse | Tap, then tap and hold, then drag | Host drags with the left button held; release ends the drag |  |
| Mouse | Three-finger tap | Windows: middle click; Android: often nothing |  |
| Mouse | Three-finger swipe left/right/up | Windows: back/forward/Task View; Android: Back/Forward/Home |  |
| Mouse | Four-finger tap | Nothing happens |  |
```

- [ ] **Step 3: Append the REPORT.md section**

Include only observed evidence: build and test counts with timestamps, APK hash or size,
logcat excerpts, and the Windows/tablet gesture results you actually saw. Mark every
unverified cell as unverified instead of guessing.

- [ ] **Step 4: Commit**

```bash
git add README.md docs/hid-gamepad-manual-test-checklist.md REPORT.md
git commit -m "docs(touchpad): gesture vocabulary, host matrix, manual test rows"
```

---

## Evidence gates (do not skip)

- Windows gesture evidence comes from `tools/pc-hid-probe.ps1` (cursor deltas, button
  transitions, wheel/hPan) plus the Windows three-finger chords observed in the UI.
- Tablet evidence requires the Lenovo Tab M11 to answer paging, pair, and show both keyboard
  typing and cursor movement. Until then, every Android-specific row stays unverified.
- Never describe Android-to-Android as working before that evidence exists.
