package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlightDisplayTest {
    private fun f(status: String, sch: Long, est: Long?) =
        Flight(FlightDir.DEP, "HEL", "AY1", sch, est, null, "", status, "X", "X", null, null, null, null, null, emptyList())

    @Test fun kategoriaTekstistaJaMyohastymisesta() {
        assertEquals(FlightStatusCat.CANCELLED, FlightDisplay.category(f("Peruttu", 0, null)))
        assertEquals(FlightStatusCat.COMPLETED, FlightDisplay.category(f("Lähtenyt", 0, null)))
        assertEquals(FlightStatusCat.COMPLETED, FlightDisplay.category(f("Laskeutunut", 0, null)))
        assertEquals(FlightStatusCat.ATTENTION, FlightDisplay.category(f("Lähtöselvitys", 0, null)))
        // 10 min myöhässä, ei lopputilaa
        assertEquals(FlightStatusCat.DELAYED, FlightDisplay.category(f("Arvioitu", 0, 600_000)))
        assertEquals(FlightStatusCat.ON_TIME, FlightDisplay.category(f("Aikataulussa", 0, null)))
        assertEquals(FlightStatusCat.ATTENTION, FlightDisplay.category(f("Portille", 0, null)))
        assertEquals(FlightStatusCat.DELAYED, FlightDisplay.category(f("Arvioitu aika", 0, null)))
        assertEquals(FlightStatusCat.DELAYED, FlightDisplay.category(f("Myöhässä", 0, null)))
        assertEquals(FlightStatusCat.ATTENTION, FlightDisplay.category(f("Viimeinen kutsu", 0, null)))
    }

    @Test fun suomeksiTilaNaytetaanLahteenSanamuodolla() {
        assertEquals(FlightText.Raw("Arvioitu aika"), FlightDisplay.statusText(f("Arvioitu aika", 0, null), "fi"))
        assertEquals(FlightText.Raw("Laskeutunut"), FlightDisplay.statusText(f("Laskeutunut", 0, null), "fi"))
    }

    @Test fun muillaKielillaTunnettuTilaKaannetaan() {
        assertEquals(FlightText.Res(R.string.flights_status_landed), FlightDisplay.statusText(f("Laskeutunut", 0, null), "en"))
        assertEquals(FlightText.Res(R.string.flights_status_cancelled), FlightDisplay.statusText(f("Peruttu", 0, null), "sv"))
        assertEquals(FlightText.Res(R.string.flights_status_delayed), FlightDisplay.statusText(f("Myöhässä", 0, null), "en"))
    }

    @Test fun tuntematonTilaJaaSuomeksi() {
        assertEquals(FlightText.Raw("Ohjattu toiselle kentälle"), FlightDisplay.statusText(f("Ohjattu toiselle kentälle", 0, null), "en"))
        assertNull(FlightDisplay.statusRes(""))
    }

    @Test fun tarkempiTilaVoittaaYleisemman() {
        assertEquals(R.string.flights_status_final_call, FlightDisplay.statusRes("Viimeinen kutsu"))
        assertEquals(R.string.flights_status_checkin_closed, FlightDisplay.statusRes("Lähtöselvitys suljettu"))
        assertEquals(R.string.flights_status_gate_closed, FlightDisplay.statusRes("Portti suljettu"))
        assertEquals(R.string.flights_status_checkin, FlightDisplay.statusRes("Lähtöselvitys"))
        assertEquals(R.string.flights_status_go_to_gate, FlightDisplay.statusRes("Portille"))
        assertEquals(R.string.flights_status_boarding, FlightDisplay.statusRes("Koneeseen"))
        assertEquals(R.string.flights_status_estimated, FlightDisplay.statusRes("Arvioitu aika"))
        assertEquals(R.string.flights_status_new_time, FlightDisplay.statusRes("Uusi aika"))
        assertEquals(R.string.flights_status_on_time, FlightDisplay.statusRes("Aikataulussa"))
        assertEquals(R.string.flights_status_departed, FlightDisplay.statusRes("Lähtenyt"))
    }

    @Test fun hihnanTilaResurssiksi() {
        assertEquals(R.string.flights_belt_not_started, FlightDisplay.beltStatusRes("not-started"))
        assertEquals(R.string.flights_belt_started, FlightDisplay.beltStatusRes("started"))
        assertEquals(R.string.flights_belt_completed, FlightDisplay.beltStatusRes("completed"))
        assertNull(FlightDisplay.beltStatusRes("unknown"))
        assertNull(FlightDisplay.beltStatusRes(null))
    }

    @Test fun matkatavaratHihnaTilaJaAlue() {
        val base = f("Laskeutunut", 0, null)
        assertNull(FlightDisplay.baggage(base))
        assertEquals(
            FlightDisplay.Baggage("6", R.string.flights_belt_not_started, "2A"),
            FlightDisplay.baggage(base.copy(belt = "6", beltStatus = "not-started", baggageArea = "2A")),
        )
        assertEquals(FlightDisplay.Baggage("6", null, null), FlightDisplay.baggage(base.copy(belt = "6")))
        assertEquals(FlightDisplay.Baggage(null, null, "2A"), FlightDisplay.baggage(base.copy(baggageArea = "2A")))
    }
}
