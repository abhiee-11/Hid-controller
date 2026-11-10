package com.hidgamepad

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
const val SCROLL_STEP_DP = 20f
const val PINCH_START_DP = 12f
const val PINCH_STEP_DP = 15f
const val SWIPE_DISTANCE_DP = 40f
const val SETTLE_MS = 40L

/**
 * Pure trackpad gesture state machine: pointer frames in, [PadAction]s out. No Android
 * imports, so every rule is unit-testable.
 *
 * [density] is pixels per dp (Compose's `LocalDensity.current.density`), used for thresholds.
 * [sensitivity] scales pointer movement into host cursor units.
 */
class TouchpadGestureRecognizer(
    private val density: Float,
    private val sensitivity: Float,
) {
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
    private var bufferedDx = 0f
    private var bufferedDy = 0f
    private var dragClassified = false
    private var dragHeld = false
    private var doubleTapCandidate = false
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
            family = familyFor(maxPoints)
            if (nowMs - startMs >= SETTLE_MS) familyLocked = true
        }
        val cx = centroidX(points)
        val cy = centroidY(points)
        travel = hypot(cx - startX, cy - startY)
        if (points.size != lastCount) {
            // A finger joining or leaving is a discontinuity, not user movement.
            lastCount = points.size
            lastX = cx
            lastY = cy
            return
        }
        val dx = cx - lastX
        val dy = cy - lastY
        lastX = cx
        lastY = cy
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
            else -> Unit
        }
    }

    private fun end(nowMs: Long, out: MutableList<PadAction>) {
        if (dragHeld) {
            out += PadAction.Button(1, false)
        } else if (!dragClassified && family == Family.ONE && nowMs - startMs <= TAP_TIMEOUT_MS) {
            out += PadAction.Click(1)
            pendingTapMs = nowMs
            pendingTapX = startX
            pendingTapY = startY
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
        bufferedDx = 0f
        bufferedDy = 0f
        dragClassified = false
        dragHeld = false
        doubleTapCandidate = false
    }

    private fun familyFor(count: Int): Family = when {
        count <= 1 -> Family.ONE
        count == 2 -> Family.TWO
        else -> Family.THREE
    }

    private fun centroidX(points: List<PadPoint>): Float = points.sumOf { it.x.toDouble() }.toFloat() / points.size

    private fun centroidY(points: List<PadPoint>): Float = points.sumOf { it.y.toDouble() }.toFloat() / points.size
}
