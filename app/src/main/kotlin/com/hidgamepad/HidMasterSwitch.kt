package com.hidgamepad

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The master HID on/off switch, shared between Settings and the Home page header.
 *
 * Off is red and on is green — the state of the phone-as-controller should read at a glance.
 * Every other switch in the app keeps the stock accent; this one alone is re-coloured.
 */
@Composable
fun HidMasterSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val green = Color(0xFF4CC38A)   // PadColors.connected
    val red = Color(0xFFD9534F)
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            // On: green thumb on a dark green track.
            checkedThumbColor = green,
            checkedTrackColor = Color(0xFF173A28),
            checkedBorderColor = Color.Transparent,
            // Off: red thumb on a dark red track.
            uncheckedThumbColor = red,
            uncheckedTrackColor = Color(0xFF3A1F1E),
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}
