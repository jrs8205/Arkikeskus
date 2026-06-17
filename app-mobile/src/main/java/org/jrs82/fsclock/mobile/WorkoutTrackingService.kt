package org.jrs82.fsclock.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.R
import org.jrs82.fsclock.db.WorkoutEntity
import java.util.Locale

/**
 * Lenkkiseurannan foreground service (type=location): omistaa GPS- ja askelkuuntelijat sekä
 * ongoing-notifikaation; kaikki laskenta on WorkoutTrackerissa. Käynnistetään AINA etualalta
 * (Aloita-nappi tai sovelluksen avauksen palautuspolku) → while-in-use-sijaintilupa riittää.
 * START_STICKY: järjestelmän restartti tulee null-intentillä → palautus kannasta.
 */
class WorkoutTrackingService : Service() {

    companion object {
        const val ACTION_START = "org.jrs82.arkikeskus.workout.START"
        const val ACTION_PAUSE = "org.jrs82.arkikeskus.workout.PAUSE"
        const val ACTION_RESUME = "org.jrs82.arkikeskus.workout.RESUME"
        const val ACTION_STOP = "org.jrs82.arkikeskus.workout.STOP"
        const val ACTION_RECOVER = "org.jrs82.arkikeskus.workout.RECOVER"
        const val EXTRA_TYPE = "type"
        const val EXTRA_OPEN_SECTION = "open_section"

        private const val CHANNEL_ID = "workout_tracking"
        // ID vaihdettu (workout_km_split → arki_workout_km), koska tärkeys nostettiin DEFAULT→HIGH.
        private const val KM_CHANNEL_ID = "arki_workout_km"
        private const val NOTIF_ID = 41
        private const val DONE_NOTIF_ID = 42
        private const val KM_NOTIF_ID = 43
        private const val TICK_MS = 5_000L
        private val FI = Locale("fi", "FI")

        /** Asetuskytkin: näytetäänkö ääni-ilmoitus joka kilometri (oletus päällä). */
        const val KEY_WORKOUT_KM_NOTIFY = "workout_km_notify"

        fun start(context: Context, type: Int) {
            val i = Intent(context, WorkoutTrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TYPE, type)
            ContextCompat.startForegroundService(context, i)
        }

        fun command(context: Context, action: String) {
            val i = Intent(context, WorkoutTrackingService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, i)
        }
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var listening = false
    private var stepListening = false

    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            WorkoutTracker.onLocation(this@WorkoutTrackingService, loc)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) { }
        override fun onProviderEnabled(provider: String) { }
        override fun onProviderDisabled(provider: String) { }
    }

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            WorkoutTracker.onStepCount(this@WorkoutTrackingService, event.values[0])
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }
    }

    private val tick = object : Runnable {
        override fun run() {
            val ended = try { WorkoutTracker.tick(this@WorkoutTrackingService) } catch (e: Exception) { false }
            if (ended) {
                onWorkoutEnded(auto = true)
                return
            }
            updateNotification()
            handler?.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val t = HandlerThread("workout-tracking")
        t.start()
        thread = t
        handler = Handler(t.looper)
        WorkoutTracker.onSplit = { split, totalMovingMs ->
            vibrateSplit()
            postKmNotification(split.index, split.durationMs, totalMovingMs)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // FGS pitää promotoida heti startForegroundService-käynnistyksen jälkeen.
        startForegroundCompat()
        when (intent?.action ?: ACTION_RECOVER) {
            ACTION_START -> {
                val type = intent?.getIntExtra(EXTRA_TYPE, WorkoutEntity.TYPE_WALK)
                    ?: WorkoutEntity.TYPE_WALK
                try {
                    WorkoutTracker.start(this, type)
                } catch (e: Exception) {
                    finishService()
                    return START_NOT_STICKY
                }
                startListening()
                schedule()
            }
            ACTION_RECOVER -> {
                val recovered = try { WorkoutTracker.recover(this) } catch (e: Exception) { false }
                if (!recovered) {
                    finishService()
                    return START_NOT_STICKY
                }
                if (WorkoutTracker.state.value.phase == WorkoutTracker.Phase.ACTIVE) startListening()
                schedule()
            }
            ACTION_PAUSE -> {
                WorkoutTracker.pause(this)
                stopListening()
                updateNotification()
            }
            ACTION_RESUME -> {
                WorkoutTracker.resume(this)
                startListening()
                updateNotification()
            }
            ACTION_STOP -> {
                WorkoutTracker.stop(this, auto = false)
                onWorkoutEnded(auto = false)
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        handler?.removeCallbacks(tick)
        WorkoutTracker.onSplit = null
        thread?.quitSafely()
        thread = null
        handler = null
        super.onDestroy()
    }

    // ---- kuuntelijat ----

    private fun startListening() {
        if (listening) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val looper = thread?.looper ?: return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, looper)
            listening = true
        } catch (e: SecurityException) {
            // Lupa puuttuu → tracker jää odottamaan; notifikaatio kertoo tilanteen.
        }
        if (!stepListening &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (sensor != null) {
                sm.registerListener(stepListener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
                stepListening = true
            }
        }
    }

    private fun stopListening() {
        if (listening) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            try { lm.removeUpdates(locListener) } catch (e: Exception) { }
            listening = false
        }
        if (stepListening) {
            val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sm.unregisterListener(stepListener)
            stepListening = false
        }
    }

    private fun schedule() {
        handler?.removeCallbacks(tick)
        handler?.postDelayed(tick, TICK_MS)
    }

    private fun onWorkoutEnded(auto: Boolean) {
        stopListening()
        handler?.removeCallbacks(tick)
        if (auto) postDoneNotification()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- notifikaatio ----

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "Lenkin seuranta", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Käynnissä olevan lenkin tila ja hallinta"
        nm.createNotificationChannel(ch)
        // Km-väli-ilmoitus: oma kanava HIGH-tärkeydellä (heads-up + ääni, sama taso kuin muut hälytykset).
        // Värinä tulee vibrateSplitistä → kanavan oma värinä pois, ettei tuplaannu.
        nm.deleteNotificationChannel("workout_km_split") // vanha DEFAULT-kanava → uusi HIGH-ID
        val km = NotificationChannel(KM_CHANNEL_ID, "Lenkin kilometrivälit", NotificationManager.IMPORTANCE_HIGH)
        km.description = "Ilmoitus jokaisen täyden kilometrin täyttyessä lenkillä"
        km.enableVibration(false)
        nm.createNotificationChannel(km)
    }

    private fun startForegroundCompat() {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(NOTIF_ID, buildNotification()) } catch (e: Exception) { }
    }

    private fun buildNotification(): Notification {
        val st = WorkoutTracker.state.value
        val typeWord = if (st.type == WorkoutEntity.TYPE_BIKE) "Pyörälenkki" else "Kävelylenkki"
        val title = when (st.phase) {
            WorkoutTracker.Phase.PAUSED -> "$typeWord tauolla"
            else -> "$typeWord käynnissä"
        }
        val moving = st.movingTimeMs +
            if (st.phase == WorkoutTracker.Phase.ACTIVE && st.movingAnchorRt > 0) {
                (SystemClock.elapsedRealtime() - st.movingAnchorRt).coerceAtLeast(0)
            } else 0L
        val text = String.format(
            FI, "%.2f km · %s · %.1f km/h",
            st.distanceM / 1000.0, formatDuration(moving), st.speedKmh,
        )
        val openIntent = Intent(this, MobileComposeMainActivity::class.java)
            .putExtra(EXTRA_OPEN_SECTION, "WORKOUT")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        val contentPi = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.mobile_ic_transit_walk)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
        if (st.phase == WorkoutTracker.Phase.PAUSED) {
            builder.addAction(0, "Jatka", actionPi(ACTION_RESUME, 2))
        } else {
            builder.addAction(0, "Tauko", actionPi(ACTION_PAUSE, 3))
        }
        builder.addAction(0, "Lopeta", actionPi(ACTION_STOP, 4))
        return builder.build()
    }

    private fun actionPi(action: String, code: Int): PendingIntent {
        val i = Intent(this, WorkoutTrackingService::class.java).setAction(action)
        return PendingIntent.getService(
            this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun postDoneNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = Intent(this, MobileComposeMainActivity::class.java)
            .putExtra(EXTRA_OPEN_SECTION, "WORKOUT")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            this, 5, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.mobile_ic_transit_walk)
            .setContentTitle("Lenkki päättyi automaattisesti")
            .setContentText("Ei liikettä 10 minuuttiin — lenkki tallennettiin.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { nm.notify(DONE_NOTIF_ID, n) } catch (e: Exception) { }
    }

    /** Näytä km-väli-ilmoitus (oma DEFAULT-kanava). Gate asetuskytkimellä + POST_NOTIFICATIONS-luvalla. */
    private fun postKmNotification(index: Int, paceMs: Long, totalMovingMs: Long) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean(KEY_WORKOUT_KM_NOTIFY, true)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = Intent(this, MobileComposeMainActivity::class.java)
            .putExtra(EXTRA_OPEN_SECTION, "WORKOUT")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            this, 6, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, KM_CHANNEL_ID)
            .setSmallIcon(R.drawable.mobile_ic_transit_walk)
            .setColor(ContextCompat.getColor(this, R.color.mobile_accent))
            .setContentTitle("$index. kilometri")
            .setContentText(kmSplitStatsText(index, paceMs, totalMovingMs))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()
        try { nm.notify(KM_NOTIF_ID, n) } catch (e: Exception) { }
    }

    private fun vibrateSplit() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 150, 200), -1))
        } catch (e: Exception) { }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(FI, "%d:%02d:%02d", h, m, s)
        else String.format(FI, "%02d:%02d", m, s)
    }
}

