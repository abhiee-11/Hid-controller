package com.hidgamepad

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reading stored settings.
 *
 * SharedPreferences throws a ClassCastException from the getter when a key holds a value of a
 * different type - a value written by an older build, or restored from a backup. These reads run
 * while the UI is being built, so an unguarded one turns a single stale value into a crash on
 * launch. Every read therefore falls back to its default instead of throwing.
 */
class PrefsTest {

    @Test
    fun `a stored float is returned`() {
        val prefs = FakePrefs(mapOf("mouse_sensitivity" to 0.5f))
        assertEquals(0.5f, readFloatPref(prefs, "mouse_sensitivity", 1f), 0f)
    }

    @Test
    fun `a missing float falls back to the default`() {
        assertEquals(0.05f, readFloatPref(FakePrefs(emptyMap()), "tilt_deadzone", 0.05f), 0f)
    }

    @Test
    fun `a float key holding a string falls back instead of throwing`() {
        val prefs = FakePrefs(mapOf("haptics_strength" to "strong"))
        assertEquals(1f, readFloatPref(prefs, "haptics_strength", 1f), 0f)
    }

    @Test
    fun `a float key holding an int falls back instead of throwing`() {
        val prefs = FakePrefs(mapOf("haptics_strength" to 1))
        assertEquals(1f, readFloatPref(prefs, "haptics_strength", 1f), 0f)
    }

    @Test
    fun `a float key holding a boolean falls back instead of throwing`() {
        val prefs = FakePrefs(mapOf("tilt_sensitivity" to true))
        assertEquals(1f, readFloatPref(prefs, "tilt_sensitivity", 1f), 0f)
    }
}

/** A SharedPreferences whose reads behave like the real one: a type mismatch throws. */
private class FakePrefs(private val stored: Map<String, Any>) : SharedPreferences {

    private fun raw(key: String?, default: Any?): Any? =
        if (key == null) default else stored[key] ?: default

    override fun getAll(): MutableMap<String, *> = stored.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = raw(key, defValue) as String?
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        raw(key, defValues) as MutableSet<String>?
    override fun getInt(key: String?, defValue: Int): Int = raw(key, defValue) as Int
    override fun getLong(key: String?, defValue: Long): Long = raw(key, defValue) as Long
    override fun getFloat(key: String?, defValue: Float): Float = raw(key, defValue) as Float
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = raw(key, defValue) as Boolean
    override fun contains(key: String?): Boolean = key != null && stored.containsKey(key)
    override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}
