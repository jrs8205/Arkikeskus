package org.jrs82.fsclock.mobile

import android.content.Context
import android.location.Location
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jrs82.fsclock.db.FsClockDb
import org.jrs82.fsclock.db.WorkoutDao
import org.jrs82.fsclock.db.WorkoutEntity
import org.jrs82.fsclock.db.WorkoutPointEntity
import org.jrs82.fsclock.db.WorkoutSplitEntity
import java.util.concurrent.Executors

/**
 * Lenkkiseurannan ydin: tilakone (IDLE/ACTIVE/PAUSED) + GPS-suodatus + kertymälaskenta +
 * puskuroitu Room-persistointi + StateFlow UI:lle. WorkoutTrackingService syöttää sijainnit
 * ja askeleet (palvelun HandlerThreadistä); kaikki mutaatiot synkronoidaan tähän olioon.
 *
 * Kesto (movingTimeMs) kertyy elapsedRealtime-ankkurista vain ACTIVE-tilassa → pause ja
 * kellonsiirrot eivät vääristä. Matka on haversine-kertymä suodatetuista pisteistä.
 */
object WorkoutTracker {

    enum class Phase { IDLE, ACTIVE, PAUSED }

    data class Split(val index: Int, val durationMs: Long, val endLat: Double, val endLon: Double)

    data class PointUi(val lat: Double, val lon: Double, val segment: Int)

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val workoutId: Long = 0L,
        val type: Int = WorkoutEntity.TYPE_WALK,
        val startedAtMs: Long = 0L,
        val movingTimeMs: Long = 0L,
        val movingAnchorRt: Long = 0L,   // elapsedRealtime, josta UI tickaa lisää ACTIVE-tilassa
        val distanceM: Double = 0.0,
        val speedKmh: Float = 0f,
        val maxSpeedKmh: Float = 0f,
        val steps: Long = 0L,
        val elevGainM: Double = 0.0,
        val elevLossM: Double = 0.0,
        val splits: List<Split> = emptyList(),
        val points: List<PointUi> = emptyList(),
        val lastFixMs: Long = 0L,
        val currentLat: Double = Double.NaN,
        val currentLon: Double = Double.NaN,
        /** Viimeksi valmistuneen lenkin id yhteenvetonäkymää varten (0 = ei mitään). */
        val lastFinishedId: Long = 0L,
        val autoStopped: Boolean = false,
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> get() = mutableState

    /** Palvelu asettaa: täyden kilometrin täyttyessä (värinä + notifikaatio). Parametrit: valmistunut
     *  [Split] (index + sen kilometrin kesto) + kokonaisliikeaika ms juuri tällä hetkellä — välitetään
     *  suoraan, koska [state] päivitetään vasta onLocationin lopussa (callback näkisi vanhan arvon). */
    @Volatile var onSplit: ((Split, Long) -> Unit)? = null

    private val io = Executors.newSingleThreadExecutor()
    private var dao: WorkoutDao? = null
    private var entity: WorkoutEntity? = null

    // Suodatus-/kertymätila (vain palvelusäikeestä)
    private var lastAccepted: Location? = null
    private var lastAcceptedWallMs = 0L
    private var segment = 0
    private var pointBuffer = ArrayList<WorkoutPointEntity>()
    private var lastFlushRt = 0L
    private var movingAnchorRt = 0L
    private var lastMovementWallMs = 0L
    private var anchorLat = Double.NaN
    private var anchorLon = Double.NaN
    private val altWindow = ArrayDeque<Double>()
    private var smoothedAlt = Double.NaN
    private var elevRefAlt = Double.NaN
    private var derivedSpeedKmh = 0f   // EMA pisteväleistä, kun GPS ei anna speed-kenttää
    // Askelkirjanpito (lenkin oma baseline, ks. WorkoutEntity)
    private var stepSeen = false

    private const val ACCURACY_MAX_M = 25f
    private const val GAP_NEW_SEGMENT_MS = 30_000L
    private const val FLUSH_POINTS = 10
    private const val FLUSH_INTERVAL_MS = 15_000L
    private const val AUTO_STOP_AFTER_MS = 10L * 60_000L
    private const val PAUSE_MAX_MS = 8L * 60L * 60_000L
    private const val MOVEMENT_ANCHOR_M = 25.0
    private const val ELEV_HYSTERESIS_M = 2.0

