package org.jrs82.fsclock.mobile.gps

import android.content.Context
import android.content.SharedPreferences
import org.jrs82.fsclock.db.TripEntity
import org.json.JSONObject
import java.util.UUID

/**
 * Matkan tilan tallennus omaan asetustiedostoonsa, jotta matka säilyy sovelluksen sulkemisen ja prosessin
 * kuoleman yli. Tiedosto on laitekohtainen: se on rajattu pois Auto Backupista ja laitesiirrosta
 * (backup_rules.xml, data_extraction_rules.xml), eikä käsin tehtävä varmuuskopio vie sitä, koska se
 * kopioi vain oletusasetukset. Kesken oleva matka ei kuulu toiselle laitteelle.
 *
 * Tallennetaan tila, matka, kokonais- ja liikeaika, huippunopeus ja tallennusleima (seinäkello, laitteen
 * käynnistyksestä kulunut aika ja tallentaneen prosessin tunniste: palautuksen aikaperuste, ks.
 * [TripCalculator.runningCreditMs]). Paikallaanoloaika ja muistutuksen tila ovat ajonaikaisia eivätkä tallennu.
 */
class TripStore(private val prefs: SharedPreferences, private val newId: () -> String = { UUID.randomUUID().toString() }) {

    // Päivityksessä alkuperäinen tallenne säilytetään koskemattomana ensimmäiseen historiakirjoitukseen.
    private fun prefix() = if (prefs.contains(CURRENT + KEY_PHASE)) CURRENT else ""

    /** Tallennettu matka; puuttuva, osittainen tai kelvoton tallenne on sama kuin ei matkaa. */
    fun load(): TripSnapshot {
        try {
            val prefix = prefix()
            val phase = TripPhase.values().firstOrNull { it.name == prefs.getString(prefix + KEY_PHASE, null) }
            if (phase == null || phase == TripPhase.NONE) return TripSnapshot()
            // Jokainen luku on oltava olemassa: puuttuvaa ei täydennetä nollalla ja tarjota jatkettavaksi.
            if (REQUIRED_KEYS.any { !prefs.contains(prefix + it) }) return TripSnapshot()
            val distance = java.lang.Double.longBitsToDouble(prefs.getLong(prefix + KEY_DISTANCE_BITS, 0L))
            val total = prefs.getLong(prefix + KEY_TOTAL_MS, 0L)
            val moving = prefs.getLong(prefix + KEY_MOVING_MS, 0L)
            val max = prefs.getFloat(prefix + KEY_MAX_SPEED_MPS, 0f)
            val valid = !distance.isNaN() && !distance.isInfinite() && distance >= 0.0 &&
                total >= 0L && moving >= 0L && moving <= total &&
                !max.isNaN() && !max.isInfinite() && max >= 0f
            return if (valid) TripSnapshot(phase, distance, total, moving, max) else TripSnapshot()
        } catch (e: ClassCastException) {
            // Avaimen alla on eri tyyppi kuin odotettiin (esim. myöhemmän version tallenne).
            return TripSnapshot()
        }
    }

    /**
     * Viimeisimmän tallennuksen leima palautuksen aikahyvitystä varten; null, jos sitä ei ole tai se on vajaa
     * (aiemman rakennuksen tallenne).
     */
    fun stamp(): SaveStamp? = try {
        val prefix = prefix()
        val session = prefs.getString(prefix + KEY_SESSION, null)
        if (session.isNullOrEmpty() || !prefs.contains(prefix + KEY_SAVED_AT_WALL_MS) || !prefs.contains(prefix + KEY_SAVED_AT_ELAPSED_MS)) {
            null
        } else {
            val wall = prefs.getLong(prefix + KEY_SAVED_AT_WALL_MS, 0L)
            val elapsed = prefs.getLong(prefix + KEY_SAVED_AT_ELAPSED_MS, -1L)
            if (wall > 0L && elapsed >= 0L) SaveStamp(wall, elapsed, session) else null
        }
    } catch (e: ClassCastException) {
        null
    }

