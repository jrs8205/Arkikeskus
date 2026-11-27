package org.jrs82.fsclock.mobile.gps

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jrs82.fsclock.R
import org.jrs82.fsclock.db.FsClockDb
import org.jrs82.fsclock.mobile.WorkoutTrackingService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CarTripLifecycleTest {
    private fun advance(ms: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(ms))

    @Test fun recoveryReconnectManualOverrideAndAutomaticArchive() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = AutoTripPrefs(app)
        val store = TripStore.of(app)
        val nm = app.getSystemService(NotificationManager::class.java)
        val dao = FsClockDb.get(app).tripDao()
        val receiver = app.packageManager.getReceiverInfo(ComponentName(app, CarBluetoothReceiver::class.java), 0)
        assertTrue(receiver.exported)
        assertEquals("android.permission.BLUETOOTH_PRIVILEGED", receiver.permission)
        assertFalse(app.packageManager.getReceiverInfo(ComponentName(app, CarTimeoutReceiver::class.java), 0).exported)
        fun hasTimeout() = PendingIntent.getBroadcast(app, 0, Intent(app, CarTimeoutReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) != null
        fun rows() = runBlocking(Dispatchers.IO) { dao.all() }
        fun await(condition: () -> Boolean) {
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!condition()) {
                check(System.nanoTime() < limit) { "Timed out waiting for archive or notification" }
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
        }
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT)
        prefs.enable(true)
        prefs.select("car", true)
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 42)
        val end = System.currentTimeMillis() - 70_000
        // Prosessin käynnistyessä levyllä on edellisen käynnistyksen keskeneräinen odotus.
        val saved = TripSnapshot(TripPhase.PAUSED, 125.0, 10_000, 8_000, 18f)
        store.transition(saved, SaveStamp(end - 10_000, 5, "old"), newTrip = true,
            timing = CarTiming(startedAuto = true))
        store.transition(saved, SaveStamp(end, 10_005, "old"), timing = CarTiming(true, end, false, end, 10_005, 41))
        assertTrue(store.flush())
        TripTracker.init(app)
        await { rows().size == 1 && store.pendingTrips().isEmpty() }
        val savedRow = rows().single()
        assertEquals(end, savedRow.endedAtMs)
        assertEquals(125.0, savedRow.distanceM, 0.0)
        assertTrue(savedRow.startedAutomatically && savedRow.endedAutomatically)
        val notice = nm.activeNotifications.single { it.tag == CarTripNotifications.TRIP_PREFIX + savedRow.id }
        val target = shadowOf(notice.notification.contentIntent).savedIntent
        assertEquals(CarTripNotifications.TRIP_PREFIX + savedRow.id, target.getStringExtra(WorkoutTrackingService.EXTRA_OPEN_SECTION))
        TripTracker.checkCarDeadline(app)
        TripTracker.archive.recover()
        assertEquals(1, rows().size)
        nm.cancelAll()

        fun up() {
            assertEquals(CarEvent.FOUND, prefs.connection("car", true))
            TripTracker.acceptCarStart(app, prefs.generation)
            assertEquals(TripPhase.RUNNING, TripTracker.state.value.phase)
        }
        fun down() {
            assertEquals(CarEvent.LOST, prefs.connection("car", false))
            TripTracker.carConnectionChanged(app, CarEvent.LOST)
        }
        assertFalse(hasTimeout())
        up()
        advance(2_000)
        down()
        assertTrue(hasTimeout())
        val first = TripTracker.state.value
        assertTrue(first.waitingForCar)
        advance(59_999)
        TripTracker.checkCarDeadline(app)
        up()
        assertFalse(hasTimeout())
        advance(1_000)
        down()
        val paused = TripTracker.state.value
        assertEquals(first.totalMs + 1_000, paused.totalMs)
        val lost = store.carTiming().disconnectWall!!
        advance(2_000)
        assertTrue(TripTracker.finish(app))
        await { rows().size == 2 && store.pendingTrips().isEmpty() }
        val manual = rows().single { it.id != savedRow.id }
        assertEquals(lost, manual.endedAtMs)
        assertEquals(paused.totalMs, manual.totalMs)
        assertTrue(manual.startedAutomatically)
        assertFalse(manual.endedAutomatically)
        assertEquals(0, nm.activeNotifications.size)

        up()
        advance(2_000)
        assertTrue(TripTracker.pause(app))
        down()
        assertFalse(TripTracker.state.value.waitingForCar)
        advance(1_000)
        up()
        advance(1_000)
        assertTrue(TripTracker.pause(app, userAction = false))
        down()
        assertTrue(TripTracker.state.value.waitingForCar)
        prefs.enable(false)
        assertFalse(store.carTiming().waiting)
        assertFalse(hasTimeout())
        advance(60_000)
        TripTracker.checkCarDeadline(app)
        assertEquals(TripPhase.PAUSED, TripTracker.state.value.phase)
        prefs.enable(true)
        up()
        down()
        advance(60_000)
        TripTracker.checkCarDeadline(app)
        assertEquals(TripPhase.NONE, TripTracker.state.value.phase)
        assertEquals(2, rows().size)
        assertEquals(0, nm.activeNotifications.size)

        // Pitkä tauko ja alle 100 m: vanha automaattinen matka hylätään ja uusi saa oman tunnisteen.
        up()
        advance(1_000)
        assertTrue(TripTracker.pause(app))
        down()
        val tripPrefs = app.getSharedPreferences(TripStore.FILE, 0)
        val oldId = tripPrefs.getString("trip_id", null)
        assertEquals(CarEvent.FOUND, prefs.connection("car", true, store.carTiming().pausedWall!! + MAX_CAR_PAUSE_MS + 1))
        TripTracker.acceptCarStart(app, prefs.generation)
        assertNotEquals(oldId, tripPrefs.getString("trip_id", null))
        assertTrue(store.carTiming().startedAuto)
        assertEquals(2, runBlocking(Dispatchers.IO) { TripTracker.archive.export().size })
        down()
        advance(60_000)
        TripTracker.checkCarDeadline(app)

        // Käsin aloitettu lyhyt matka säilyy. Uusi käsin lopetus korvaa viimeisen lopetustavan.
        advance(1_000)
        assertTrue(TripTracker.start(app))
        advance(1_000)
        assertEquals(CarEvent.FOUND, prefs.connection("car", true))
        down()
        val lastLoss = store.carTiming().disconnectWall!!
        advance(CAR_WAIT_MS)
        TripTracker.checkCarDeadline(app)
        await { rows().size == 3 && store.pendingTrips().isEmpty() }
        val old = rows().single { !it.startedAutomatically }
        assertEquals(lastLoss, old.endedAtMs)
        assertTrue(old.endedAutomatically)
        advance(1_000)
        assertTrue(TripTracker.resume(app))
        advance(1_000)
        assertTrue(TripTracker.finish(app))
        await { !rows().single { it.id == old.id }.endedAutomatically }
        assertEquals(3, rows().size)

        // Uusi palvelupyyntö hylätään jo puuttuvan luvan kohdalla. Tila ja kohde tarkistetaan.
        CarTripNotifications.failed(app, R.string.car_service_blocked)
        val failure = nm.activeNotifications.single { it.tag == CarTripNotifications.SETTINGS }
        assertEquals(CarTripNotifications.SETTINGS, shadowOf(failure.notification.contentIntent).savedIntent
            .getStringExtra(WorkoutTrackingService.EXTRA_OPEN_SECTION))
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        TripTrackingService.startFromCar(app, prefs.generation)
        assertEquals(app.getString(R.string.car_permission_fine), nm.activeNotifications.single {
            it.tag == CarTripNotifications.SETTINGS
        }.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(R.string.car_permission_background, CarTripNotifications.failureReason(app))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val location = app.getSystemService(android.location.LocationManager::class.java)
        shadowOf(location).setLocationEnabled(false)
        assertEquals(R.string.car_location_off, CarTripNotifications.failureReason(app))
        shadowOf(location).setLocationEnabled(true)
        shadowOf(location).setProviderEnabled(android.location.LocationManager.GPS_PROVIDER, true)
        val blocked = object : android.content.ContextWrapper(app) {
            override fun startForegroundService(service: android.content.Intent): android.content.ComponentName? {
                throw IllegalStateException("blocked")
            }
        }
        val before = TripTracker.state.value
        TripTrackingService.startFromCar(blocked, prefs.generation)
        assertEquals(before, TripTracker.state.value)
        assertEquals(app.getString(R.string.car_service_blocked), nm.activeNotifications.single {
            it.tag == CarTripNotifications.SETTINGS
        }.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT))

        // Koeympäristön Bluetooth-tapahtuma kulkee tuotannon vastaanottimen ja palvelun kautta.
        val car = "00:11:22:33:44:55"
        prefs.select(car, true)
        val adapter = app.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
        fun broadcast(address: String, action: String) {
            CarBluetoothReceiver().onReceive(app, android.content.Intent(action)
                .putExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, adapter.getRemoteDevice(address)))
        }
        val upAction = android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED
        val downAction = android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED
        broadcast("00:11:22:33:44:66", upAction)
        assertNull(shadowOf(app).nextStartedService)
        broadcast(car, upAction)
        val request = checkNotNull(shadowOf(app).nextStartedService)
        val controller = org.robolectric.Robolectric.buildService(TripTrackingService::class.java).create()
        try {
            controller.get().onStartCommand(request, 0, 1)
            assertEquals(TripPhase.RUNNING, TripTracker.state.value.phase)
            assertTrue(store.carTiming().startedAuto)
            broadcast(car, downAction)
            assertTrue(TripTracker.state.value.waitingForCar)
            assertEquals(R.string.car_waiting, tripNotifModel(TripTracker.state.value, null, java.util.Locale.UK)?.titleRes)
            prefs.enable(false)
            assertFalse(TripTracker.state.value.waitingForCar)
            assertEquals(TripPhase.PAUSED, TripTracker.state.value.phase)
            broadcast(car, upAction)
            assertNull(shadowOf(app).nextStartedService)
        } finally { controller.destroy() }

        // Y on jo pyytänyt palvelua, mutta käyttäjä ehti lopettaa tauolla olleen matkan.
        prefs.enable(true)
        broadcast(car, upAction)
        val delayedToken = prefs.generation
        advance(1_000)
        assertTrue(TripTracker.finish(app))
        await { TripTracker.state.value.phase == TripPhase.FINISHED }
        TripTracker.acceptCarStart(app, delayedToken)
        assertEquals(TripPhase.FINISHED, TripTracker.state.value.phase)

        val locales = app.getSystemService(android.app.LocaleManager::class.java)
        val original = locales.applicationLocales
        try {
            for ((tag, title) in mapOf("fi-FI" to "Matka tallennettu historiaan",
                "sv-FI" to "Resan har sparats i historiken", "en-GB" to "Trip saved to history")) {
                locales.applicationLocales = android.os.LocaleList.forLanguageTags(tag)
                CarTripNotifications.saved(app, savedRow)
                val posted = nm.activeNotifications.single { it.tag == CarTripNotifications.TRIP_PREFIX + savedRow.id }
                assertEquals(title, posted.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
            }
        } finally { locales.applicationLocales = original }
    }
}
