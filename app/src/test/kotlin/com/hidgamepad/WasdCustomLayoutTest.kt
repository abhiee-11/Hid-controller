package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WASD page's customisable geometry: a standalone move/resize model that stays
 * independent of the grid page, so customising one key never disturbs another.
 */
class WasdCustomLayoutTest {

    @Test
    fun `the default geometry is the driving layout`() {
        // Left stack: Space (handbrake) over W (gas) over S (brake). Right pair: A, D (steer).
        // Esc sits top-right. Every one of them must be placeable from the palette.
        val ids = defaultWasdGeometry().map { it.id }
        assertEquals(listOf("Esc", "Space", "W", "S", "A", "D"), ids)
        val pool = assignKeyIds(
            (wasdLayout().keys + keyboardRows().flatten() + functionRow()).distinct(),
        )
        for (id in ids) {
            assertTrue("$id is not in the palette", pool.containsKey(id))
        }
    }

    @Test
    fun `the driving layout puts the stack on the left and the steer pair on the right`() {
        val byId = defaultWasdGeometry().associateBy { it.id }
        val space = byId.getValue("Space")
        val w = byId.getValue("W")
        val s = byId.getValue("S")
        val a = byId.getValue("A")
        val d = byId.getValue("D")
        val esc = byId.getValue("Esc")
        // The stack shares a left edge and runs downward.
        assertEquals(space.x, w.x, 1e-6f)
        assertEquals(space.x, s.x, 1e-6f)
        assertTrue("Space is above W", space.y < w.y)
        assertTrue("W is above S", w.y < s.y)
        // The steer pair shares a top edge, A on the left.
        assertEquals(a.y, d.y, 1e-6f)
        assertTrue("A is left of D", a.x < d.x)
        assertTrue("the stack is left of the steer pair", s.x + s.w <= a.x + 0.01f)
        // Esc is top-right, above the steer pair.
        assertTrue("Esc is right of centre", esc.x > 0.5f)
        assertTrue("Esc is near the top", esc.y < a.y)
    }

    @Test
    fun `the default geometry sits inside the pad`() {
        for (g in defaultWasdGeometry()) {
            assertTrue("${g.id} x=${g.x} y=${g.y}", g.x >= 0f && g.y >= 0f)
            assertTrue("${g.id} w=${g.w} h=${g.h} size too small", g.w >= MIN_KEY_SIZE && g.h >= MIN_KEY_SIZE)
            assertTrue("${g.id} overflows horizontally", g.x + g.w <= 1.0001f)
            assertTrue("${g.id} overflows vertically", g.y + g.h <= 1.0001f)
        }
    }

    @Test
    fun `a moved key is clamped back inside the pad`() {
        val clamped = WasdKeyGeometry("W", -0.5f, 0.8f, 0.9f, 0.6f).clamped()
        assertEquals(0f, clamped.x, 0f)
        assertEquals(0.4f, clamped.y, 1e-6f)
        assertEquals(0.9f, clamped.w, 0f)
        assertEquals(0.6f, clamped.h, 0f)
    }

    @Test
    fun `a stored key smaller than the minimum size is bumped up to it`() {
        val clamped = WasdKeyGeometry("A", 0.2f, 0.2f, 0f, 0f).clamped()
        assertEquals(MIN_KEY_SIZE, clamped.w, 0f)
        assertEquals(MIN_KEY_SIZE, clamped.h, 0f)
    }

    @Test
    fun `an oversized key shrinks to the pad`() {
        val clamped = WasdKeyGeometry("Space", 0.1f, 0.1f, 1.5f, 1.5f).clamped()
        assertEquals(1f, clamped.w, 0f)
        assertEquals(1f, clamped.h, 0f)
        assertEquals(0f, clamped.x, 0f)
        assertEquals(0f, clamped.y, 0f)
    }

    @Test
    fun `toggling size grows from the middle`() {
        val g = WasdKeyGeometry("W", 0.3f, 0.3f, 0.2f, 0.2f)
        val grown = g.resizeBy(1.5f)
        assertTrue(grown.w > g.w)
        assertEquals(g.x + g.w / 2f, grown.x + grown.w / 2f, 1e-6f)
        assertEquals(g.y + g.h / 2f, grown.y + grown.h / 2f, 1e-6f)
    }

    @Test
    fun `storage round-trip keeps every entry`() {
        val items = defaultWasdGeometry()
        val restored = wasdLayoutFromStorage(wasdLayoutToStorage(items))
        assertEquals(items, restored)
    }

    @Test
    fun `malformed storage returns null`() {
        assertNull(wasdLayoutFromStorage(""))
        assertNull(wasdLayoutFromStorage("   "))
        assertNull(wasdLayoutFromStorage(null))
        assertNull(wasdLayoutFromStorage("garbage"))
        assertNull(wasdLayoutFromStorage("W"))
        assertNull(wasdLayoutFromStorage("W,0.1,0.1,0.1"))
        assertNull(wasdLayoutFromStorage("W,a,0.1,0.1,0.1"))
    }

    @Test
    fun `stored values are clamped but otherwise survive`() {
        val restored = wasdLayoutFromStorage("W,0.99,0.99,0.5,0.5;A,0.1,0.1,0.2,0.2")
        assertNotNull(restored)
        val w = restored!!.first { it.id == "W" }
        assertEquals(0.5f, w.x, 0.001f) // 0.99 would overflow: clamped to 1 - 0.5
        assertEquals(0.5f, w.y, 0.001f)
        val a = restored.first { it.id == "A" }
        assertEquals(0.1f, a.x, 0.001f)
        assertEquals(0.2f, a.w, 0.001f)
    }

    @Test
    fun `the preferred-key list matches the page exactly`() {
        assertEquals(
            defaultWasdGeometry().map { it.id }.sorted(),
            DEFAULT_WASD_GEOMETRY_IDS.sorted(),
        )
    }

    @Test
    fun `ids are labels, with a suffix when a label repeats`() {
        val pool = assignKeyIds(
            listOf(
                KeyCap("Shift", KeyAction.Modifier(MOD_LEFT_SHIFT)),
                KeyCap("Shift", KeyAction.Modifier(MOD_RIGHT_SHIFT)),
                KeyCap("W", KeyAction.Usage(KeyUsage.W)),
            ),
        )
        assertEquals(listOf("Shift", "Shift#2", "W"), pool.keys.toList())
    }

    @Test
    fun `the whole key pool gets one id per entry`() {
        val caps = (wasdLayout().keys + keyboardRows().flatten() + functionRow()).distinct()
        val pool = assignKeyIds(caps)
        assertEquals(caps.size, pool.size)
        assertEquals(pool.size, pool.keys.toSet().size)
        // Every key the page draws is addable back after removal.
        for (label in wasdLayout().keys.map { it.label }) {
            assertTrue("$label is not in the pool", pool.values.any { it.label == label })
        }
    }
}