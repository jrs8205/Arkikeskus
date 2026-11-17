package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.ElectricityData
import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** Sähkösivun tyhjän tilan teksti ja lähdemaininta: puhtaat valinnat ilman Androidia. */
class ElectricityPageStateTest {

    @Test
    fun `huomenna ilman dataa kertoo julkaisuajan kun haku onnistui`() {
        assertEquals(R.string.electricity_tomorrow_pending, electricityEmptyTextRes(dayOffset = 1, fetchFailed = false))
    }

    @Test
    fun `huomenna ilman dataa kertoo virheesta kun haku epaonnistui`() {
        assertEquals(R.string.electricity_fetch_failed, electricityEmptyTextRes(dayOffset = 1, fetchFailed = true))
    }

    @Test
    fun `tanaan ilman dataa kertoo virheesta kun haku epaonnistui`() {
        assertEquals(R.string.electricity_fetch_failed, electricityEmptyTextRes(dayOffset = 0, fetchFailed = true))
    }

    @Test
    fun `tanaan ilman dataa ilman virhetta sailyttaa aiemman tekstin`() {
        assertEquals(R.string.electricity_today_unavailable, electricityEmptyTextRes(dayOffset = 0, fetchFailed = false))
    }

    @Test
    fun `tanaan nayttaa kuluvan jakson hinnan kun se on saatavilla`() {
        assertEquals(ElectricityHero.CURRENT, electricityHero(dayOffset = 0, hasCurrent = true))
    }

    @Test
    fun `tanaan ilman kuluvan jakson hintaa on oma tilansa eika huomisen esitys`() {
        // Esim. tuntitilassa kuluvalta tunnilta puuttuu vartti: muilla tunneilla on hinnat, mutta
        // nykyhinnan paikalle ei saa tulla Huomenna-tunnusta ja päivän keskiarvoa.
        assertEquals(ElectricityHero.NO_CURRENT, electricityHero(dayOffset = 0, hasCurrent = false))
    }

    @Test
    fun `huomenna nayttaa paivan keskihinnan`() {
        assertEquals(ElectricityHero.DAY_AVERAGE, electricityHero(dayOffset = 1, hasCurrent = false))
        assertEquals(ElectricityHero.DAY_AVERAGE, electricityHero(dayOffset = 1, hasCurrent = true))
    }

    @Test
    fun `puuttuvan nykyhinnan teksti seuraa tarkkuutta`() {
        assertEquals(R.string.electricity_no_current_hour_price, electricityNoCurrentTextRes(hourly = true))
        assertEquals(R.string.electricity_no_current_price, electricityNoCurrentTextRes(hourly = false))
    }

    @Test
    fun `lahdemaininta seuraa datan lahdetta`() {
        assertEquals(R.string.electricity_source_footer, electricitySourceFooterRes(ElectricityData.SOURCE_ELERING))
        assertEquals(
            R.string.electricity_source_footer_spot_hinta,
            electricitySourceFooterRes(ElectricityData.SOURCE_SPOT_HINTA),
        )
    }
}
