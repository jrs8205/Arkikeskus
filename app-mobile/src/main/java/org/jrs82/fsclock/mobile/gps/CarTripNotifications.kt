package org.jrs82.fsclock.mobile.gps

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import org.jrs82.fsclock.R
import org.jrs82.fsclock.db.TripEntity
import org.jrs82.fsclock.mobile.AppLocale
import org.jrs82.fsclock.mobile.MobileComposeMainActivity
import org.jrs82.fsclock.mobile.WorkoutTrackingService
import java.time.LocalDate
import java.time.ZoneId

internal object CarTripNotifications {
    const val SETTINGS = "GPS_CAR_SETTINGS"
    const val TRIP_PREFIX = "GPS_HISTORY_TRIP:"
    private const val CHANNEL = "gps_auto_trips"

    fun failureReason(context: Context): Int? = when {
        !canReadCars(context) -> R.string.car_permission_nearby
        !hasCarPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) -> R.string.car_permission_fine
        !hasCarPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> R.string.car_permission_background
        !context.getSystemService(LocationManager::class.java).isLocationEnabled -> R.string.car_location_off
        !context.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER) -> R.string.gps_unavailable
        else -> null
    }

    private const val WAIT_TAG = "car_disconnect"

    @SuppressLint("MissingPermission")
    fun waiting(context: Context, wall: Long) {
        val texts = AppLocale.localizedContext(context)
        if (!canNotify(texts)) return
        val manager = texts.getSystemService(NotificationManager::class.java)
        TripTrackingService.createChannels(texts)
        val notice = NotificationCompat.Builder(texts, TripTrackingService.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.mobile_ic_car_24).setContentTitle(texts.getString(R.string.car_waiting))
            .setContentIntent(open(texts, "SPEEDOMETER")).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(0, texts.getString(R.string.gps_action_resume), CarTimeoutReceiver.control(texts, CarTimeoutReceiver.RESUME, wall))
            .addAction(0, texts.getString(R.string.gps_action_finish), CarTimeoutReceiver.control(texts, CarTimeoutReceiver.FINISH, wall))
            .build()
        try { manager.notify(WAIT_TAG, 64, notice) } catch (_: SecurityException) {}
    }

    fun clearWaiting(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(WAIT_TAG, 64)

    fun failed(context: Context, reason: Int) {
        val texts = AppLocale.localizedContext(context)
        post(texts, SETTINGS, R.string.car_start_failed, texts.getString(reason))
    }

    fun saved(context: Context, row: TripEntity) {
        val texts = AppLocale.localizedContext(context)
        val locale = AppLocale.current(texts)
        val zone = ZoneId.systemDefault()
        val format = HistoryFormat(texts, locale, zone, LocalDate.now(zone), DateFormat.is24HourFormat(texts))
        post(texts, TRIP_PREFIX + row.id, R.string.car_trip_saved,
            "${format.interval(row)} · ${TripFormat.distance(row.distanceM, locale)} · ${TripFormat.clock(row.totalMs)}")
    }

    private fun canNotify(context: Context) = (Build.VERSION.SDK_INT < 33 ||
        hasCarPermission(context, Manifest.permission.POST_NOTIFICATIONS)) &&
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun open(context: Context, target: String): PendingIntent {
        val open = Intent(context, MobileComposeMainActivity::class.java)
            .setData(Uri.Builder().scheme("arkikeskus-trip").authority("open").appendPath(target).build())
            .putExtra(WorkoutTrackingService.EXTRA_OPEN_SECTION, target)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, target: String, title: Int, body: String) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.car_channel),
            NotificationManager.IMPORTANCE_DEFAULT))
        try {
            manager.notify(target, 63, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.mobile_ic_car_24).setContentTitle(context.getString(title)).setContentText(body)
                .setContentIntent(open(context, target)).setOnlyAlertOnce(true).setAutoCancel(true).build())
        } catch (_: SecurityException) { /* Ilmoituslupa peruttiin kesken toimituksen. */ }
    }
}
