package com.hidgamepad

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** One pressed pointer in pad-local pixels. */
data class PadPoint(val id: Long, val x: Float, val y: Float)

/** All currently pressed pointers for one frame. An empty list means every finger lifted. */
data class PadFrame(val timeMs: Long, val points: List<PadPoint>)

enum class PadDirection { UP, DOWN, LEFT, RIGHT }

/** Pointer semantics from the recognizer; the bridge turns these into HID reports. */
sealed interface PadAction {
    data class Move(val dx: Int, val dy: Int) : PadAction
    data class Scroll(val wheel: Int, val hPan: Int) : PadAction
    data class Click(val button: Int) : PadAction
    data class Button(val button: Int, val down: Boolean) : PadAction
    data object ZoomStart : PadAction
    data class Zoom(val steps: Int) : PadAction
    data object ZoomEnd : PadAction
    data class Host(val direction: PadDirection) : PadAction
}

const val TAP_TIMEOUT_MS = 220L
const val TAP_SLOP_DP = 8f
const val DOUBLE_TAP_WINDOW_MS = 300L
const val DRAG_ARM_MS = 250L
const val SCROLL_START_DP = 10f

/**
 * Finger travel per wheel notch at ×1.00 sensitivity.
 *
 * The scroll step is divided by the sensitivity, so the same slider that sets the pointer
 * speed also sets the scroll speed: twice the sensitivity is twice the notches per dp of
 * travel. Halved from 20f, which made a notch cost 37.5 px of finger travel on a 1.875x
 * screen.
 */
const val SCROLL_STEP_DP = 10f

const val PINCH_START_DP = 12f
const val PINCH_STEP_DP = 15f
const val SWIPE_DISTANCE_DP = 40f

/**
 * Pure trackpad gesture state machine: pointer frames in, [PadAction]s out. No Android
 * imports, so every rule is unit-testable.
 *
 * [density] is pixels per dp (Compose's `LocalDensity.current.density`), used for thresholds.
 * [sensitivity] scales pointer movement into host cursor units.
 */
