package org.jrs82.fsclock.mobile.gps

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit

internal class AutoTripPrefs(private val context: Context) {
    private val prefs = context.getSharedPreferences(TripStore.FILE, Context.MODE_PRIVATE)
    val enabled get() = prefs.getBoolean("car_enabled", false)
    val selected: Set<String> get() = prefs.getStringSet("car_selected", emptySet()).orEmpty()
    private val connected: Set<String> get() = if (prefs.getInt("car_boot", -1) == bootCount(context))
        prefs.getStringSet("car_connected", emptySet()).orEmpty() else emptySet()
    val generation get() = prefs.getLong("car_generation", 0)
    val foundAt get() = prefs.getLong("car_found_wall", System.currentTimeMillis())

    fun enable(value: Boolean) {
        prefs.edit {
            putBoolean("car_enabled", value)
            remove("car_connected")
            putLong("car_generation", generation + 1)
        }
        if (!value) TripTracker.cancelCarWait(context)
        TripTrackingService.onCarSettingChanged()
    }

    fun select(address: String, value: Boolean) {
        val next = if (value) selected + address else selected - address
        prefs.edit {
            putStringSet("car_selected", next)
            putStringSet("car_connected", selectedLinks(connected, next))
        }
    }

    fun connection(address: String, up: Boolean, wall: Long = System.currentTimeMillis(), transport: Int = 0): CarEvent? {
        if (!enabled || address !in selected) return null
        val change = carConnection(true, selected, connected, address, up, transport)
        record(change.connected, change.event, wall)
        return change.event
    }

    fun adapterOff(): CarEvent? {
        if (!enabled || connected.isEmpty()) return null
        record(emptySet(), CarEvent.LOST, System.currentTimeMillis())
        return CarEvent.LOST
    }

    private fun record(next: Set<String>, event: CarEvent?, wall: Long) {
        prefs.edit {
            putInt("car_boot", bootCount(context))
            putStringSet("car_connected", next)
            if (event == CarEvent.FOUND) putLong("car_found_wall", wall)
            if (event != null) putLong("car_generation", generation + 1)
        }
    }

    fun supersedeStart() {
        if (enabled && connected.isNotEmpty()) prefs.edit { putLong("car_generation", generation + 1) }
    }

    fun currentConnection(token: Long) = enabled && token == generation && connected.isNotEmpty()
}

internal fun bootCount(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

internal fun hasCarPermission(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

internal fun canReadCars(context: Context) = Build.VERSION.SDK_INT < 31 ||
    hasCarPermission(context, Manifest.permission.BLUETOOTH_CONNECT)

@SuppressLint("MissingPermission")
internal fun pairedCars(context: Context): Map<String, String>? {
    if (!canReadCars(context)) return null
    return try {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return null
        adapter.bondedDevices.orEmpty().associate { it.address to (it.name?.takeIf(String::isNotBlank) ?: it.address) }
    } catch (_: SecurityException) { null }
}

class CarBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val up = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }
        val prefs = AutoTripPrefs(context)
        if (!prefs.enabled || !canReadCars(context)) return
        val car = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val transport = if (Build.VERSION.SDK_INT >= 33)
            intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_AUTO) else BluetoothDevice.TRANSPORT_AUTO
        val event = prefs.connection(car.address, up, transport = transport) ?: return
        TripTracker.carConnectionChanged(context, event)
    }
}

/** Yksi herätys vain avoimelle katkolle. Vanhentunut herätys tarkistaa nykyisen matkan määräajan. */
class CarTimeoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TripTracker.init(context)
        when (intent.action) {
            RESUME, FINISH -> {
                val wait = TripStore.of(context).carTiming().disconnectWall ?: return
                if (intent.getLongExtra("disconnect", -1) != wait) return
                if (intent.action == FINISH) TripTracker.finish(context)
                else if (!TripTrackingService.start(context, TripTrackingService.ACTION_RESUME))
                    CarTripNotifications.failed(context, org.jrs82.fsclock.R.string.car_service_blocked)
            }
            else -> TripTracker.checkCarDeadline(context)
        }
        goAsync()?.let { result -> TripTracker.archive.completeBroadcast(result::finish) }
    }

    companion object {
        const val RESUME = "org.jrs82.arkikeskus.trip.RESUME_WAIT"
        const val FINISH = "org.jrs82.arkikeskus.trip.FINISH_WAIT"

        fun control(context: Context, action: String, wall: Long): PendingIntent =
            PendingIntent.getBroadcast(context, 0, Intent(context, CarTimeoutReceiver::class.java)
                .setAction(action).setData(android.net.Uri.fromParts("trip-wait", wall.toString(), null))
                .putExtra("disconnect", wall), PendingIntent.FLAG_IMMUTABLE)

        private fun intent(context: Context, flags: Int) = PendingIntent.getBroadcast(context, 0,
            Intent(context, CarTimeoutReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or flags)

        fun schedule(context: Context, delay: Long) {
            val alarms = context.getSystemService(AlarmManager::class.java)
            val at = SystemClock.elapsedRealtime() + delay
            val action = intent(context, PendingIntent.FLAG_UPDATE_CURRENT)
            try {
                if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, action)
                    return
                }
            } catch (_: SecurityException) { /* Oikeus voi muuttua tarkistuksen jälkeen. */ }
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, action)
        }

        fun cancel(context: Context) {
            val action = intent(context, PendingIntent.FLAG_NO_CREATE) ?: return
            context.getSystemService(AlarmManager::class.java).cancel(action)
            action.cancel()
        }
    }
}
