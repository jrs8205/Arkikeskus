package org.jrs82.fsclock.mobile.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** GPS-lukeman julkaisu on sidottu rekisteröintikertaan: päättyneen kerran kuuntelija ei saa muuttaa lukemaa. */
class GpsReadingSlotTest {

    private fun fix(rt: Long) = GpsFix(rt, 60.0, 25.0, 10f, 5f, null)

    @Test fun voimassaOlevaRekisterointiJulkaisee() {
        val slot = GpsReadingSlot()
        val token = slot.open(GpsReading())
        assertTrue(slot.isCurrent(token))
        assertTrue(slot.publish(token) { it.withFix(fix(1_000)) })
        assertEquals(1_000L, slot.reading.value.fix?.rtMs)
    }

    /**
     * Vanhan rekisteröinnin kuuntelija on jo lukenut paikannuksen olevan päällä, mutta ehtii julkaista vasta,
     * kun lähde on vapautettu ja rekisteröity uudelleen paikannus pois päältä: päivitys hylätään, eikä vanha
     * nopeus palaa näkyviin.
     */
    @Test fun paattyneenRekisteroinninPaivitysHylataan() {
        val slot = GpsReadingSlot()
        val old = slot.open(GpsReading())
        slot.close()
        val new = slot.open(GpsReading(providerEnabled = false))
        assertFalse(slot.publish(old) { it.withProvider(true).withFix(fix(1_000)) })
        assertEquals(GpsReading(providerEnabled = false), slot.reading.value)
        assertFalse(slot.isCurrent(old))
        assertTrue(slot.isCurrent(new))
        // Uusi rekisteröinti julkaisee normaalisti.
        assertTrue(slot.publish(new) { it.withProvider(true).withFix(fix(2_000)) })
        assertEquals(2_000L, slot.reading.value.fix?.rtMs)
    }

    @Test fun vapautuksenJalkeenLukemaOnTyhjaEikaVanhaJulkaisuMuutaSita() {
        val slot = GpsReadingSlot()
        val token = slot.open(GpsReading())
        slot.publish(token) { it.withFix(fix(1_000)) }
        slot.close()
        assertEquals(GpsReading(), slot.reading.value)
        assertFalse(slot.isCurrent(token))
        assertFalse(slot.publish(token) { it.withFix(fix(2_000)) })
        assertEquals(GpsReading(), slot.reading.value)
    }

    /** Rekisteröinti epäonnistui, koska laitteessa ei ole GPS-paikanninta: syy jää lukemaan. */
    @Test fun epaonnistuneenRekisteroinninSyyJaaLukemaan() {
        val slot = GpsReadingSlot()
        slot.close(GpsReading(providerPresent = false))
        assertEquals(GpsStatus.UNAVAILABLE, GpsReadout.status(slot.reading.value, 0))
        // Vapautus palauttaa tyhjän lukeman.
        slot.close()
        assertEquals(GpsReading(), slot.reading.value)
    }

    /** Mittaus toimitetaan laskennalle vain voimassa olevan rekisteröintikerran tunnisteella. */
    @Test fun paattyneenRekisteroinninMittaustaEiToimiteta() {
        val slot = GpsReadingSlot()
        val old = slot.open(GpsReading())
        var delivered = 0
        assertTrue(slot.deliver(old) { delivered++ })
        slot.close()
        assertFalse(slot.deliver(old) { delivered++ })
        val new = slot.open(GpsReading())
        assertFalse(slot.deliver(old) { delivered++ })
        assertTrue(slot.deliver(new) { delivered++ })
        assertEquals(2, delivered)
    }

    /**
     * Tunnisteen tarkistus ja toimitus ovat yksi operaatio: vapautus odottaa kesken olevan toimituksen loppuun,
     * eikä sen jälkeen ala uutta. Vapautuksen palattua päättyneen kerran mittaus ei siis voi enää päätyä
     * laskentaan.
     */
    @Test fun vapautusOdottaaKeskenOlevanToimituksenEikaSenJalkeenToimiteta() {
        val slot = GpsReadingSlot()
        val token = slot.open(GpsReading())
        val inside = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val events = Collections.synchronizedList(ArrayList<String>())
        val listener = Thread {
            slot.deliver(token) {
                inside.countDown()
                proceed.await()
                events.add("toimitus valmis")
            }
        }
        listener.start()
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        val releaser = Thread {
            slot.close()
            events.add("vapautus valmis")
            closed.countDown()
        }
        releaser.start()
        // Vapautus ei saa valmistua, kun toimitus on kesken.
        assertFalse(closed.await(300, TimeUnit.MILLISECONDS))
        proceed.countDown()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        listener.join(5_000)
        assertEquals(listOf("toimitus valmis", "vapautus valmis"), events.toList())
        assertFalse(slot.deliver(token) { events.add("myöhästynyt toimitus") })
        assertEquals(2, events.size)
    }
}