class TouchpadGestureRecognizer(
    private val density: Float,
    sensitivity: Float,
) {
    /** Live cursor gain; mutable so a sensitivity slider change never rebuilds the state machine. */
    var sensitivity: Float = sensitivity
    private enum class Family { ONE, TWO, THREE }

    private var active = false
    private var startMs = 0L
    private var family: Family? = null
    private var familyLocked = false
    private var maxPoints = 0
    private var lastCount = 0
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var travel = 0f
    private var peakTravel = 0f
    private var swipeDx = 0f
    private var swipeDy = 0f
    private val startXById = mutableMapOf<Long, Float>()
    private val startYById = mutableMapOf<Long, Float>()
    private var bufferedDx = 0f
    private var bufferedDy = 0f
    private var dragClassified = false
    private var dragHeld = false
    private var doubleTapCandidate = false
    private var scrollAccumX = 0f
    private var scrollAccumY = 0f
    private var scrollClassified = false
    private var pinchAccum = 0f
    private var pinchRefDistance = -1f
    private var pinchLive = false
    private var swipeEmitted = false
    private var pendingTapMs: Long? = null
    private var pendingTapX = 0f
    private var pendingTapY = 0f

    fun frame(frame: PadFrame): List<PadAction> {
        val out = mutableListOf<PadAction>()
        val points = frame.points
        if (points.isEmpty()) {
            if (active) end(frame.timeMs, out)
            return out
        }
        if (!active) start(frame.timeMs, points) else update(frame.timeMs, points, out)
        return out
    }

    /** Time-only advance, for holds that produce no pointer events. */
    fun tick(nowMs: Long): List<PadAction> {
        val out = mutableListOf<PadAction>()
        if (active && !dragHeld && doubleTapCandidate && family == Family.ONE &&
            nowMs - startMs >= DRAG_ARM_MS && travel <= TAP_SLOP_DP * density
        ) {
            out += PadAction.Button(1, true)
            dragHeld = true
            dragClassified = true
            familyLocked = true
        }
        return out
    }

    /** Releases whatever a live gesture is holding. Call on teardown. */
    fun reset(): List<PadAction> {
        val out = mutableListOf<PadAction>()
        if (pinchLive) out += PadAction.ZoomEnd
        if (dragHeld) out += PadAction.Button(1, false)
        resetGesture()
        pendingTapMs = null
        return out
    }

    private fun start(nowMs: Long, points: List<PadPoint>) {
        active = true
        startMs = nowMs
        maxPoints = points.size
        lastCount = points.size
        family = familyFor(points.size)
        familyLocked = false
        startX = centroidX(points)
        startY = centroidY(points)
        lastX = startX
        lastY = startY
        travel = 0f
        peakTravel = 0f
        swipeDx = 0f
        swipeDy = 0f
        startXById.clear()
        startYById.clear()
        for (p in points) {
            startXById[p.id] = p.x
            startYById[p.id] = p.y
        }
        bufferedDx = 0f
        bufferedDy = 0f
        dragClassified = false
        dragHeld = false
        val tapMs = pendingTapMs
        doubleTapCandidate = tapMs != null &&
            nowMs - tapMs <= DOUBLE_TAP_WINDOW_MS &&
            hypot(startX - pendingTapX, startY - pendingTapY) <= TAP_SLOP_DP * density
        pendingTapMs = null
    }

    private fun update(nowMs: Long, points: List<PadPoint>, out: MutableList<PadAction>) {
        maxPoints = maxOf(maxPoints, points.size)
        if (!familyLocked) {
            // The family follows the fingers that are actually down, and may still be
            // upgraded while the gesture is a tap candidate. It is committed by the first
            // *classified* gesture (drag, scroll, pinch, swipe), or once the tap window has
            // passed. A fixed 40 ms settle was wrong: on a touchscreen the second finger
            // routinely lands well after that, and the gesture would freeze at one finger.
            family = familyFor(maxPoints)
            if (nowMs - startMs > TAP_TIMEOUT_MS) familyLocked = true
        }
        val cx = centroidX(points)
        val cy = centroidY(points)
        for (p in points) {
            if (!startXById.containsKey(p.id)) {
                startXById[p.id] = p.x
                startYById[p.id] = p.y
            }
        }
        // Travel is the worst displacement of any finger from where that finger landed, so a
        // finger joining later cannot look like movement of the fingers already down.
        var worst = 0f
        for (p in points) {
            val sx = startXById[p.id] ?: continue
            val sy = startYById[p.id] ?: continue
            val d = hypot(p.x - sx, p.y - sy)
            if (d > worst) worst = d
        }
        travel = worst
        if (worst > peakTravel) peakTravel = worst
        if (points.size != lastCount) {
            // A finger joining or leaving is a discontinuity, not user movement.
            lastCount = points.size
            lastX = cx
            lastY = cy
            pinchRefDistance = -1f
            return
        }
        val dx = cx - lastX
        val dy = cy - lastY
        lastX = cx
        lastY = cy
        swipeDx += dx
        swipeDy += dy
        when (family) {
            Family.ONE -> {
                bufferedDx += dx
                bufferedDy += dy
                if (!dragClassified && travel > TAP_SLOP_DP * density) {
                    dragClassified = true
                    familyLocked = true
                }
                if (dragClassified) emitBuffered(out)
            }
            Family.TWO -> {
                if (points.size < 2) return
                val distance = hypot(points[0].x - points[1].x, points[0].y - points[1].y)
                if (pinchRefDistance < 0f) pinchRefDistance = distance
                pinchAccum += distance - pinchRefDistance
                pinchRefDistance = distance
                if (!pinchLive && abs(pinchAccum) > PINCH_START_DP * density) {
                    pinchLive = true
                    familyLocked = true
                    scrollClassified = false
                    out += PadAction.ZoomStart
                }
                if (pinchLive) {
                    val step = PINCH_STEP_DP * density
                    val steps = (pinchAccum / step).toInt()
                    if (steps != 0) {
                        pinchAccum -= steps * step
                        out += PadAction.Zoom(steps)
                    }
                } else {
                    scrollAccumX += dx
                    scrollAccumY += dy
                    if (!scrollClassified && travel > SCROLL_START_DP * density) {
                        scrollClassified = true
                        familyLocked = true
                    }
                    if (scrollClassified) emitScroll(out)
                }
            }
            Family.THREE -> {
                // The swipe is the hand moving, so it is measured by the centroid, not by how
                // far a single finger went. Gating on per-finger travel made a splay - fingers
                // spreading out while the hand stays put - look like a swipe, and a centroid
                // that barely moved then resolved to RIGHT.
                if (
                    maxPoints == 3 && !swipeEmitted &&
                    hypot(swipeDx, swipeDy) > SWIPE_DISTANCE_DP * density
                ) {
                    out += PadAction.Host(direction(swipeDx, swipeDy))
                    swipeEmitted = true
                    familyLocked = true
                }
            }
            null -> Unit
        }
    }

    private fun direction(dx: Float, dy: Float): PadDirection = when {
        abs(dx) >= abs(dy) -> if (dx >= 0f) PadDirection.RIGHT else PadDirection.LEFT
        else -> if (dy >= 0f) PadDirection.DOWN else PadDirection.UP
    }

    private fun emitScroll(out: MutableList<PadAction>) {
        // The step is divided by the sensitivity, so the slider that sets the pointer speed
        // also sets the scroll speed. A zero sensitivity would divide by zero, so it is
        // floored at a small value.
        val step = (SCROLL_STEP_DP * density / sensitivity.coerceAtLeast(0.01f))
            .coerceAtLeast(1f)
        val wheel = (scrollAccumY / step).toInt()
        val hPan = (scrollAccumX / step).toInt()
        if (wheel == 0 && hPan == 0) return
        out += PadAction.Scroll(wheel, hPan)
        if (wheel != 0) scrollAccumY -= wheel * step
        if (hPan != 0) scrollAccumX -= hPan * step
    }

    private fun end(nowMs: Long, out: MutableList<PadAction>) {
        if (pinchLive) {
            out += PadAction.ZoomEnd
        } else if (dragHeld) {
            out += PadAction.Button(1, false)
        } else if (nowMs - startMs <= TAP_TIMEOUT_MS && peakTravel <= TAP_SLOP_DP * density) {
            // The tap's finger count is the number of fingers that were down together at the
            // peak of the gesture. Fingers never lift in the same frame on a touchscreen, so
            // requiring the last count to equal the peak would discard every real tap.
            // peakTravel (not travel) is the test: a finger that moved and then lifted must
            // not be able to erase its own movement by leaving the frame.
            val button = when (family) {
                Family.ONE -> if (dragClassified) 0 else 1
                Family.TWO -> if (scrollClassified) 0 else 2
                Family.THREE -> if (maxPoints == 3 && !swipeEmitted) 3 else 0
                null -> 0
            }
            if (button != 0) {
                out += PadAction.Click(button)
                if (button == 1) {
                    pendingTapMs = nowMs
                    pendingTapX = startX
                    pendingTapY = startY
                }
            }
        }
        resetGesture()
    }

    private fun emitBuffered(out: MutableList<PadAction>) {
        val dx = (bufferedDx * sensitivity).roundToInt()
        val dy = (bufferedDy * sensitivity).roundToInt()
        if (dx == 0 && dy == 0) return
        out += PadAction.Move(dx, dy)
        if (dx != 0) bufferedDx = 0f
        if (dy != 0) bufferedDy = 0f
    }

    private fun resetGesture() {
        active = false
        family = null
        familyLocked = false
        maxPoints = 0
        lastCount = 0
        travel = 0f
        peakTravel = 0f
        swipeDx = 0f
        swipeDy = 0f
        startXById.clear()
        startYById.clear()
        bufferedDx = 0f
        bufferedDy = 0f
        dragClassified = false
        dragHeld = false
        doubleTapCandidate = false
        scrollAccumX = 0f
        scrollAccumY = 0f
        scrollClassified = false
        pinchAccum = 0f
        pinchRefDistance = -1f
        pinchLive = false
        swipeEmitted = false
    }

    private fun familyFor(count: Int): Family = when {
        count <= 1 -> Family.ONE
        count == 2 -> Family.TWO
        else -> Family.THREE
    }

    private fun centroidX(points: List<PadPoint>): Float = points.sumOf { it.x.toDouble() }.toFloat() / points.size

    private fun centroidY(points: List<PadPoint>): Float = points.sumOf { it.y.toDouble() }.toFloat() / points.size
}
