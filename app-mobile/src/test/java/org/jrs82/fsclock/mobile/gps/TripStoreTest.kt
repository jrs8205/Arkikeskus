package org.jrs82.fsclock.mobile.gps

import android.content.SharedPreferences
import org.jrs82.fsclock.mobile.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Matkan tallennus: tila säilyy prosessin kuoleman yli, eikä rikkinäinen tiedosto kaada sovellusta. */
class TripStoreTest {

    private val valid = TripSnapshot(TripPhase.PAUSED, 12_400.5, 2_520_000, 2_265_000, 23.3f)

    @Test fun edestakaisin() {
        val store = TripStore(FakeSharedPreferences())
        // Paikallaanoloaika ja muistutuksen tila ovat ajonaikaisia: ne eivät saa palata tallennuksesta.
        store.save(valid.copy(idleMs = 99, reminded = true))
        assertEquals(valid, store.load())
    }

    @Test fun jokainenTilaSailyy() {
        for (phase in TripPhase.values()) {
            val store = TripStore(FakeSharedPreferences())
            store.save(valid.copy(phase = phase))
            val expected = if (phase == TripPhase.NONE) TripSnapshot() else valid.copy(phase = phase)
            assertEquals(expected, store.load())
        }
    }

    @Test fun tyhjaAntaaEiMatkaa() {
        assertEquals(TripSnapshot(), TripStore(FakeSharedPreferences()).load())
    }

    @Test fun rikkinainenAntaaEiMatkaa() {
        val nan = java.lang.Double.doubleToRawLongBits(Double.NaN)
        val negative = java.lang.Double.doubleToRawLongBits(-1.0)
        val infinite = java.lang.Double.doubleToRawLongBits(Double.POSITIVE_INFINITY)
        val breaks: List<(SharedPreferences.Editor) -> Unit> = listOf(
            { e -> e.putString(TripStore.KEY_PHASE, "BOGUS") },
            { e -> e.putLong(TripStore.KEY_DISTANCE_BITS, nan) },
            { e -> e.putLong(TripStore.KEY_DISTANCE_BITS, negative) },
            { e -> e.putLong(TripStore.KEY_DISTANCE_BITS, infinite) },
            { e -> e.putLong(TripStore.KEY_TOTAL_MS, -1L) },
            { e -> e.putLong(TripStore.KEY_MOVING_MS, -1L) },
            { e -> e.putLong(TripStore.KEY_MOVING_MS, 2_520_001L) },
            { e -> e.putFloat(TripStore.KEY_MAX_SPEED_MPS, Float.NaN) },
            { e -> e.putFloat(TripStore.KEY_MAX_SPEED_MPS, -1f) },
        )
        breaks.forEachIndexed { i, breakIt ->
            val prefs = FakeSharedPreferences()
            val store = TripStore(prefs)
            store.save(valid)
            val editor = prefs.edit()
            breakIt(editor)
            editor.apply()
            assertEquals("tapaus $i", TripSnapshot(), store.load())
        }
    }

    @Test fun vaaranTyyppinenArvoAntaaEiMatkaa() {
        // Oikea SharedPreferences heittää ClassCastExceptionin, kun avaimen alla on eri tyyppi.
        val backing = FakeSharedPreferences()
        TripStore(backing).save(valid)
        val throwing = object : SharedPreferences by backing {
            override fun getLong(key: String?, defValue: Long): Long = throw ClassCastException("String")
        }
        assertEquals(TripSnapshot(), TripStore(throwing).load())
    }

    @Test fun varmuuskopiosaannotRajaavatTiedoston() {
        // Tiedoston nimi tulee koodista: jos se vaihtuu, säännöt eivät saa jäädä vanhaan nimeen.
        val needle = "path=\"${TripStore.FILE}.xml\""
        val backup = File("src/main/res/xml/backup_rules.xml").readText()
        val extraction = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertEquals(1, backup.split(needle).size - 1)
        // Sekä pilvivarmuuskopio että laitteelta laitteelle -siirto.
        assertEquals(2, extraction.split(needle).size - 1)
    }

    @Test fun osittainenTallenneAntaaEiMatkaa() {
        val required = listOf(
            TripStore.KEY_DISTANCE_BITS, TripStore.KEY_TOTAL_MS, TripStore.KEY_MOVING_MS, TripStore.KEY_MAX_SPEED_MPS,
        )
        for (missing in required) {
            val prefs = FakeSharedPreferences()
            TripStore(prefs).save(valid)
            prefs.edit().remove(missing).apply()
            // Puuttuvaa lukua ei saa täydentää nollalla ja tarjota matkaa jatkettavaksi.
            assertEquals("puuttuu $missing", TripSnapshot(), TripStore(prefs).load())
        }
    }

    @Test fun tallennusleimaSailyyJaOnValinnainen() {
        val prefs = FakeSharedPreferences()
        val store = TripStore(prefs)
        assertNull(store.stamp())
        val stamp = SaveStamp(1_700_000_000_000L, 5_400_000L, "prosessi-1")
        store.save(valid, stamp)
        assertEquals(stamp, store.stamp())
        // Tallennus ilman leimaa poistaa vanhan leiman: se ei saa jäädä kuvaamaan uudempaa tallennetta.
        store.save(valid)
        assertNull(store.stamp())
        assertEquals(valid, store.load())
    }

    @Test fun vajaaTaiKelvotonTallennusleimaOnTyhja() {
        val stamp = SaveStamp(1_700_000_000_000L, 5_400_000L, "prosessi-1")
        for (missing in listOf(TripStore.KEY_SAVED_AT_WALL_MS, TripStore.KEY_SAVED_AT_ELAPSED_MS, TripStore.KEY_SESSION)) {
            val prefs = FakeSharedPreferences()
            TripStore(prefs).save(valid, stamp)
            prefs.edit().remove(missing).apply()
            assertNull("puuttuu $missing", TripStore(prefs).stamp())
            assertEquals(valid, TripStore(prefs).load())
        }
        val backing = FakeSharedPreferences()
        TripStore(backing).save(valid, stamp)
        val throwing = object : SharedPreferences by backing {
            override fun getLong(key: String?, defValue: Long): Long =
                if (key == TripStore.KEY_SAVED_AT_ELAPSED_MS) throw ClassCastException("String")
                else backing.getLong(key, defValue)
        }
        assertNull(TripStore(throwing).stamp())
    }
}
