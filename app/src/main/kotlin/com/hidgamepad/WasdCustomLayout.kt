package com.hidgamepad

/**
 * Per-key geometry for the WASD page, stored as fractions of the pad (0..1 on every axis).
 * A key is described by a top-left corner [x],[y] and its size [w],[h].
 */
data class WasdKeyGeometry(val id: String, val x: Float, val y: Float, val w: Float, val h: Float)

/** The key may never be smaller than this on either axis — it has to stay tappable. */
const val MIN_KEY_SIZE = 0.08f

/**
 * The built-in layout: roughly what the page drew before customisation. The ids are the key
 * labels from [wasdLayout].
 */
fun defaultWasdGeometry(): List<WasdKeyGeometry> = listOf(
    WasdKeyGeometry("Tab", 0.02f, 0.02f, 0.15f, 0.16f),
    WasdKeyGeometry("Esc", 0.83f, 0.02f, 0.15f, 0.16f),
    // Left thumb: Shift / W / A S D.
    WasdKeyGeometry("Shift", 0.02f, 0.24f, 0.26f, 0.21f),
    WasdKeyGeometry("W", 0.02f, 0.50f, 0.26f, 0.21f),
    WasdKeyGeometry("A", 0.02f, 0.76f, 0.085f, 0.215f),
    WasdKeyGeometry("S", 0.115f, 0.76f, 0.085f, 0.215f),
    WasdKeyGeometry("D", 0.205f, 0.76f, 0.085f, 0.215f),
    // Centre column: Enter on top, Space as the wide bar below it.
    WasdKeyGeometry("Enter", 0.305f, 0.24f, 0.145f, 0.21f),
    WasdKeyGeometry("Space", 0.305f, 0.50f, 0.145f, 0.475f),
    // Right thumb: Ctrl / E R / Q F.
    WasdKeyGeometry("Ctrl", 0.475f, 0.24f, 0.505f, 0.21f),
    WasdKeyGeometry("E", 0.475f, 0.50f, 0.235f, 0.21f),
    WasdKeyGeometry("R", 0.72f, 0.50f, 0.235f, 0.21f),
    WasdKeyGeometry("Q", 0.475f, 0.76f, 0.235f, 0.215f),
    WasdKeyGeometry("F", 0.72f, 0.76f, 0.235f, 0.215f),
)

/** Pulls the key inside the pad and enforces the minimum size. Keys larger than the pad shrink. */
fun WasdKeyGeometry.clamped(): WasdKeyGeometry {
    val w = w.coerceIn(MIN_KEY_SIZE, 1f)
    val h = h.coerceIn(MIN_KEY_SIZE, 1f)
    val x = x.coerceIn(0f, 1f - w)
    val y = y.coerceIn(0f, 1f - h)
    return copy(x = x, y = y, w = w, h = h)
}

/** The ids that must be present for the layout the page draws today. */
val DEFAULT_WASD_GEOMETRY_IDS: List<String>
    get() = defaultWasdGeometry().map { it.id }

/**
 * Gives every key a stable storage id: its label, with "#2", "#3"… appended when the same
 * label appears more than once with a different action (the keyboard page has two Shift keys).
 */
fun assignKeyIds(caps: List<KeyCap>): Map<String, KeyCap> {
    val out = linkedMapOf<String, KeyCap>()
    val counts = mutableMapOf<String, Int>()
    for (cap in caps) {
        val n = (counts[cap.label] ?: 0) + 1
        counts[cap.label] = n
        out[if (n == 1) cap.label else "${cap.label}#$n"] = cap
    }
    return out
}

/** Folds a moved or resized key back into a clamped layout list. */
fun WasdKeyGeometry.resizeBy(factor: Float): WasdKeyGeometry {
    val grown = copy(w = w * factor, h = h * factor)
    // Resizing from the centre keeps the key where it is.
    val dx = (grown.w - w) / 2f
    val dy = (grown.h - h) / 2f
    return grown.copy(x = x - dx, y = y - dy).clamped()
}

// ---------------------------------------------------------------
// Persistence: a prefs value holding every key as `id,x,y,w,h` separated by `;`.
// Never trust it: one malformed entry drops the whole stored layout and the page
// goes back to the built-in grid.
// ---------------------------------------------------------------

const val WASD_LAYOUT_PREF_KEY = "wasd_layout_custom_v1"

fun wasdLayoutToStorage(items: List<WasdKeyGeometry>): String =
    items.joinToString(";") { "${it.id},${it.x},${it.y},${it.w},${it.h}" }

fun wasdLayoutFromStorage(stored: String?): List<WasdKeyGeometry>? {
    if (stored.isNullOrBlank()) return null
    val items = mutableListOf<WasdKeyGeometry>()
    for (part in stored.split(';')) {
        if (part.isBlank()) continue
        val fields = part.split(',')
        if (fields.size != 5) return null
        val id = fields[0]
        if (id.isBlank()) return null
        val numbers = fields.drop(1).map { it.toFloatOrNull() ?: return null }
        items += WasdKeyGeometry(id, numbers[0], numbers[1], numbers[2], numbers[3]).clamped()
    }
    if (items.isEmpty()) return null
    return items
}
