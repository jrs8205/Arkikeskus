package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsChannelsTest {

    @Test fun kanavienNimetJaKuvauksetTulevatResursseista() {
        val ch = Notifications.CHANNELS
        assertEquals(6, ch.size)
        assertEquals(ch.size, ch.map { it.id }.toSet().size)
        assertEquals(ch.size, ch.map { it.nameRes }.toSet().size)
        assertEquals(ch.size, ch.map { it.descRes }.toSet().size)
        val hsl = ch.first { it.id == Notifications.CHANNEL_HSL_ALERTS }
        assertEquals(R.string.notif_channel_hsl_name, hsl.nameRes)
        assertEquals(R.string.notif_channel_hsl_desc, hsl.descRes)
    }

    @Test fun kanavienIdtEivatMuutu() {
        // Id:t ovat käyttäjän Android-asetusten avaimia → käännös ei saa vaihtaa niitä.
        assertEquals(
            listOf("arki_hsl_alerts", "arki_weather_warnings", "arki_electricity", "arki_step_goal", "arki_departure", "arki_app_update"),
            Notifications.CHANNELS.map { it.id },
        )
    }

    @Test fun kanavatLuodaanUudelleenVainKielenVaihtuessa() {
        assertTrue(Notifications.needsChannelRefresh(null, "fi"))
        assertFalse(Notifications.needsChannelRefresh("fi", "fi"))
        assertTrue(Notifications.needsChannelRefresh("fi", "sv"))
        assertTrue(Notifications.needsChannelRefresh("sv", "en"))
    }
}