    private fun dao(context: Context): WorkoutDao {
        val d = dao ?: FsClockDb.get(context.applicationContext).workoutDao()
        dao = d
        return d
    }

    private fun minMoveM(type: Int) = if (type == WorkoutEntity.TYPE_BIKE) 5.0 else 3.0
    private fun maxDerivedMps(type: Int) = if (type == WorkoutEntity.TYPE_BIKE) 25.0 else 4.0

    @Synchronized
    fun start(context: Context, type: Int) {
        if (mutableState.value.phase != Phase.IDLE) return
        val d = dao(context)
        val w = WorkoutEntity()
        w.type = type
        w.status = WorkoutEntity.STATUS_ACTIVE
        w.startedAtMs = System.currentTimeMillis()
        w.updatedAtMs = w.startedAtMs
        w.id = io.submit<Long> { d.insertWorkout(w) }.get()
        entity = w
        resetRuntime()
        movingAnchorRt = SystemClock.elapsedRealtime()
        lastMovementWallMs = System.currentTimeMillis()
        mutableState.value = UiState(
            phase = Phase.ACTIVE, workoutId = w.id, type = type,
            startedAtMs = w.startedAtMs, movingAnchorRt = movingAnchorRt,
        )
    }

    /** Palauttaa true jos keskeneräinen lenkki löytyi ja palautettiin (ACTIVE/PAUSED-tilaan). */
    @Synchronized
    fun recover(context: Context): Boolean {
        if (mutableState.value.phase != Phase.IDLE) return true
        val d = dao(context)
        val w = io.submit<WorkoutEntity?> { d.activeWorkout() }.get() ?: return false
        // Vanhentunut jäänne (esim. force-stop päiviä sitten) → finalisoi takautuvasti.
        val staleMs = System.currentTimeMillis() - w.updatedAtMs
        if (staleMs > AUTO_STOP_AFTER_MS) {
            finalizeEntity(context, w, autoStopped = true, endAtMs = w.updatedAtMs)
            mutableState.value = UiState(lastFinishedId = w.id, autoStopped = true)
            return false
        }
        entity = w
        resetRuntime()
        // Askel-baseline (stepBaselineCum/lastCumSeen) on kannassa yhä pätevä samalla bootilla.
        // Ilman tätä ensimmäinen anturitapahtuma osuisi !stepSeen-haaraan ja nollaisi baselinen
        // commitoimatta kertynyttä väliä (lastCumSeen − stepBaselineCum) → lenkin askeleet
        // putoaisivat. Reboot katkon aikana tunnistuu yhä onStepCountissa
        // (cumulative < lastCumSeen → commit-haara).
        stepSeen = w.lastCumSeen > 0f
        val pts = io.submit<List<WorkoutPointEntity>> { d.pointsFor(w.id) }.get()
        val sps = io.submit<List<WorkoutSplitEntity>> { d.splitsFor(w.id) }.get()
        segment = (pts.lastOrNull()?.segment ?: 0) + 1
        movingAnchorRt = SystemClock.elapsedRealtime()
        lastMovementWallMs = System.currentTimeMillis()
        val phase = if (w.status == WorkoutEntity.STATUS_PAUSED) Phase.PAUSED else Phase.ACTIVE
        mutableState.value = UiState(
            phase = phase, workoutId = w.id, type = w.type, startedAtMs = w.startedAtMs,
            movingTimeMs = w.movingTimeMs,
            movingAnchorRt = if (phase == Phase.ACTIVE) movingAnchorRt else 0L,
            distanceM = w.distanceM, maxSpeedKmh = w.maxSpeedMps * 3.6f, steps = w.steps,
            elevGainM = w.elevGainM, elevLossM = w.elevLossM,
            splits = sps.map { Split(it.splitIndex, it.durationMs, it.endLat, it.endLon) },
            points = pts.map { PointUi(it.lat, it.lon, it.segment) },
        )
        return true
    }

