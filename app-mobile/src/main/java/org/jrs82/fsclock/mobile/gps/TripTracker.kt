package org.jrs82.fsclock.mobile.gps

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import org.jrs82.fsclock.R
import org.jrs82.fsclock.mobile.AppLocale
import org.jrs82.fsclock.db.FsClockDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Matkan ohjaus: yhdistää GPS-lähteen ([GpsSource]), laskennan ([TripCalculator]) ja tallennuksen
 * ([TripStore]) ja julkaisee tilan näkymille ja taustapalvelulle ([state]). Yksi olio koko prosessille,
 * jotta kaikki näkymät ja ilmoitus näyttävät samat luvut.
 *
 * Mittaukset tulevat lähteen säikeestä ja komennot pääsäikeestä, joten kaikki laskentaa koskevat
 * metodit on synkronoitu. Aika on aina laitteen käynnistyksestä kulunut aika.
 */
/** Varattu muistutus: paikallaanolojakson tunniste ja tilannekuva, josta ilmoitus rakennetaan. */
data class ReminderClaim(val token: Long, val snapshot: TripSnapshot)

object TripTracker {

    private val mutableState = MutableStateFlow(TripSnapshot())

    /** Viimeisin tilannekuva. Päivittyy komennoista, mittauksista ja [refresh]-kutsuista. */
    val state: StateFlow<TripSnapshot> get() = mutableState

    private var calculator = TripCalculator()
    private var store: TripStore? = null
    internal lateinit var archive: TripArchive
        private set
    private var timing = CarTiming()
    private var retryConnection: Long? = null
    private var finishing = false
    private var initialized = false
    private val persistThrottle = Throttle(PERSIST_INTERVAL_MS)

    /** Käynnissä olevan matkan tallennusväli. */
    private const val PERSIST_INTERVAL_MS = 10_000L

    /** Kuinka monta viimeisintä prosessin päättymistä luetaan palautuksessa. */
    private const val EXIT_RECORDS = 16

    /** Tämän prosessin tunniste: sitoo tallenteen prosessiin, jonka päättymisaikaa palautus saa käyttää. */
    private val session: String = java.util.UUID.randomUUID().toString()

    /** Tilan muutoksen jälkeen uusi komento hyväksytään vasta tämän ajan kuluttua. */
    private const val COMMAND_QUIET_MS = 700L
    private val gate = CommandGate(COMMAND_QUIET_MS)

