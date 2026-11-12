package com.hidgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TutorialTest {

    @Test
    fun `the tutorial has five steps`() {
        assertEquals(5, TutorialStep.entries.size)
        assertEquals(5, TutorialStep.count)
    }

    @Test
    fun `steps are numbered from one`() {
        assertEquals(1, TutorialStep.WELCOME.number)
        assertEquals(5, TutorialStep.READY.number)
    }

    @Test
    fun `the tutorial starts at the welcome step`() {
        assertEquals(TutorialStep.WELCOME, TutorialStep.start)
    }

    @Test
    fun `next walks forward through every step`() {
        assertEquals(TutorialStep.HOST, TutorialStep.WELCOME.next())
        assertEquals(TutorialStep.MODE, TutorialStep.HOST.next())
        assertEquals(TutorialStep.PAIR, TutorialStep.MODE.next())
        assertEquals(TutorialStep.READY, TutorialStep.PAIR.next())
    }

    @Test
    fun `there is no step after the last one`() {
        assertNull(TutorialStep.READY.next())
    }

    @Test
    fun `previous walks back through every step`() {
        assertEquals(TutorialStep.PAIR, TutorialStep.READY.previous())
        assertEquals(TutorialStep.MODE, TutorialStep.PAIR.previous())
        assertEquals(TutorialStep.HOST, TutorialStep.MODE.previous())
        assertEquals(TutorialStep.WELCOME, TutorialStep.HOST.previous())
    }

    @Test
    fun `there is no step before the first one`() {
        assertNull(TutorialStep.WELCOME.previous())
    }

    @Test
    fun `skip lands on the final step so the user can still start the app`() {
        // Skipping must never leave the user stranded in the middle of the tutorial with no
        // way to finish it, so it goes to the step that confirms the setup.
        for (step in TutorialStep.entries) {
            assertEquals(TutorialStep.READY, step.skipTarget())
        }
    }

    @Test
    fun `the final step is the one that finishes the tutorial`() {
        assertEquals(true, TutorialStep.READY.isFinal)
        assertEquals(false, TutorialStep.WELCOME.isFinal)
    }
}