    @Synchronized
    fun pause(context: Context) {
        val w = entity ?: return
        if (mutableState.value.phase != Phase.ACTIVE) return
        accrueMovingTime(w)
        w.status = WorkoutEntity.STATUS_PAUSED
        w.pauseStartedMs = System.currentTimeMillis()
        flush(context, force = true)
        mutableState.value = mutableState.value.copy(
            phase = Phase.PAUSED, movingTimeMs = w.movingTimeMs, movingAnchorRt = 0L, speedKmh = 0f,
        )
    }

    @Synchronized
    fun resume(context: Context) {
        val w = entity ?: return
        if (mutableState.value.phase != Phase.PAUSED) return
        w.status = WorkoutEntity.STATUS_ACTIVE
        w.pauseStartedMs = null
        segment++
        lastAccepted = null   // pausen yli ei vedetä matkaa eikä viivaa
        movingAnchorRt = SystemClock.elapsedRealtime()
        lastMovementWallMs = System.currentTimeMillis()
        flush(context, force = true)
        mutableState.value = mutableState.value.copy(phase = Phase.ACTIVE, movingAnchorRt = movingAnchorRt)
    }

    @Synchronized
    fun stop(context: Context, auto: Boolean, endAtMs: Long = System.currentTimeMillis()) {
        val w = entity ?: return
        if (mutableState.value.phase == Phase.IDLE) return
        val wasActive = mutableState.value.phase == Phase.ACTIVE
        if (wasActive) accrueMovingTime(w)
        if (auto && wasActive) {
            // Automaattipysäytys ACTIVE-tilasta: accrue lisäsi juuri paikallaanolohännän
            // (lastMovement→nyt) kestoon → vähennetään se pois. PAUSED-tilasta (pause-katto)
            // kestoa EI kerry tauolla, joten häntää ei ole — vähennys söisi aitoa liikeaikaa
            // (pahimmillaan koko keston nollaan).
            val idleTail = System.currentTimeMillis() - endAtMs
            if (idleTail > 0) w.movingTimeMs = (w.movingTimeMs - idleTail).coerceAtLeast(0)
        }
        flush(context, force = true)
        finalizeEntity(context, w, auto, endAtMs)
        entity = null
        mutableState.value = UiState(lastFinishedId = w.id, autoStopped = auto)
        resetRuntime()
    }

    private fun finalizeEntity(context: Context, w: WorkoutEntity, autoStopped: Boolean, endAtMs: Long) {
        w.status = WorkoutEntity.STATUS_FINISHED
        w.endedAtMs = endAtMs
        w.autoStopped = autoStopped
        w.pauseStartedMs = null
        w.kcal = WorkoutCalories.estimateKcal(context, w)
        w.updatedAtMs = System.currentTimeMillis()
        val d = dao(context)
        io.execute { d.updateWorkout(w) }
    }

    /** Palvelun määräaikaistick: automaattipysäytys + pause-katto. Palauttaa true jos lenkki päättyi. */
    @Synchronized
    fun tick(context: Context): Boolean {
        val w = entity ?: return false
        val now = System.currentTimeMillis()
        val st = mutableState.value
        if (st.phase == Phase.ACTIVE && now - lastMovementWallMs > AUTO_STOP_AFTER_MS) {
            stop(context, auto = true, endAtMs = lastMovementWallMs)
            return true
        }
        if (st.phase == Phase.PAUSED) {
            val pausedAt = w.pauseStartedMs ?: now
            if (now - pausedAt > PAUSE_MAX_MS) {
                stop(context, auto = true, endAtMs = pausedAt)
                return true
            }
        }
        if (st.phase == Phase.ACTIVE) {
            accrueMovingTime(w)
            flush(context, force = false)
            mutableState.value = mutableState.value.copy(
                movingTimeMs = w.movingTimeMs, movingAnchorRt = movingAnchorRt,
            )
        }
        return false
    }

