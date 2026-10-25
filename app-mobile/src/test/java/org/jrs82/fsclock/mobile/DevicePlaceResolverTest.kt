package org.jrs82.fsclock.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Laitteen sijainnin paikannimen ketju MML → Androidin Geocoder. Tausta 23.10.2026:
 *  MML sulki avoimet rajapintansa, ja sääpaikka jäi päiviksi viimeisimpään onnistuneeseen paikkaan. */
class DevicePlaceResolverTest {

    private val mmlPlace = DevicePlaceResolver.Resolved("Vantaa", "Vantaa, Martinlaakso", DevicePlaceResolver.Source.MML)
    private val geoPlace = DevicePlaceResolver.Resolved("Espoo", "Espoo, Tapiola", DevicePlaceResolver.Source.GEOCODER)
    private val martinlaakso = PostalAreas.Area("Martinlaakso", listOf("Vantaa"))
    private var now = 1_792_000_000_000L

    @Before fun resetBreaker() {
        DevicePlaceResolver.resetMmlBreaker()
    }

    private fun resolve(
        mml: suspend () -> DevicePlaceResolver.Resolved?,
        geocoder: suspend () -> DevicePlaceResolver.Resolved? = { geoPlace },
    ) = runBlocking { DevicePlaceResolver.resolveWith(mml, geocoder) { now } }

    @Test fun mmlWinsWhenItAnswers() {
        assertEquals(mmlPlace, resolve(mml = { mmlPlace }))
    }

    @Test fun geocoderUsedWhenMmlThrows() {
        assertEquals(geoPlace, resolve(mml = { throw java.net.SocketTimeoutException("connect timed out") }))
    }

    @Test fun geocoderUsedWhenMmlFindsNothing() {
        assertEquals(geoPlace, resolve(mml = { null }))
    }

    @Test fun nullWhenBothServicesFindNothing() {
        // Kutsuja pitää silloin edellisen paikan (ei arvata nimeä).
        assertNull(resolve(mml = { null }, geocoder = { null }))
    }

    @Test fun geocoderExceptionGivesNull() {
        assertNull(resolve(mml = { throw java.net.ConnectException("refused") }, geocoder = { throw java.io.IOException("grpc failed") }))
    }

    @Test fun mmlSkippedForTenMinutesAfterNetworkFailure() {
        var mmlCalls = 0
        val failing: suspend () -> DevicePlaceResolver.Resolved? = {
            mmlCalls++
            throw java.net.ConnectException("refused")
        }
        resolve(mml = failing)
        now += 9L * 60_000L
        assertEquals(geoPlace, resolve(mml = failing))
        assertEquals(1, mmlCalls)
        now += 2L * 60_000L
        resolve(mml = failing)
        assertEquals(2, mmlCalls)
    }

    @Test fun emptyMmlAnswerDoesNotTripBreaker() {
        // Tyhjä vastaus (esim. ulkomailla) ei ole palvelukatko: MML kysytään taas heti seuraavalla kerralla.
        var mmlCalls = 0
        resolve(mml = { mmlCalls++; null })
        resolve(mml = { mmlCalls++; null })
        assertEquals(2, mmlCalls)
    }

    @Test fun successfulMmlCallClearsBreakerAfterExpiry() {
        resolve(mml = { throw java.net.ConnectException("refused") })
        now += 11L * 60_000L
        assertEquals(mmlPlace, resolve(mml = { mmlPlace }))
        now += 60_000L
        assertEquals(mmlPlace, resolve(mml = { mmlPlace }))
    }

    @Test fun addressWithLocalityAndDistrict() {
        val r = DevicePlaceResolver.fromAddressFields("FI", "Vantaa", "Martinlaakso", "Vantaa")
        assertEquals("Vantaa", r?.dataPlace)
        assertEquals("Vantaa, Martinlaakso", r?.displayPlace)
        assertEquals(DevicePlaceResolver.Source.GEOCODER, r?.source)
    }

    @Test fun addressFallsBackToSubAdminArea() {
        val r = DevicePlaceResolver.fromAddressFields("FI", null, "Leppävaara", "Espoo")
        assertEquals("Espoo", r?.dataPlace)
        assertEquals("Espoo, Leppävaara", r?.displayPlace)
    }

    @Test fun districtSameAsCityIsDropped() {
        assertEquals("Espoo", DevicePlaceResolver.fromAddressFields("FI", "Espoo", "espoo", null)?.displayPlace)
    }

