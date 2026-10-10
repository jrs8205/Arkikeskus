package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 4b-ilmoitus #4: lähtömuistutuksen laukaisuajan laskenta. */
class DepartureReminderTest {

    @Test fun triggerIsLeadMinutesBeforeDeparture() {
        val depEpochSec = 1_700_000_000L
        assertEquals((depEpochSec - 5 * 60) * 1000L, DepartureReminder.triggerMs(depEpochSec, 5))
        assertEquals((depEpochSec - 10 * 60) * 1000L, DepartureReminder.triggerMs(depEpochSec, 10))
        assertEquals((depEpochSec - 15 * 60) * 1000L, DepartureReminder.triggerMs(depEpochSec, 15))
    }

    private fun clock(epochSec: Long) = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(epochSec * 1000L))

    @Test fun notificationTextWithLineAndHeadsign() {
        val dep = 1_700_000_000L
        val t = DepartureReminder.notificationText("550", "Itäkeskus", "Kamppi", dep, 10)
        assertEquals(TransitText.Res(R.string.transit_reminder_notif_title_line, listOf("550", clock(dep))), t.title)
        assertEquals(
            TransitText.Plural(R.plurals.transit_reminder_notif_body_headsign, 10, listOf("Kamppi", 10, "Itäkeskus")),
            t.body,
        )
    }

    @Test fun notificationTextWithoutLineOrHeadsign() {
        val dep = 1_700_000_000L
        val t = DepartureReminder.notificationText("", "", "Kamppi", dep, 1)
        assertEquals(TransitText.Res(R.string.transit_departure_at, listOf(clock(dep))), t.title)
        assertEquals(TransitText.Plural(R.plurals.transit_reminder_notif_body, 1, listOf("Kamppi", 1)), t.body)
    }
}