    @Synchronized
    fun onLocation(context: Context, loc: Location) {
        val st = mutableState.value
        val w = entity ?: return
        val now = System.currentTimeMillis()
        // Nopeusnäyttö päivittyy aina (myös ennen ensimmäistä hyväksyttyä pistettä).
        val speedKmh = displaySpeedKmh(loc)
        var newState = st.copy(
            speedKmh = speedKmh, lastFixMs = now,
            currentLat = loc.latitude, currentLon = loc.longitude,
        )
        if (st.phase != Phase.ACTIVE) {
            mutableState.value = newState
            return
        }

        val accepted = acceptPoint(loc, w.type)
        if (accepted) {
            val prev = lastAccepted
            var addedM = 0.0
            if (prev != null) {
                val gap = now - lastAcceptedWallMs
                if (gap > GAP_NEW_SEGMENT_MS) {
                    segment++   // GPS-katko: uusi segmentti, ei matkaa katkon yli
                } else {
                    addedM = haversineM(prev.latitude, prev.longitude, loc.latitude, loc.longitude)
                    // Johdettu nopeus pisteväleistä, kun fix ei sisällä speed-kenttää
                    if (!loc.hasSpeed() && gap in 1..15_000) {
                        val kmh = (addedM / (gap / 1000.0) * 3.6).toFloat()
                        derivedSpeedKmh = 0.6f * kmh + 0.4f * derivedSpeedKmh
                        newState = newState.copy(
                            speedKmh = if (derivedSpeedKmh < 1f) 0f else derivedSpeedKmh,
                        )
                    }
                }
            }
            val beforeM = w.distanceM
            w.distanceM += addedM
            // Maksiminopeus: GPS:n oma nopeus, tai johdettu (EMA) kun speed-kenttää ei ole —
            // muuten maksimi jäisi nollaan esim. emulaattorissa tai heikolla signaalilla.
            val spd = if (loc.hasSpeed()) loc.speed else derivedSpeedKmh / 3.6f
            if (spd > w.maxSpeedMps) w.maxSpeedMps = spd

            // Liikeankkuri automaattipysäytykselle
            if (anchorLat.isNaN() ||
                haversineM(anchorLat, anchorLon, loc.latitude, loc.longitude) > MOVEMENT_ANCHOR_M
            ) {
                anchorLat = loc.latitude
                anchorLon = loc.longitude
                lastMovementWallMs = now
            }

            updateElevation(w, loc)
            accrueMovingTime(w)

            // Km-splitit (voi ylittyä useampi kerralla vain teoriassa)
            var splits = newState.splits
            var nextKm = splits.size + 1
            while (w.distanceM >= nextKm * 1000.0 && prev != null && addedM > 0) {
                val overshoot = w.distanceM - nextKm * 1000.0
                val frac = ((addedM - overshoot) / addedM).coerceIn(0.0, 1.0)
                val sLat = prev.latitude + (loc.latitude - prev.latitude) * frac
                val sLon = prev.longitude + (loc.longitude - prev.longitude) * frac
                val prevTotal = splits.sumOf { it.durationMs }
                val split = Split(nextKm, w.movingTimeMs - prevTotal, sLat, sLon)
                splits = splits + split
                val se = WorkoutSplitEntity()
                se.workoutId = w.id
                se.splitIndex = split.index
                se.durationMs = split.durationMs
                se.endLat = sLat
                se.endLon = sLon
                val d = dao(context)
                io.execute { d.insertSplit(se) }
                onSplit?.invoke(split, w.movingTimeMs)
                nextKm++
            }

            val pe = WorkoutPointEntity()
            pe.workoutId = w.id
            pe.tMs = now
            pe.lat = loc.latitude
            pe.lon = loc.longitude
            pe.altM = if (loc.hasAltitude()) loc.altitude else null
            pe.speedMps = spd
            pe.accuracyM = if (loc.hasAccuracy()) loc.accuracy else 0f
            pe.segment = segment
            pointBuffer.add(pe)
            lastAccepted = loc
            lastAcceptedWallMs = now

            newState = newState.copy(
                distanceM = w.distanceM,
                maxSpeedKmh = w.maxSpeedMps * 3.6f,
                movingTimeMs = w.movingTimeMs,
                movingAnchorRt = movingAnchorRt,
                elevGainM = w.elevGainM, elevLossM = w.elevLossM,
                splits = splits,
                points = newState.points + PointUi(loc.latitude, loc.longitude, segment),
            )
            if (beforeM == 0.0 && w.distanceM > 0) lastMovementWallMs = now
            flush(context, force = false)
        }
        mutableState.value = newState
    }

