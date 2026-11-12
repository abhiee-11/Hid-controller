package com.hidgamepad

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * The device and screen facts that make a bug report actionable.
 *
 * A report that says only "the gamepad does nothing" cannot be acted on. The window size in
 * dp, the display density, whether the screen has a cutout and which screen the user was on
 * are what turn it into something a maintainer can reproduce or dismiss.
 *
 * Deliberately **not** collected: Bluetooth addresses and bonded-device names. They identify
 * other people's devices and have no business on a public server.
 */
data class DeviceScreenInfo(
    val windowWidthDp: Int,
    val windowHeightDp: Int,
    val orientation: String,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val densityDpi: Int,
    val densityScale: String,
    val smallestWidthDp: Int,
    val refreshRateHz: String,
    /** The cutout and the edge it sits on, e.g. "67 px top inset"; null when there is none. */
    val cutoutInset: String?,
    val cornerRadiusPx: Int,
    val theme: String,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val appVersion: String,
)

/** Reads the facts from the platform. */
fun collectDeviceScreenInfo(activity: Activity): DeviceScreenInfo {
    val configuration = activity.resources.configuration
    val metrics = activity.resources.displayMetrics

    val refreshRate = run {
        val hz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.refreshRate
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay?.refreshRate
        }
        if (hz == null || hz <= 0f) "unknown" else hz.toInt().toString()
    }

    val insets = activity.window.decorView.rootWindowInsets
    val cutout = insets?.displayCutout

    val cornerRadius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Any corner can carry the radius; the largest one is the device's.
        runCatching {
            (0..3).mapNotNull { insets?.getRoundedCorner(it)?.radius }.maxOrNull() ?: 0
        }.getOrDefault(0)
    } else {
        0
    }

    return DeviceScreenInfo(
        windowWidthDp = configuration.screenWidthDp,
        windowHeightDp = configuration.screenHeightDp,
        orientation = when (configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "other"
        },
        displayWidthPx = metrics.widthPixels,
        displayHeightPx = metrics.heightPixels,
        densityDpi = metrics.densityDpi,
        densityScale = String.format(Locale.US, "%.3f", metrics.density).trimEnd('0').trimEnd('.'),
        smallestWidthDp = configuration.smallestScreenWidthDp,
        refreshRateHz = refreshRate,
        // The cutout moves with the rotation: a phone's notch is at the top in portrait and
        // on a side in landscape. Naming the edge it is actually on is the only way the
        // detail is useful.
        cutoutInset = when {
            cutout == null -> null
            cutout.safeInsetTop > 0 -> "${cutout.safeInsetTop} px top inset"
            cutout.safeInsetBottom > 0 -> "${cutout.safeInsetBottom} px bottom inset"
            cutout.safeInsetLeft > 0 -> "${cutout.safeInsetLeft} px left inset"
            cutout.safeInsetRight > 0 -> "${cutout.safeInsetRight} px right inset"
            else -> null
        },
        cornerRadiusPx = cornerRadius,
        theme = if (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        ) "dark" else "light",
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        androidRelease = Build.VERSION.RELEASE ?: "unknown",
        sdkInt = Build.VERSION.SDK_INT,
        appVersion = runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        }.getOrNull() ?: "unknown",
    )
}

/**
 * The details block that accompanies the user's description.
 *
 * The screen the user was on is named separately from the controller mode, because a layout
 * bug only reproduces on the screen it was seen on.
 */
fun DeviceScreenInfo.toDetailsBlock(
    screenLabel: String,
    mode: ControllerMode,
    hostType: HostType,
    hidStatus: String,
    tiltOn: Boolean,
): String = buildString {
    appendLine("Screen")
    appendLine("  Window        $windowWidthDp x $windowHeightDp dp ($orientation)")
    appendLine("  Display       $displayWidthPx x $displayHeightPx px, $densityDpi dpi ($densityScale" + "x)")
    appendLine("  Smallest w    $smallestWidthDp dp")
    appendLine("  Width class   ${windowSizeClass(windowWidthDp)}")
    appendLine("  Refresh rate  $refreshRateHz Hz")
    appendLine(
        if (cutoutInset != null) "  Cutout        yes — $cutoutInset" else "  Cutout        no"
    )
    appendLine(
        if (cornerRadiusPx > 0) "  Corners       $cornerRadiusPx px radius" else "  Corners       none"
    )
    appendLine("  Theme         $theme")
    appendLine()
    appendLine("Device")
    appendLine("  $manufacturer $model")
    appendLine("  Android $androidRelease (SDK $sdkInt)")
    appendLine()
    appendLine("App")
    appendLine("  HID Controller $appVersion")
    appendLine("  Screen         $screenLabel")
    appendLine("  Mode           ${modeLabel(mode)}")
    appendLine("  Host type      ${hostTitle(hostType)}")
    appendLine("  HID            $hidStatus")
    appendLine("  Tilt           ${if (tiltOn) "on" else "off"}")
}

/**
 * The window width class, using the standard breakpoints.
 *
 * A layout bug usually depends on which bucket the window fell into, not on the raw pixel
 * count — the app lays out differently for a compact window than for an expanded one.
 */
private fun windowSizeClass(widthDp: Int): String = when {
    widthDp < 600 -> "compact"
    widthDp < 840 -> "medium"
    else -> "expanded"
}

