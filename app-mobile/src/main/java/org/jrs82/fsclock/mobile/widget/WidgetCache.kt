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

    // --- Sää: Open-Meteo (näytetään FMI:n rinnalla). Säätila talletetaan osina, jotta
    //     widget_weather_icon_om.png voidaan piirtää uudelleen joka kierroksella teemankestävästi. ---
    fun setWeatherOpenMeteo(
        ctx: Context,
        tempC: Double,
        windSpeed: Double,
        conditionLabel: String,
        condType: String,
        condIntensity: String,
        condNight: Boolean,
        condShower: Boolean,
        atMs: Long,
    ) {
        p(ctx).edit()
            .putString("w_om_temp", tempC.toString())
            .putString("w_om_wind", windSpeed.toString())
            .putString("w_om_cond", conditionLabel)
            .putString("w_om_ctype", condType)
            .putString("w_om_cint", condIntensity)
            .putBoolean("w_om_cnight", condNight)
            .putBoolean("w_om_cshower", condShower)
            .putLong("w_om_at", atMs)
            .apply()
    }
    fun weatherOmTempC(ctx: Context) = p(ctx).getString("w_om_temp", "NaN")?.toDoubleOrNull() ?: Double.NaN
    fun weatherOmWind(ctx: Context) = p(ctx).getString("w_om_wind", "NaN")?.toDoubleOrNull() ?: Double.NaN
    fun weatherOmCondition(ctx: Context) = p(ctx).getString("w_om_cond", "") ?: ""
    fun weatherOmCondType(ctx: Context) = p(ctx).getString("w_om_ctype", "") ?: ""
    fun weatherOmCondIntensity(ctx: Context) = p(ctx).getString("w_om_cint", "") ?: ""
    fun weatherOmCondNight(ctx: Context) = p(ctx).getBoolean("w_om_cnight", false)
    fun weatherOmCondShower(ctx: Context) = p(ctx).getBoolean("w_om_cshower", false)
    fun weatherOmUpdatedAt(ctx: Context) = p(ctx).getLong("w_om_at", 0L)

    // --- Pörssisähkö ---
    fun setElectricity(ctx: Context, snt: Double, atMs: Long) {
        p(ctx).edit().putString("e_snt", snt.toString()).putLong("e_at", atMs).apply()
    }
    fun electricitySnt(ctx: Context) = p(ctx).getString("e_snt", "NaN")?.toDoubleOrNull() ?: Double.NaN
    fun electricityUpdatedAt(ctx: Context) = p(ctx).getLong("e_at", 0L)

    // --- Pörssisähkö: päivän halvin/kallein vartti (snt + varttialun aikaleima ms) ---
    fun setElectricityExtremes(
        ctx: Context,
        minSnt: Double?, minAtMs: Long?,
        maxSnt: Double?, maxAtMs: Long?,
    ) {
        p(ctx).edit()
            .putString("e_min_snt", (minSnt ?: Double.NaN).toString())
            .putLong("e_min_at", minAtMs ?: 0L)
            .putString("e_max_snt", (maxSnt ?: Double.NaN).toString())
            .putLong("e_max_at", maxAtMs ?: 0L)
            .apply()
    }
    fun electricityMinSnt(ctx: Context) = p(ctx).getString("e_min_snt", "NaN")?.toDoubleOrNull() ?: Double.NaN
    fun electricityMinAt(ctx: Context) = p(ctx).getLong("e_min_at", 0L)
    fun electricityMaxSnt(ctx: Context) = p(ctx).getString("e_max_snt", "NaN")?.toDoubleOrNull() ?: Double.NaN
    fun electricityMaxAt(ctx: Context) = p(ctx).getLong("e_max_at", 0L)

    // --- Askeleet ---
    fun setSteps(ctx: Context, steps: Int, goal: Int, atMs: Long) {
        p(ctx).edit().putInt("s_steps", steps).putInt("s_goal", goal).putLong("s_at", atMs).apply()
    }
    fun steps(ctx: Context) = p(ctx).getInt("s_steps", 0)
    fun stepsGoal(ctx: Context) = p(ctx).getInt("s_goal", 10000)
    fun stepsUpdatedAt(ctx: Context) = p(ctx).getLong("s_at", 0L)
    /** Päiväavain (YYYYMMDD) jolle viimeisin s_steps on tallennettu. 0 = ei dataa. */
    fun stepsDayKey(ctx: Context) = p(ctx).getInt("s_day", 0)
    fun setStepsWithDay(ctx: Context, steps: Int, goal: Int, dayKey: Int, atMs: Long) {
        p(ctx).edit()
            .putInt("s_steps", steps).putInt("s_goal", goal)
            .putInt("s_day", dayKey).putLong("s_at", atMs).apply()
    }

    // --- Sähkö: erillinen fetch-aikaleima (pyöräytys vs. hakuleima) ---
    fun electricityFetchAt(ctx: Context) = p(ctx).getLong("e_fetch_at", 0L)
    fun setElectricityFetchAt(ctx: Context, atMs: Long) {
        p(ctx).edit().putLong("e_fetch_at", atMs).apply()
    }

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
            .remove("d_label_$id").remove("d_code_$id").remove("d_json_$id").remove("d_at_$id").apply()
    }

    // --- Lähtö-widgetin data (per appWidgetId) ---
    fun setDepartureData(ctx: Context, id: Int, stopName: String, stopCode: String, json: String, atMs: Long) {
        p(ctx).edit().putString("d_label_$id", stopName).putString("d_code_$id", stopCode)
            .putString("d_json_$id", json).putLong("d_at_$id", atMs).apply()
    }
    fun departureStopLabel(ctx: Context, id: Int) = p(ctx).getString("d_label_$id", "") ?: ""
    fun departureStopCode(ctx: Context, id: Int) = p(ctx).getString("d_code_$id", "") ?: ""
    fun departureJson(ctx: Context, id: Int) = p(ctx).getString("d_json_$id", "[]") ?: "[]"
    fun departureUpdatedAt(ctx: Context, id: Int) = p(ctx).getLong("d_at_$id", 0L)
}