    /** Lenkin askeleet: kumulatiivinen TYPE_STEP_COUNTER-arvo palvelulta. Oma baseline-kirjanpito —
     *  reboot kesken lenkin (cum pienenee) siirtää kertymän committed-kenttään. Pause-tilassa
     *  askeleet ohjataan excludediin (kuuntelija on yleensä irti, mutta varmistus). */
    @Synchronized
    fun onStepCount(context: Context, cumulative: Float) {
        val w = entity ?: return
        val st = mutableState.value
        if (!stepSeen || cumulative < w.lastCumSeen) {
            if (stepSeen && cumulative < w.lastCumSeen) {
                w.stepCommitted += (w.lastCumSeen - w.stepBaselineCum).toLong()
            }
            w.stepBaselineCum = cumulative
            stepSeen = true
        }
        if (st.phase == Phase.PAUSED) {
            w.stepsExcluded += (cumulative - w.lastCumSeen).toLong().coerceAtLeast(0)
        }
        w.lastCumSeen = cumulative
        val total = (w.stepCommitted + (cumulative - w.stepBaselineCum).toLong() - w.stepsExcluded)
            .coerceAtLeast(0)
        if (total != w.steps) {
            w.steps = total
            // Askellus on liikesignaali GPS-katveessa (kävely sisätiloissa/tunnelissa)
            if (st.phase == Phase.ACTIVE) lastMovementWallMs = System.currentTimeMillis()
            mutableState.value = mutableState.value.copy(steps = total)
        }
    }

    // ---- sisäiset ----

    private fun resetRuntime() {
        lastAccepted = null
        lastAcceptedWallMs = 0L
        segment = 0
        pointBuffer = ArrayList()
        lastFlushRt = SystemClock.elapsedRealtime()
        anchorLat = Double.NaN
        anchorLon = Double.NaN
        altWindow.clear()
        smoothedAlt = Double.NaN
        elevRefAlt = Double.NaN
        derivedSpeedKmh = 0f
        stepSeen = false
    }

    private fun accrueMovingTime(w: WorkoutEntity) {
        val nowRt = SystemClock.elapsedRealtime()
        if (movingAnchorRt > 0) {
            w.movingTimeMs += (nowRt - movingAnchorRt).coerceAtLeast(0)
        }
        movingAnchorRt = nowRt
    }

    private fun acceptPoint(loc: Location, type: Int): Boolean {
        if (!loc.hasAccuracy() || loc.accuracy > ACCURACY_MAX_M) return false
        val prev = lastAccepted ?: return true
        val d = haversineM(prev.latitude, prev.longitude, loc.latitude, loc.longitude)
        if (d < minMoveM(type)) return false
        val dtSec = (System.currentTimeMillis() - lastAcceptedWallMs) / 1000.0
        if (dtSec > 0 && d / dtSec > maxDerivedMps(type)) return false   // GPS-piikki
        return true
    }

    private fun displaySpeedKmh(loc: Location): Float {
        if (!loc.hasSpeed()) return mutableState.value.speedKmh
        val kmh = loc.speed * 3.6f
        return if (kmh < 1.0f) 0f else kmh
    }

    private fun updateElevation(w: WorkoutEntity, loc: Location) {
        if (!loc.hasAltitude()) return
        altWindow.addLast(loc.altitude)
        while (altWindow.size > 5) altWindow.removeFirst()
        smoothedAlt = altWindow.average()
        if (elevRefAlt.isNaN()) {
            elevRefAlt = smoothedAlt
            return
        }
        val delta = smoothedAlt - elevRefAlt
        if (delta > ELEV_HYSTERESIS_M) {
            w.elevGainM += delta
            elevRefAlt = smoothedAlt
        } else if (delta < -ELEV_HYSTERESIS_M) {
            w.elevLossM += -delta
            elevRefAlt = smoothedAlt
        }
    }

    private fun flush(context: Context, force: Boolean) {
        val w = entity ?: return
        val nowRt = SystemClock.elapsedRealtime()
        if (!force && pointBuffer.size < FLUSH_POINTS && nowRt - lastFlushRt < FLUSH_INTERVAL_MS) return
        val pts = pointBuffer
        pointBuffer = ArrayList()
        lastFlushRt = nowRt
        w.updatedAtMs = System.currentTimeMillis()
        val d = dao(context)
        io.execute {
            if (pts.isNotEmpty()) d.insertPoints(pts)
            d.updateWorkout(w)
        }
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dp / 2) * Math.sin(dp / 2) +
            Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
