package com.hidgamepad

import android.content.SharedPreferences

/**
 * Reads a float preference, falling back to [default] when the stored value is not a float.
 *
 * SharedPreferences throws a ClassCastException from the getter when a key holds a value of a
 * different type - one written by an older build, or restored from a backup. These reads run
 * while the screen is being built, so an unguarded one turns a single stale value into a crash
 * on launch.
 */
fun readFloatPref(prefs: SharedPreferences, key: String, default: Float): Float =
    runCatching { prefs.getFloat(key, default) }.getOrDefault(default)