    /** Tallentaa tilan. Ilman leimaa vanha leima poistetaan, jottei se jää kuvaamaan uudempaa tallennetta. */
    fun save(s: TripSnapshot, stamp: SaveStamp? = null) {
        val editor = prefs.edit()
        stage(editor, prefix(), s, stamp)
        editor.apply()
    }

    private fun stage(editor: SharedPreferences.Editor, prefix: String, s: TripSnapshot, stamp: SaveStamp?) {
        editor.putString(prefix + KEY_PHASE, s.phase.name)
            .putLong(prefix + KEY_DISTANCE_BITS, java.lang.Double.doubleToRawLongBits(s.distanceM))
            .putLong(prefix + KEY_TOTAL_MS, s.totalMs)
            .putLong(prefix + KEY_MOVING_MS, s.movingMs)
            .putFloat(prefix + KEY_MAX_SPEED_MPS, s.maxSpeedMps)
        if (stamp != null) {
            editor.putLong(prefix + KEY_SAVED_AT_WALL_MS, stamp.wallMs)
                .putLong(prefix + KEY_SAVED_AT_ELAPSED_MS, stamp.elapsedMs)
                .putString(prefix + KEY_SESSION, stamp.session)
        } else {
            editor.remove(prefix + KEY_SAVED_AT_WALL_MS).remove(prefix + KEY_SAVED_AT_ELAPSED_MS).remove(prefix + KEY_SESSION)
        }
    }

    /** Kutsutaan kerran käynnistyksessä, ennen uusien komentojen hyväksymistä. */
    @Synchronized
    internal fun recover(saved: TripSnapshot, restored: TripSnapshot, now: SaveStamp, timing: CarTiming = carTiming()) {
        if (saved.phase == TripPhase.NONE) return
        val editor = prefs.edit()
        stageTiming(editor, timing)
        val legacy = !prefs.contains(ID)
        // Historia tarvitsee vain seinäkellon; laskennan aikahyvitys vaatii yhä kokonaisen leiman.
        val lastWall = (prefs.all[prefix() + KEY_SAVED_AT_WALL_MS] as? Long)?.takeIf { it > 0 } ?: now.wallMs
        val id = prefs.getString(ID, null) ?: newId()
        val missingStart = !prefs.contains(START)
        val start = if (missingStart) estimatedStartMs(lastWall, saved) else prefs.getLong(START, lastWall)
        if (legacy) {
            editor.putString(ID, id).putString(LEGACY_ID, id).putBoolean(RECORDED, false)
        }
        if (missingStart) editor.putLong(START, start).putBoolean(ESTIMATED, true)
        if (legacy || saved != restored) {
            val currentStamp = if (saved == restored) stamp() else now
            stage(editor, if (legacy) CURRENT else prefix(), restored, currentStamp)
        }
        if (needsHistory(saved.phase, prefs.getBoolean(RECORDED, false)) && !prefs.contains(PENDING + id)) {
            stageFinish(editor, id, start, lastWall, missingStart || prefs.getBoolean(ESTIMATED, false), saved)
        }
        editor.apply()
    }

    internal fun carTiming() = CarTiming(
        startedAuto = prefs.getBoolean("car_started", false),
        pausedWall = (prefs.all["pause_wall"] as? Long),
        userPaused = prefs.getBoolean("pause_user", true),
        disconnectWall = prefs.all["car_lost_wall"] as? Long,
        disconnectElapsed = prefs.getLong("car_lost_elapsed", 0),
        boot = prefs.getInt("car_lost_boot", -1),
    )

    private fun stageTiming(editor: SharedPreferences.Editor, timing: CarTiming) {
        editor.putBoolean("car_started", timing.startedAuto).putBoolean("pause_user", timing.userPaused)
        if (timing.pausedWall == null) editor.remove("pause_wall") else editor.putLong("pause_wall", timing.pausedWall)
        if (timing.disconnectWall == null) editor.remove("car_lost_wall")
        else editor.putLong("car_lost_wall", timing.disconnectWall)
            .putLong("car_lost_elapsed", timing.disconnectElapsed).putInt("car_lost_boot", timing.boot)
    }

