package com.hidgamepad

/** One page of the Settings sheet. `null` in the panel means the list itself. */
enum class SettingsSection(val title: String, val summary: String) {
    CONNECTION("Connection", "Host, pairing"),
    CONTROLS("Controls", "Mode, host type, haptics, tilt, pointer"),
    RGB("RGB backlight", "Effects and colours"),
    INFORMATION("Information", "Help and status"),
}

/** The four pages, in the order the Settings list shows them. */
val settingsSections: List<SettingsSection> = SettingsSection.entries

/** What the Back action does inside Settings. */
enum class SettingsBack { TO_LIST, CLOSE }

/** Back from a page returns to the list; back from the list closes the sheet. */
fun settingsBackStep(current: SettingsSection?): SettingsBack =
    if (current == null) SettingsBack.CLOSE else SettingsBack.TO_LIST
