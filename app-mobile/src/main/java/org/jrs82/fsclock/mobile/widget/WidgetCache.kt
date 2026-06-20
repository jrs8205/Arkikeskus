package org.jrs82.fsclock.mobile.widget

import android.content.Context

/** Widgettien näyttöarvojen + lähtö-widgetin konfiguroinnin pysyvä varasto (oma SharedPreferences-
 *  tiedosto, erillään muista asetuksista). Worker kirjoittaa, widgetit lukevat. */
object WidgetCache {
    private const val FILE = "arkikeskus_widgets"
    private fun p(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- Sää ---
    fun setWeather(
        ctx: Context,
        place: String,
        tempC: Double,
        conditionLabel: String,
        windSpeed: Double,
        feelsLike: Double,
        precip1h: Double,
        atMs: Long,
    ) {
        p(ctx).edit()
            .putString("w_place", place)
            .putString("w_temp", tempC.toString())
            .putString("w_cond", conditionLabel)
            .putString("w_wind", windSpeed.toString())
            .putString("w_feels", feelsLike.toString())
            .putString("w_precip", precip1h.toString())
            .putLong("w_at", atMs)
            .apply()
    }
    fun weatherPlace(ctx: Context) = p(ctx).getString("w_place", "") ?: ""
    fun weatherTempC(ctx: Context) = p(ctx).getString("w_temp", "NaN")?.toDoubleOrNull() ?: Double.NaN
    fun weatherCondition(ctx: Context) = p(ctx).getString("w_cond", "") ?: ""
    fun weatherWind(ctx: Context) = p(ctx).getString("w_wind", "NaN")?.toDoubleOrNull() ?: Double.NaN
    fun weatherFeelsLike(ctx: Context) = p(ctx).getString("w_feels", "NaN")?.toDoubleOrNull() ?: Double.NaN
    fun weatherPrecip(ctx: Context) = p(ctx).getString("w_precip", "NaN")?.toDoubleOrNull() ?: Double.NaN
    fun weatherUpdatedAt(ctx: Context) = p(ctx).getLong("w_at", 0L)

    // --- Pörssisähkö ---
    fun setElectricity(ctx: Context, snt: Double, atMs: Long) {
        p(ctx).edit().putString("e_snt", snt.toString()).putLong("e_at", atMs).apply()
    }
    fun electricitySnt(ctx: Context) = p(ctx).getString("e_snt", "NaN")?.toDoubleOrNull() ?: Double.NaN
    fun electricityUpdatedAt(ctx: Context) = p(ctx).getLong("e_at", 0L)

    // --- Askeleet ---
    fun setSteps(ctx: Context, steps: Int, goal: Int, atMs: Long) {
        p(ctx).edit().putInt("s_steps", steps).putInt("s_goal", goal).putLong("s_at", atMs).apply()
    }
    fun steps(ctx: Context) = p(ctx).getInt("s_steps", 0)
    fun stepsGoal(ctx: Context) = p(ctx).getInt("s_goal", 10000)
    fun stepsUpdatedAt(ctx: Context) = p(ctx).getLong("s_at", 0L)

    // --- Lähtö-widgetin konfigurointi (per appWidgetId) ---
    fun setDepartureConfig(ctx: Context, id: Int, mode: String, stopId: String, stopName: String) {
        p(ctx).edit().putString("d_mode_$id", mode).putString("d_stopid_$id", stopId)
            .putString("d_stopname_$id", stopName).apply()
    }
    fun departureMode(ctx: Context, id: Int) = p(ctx).getString("d_mode_$id", "") ?: ""
    fun departureStopId(ctx: Context, id: Int) = p(ctx).getString("d_stopid_$id", "") ?: ""
    fun departureStopName(ctx: Context, id: Int) = p(ctx).getString("d_stopname_$id", "") ?: ""
    fun clearDeparture(ctx: Context, id: Int) {
        p(ctx).edit().remove("d_mode_$id").remove("d_stopid_$id").remove("d_stopname_$id")
            .remove("d_label_$id").remove("d_json_$id").remove("d_at_$id").apply()
    }

    // --- Lähtö-widgetin data (per appWidgetId) ---
    fun setDepartureData(ctx: Context, id: Int, stopName: String, json: String, atMs: Long) {
        p(ctx).edit().putString("d_label_$id", stopName).putString("d_json_$id", json)
            .putLong("d_at_$id", atMs).apply()
    }
    fun departureStopLabel(ctx: Context, id: Int) = p(ctx).getString("d_label_$id", "") ?: ""
    fun departureJson(ctx: Context, id: Int) = p(ctx).getString("d_json_$id", "[]") ?: "[]"
    fun departureUpdatedAt(ctx: Context, id: Int) = p(ctx).getLong("d_at_$id", 0L)
}