    /** Yksi asetusmuutos sisältää sekä yhteenvedon että mahdollisen kirjoittamattoman lopetuksen. */
    @Synchronized
    internal fun transition(s: TripSnapshot, stamp: SaveStamp, newTrip: Boolean = false,
                            timing: CarTiming = if (newTrip) CarTiming() else carTiming(), endedAuto: Boolean = false) {
        val editor = prefs.edit()
        stage(editor, prefix(), s, stamp)
        stageTiming(editor, timing)
        when {
            newTrip -> editor.putString(ID, newId()).putLong(START, stamp.wallMs).putBoolean(ESTIMATED, false)
            s.phase == TripPhase.NONE -> editor.remove(ID).remove(START).remove(ESTIMATED)
            s.phase == TripPhase.FINISHED -> stageFinish(editor, checkNotNull(prefs.getString(ID, null)),
                prefs.getLong(START, stamp.wallMs), stamp.wallMs, prefs.getBoolean(ESTIMATED, false), s, timing.startedAuto, endedAuto)
        }
        editor.putBoolean(RECORDED, false).apply()
    }

    private fun stageFinish(editor: SharedPreferences.Editor, id: String, start: Long, end: Long,
                            estimated: Boolean, s: TripSnapshot, startedAuto: Boolean = false, endedAuto: Boolean = false) {
        val row = TripEntity(id, start, end, estimated, s.distanceM, s.totalMs, s.movingMs, s.maxSpeedMps,
            startedAuto, endedAuto)
        editor.putString(PENDING + id, row.toJson().toString())
    }

    internal fun pendingTrips(): List<TripEntity> = prefs.all.entries.filter { it.key.startsWith(PENDING) }
        .map { checkNotNull(JSONObject(it.value as String).tripOrNull()) }

    /** Kuittaus ei saa pyyhkiä saman matkan uudempaa lopetusta eikä vaihtaa toisen matkan tilaa. */
    @Synchronized
    internal fun acknowledge(row: TripEntity) {
        val key = PENDING + row.id
        val pending = prefs.getString(key, null)?.let { JSONObject(it).tripOrNull() } ?: return
        if (!row.sameFinish(pending)) return
        val editor = prefs.edit().remove(key)
        if (prefs.getString(ID, null) == row.id && load().phase == TripPhase.FINISHED) {
            editor.putBoolean(RECORDED, true)
        }
        if (prefs.getString(LEGACY_ID, null) == row.id) {
            (REQUIRED_KEYS + listOf(KEY_PHASE, KEY_SESSION, KEY_SAVED_AT_WALL_MS, KEY_SAVED_AT_ELAPSED_MS))
                .forEach(editor::remove)
            editor.remove(LEGACY_ID)
        }
        editor.apply()
    }

    /** Taustasäikeen levyraja. commit odottaa myös edeltävät apply-kirjoitukset. */
    internal fun flush() = prefs.edit().commit()

    companion object {
        /** Asetustiedoston nimi ilman päätettä; sama nimi on varmuuskopiosäännöissä. */
        const val FILE = "gps_trip_prefs"
        internal const val KEY_PHASE = "phase"
        internal const val KEY_DISTANCE_BITS = "distance_bits"
        internal const val KEY_TOTAL_MS = "total_ms"
        internal const val KEY_MOVING_MS = "moving_ms"
        internal const val KEY_MAX_SPEED_MPS = "max_speed_mps"
        internal const val KEY_SAVED_AT_WALL_MS = "saved_at_wall_ms"
        internal const val KEY_SAVED_AT_ELAPSED_MS = "saved_at_elapsed_ms"
        internal const val KEY_SESSION = "session"
        private const val ID = "trip_id"
        private const val START = "started_at_ms"
        private const val ESTIMATED = "estimated_start"
        private const val RECORDED = "history_recorded"
        private const val LEGACY_ID = "legacy_trip_id"
        private const val CURRENT = "current_"
        private const val PENDING = "pending_trip."
        private val REQUIRED_KEYS = listOf(KEY_DISTANCE_BITS, KEY_TOTAL_MS, KEY_MOVING_MS, KEY_MAX_SPEED_MPS)

        fun of(context: Context): TripStore =
            TripStore(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
