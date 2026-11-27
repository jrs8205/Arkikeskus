package org.jrs82.fsclock.mobile.gps

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jrs82.fsclock.BuildConfig
import org.jrs82.fsclock.R
import org.jrs82.fsclock.mobile.AppLocale
import org.jrs82.fsclock.mobile.MobileComposeMainActivity
import org.jrs82.fsclock.mobile.WorkoutTrackingService

/**
 * GPS-mittariston matkan taustapalvelu (tyyppi location): pitää matkan käynnissä näytön sammuttua ja
 * sovelluksen ollessa taustalla, näyttää pysyvän ilmoituksen toimintoineen ja muistuttaa unohtuneesta
 * matkasta. Kaikki laskenta on [TripTracker]issa; palvelu seuraa sen tilaa.
 *
 * Käsikäytössä riittää käytön aikainen sijaintilupa. Valitun auton yhdistys saa pyytää
 * käynnistystä myös taustalta, kun taustasijainti on sallittu. Jos
 * prosessi kuolee, matka palaa tauolle, kun pääprosessi seuraavan kerran käynnistyy ([TripTracker.init]).
 */
class TripTrackingService : Service() {

    companion object {
        /** Aloita matka ja Uusi matka. */
        const val ACTION_START = "org.jrs82.arkikeskus.trip.START"
        /** Jatka ja Jatka tätä matkaa. */
        const val ACTION_RESUME = "org.jrs82.arkikeskus.trip.RESUME"
        /** Ilmoituksen toiminnot; näkymä kutsuu näissä suoraan TripTrackeria. */
        const val ACTION_PAUSE = "org.jrs82.arkikeskus.trip.PAUSE"
        const val ACTION_FINISH = "org.jrs82.arkikeskus.trip.FINISH"
        /** AlarmManagerin herätys muistutuksen tarkistukseen; vain palvelun oma. */
        private const val ACTION_REMINDER_CHECK = "org.jrs82.arkikeskus.trip.REMINDER_CHECK"

        private const val ACTION_CAR_START = "org.jrs82.arkikeskus.trip.CAR_START"
        private const val TOKEN = "car_connection_generation"

        fun startFromCar(context: Context, token: Long) {
            val reason = CarTripNotifications.failureReason(context)
            if (reason != null) { CarTripNotifications.failed(context, reason); return }
            if (running?.foreground == true) {
                TripTracker.acceptCarStart(context, token)
                return
            }
            val request = Intent(context, TripTrackingService::class.java).setAction(ACTION_CAR_START).putExtra(TOKEN, token)
            try {
                ContextCompat.startForegroundService(context, request)
            } catch (_: IllegalStateException) { CarTripNotifications.failed(context, R.string.car_service_blocked) }
            catch (_: SecurityException) { CarTripNotifications.failed(context, R.string.car_service_blocked) }
        }

        fun onCarSettingChanged() { running?.updateAdapterListener() }

        val hasForeground get() = running?.foreground == true
        internal const val CHANNEL_TRACKING = "gps_trip_tracking"
        private const val CHANNEL_REMINDER = "gps_trip_reminder"
        // 41–43 lenkki, 44–60 Notifications, 1000– lähtömuistutukset.
        private const val NOTIF_ID = 61
        private const val REMINDER_NOTIF_ID = 62
        private const val TICK_MS = 5_000L
        /** Tallennus joka toisella tikillä eli noin 10 s välein. */
        private const val PERSIST_EVERY_TICKS = 2
        /** Tätä pidemmän tauon jälkeen palvelu ja ilmoitus poistuvat; matka jää tauolle. */
        private const val PAUSE_MAX_MS = 12L * 60L * 60_000L
        private const val SECTION = "SPEEDOMETER"

        /** Luo ilmoituskanavat näkyvän kielen nimillä. Julkinen, jotta kielen vaihtuessa kanavat voidaan
         *  nimetä uudelleen ilman palvelun käynnistystä. */
        fun createChannels(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val texts = AppLocale.localizedContext(context)
            val tracking = NotificationChannel(
                CHANNEL_TRACKING, texts.getString(R.string.gps_channel_tracking_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            tracking.description = texts.getString(R.string.gps_channel_tracking_desc)
            nm.createNotificationChannel(tracking)
            val reminder = NotificationChannel(
                CHANNEL_REMINDER, texts.getString(R.string.gps_channel_reminder_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            reminder.description = texts.getString(R.string.gps_channel_reminder_desc)
            nm.createNotificationChannel(reminder)
        }

        @Volatile
        private var running: TripTrackingService? = null

        /**
         * Kieli vaihtui: kanavat nimetään uudelleen ja näkyvä ilmoitus rakennetaan uudelleen. Tauolla ilmoitus
         * ei muuten päivity, joten otsikko ja toiminnot jäisivät vanhalle kielelle koko tauon ajaksi.
         */
        fun onLocaleChanged(context: Context) {
            createChannels(context)
            running?.refreshNotification()
        }

        /** Poistaa muistutusilmoituksen. Kutsutaan myös prosessin käynnistyessä: silloin mikään matka ei ole käynnissä. */
        fun cancelReminderNotification(context: Context) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(REMINDER_NOTIF_ID)
            } catch (e: Exception) {
            }
        }

        /**
         * Peruu muistutuksen tarkistusherätyksen. Kutsutaan myös prosessin käynnistyessä: jos edellinen prosessi
         * kuoli kesken matkan ilman [onDestroy]-kutsua, sen herätys on yhä voimassa, vaikka matka palautetaan
         * tauolle.
         */
        fun cancelReminderAlarm(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.cancel(reminderCheckPi(context))
            } catch (e: Exception) {
            }
        }

        private fun reminderCheckPi(context: Context): PendingIntent {
            val i = Intent(context, TripTrackingService::class.java).setAction(ACTION_REMINDER_CHECK)
            return PendingIntent.getService(
                context, 15, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        /**
         * Käynnistää palvelun etualalta. [action] on [ACTION_START] tai [ACTION_RESUME]. Palauttaa false,
         * jos järjestelmä estää käynnistyksen jo kutsussa (Android 12+: sovellus ei ole enää etualalla):
         * silloin [onStartCommand] ei käynnisty lainkaan, eikä matkan tila muutu.
         */
        fun start(context: Context, action: String): Boolean {
            val i = Intent(context, TripTrackingService::class.java)
            i.action = action
            return try {
                ContextCompat.startForegroundService(context, i)
                true
            } catch (e: IllegalStateException) {
                false
            } catch (e: SecurityException) {
                false
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private val sourceOwner = Any()

    /** Komento on käsitelty: vasta sen jälkeen tilan seuranta saa pysäyttää palvelun. */
    private var armed = false
    private var foreground = false
    private var lastPhase: TripPhase? = null
    private var pausedSinceRt = 0L
    private var lastWaiting = false
    private var listeningForAdapter = false
    private val adapterReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)
            if (state == android.bluetooth.BluetoothAdapter.STATE_OFF || state == android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF) {
                AutoTripPrefs(context).adapterOff()?.let { TripTracker.carConnectionChanged(context, it) }
            }
        }
    }

    private var tickCount = 0
    private var reminderShown = false
    /** Viimeisin käynnistyspyyntö: pysäytys ei saa kaataa jonossa odottavaa uudempaa pyyntöä. */
    private var lastStartId = 0

    private val tick = object : Runnable {
        override fun run() {
            TripTracker.refresh()
            val s = TripTracker.state.value
            when (s.phase) {
                TripPhase.RUNNING -> {
                    tickCount++
                    if (tickCount % PERSIST_EVERY_TICKS == 0) TripTracker.persist(this@TripTrackingService)
                    checkReminder()
                    updateNotification(TripTracker.state.value)
                }
                TripPhase.PAUSED -> {
                    if (s.waitingForCar) TripTracker.checkCarDeadlineIfDue(this@TripTrackingService)
                    if (pausedSinceRt > 0L && SystemClock.elapsedRealtime() - pausedSinceRt > PAUSE_MAX_MS) {
                        // Pitkä tauko: ilmoitus pois, matka säilyy tauolla ja on jatkettavissa sovelluksesta.
                        shutDown()
                        return
                    }
                }
                else -> return
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
        createChannels(this)
        TripTracker.init(this)
        val s = MainScope()
        scope = s
        s.launch {
            TripTracker.state.collect { snapshot ->
                if (armed && (snapshot.phase != lastPhase || snapshot.waitingForCar != lastWaiting)) onPhaseChanged(snapshot)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_REMINDER_CHECK) {
            onReminderCheck()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_CAR_START -> {
                val token = intent.getLongExtra(TOKEN, -1)
                val reason = CarTripNotifications.failureReason(this)
                val active = AutoTripPrefs(this).currentConnection(token)
                if (!active || reason != null || !promote(TripSnapshot(TripPhase.RUNNING))) {
                    if (active) CarTripNotifications.failed(this, reason ?: R.string.car_service_blocked)
                    if (!armed) { stopSelf(startId); return START_NOT_STICKY }
                } else TripTracker.acceptCarStart(this, token)
            }
            ACTION_START, ACTION_RESUME -> {
                val isStart = intent.action == ACTION_START
                // Palvelu on nostettava etualalle heti startForegroundServicen jälkeen. Jos nosto ei onnistu
                // (esim. sijaintilupa peruttu), matkaa ei aloiteta.
                val current = TripTracker.state.value
                val provisional = if (isStart) TripSnapshot(TripPhase.RUNNING) else current.copy(phase = TripPhase.RUNNING)
                if (!promote(provisional)) {
                    Toast.makeText(this, texts().getString(R.string.gps_trip_start_failed), Toast.LENGTH_LONG).show()
                    if (!armed) {
                        stopSelf(startId)
                        return START_NOT_STICKY
                    }
                } else if (isStart) {
                    TripTracker.start(this)
                } else {
                    TripTracker.resume(this)
                }
            }
            ACTION_PAUSE -> TripTracker.pause(this)
            ACTION_FINISH -> TripTracker.finish(this)
            else -> { }
        }
        armed = true
        onPhaseChanged(TripTracker.state.value)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Kaikki tämän instanssin jonottamat kutsut pois (tikki ja ilmoituksen päivitys), eikä tuhottu palvelu
        // enää julkaise ilmoitusta: muuten juuri ennen tuhoutumista jonotettu päivitys toisi pysyvän ilmoituksen
        // takaisin, vaikka palvelua ei ole.
        if (listeningForAdapter) unregisterReceiver(adapterReceiver)
        handler.removeCallbacksAndMessages(null)
        foreground = false
        scope?.cancel()
        scope = null
        if (running === this) running = null
        // Palvelu ei saa kadota matkan jäädessä käyntiin: ilman palvelua GPS on pois, joten aika kulkisi
        // mutta matka ei. Jos palvelu tuhoutuu kesken matkan, matka jää tauolle ja on jatkettavissa. Tauko
        // tehdään ennen lähteen vapautusta, jottei vapautuksen aikana saapuva mittaus kirjaudu matkaan.
        if (TripTracker.state.value.phase == TripPhase.RUNNING) TripTracker.pause(this, userAction = false)
        GpsSource.release(sourceOwner)
        // Tilan seuranta on jo peruttu, joten tämä tauko ei kulje sen kautta: muistutus ja sen herätys perutaan
        // tässä. Muuten tauolla olevasta matkasta jäisi näkyviin "Matka on yhä käynnissä", ja vanha herätys
        // käynnistäisi palvelun uudelleen pelkkää siivousta varten.
        cancelReminder()
        cancelReminderCheck()
        // Pysyvä ilmoitus poistetaan myös itse. Järjestelmä poistaa sen palvelun pysähtyessä, mutta juuri sitä
        // ennen lähetetty päivitys (esim. kielen vaihtuessa) voi ehtiä järjestelmään vasta poiston jälkeen ja
        // jäädä näkyviin tavallisena ilmoituksena, vaikka palvelua ei enää ole.
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
        } catch (e: Exception) {
        }
        if (TripTracker.state.value.waitingForCar) TripTracker.checkCarDeadline(this)
        super.onDestroy()
    }

    // ---- tilan seuranta ----

    private fun onPhaseChanged(s: TripSnapshot) {
        lastPhase = s.phase
        lastWaiting = s.waitingForCar
        val active = s.phase == TripPhase.RUNNING || s.phase == TripPhase.PAUSED
        if (!active || !foreground) {
            // Matka on lopetettu tai nollattu, tai toiminto saapui palveluun, joka ei ole etualalla
            // (esim. vanhan ilmoituksen painike): mitään näytettävää ei ole.
            shutDown()
            return
        }
        updateAdapterListener()
        cancelReminder()
        if (s.phase == TripPhase.RUNNING) {
            pausedSinceRt = 0L
            GpsSource.acquire(this, sourceOwner)
        } else {
            pausedSinceRt = SystemClock.elapsedRealtime()
            GpsSource.release(sourceOwner)
        }
        updateNotification(s)
        scheduleReminderCheck(s)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
    }

    private fun updateAdapterListener() {
        val needed = foreground && AutoTripPrefs(this).enabled
        if (needed == listeningForAdapter) return
        if (needed) {
            ContextCompat.registerReceiver(this, adapterReceiver,
                android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED),
                "android.permission.BLUETOOTH_PRIVILEGED", null, ContextCompat.RECEIVER_EXPORTED)
        } else unregisterReceiver(adapterReceiver)
        listeningForAdapter = needed
    }

    private fun shutDown() {
        handler.removeCallbacks(tick)
        GpsSource.release(sourceOwner)
        cancelReminder()
        cancelReminderCheck()
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        // Tunnisteella: jos uudempi käynnistyspyyntö (esim. Jatka heti lopetuksen perään) on jo jonossa,
        // palvelu jää eloon käsittelemään sen. Ilman tunnistetta järjestelmä kaataisi sovelluksen.
        stopSelf(lastStartId)
    }

    // ---- muistutus unohtuneesta matkasta ----

    /**
     * Muistutuksen yritykset. Ehdon tarkistus ja pysähdyksen varaus ovat yksi operaatio (GPS-säie ei voi
     * kirjata liikettä niiden väliin), ja näyttämättä jäänyt muistutus perutaan. Epäonnistuneen yrityksen
     * jälkeen seuraava sallitaan vasta uusintavälin päästä, myös palvelun omassa tikissä.
     */
    private val reminderFlow = ReminderFlow(
        retryMs = reminderRetryMs(reminderLimitMs(BuildConfig.DEBUG)),
        claim = { TripTracker.claimReminder(reminderLimitMs(BuildConfig.DEBUG)) },
        post = { postReminder(it) },
        release = { TripTracker.releaseReminder(it) },
    )

    private fun checkReminder() {
        val shown = reminderFlow.attempt(SystemClock.elapsedRealtime())
        if (!shown && reminderShown && !TripTracker.state.value.reminded) {
            // Liike jatkui: muistutus on vanhentunut.
            cancelReminder()
        }
    }

    /**
     * AlarmManagerin herätys. Palvelun oma tikki laskee vain puhelimen hereilläoloaikaa, joten näytön
     * ollessa pois se voi nukkua tuntikausia; herätys tarkistaa muistutuksen silloinkin.
     */
    private fun onReminderCheck() {
        if (!armed || !foreground) {
            // Vanha herätys palveluun, joka ei ole käynnissä (prosessi ehti kuolla): ei mitään tehtävää.
            shutDown()
            return
        }
        TripTracker.refresh()
        if (TripTracker.state.value.phase == TripPhase.RUNNING) checkReminder()
        scheduleReminderCheck(TripTracker.state.value)
    }

    /** Ajastaa seuraavan muistutuksen tarkistuksen tai peruu sen, jos matka ei ole käynnissä. */
    private fun scheduleReminderCheck(s: TripSnapshot) {
        val delay = nextReminderCheckDelayMs(
            s, reminderLimitMs(BuildConfig.DEBUG), reminderFlow.retryInMs(SystemClock.elapsedRealtime()),
        )
        if (delay == null) {
            cancelReminderCheck()
            return
        }
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, reminderCheckPi(),
            )
        } catch (e: Exception) {
        }
    }

    private fun cancelReminderCheck() = cancelReminderAlarm(this)

    private fun reminderCheckPi(): PendingIntent = reminderCheckPi(this)

    /** Näyttää muistutuksen. Palauttaa false, jos sitä ei saatu näkyviin. */
    private fun postReminder(s: TripSnapshot): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return try {
            // Sovelluksen ilmoitukset tai muistutuskanava estetty: ilmoitus ei näkyisi, vaikka kutsu onnistuu.
            if (!nm.areNotificationsEnabled()) return false
            if (nm.getNotificationChannel(CHANNEL_REMINDER)?.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
            val texts = texts()
            val distance = TripFormat.distance(s.distanceM, AppLocale.current(texts)).toString()
            val n = NotificationCompat.Builder(this, CHANNEL_REMINDER)
                .setSmallIcon(R.drawable.mobile_ic_car_24)
                .setContentTitle(texts.getString(R.string.gps_trip_reminder_title))
                .setContentText(texts.getString(R.string.gps_trip_reminder_text, distance))
                .setContentIntent(openPi(14))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .addAction(0, texts.getString(R.string.gps_action_pause), actionPi(ACTION_PAUSE))
                .addAction(0, texts.getString(R.string.gps_action_finish), actionPi(ACTION_FINISH))
                .build()
            nm.notify(REMINDER_NOTIF_ID, n)
            reminderShown = true
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun cancelReminder() {
        cancelReminderNotification(this)
        reminderShown = false
    }

    /** Tekstit sovellukselle valitulla kielellä myös silloin, kun sovelluksella ei ole aktiviteettia. */
    private fun texts(): Context = AppLocale.localizedContext(this)

    // ---- pysyvä ilmoitus ----

    /** Nostaa palvelun etualalle. Palauttaa false, jos järjestelmä estää sen (ks. WorkoutTrackingService). */
    private fun promote(s: TripSnapshot): Boolean {
        val n = buildNotification(s) ?: return false
        return try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            foreground = true
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Rakentaa näkyvän ilmoituksen uudelleen pääsäikeessä (kielen vaihtuessa). */
    private fun refreshNotification() {
        handler.post { if (foreground) updateNotification(TripTracker.state.value) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Laitteen tai sovelluksen kieli on voinut vaihtua: tauolla ilmoitus ei muuten päivity.
        refreshNotification()
    }

    private fun updateNotification(s: TripSnapshot) {
        if (!foreground) return
        val n = buildNotification(s) ?: return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIF_ID, n)
        } catch (e: Exception) {
        }
    }

    private fun buildNotification(s: TripSnapshot): Notification? {
        val speed = GpsReadout.displaySpeedKmh(GpsSource.reading.value.fix, SystemClock.elapsedRealtime())
        val texts = texts()
        val model = tripNotifModel(s, speed, AppLocale.current(texts)) ?: return null
        val builder = NotificationCompat.Builder(this, CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.mobile_ic_car_24)
            .setContentTitle(texts.getString(model.titleRes))
            .setContentText(model.text)
            .setContentIntent(openPi(10))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        for (action in model.actions) {
            when (action) {
                TripAction.PAUSE ->
                    builder.addAction(0, texts.getString(R.string.gps_action_pause), actionPi(ACTION_PAUSE))
                TripAction.RESUME ->
                    builder.addAction(0, texts.getString(R.string.gps_action_resume), actionPi(ACTION_RESUME))
                TripAction.FINISH ->
                    builder.addAction(0, texts.getString(R.string.gps_action_finish), actionPi(ACTION_FINISH))
                else -> { }
            }
        }
        return builder.build()
    }

    /** Ilmoituksen napautus avaa sovelluksen GPS-näkymään. */
    private fun openPi(code: Int): PendingIntent {
        val open = Intent(this, MobileComposeMainActivity::class.java)
            .putExtra(WorkoutTrackingService.EXTRA_OPEN_SECTION, SECTION)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this, code, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun actionPi(action: String): PendingIntent {
        val code = when (action) {
            ACTION_PAUSE -> 11
            ACTION_RESUME -> 12
            else -> 13
        }
        val i = Intent(this, TripTrackingService::class.java).setAction(action)
        return PendingIntent.getService(
            this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** Pysyvän ilmoituksen sisältö ilman Android-olioita, jotta se on yksikkötestattavissa. */
internal data class TripNotifModel(val titleRes: Int, val text: String, val actions: List<TripAction>)

/** Ilmoituksen sisältö matkan tilan mukaan. null = ilmoitusta ei näytetä (ei matkaa tai matka lopetettu). */
internal fun tripNotifModel(s: TripSnapshot, speedKmh: Int?, locale: Locale): TripNotifModel? = when (s.phase) {
    TripPhase.RUNNING -> TripNotifModel(
        R.string.gps_trip_running,
        TripFormat.notificationLine(s, speedKmh, locale),
        listOf(TripAction.PAUSE, TripAction.FINISH),
    )
    TripPhase.PAUSED -> TripNotifModel(
        if (s.waitingForCar) R.string.car_waiting else R.string.gps_trip_paused,
        TripFormat.notificationLine(s, speedKmh, locale),
        listOf(TripAction.RESUME, TripAction.FINISH),
    )
    else -> null
}

/** Lyhin väli muistutuksen tarkistusten välillä. */
private const val MIN_REMINDER_CHECK_MS = 60_000L

/** Näyttämättä jääneen muistutuksen uusi yritys. */
private const val REMINDER_RETRY_MS = 15L * 60_000L

/** Uusintaväli: 15 min, mutta ei muistutusrajaa pidempi (debug-rakennuksen lyhyt raja). */
internal fun reminderRetryMs(limitMs: Long): Long = minOf(limitMs, REMINDER_RETRY_MS)

/**
 * Milloin muistutus pitää tarkistaa seuraavan kerran. null = ei ajasteta (matka ei ole käynnissä).
 * Käynnissä: jäljellä oleva aika rajaan. Jo muistutettu: täysi raja, koska liike virittää muistutuksen
 * uudelleen ja uusi pysähdys voi täyttää rajan aikaisintaan sen päästä. Raja täynnä ilman muistutusta:
 * [retryInMs] eli aika seuraavaan sallittuun yritykseen.
 */
internal fun nextReminderCheckDelayMs(s: TripSnapshot, limitMs: Long, retryInMs: Long): Long? = when {
    s.phase != TripPhase.RUNNING -> null
    s.reminded -> limitMs
    // Raja on täynnä, mutta muistutusta ei saatu näytettyä (esim. ilmoituslupa evätty): herätys ajastetaan
    // hetkeen, jolloin seuraava yritys on sallittu ([ReminderFlow.retryInMs]), ei aina täyden välin päähän.
    s.idleMs >= limitMs -> retryInMs.coerceAtLeast(MIN_REMINDER_CHECK_MS)
    else -> (limitMs - s.idleMs).coerceAtLeast(MIN_REMINDER_CHECK_MS)
}

/** Muistutuksen raja. Debug-rakennuksessa kaksi minuuttia, jotta muistutuksen voi kokeilla. */
internal fun reminderLimitMs(debug: Boolean): Long = if (debug) 120_000L else TripCalculator.REMINDER_IDLE_MS
