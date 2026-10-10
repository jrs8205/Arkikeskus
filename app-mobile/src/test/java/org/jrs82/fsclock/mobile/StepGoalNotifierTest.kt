package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Askeltavoiteilmoituksen päätös (StepGoalNotifier.decide): rakenne ja resurssi-id:t, ei tekstiä. */
class StepGoalNotifierTest {

    @Test
    fun `tavoite taynna antaa tavoiteviestin askelmaaran monikolla`() {
        val m = StepGoalNotifier.decide(12_345L, 10_000, halfNotifiedToday = false)!!
        assertEquals(StepGoalNotifier.Kind.GOAL, m.kind)
        assertEquals(R.string.steps_goal_reached_title, m.titleRes)
        assertEquals(R.plurals.steps_goal_reached_text, m.textRes)
        assertEquals(12_345, m.quantity)
    }

    @Test
    fun `puolimatka kerran paivassa ennen tavoitetta`() {
        val m = StepGoalNotifier.decide(5_000L, 10_000, halfNotifiedToday = false)!!
        assertEquals(StepGoalNotifier.Kind.HALF, m.kind)
        assertEquals(R.string.steps_goal_half_title, m.titleRes)
        assertEquals(R.plurals.steps_goal_half_text, m.textRes)
        // Puolimatkan monikko seuraa tavoitetta ("/ 10 000 askelta")
        assertEquals(10_000, m.quantity)
        assertNull(StepGoalNotifier.decide(5_000L, 10_000, halfNotifiedToday = true))
    }

    @Test
    fun `alle puolen tai nollatavoite ei ilmoita`() {
        assertNull(StepGoalNotifier.decide(4_999L, 10_000, halfNotifiedToday = false))
        assertNull(StepGoalNotifier.decide(50_000L, 0, halfNotifiedToday = false))
    }

    @Test
    fun `tavoite voittaa vaikka puolimatka ilmoitettu`() {
        val m = StepGoalNotifier.decide(10_000L, 10_000, halfNotifiedToday = true)!!
        assertEquals(StepGoalNotifier.Kind.GOAL, m.kind)
    }
}