/**
 * Km-väli-ilmoituksen tilastorivi (pure → yksikkötestattava, ei Android-riippuvuuksia).
 * - tahti = `paceMs` (juuri täyttyneen kilometrin kesto) → m:ss /km
 * - aika = `totalMovingMs` (kokonaisliikeaika) → m:ss tai h:mm:ss
 * - keskinopeus: km-splitissä matka on tasan `index` km → ka = index / (totalMovingMs tunteina)
 */
internal fun kmSplitStatsText(index: Int, paceMs: Long, totalMovingMs: Long): String {
    val fi = Locale("fi", "FI")
    val paceSec = (paceMs / 1000).coerceAtLeast(0)
    val pace = String.format(fi, "%d:%02d", paceSec / 60, paceSec % 60)
    val totalSec = (totalMovingMs / 1000).coerceAtLeast(0)
    val total = if (totalSec >= 3600) {
        String.format(fi, "%d:%02d:%02d", totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
    } else {
        String.format(fi, "%d:%02d", totalSec / 60, totalSec % 60)
    }
    val avgKmh = if (totalMovingMs > 0) index * 3_600_000.0 / totalMovingMs else 0.0
    return String.format(fi, "tahti %s/km · aika %s · ka %.1f km/h", pace, total, avgKmh)
}
