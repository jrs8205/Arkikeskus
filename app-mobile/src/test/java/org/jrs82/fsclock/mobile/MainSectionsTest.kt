package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Test

class MainSectionsTest {

    @Test fun jokaisellaOsiollaOmaOtsikkoresurssi() {
        val ids = HomeSection.values().map { it.titleRes }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(R.string.main_section_flights, HomeSection.FLIGHTS.titleRes)
        assertEquals(R.string.main_section_teletext, HomeSection.TELETEXT.titleRes)
    }

    @Test fun deepLinkNimetEivatMuutu() {
        // Widgetit ja ilmoitukset avaavat osion enumin nimellä → nimet pysyvät kielestä riippumatta.
        assertEquals(HomeSection.HSL_DISRUPTIONS, HomeSection.valueOf("HSL_DISRUPTIONS"))
        assertEquals(HomeSection.WORKOUT, HomeSection.valueOf("WORKOUT"))
        assertEquals(HomeSection.FORECAST, HomeSection.valueOf("FORECAST"))
    }

    @Test fun uutiskategorioidenTagitSailyvatJaNimetResursseista() {
        assertEquals(
            listOf("world", "tech", "mobile", "sport", "culture", "entertainment", "science", "nature", "fashion"),
            FOREIGN_CATEGORY_TAGS.map { it.first },
        )
        assertEquals(R.string.news_cat_world, FOREIGN_CATEGORY_TAGS.first().labelRes)
        assertEquals(R.string.news_cat_fashion, FOREIGN_CATEGORY_TAGS.last().labelRes)
        val labels = FOREIGN_CATEGORY_TAGS.map { it.labelRes }
        assertEquals(labels.size, labels.toSet().size)
    }
}