    @Test fun postalCodeIsNotADistrict() {
        assertEquals("Espoo", DevicePlaceResolver.fromAddressFields("FI", "Espoo", "02600", null)?.displayPlace)
    }

    @Test fun blankFieldsAreTrimmedAndIgnored() {
        assertEquals("Kerava", DevicePlaceResolver.fromAddressFields("FI", "  Kerava ", "  ", null)?.displayPlace)
    }

    @Test fun addressWithoutCityIsNull() {
        assertNull(DevicePlaceResolver.fromAddressFields("FI", null, "Martinlaakso", " "))
    }

    @Test fun postalAreaGivesDistrictWhenGeocoderHasNone() {
        // Pixel 8a 25.10.2026: Google palautti "Raappavuorenreuna 2b, 01620 Vantaa" ilman subLocalitya.
        val r = DevicePlaceResolver.fromAddressFields("FI", "Vantaa", null, "Helsingin seutukunta", martinlaakso)
        assertEquals("Vantaa", r?.dataPlace)
        assertEquals("Vantaa, Martinlaakso", r?.displayPlace)
    }

    @Test fun geocoderSubLocalityWinsOverPostalArea() {
        assertEquals(
            "Vantaa, Raappavuori",
            DevicePlaceResolver.fromAddressFields("FI", "Vantaa", "Raappavuori", null, martinlaakso)?.displayPlace,
        )
    }

    @Test fun postalAreaOfAnotherMunicipalityIsIgnored() {
        assertEquals("Espoo", DevicePlaceResolver.fromAddressFields("FI", "Espoo", null, null, martinlaakso)?.displayPlace)
    }

    @Test fun postalAreaDoesNotRescueForeignAddress() {
        assertNull(DevicePlaceResolver.fromAddressFields("SE", "Vantaa", null, null, martinlaakso))
    }

    // Ulkomaan paikka jäisi FMI:ltä ilman säätä (havaintohaku nimellä: Tallinna → HTTP 400; ennuste koordinaateilla:
    // New York → HTTP 400) ja widget näyttäisi uuden nimen vanhalla lämpötilalla → hylätään, edellinen paikka säilyy.
    @Test fun foreignAddressesAreRejected() {
        assertNull(DevicePlaceResolver.fromAddressFields("SE", "Tukholma", "Södermalm", "Tukholman lääni"))
        assertNull(DevicePlaceResolver.fromAddressFields("EE", "Tallinna", "Kesklinn", "Harjumaa"))
        assertNull(DevicePlaceResolver.fromAddressFields("US", "New York", "Manhattan", "New York County"))
    }

    @Test fun missingCountryCodeIsRejected() {
        assertNull(DevicePlaceResolver.fromAddressFields(null, "Vantaa", "Martinlaakso", "Vantaa"))
        assertNull(DevicePlaceResolver.fromAddressFields(" ", "Vantaa", "Martinlaakso", "Vantaa"))
    }

    @Test fun alandIsAcceptedWithItsOwnCountryCode() {
        // Ahvenanmaan ISO 3166 -koodi on AX, vaikka se kuuluu Suomeen ja FMI:n alueeseen.
        assertEquals("Maarianhamina", DevicePlaceResolver.fromAddressFields("AX", "Maarianhamina", null, null)?.dataPlace)
    }

    @Test fun countryCodeIsCaseInsensitive() {
        assertEquals("Vantaa", DevicePlaceResolver.fromAddressFields(" fi ", "Vantaa", null, null)?.dataPlace)
    }

    @Test fun mmlRetriedAtTenMonotonicMinutes() {
        // Tuotannossa kello on SystemClock.elapsedRealtime: seinäkellon siirto taaksepäin ei venytä 10 minuutin ohitusta.
        var mmlCalls = 0
        val failing: suspend () -> DevicePlaceResolver.Resolved? = {
            mmlCalls++
            throw java.net.ConnectException("refused")
        }
        val monotonicStart = 5_000L
        runBlocking { DevicePlaceResolver.resolveWith(failing, { geoPlace }) { monotonicStart } }
        runBlocking { DevicePlaceResolver.resolveWith(failing, { geoPlace }) { monotonicStart + 10L * 60_000L } }
        assertEquals(2, mmlCalls)
    }
}
