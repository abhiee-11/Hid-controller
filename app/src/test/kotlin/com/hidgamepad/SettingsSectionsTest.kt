package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSectionsTest {

    @Test
    fun `there are four sections in order`() {
        assertEquals(
            listOf(
                SettingsSection.CONNECTION,
                SettingsSection.CONTROLS,
                SettingsSection.RGB,
                SettingsSection.INFORMATION,
            ),
            settingsSections,
        )
    }

    @Test
    fun `every section has a title and a summary`() {
        for (section in settingsSections) {
            assertTrue("${section.name} needs a title", section.title.isNotBlank())
            assertTrue("${section.name} needs a summary", section.summary.isNotBlank())
        }
    }

    @Test
    fun `back from a page returns to the list`() {
        assertEquals(SettingsBack.TO_LIST, settingsBackStep(SettingsSection.CONNECTION))
        assertEquals(SettingsBack.TO_LIST, settingsBackStep(SettingsSection.RGB))
    }

    @Test
    fun `back from the list closes settings`() {
        assertEquals(SettingsBack.CLOSE, settingsBackStep(null))
    }
}
