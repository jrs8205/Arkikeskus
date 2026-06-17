package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/** 4b-ilmoitus #4: lähtömuistutuksen laukaisuajan laskenta. */
class DepartureReminderTest {

    @Test fun triggerIsLeadMinutesBeforeDeparture() {
        val depEpochSec = 1_700_000_000L
        assertEquals((depEpochSec - 5 * 60) * 1000L, DepartureReminder.triggerMs(depEpochSec, 5))
        assertEquals((depEpochSec - 10 * 60) * 1000L, DepartureReminder.triggerMs(depEpochSec, 10))
        assertEquals((depEpochSec - 15 * 60) * 1000L, DepartureReminder.triggerMs(depEpochSec, 15))
    }
}
