package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TutorialTest {

    @Test
    fun `setup has two steps`() {
        assertEquals(2, TutorialStep.entries.size)
        assertEquals(2, TutorialStep.count)
    }

    @Test
    fun `steps are numbered from one`() {
        assertEquals(1, TutorialStep.HOST.number)
        assertEquals(2, TutorialStep.MODE.number)
    }

    @Test
    fun `setup starts at the host step`() {
        assertEquals(TutorialStep.HOST, TutorialStep.start)
    }

    @Test
    fun `next walks forward through every step`() {
        assertEquals(TutorialStep.MODE, TutorialStep.HOST.next())
    }

    @Test
    fun `there is no step after the last one`() {
        assertNull(TutorialStep.MODE.next())
    }

    @Test
    fun `previous walks back through every step`() {
        assertEquals(TutorialStep.HOST, TutorialStep.MODE.previous())
    }

    @Test
    fun `there is no step before the first one`() {
        assertNull(TutorialStep.HOST.previous())
    }

    @Test
    fun `skip lands on the final step so the user can still start the app`() {
        for (step in TutorialStep.entries) {
            assertEquals(TutorialStep.MODE, step.skipTarget())
        }
    }

    @Test
    fun `the final step is the one that finishes setup`() {
        assertEquals(true, TutorialStep.MODE.isFinal)
        assertEquals(false, TutorialStep.HOST.isFinal)
    }
}