    /**
     * Lataa tallennetun matkan. Idempotentti. Prosessin kuollessa käynnissä ollut matka palaa tauolle.
     * Kutsutaan pääprosessin käynnistyessä (sovellusolio), jotta palautus ja edellisen prosessin jälkien siivous
     * tehdään myös, kun prosessin käynnistää taustatyö tai widget eikä näkymä tai matkapalvelu.
     */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val s = TripStore.of(context)
        val saved = s.load()
        // Käynnissä ollut matka oli käynnissä prosessin kuolemaan asti, vaikka viimeinen tallennus olisi
        // paljon vanhempi (ei mittauksia, puhelin nukkui): puuttuva aika luetaan matkaa ajaneen prosessin
        // päättymisajasta, jonka järjestelmä on kirjannut, eikä viimeisestä mittauksesta tai tikistä.
        val credit = if (saved.phase == TripPhase.RUNNING) {
            val savedStamp = s.stamp()
            val exit = savedStamp?.let { TripCalculator.exitOfSession(processExits(context), it.session) }
            TripCalculator.runningCreditMs(savedStamp, exit, System.currentTimeMillis(), now())
        } else {
            0L
        }
        // Tästä eteenpäin tallenteet kuuluvat tälle prosessille: tunniste kirjataan myös järjestelmän
        // päättymistietoihin, jotta seuraava käynnistys löytää juuri tämän prosessin päättymisajan.
        registerSession(context)
        val restored = TripCalculator.restore(saved, credit)
        val current = stamp()
        timing = s.carTiming()
        if (saved.phase == TripPhase.RUNNING) {
            val pauseWall = s.stamp()?.let { it.wallMs + credit } ?: current.wallMs
            timing = CarTiming(timing.startedAuto, pausedWall = pauseWall)
        } else if (restored.phase == TripPhase.PAUSED && timing.pausedWall == null) {
            timing = timing.copy(pausedWall = s.stamp()?.wallMs ?: current.wallMs)
        }
        s.recover(saved, restored, current, timing)
        val app = context.applicationContext
        archive = TripArchive(s, { row -> CarTripNotifications.saved(app, row) }) { FsClockDb.get(app).tripDao() }
        archive.recover()
        // Prosessin alussa mikään matka ei ole käynnissä (käynnissä ollut palautettiin juuri tauolle), joten
        // muistutusta "Matka on yhä käynnissä" tai sen tarkistusherätystä ei saa olla. Kumpikin on voinut jäädä
        // edelliseltä prosessilta, myös silloin, kun tallenne ehti jo tauolle: palvelu tallentaa tauon ennen kuin
        // poistaa muistutuksen, ja prosessi voi kuolla niiden välissä. Siksi ne perutaan tallenteesta riippumatta.
        // Herätys herättäisi laitteen ja käynnistäisi palvelun tauolla olevalle matkalle.
        TripTrackingService.cancelReminderNotification(context)
        TripTrackingService.cancelReminderAlarm(context)
        calculator = TripCalculator(restored)
        store = s
        GpsSource.onFix = { fix -> onFix(fix) }
        initialized = true
        publish()
        if (timing.waiting) checkCarDeadline(app)
    }

    /** Aloita matka tai Uusi matka. Palauttaa true, jos tila muuttui. */
    fun start(context: Context): Boolean = command(context, newTrip = true) { it.start(now()) }

    /**
     * Tauko. [userAction] = false, kun taustapalvelu jättää matkan tauolle tuhoutuessaan: se ei ole
     * napautus, joten kaksoisnapautussuoja ei saa hylätä sitä.
     */
    fun pause(context: Context, userAction: Boolean = true): Boolean =
        command(context, gated = userAction, userPause = userAction) { it.pause(now()) }

    /** Jatka tauolta tai Jatka tätä matkaa lopetetusta. */
    fun resume(context: Context): Boolean = command(context) { it.resume(now()) }

    @Synchronized
    fun finish(context: Context): Boolean {
        init(context)
        val at = stamp()
        if (!acceptsCommands() || !calculator.finish(at.elapsedMs)) return false
        AutoTripPrefs(context).supersedeStart()
        finishing = true
        val end = at.copy(wallMs = timing.disconnectWall ?: at.wallMs)
        clearWait(context)
        store!!.transition(calculator.snapshot(at.elapsedMs), end, timing = timing)
        val app = context.applicationContext
        archive.finish { saved ->
            if (!saved) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(AppLocale.localizedContext(app), R.string.gps_history_save_pending, Toast.LENGTH_LONG).show()
                }
            }
            synchronized(this) {
                finishing = false
                gate.changed(now())
                publish()
                retryConnection?.let { token ->
                    retryConnection = null
                    Handler(Looper.getMainLooper()).post {
                        if (AutoTripPrefs(app).currentConnection(token)) carConnectionChanged(app, CarEvent.FOUND)
                    }
                }
            }
        }
        return true
    }

    private fun clearWait(context: Context) {
        if (timing.waiting) {
            CarTimeoutReceiver.cancel(context)
            CarTripNotifications.clearWaiting(context)
        }
        timing = CarTiming(timing.startedAuto)
    }

    @Synchronized
    internal fun cancelCarWait(context: Context) {
        init(context)
        if (!timing.waiting) return
        val pause = timing.disconnectWall
        clearWait(context)
        timing = timing.copy(pausedWall = pause)
        store!!.transition(publish(), stamp(), timing = timing)
    }

    @Synchronized
    internal fun checkCarDeadlineIfDue(context: Context) {
        if (timing.waiting && timing.remaining(System.currentTimeMillis(), now(), bootCount(context)) == 0L) {
            checkCarDeadline(context)
        }
    }

    @Synchronized
    internal fun checkCarDeadline(context: Context) {
        init(context)
        if (!timing.waiting) return
        if (!AutoTripPrefs(context).enabled) { cancelCarWait(context); return }
        val at = stamp()
        val delay = timing.remaining(at.wallMs, at.elapsedMs, bootCount(context))
        if (delay > 0) {
            CarTimeoutReceiver.schedule(context, delay)
            if (!TripTrackingService.hasForeground) CarTripNotifications.waiting(context, checkNotNull(timing.disconnectWall))
        }
        else if (carAction(state.value.phase, timing, CarEvent.EXPIRED, at.wallMs) == CarAction.FINISH) {
            automaticFinish(context, at, checkNotNull(timing.disconnectWall))
        }
    }

    private fun automaticFinish(context: Context, at: SaveStamp, endWall: Long) {
        if (!calculator.finish(at.elapsedMs)) return
        clearWait(context)
        val discard = discardAutoTrip(timing.startedAuto, true, calculator.snapshot(at.elapsedMs).distanceM)
        if (discard) {
            calculator.reset()
            timing = CarTiming()
        }
        store!!.transition(calculator.snapshot(at.elapsedMs), at.copy(wallMs = endWall), timing = timing, endedAuto = true)
        if (!discard) archive.recover()
        gate.changed(at.elapsedMs)
        publish()
    }

    @Synchronized
    internal fun carConnectionChanged(context: Context, event: CarEvent) {
        init(context)
        val prefs = AutoTripPrefs(context)
        if (!prefs.enabled) return
        if (finishing) {
            retryConnection = if (event == CarEvent.FOUND) prefs.generation else null
            return
        }
        if (timing.waiting) checkCarDeadline(context)
        val at = stamp()
        when (carAction(state.value.phase, timing, event, at.wallMs)) {
            CarAction.WAIT -> {
                calculator.pause(at.elapsedMs)
                timing = timing.copy(pausedWall = timing.pausedWall ?: at.wallMs, userPaused = false,
                    disconnectWall = at.wallMs, disconnectElapsed = at.elapsedMs, boot = bootCount(context))
                store!!.transition(publish(), at, timing = timing)
                CarTimeoutReceiver.schedule(context, CAR_WAIT_MS)
                if (!TripTrackingService.hasForeground) CarTripNotifications.waiting(context, at.wallMs)
            }
            CarAction.START, CarAction.RESUME, CarAction.REPLACE -> {
                if (timing.waiting) {
                    // Y ehti ajoissa. Palvelun käynnistysviive ei saa päättää tätä matkaa.
                    clearWait(context)
                    timing = timing.copy(pausedWall = at.wallMs)
                    store!!.transition(publish(), at, timing = timing)
                }
                TripTrackingService.startFromCar(context, prefs.generation)
            }
            else -> Unit
        }
    }

    /** Kutsutaan vasta, kun palvelu on saanut sijaintipalvelun etualalle. */
    @Synchronized
    internal fun acceptCarStart(context: Context, token: Long) {
        val prefs = AutoTripPrefs(context)
        if (!prefs.currentConnection(token) || finishing) return
        val at = stamp()
        val action = carAction(state.value.phase, timing, CarEvent.FOUND, prefs.foundAt)
        if (action == CarAction.REPLACE) automaticFinish(context, at, timing.pausedWall ?: at.wallMs)
        when (action) {
            CarAction.START, CarAction.REPLACE -> command(context, gated = false, newTrip = true, autoStart = true) { it.start(at.elapsedMs) }
            CarAction.RESUME -> command(context, gated = false) { it.resume(at.elapsedMs) }
            else -> Unit
        }
    }

    /** Nollaa: lopetetun matkan yhteenveto pois. */
    fun reset(context: Context): Boolean = command(context) { it.reset() }

    /**
     * false heti tilan muutoksen jälkeen. Näkymä kysyy tämän ennen komentoa, jottei tahaton toinen
     * napautus käynnistä palvelua turhaan; komennot tarkistavat saman itse (ilmoituksen painikkeet).
     */
    @Synchronized
    fun acceptsCommands(): Boolean = !finishing && gate.open(now())

    /** Julkaisee tämän hetken tilannekuvan: kokonaisaika kulkee, vaikka mittauksia ei tulisi. */
    @Synchronized
    fun refresh() {
        if (initialized && !finishing) publish()
    }

    /** Tallentaa tämän hetken tilan levylle. Taustapalvelu kutsuu tätä matkan aikana. */
    @Synchronized
    fun persist(context: Context) {
        init(context)
        if (!finishing && mutableState.value.phase == TripPhase.RUNNING) store?.save(calculator.snapshot(now()), stamp())
    }

    /**
     * Tarkistaa muistutuksen ehdon ja varaa muistutuksen samalla lukolla, jolla mittaukset käsitellään:
     * tarkistuksen ja varauksen väliin ei voi osua liikettä. null, jos muistutus ei ole ajankohtainen.
     */
    @Synchronized
    fun claimReminder(limitMs: Long): ReminderClaim? {
        if (!initialized || finishing) return null
        val token = calculator.claimReminder(now(), limitMs) ?: return null
        return ReminderClaim(token, publish())
    }

    /** Peruu varauksen, kun muistutusta ei saatu näytettyä; ei vaikuta, jos liike on jo aloittanut uuden jakson. */
    @Synchronized
    fun releaseReminder(token: Long) {
        calculator.releaseReminder(token)
        if (initialized && !finishing) publish()
    }

    private fun stamp(): SaveStamp = SaveStamp(System.currentTimeMillis(), now(), session)

    /** Kirjaa tämän prosessin tunnisteen järjestelmän päättymistietoihin (luetaan seuraavassa käynnistyksessä). */
    private fun registerSession(context: Context) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.setProcessStateSummary(session.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
        }
    }

    /** Järjestelmän kirjaamat tämän sovelluksen prosessien päättymiset: prosessin tunniste ja aika (seinäkello). */
    private fun processExits(context: Context): List<ProcessExit> = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getHistoricalProcessExitReasons(null, 0, EXIT_RECORDS).map { info ->
            val summary = info.processStateSummary?.takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8)
            ProcessExit(summary, info.timestamp)
        }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    private fun command(context: Context, gated: Boolean = true, newTrip: Boolean = false, userPause: Boolean = false,
                        autoStart: Boolean = false, block: (TripCalculator) -> Boolean): Boolean {
        init(context)
        // Kaksoisnapautus: painikkeet vaihtuvat tilan mukana samalla paikalla, joten heti muutoksen
        // perään tuleva komento on todennäköisesti vahinko (esim. Lopeta ja sen kohdalle tullut Jatka).
        if (finishing || (gated && !gate.open(now()))) return false
        val changed = block(calculator)
        if (changed) {
            if (gated) AutoTripPrefs(context).supersedeStart()
            val at = stamp()
            clearWait(context)
            timing = when (calculator.snapshot(at.elapsedMs).phase) {
                TripPhase.PAUSED -> timing.copy(pausedWall = at.wallMs, userPaused = userPause)
                TripPhase.NONE -> CarTiming()
                else -> if (newTrip) CarTiming(startedAuto = autoStart) else timing
            }
            gate.changed(at.elapsedMs)
            store?.transition(publish(), at, newTrip, timing)
        }
        return changed
    }

    @Synchronized
    private fun onFix(fix: GpsFix) {
        if (finishing) return
        calculator.onFix(fix)
        val s = publish()
        // Tallennus kulkee mittausten tahdissa: mittaus herättää puhelimen myös näytön ollessa pois,
        // toisin kuin palvelun oma tikki, joka voi nukkua. Prosessin kuollessa menetetään enintään ~10 s.
        if (s.phase == TripPhase.RUNNING && persistThrottle.due(now())) store?.save(s, stamp())
    }

    private fun publish(): TripSnapshot {
        val s = calculator.snapshot(now()).let { if (timing.waiting) it.copy(waitingForCar = true) else it }
        mutableState.value = s
        return s
    }

    private fun now(): Long = SystemClock.elapsedRealtime()
}
