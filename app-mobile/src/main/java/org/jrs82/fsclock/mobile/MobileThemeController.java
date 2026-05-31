package org.jrs82.fsclock.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

final class MobileThemeController {

    static final String KEY_THEME_MODE = "mobile_theme_mode";
    static final String KEY_CHEAP_ELECTRICITY_NOTICE = "mobile_cheap_electricity_notice";
    static final String KEY_CHEAP_ELECTRICITY_THRESHOLD = "mobile_cheap_electricity_threshold";
    static final String KEY_CHEAP_ELECTRICITY_MODE = "mobile_cheap_electricity_mode";
    static final String DEFAULT_CHEAP_ELECTRICITY_THRESHOLD = "5.0";
    static final String CHEAP_MODE_CURRENT = "current";
    static final String CHEAP_MODE_REMAINING_DAY = "remaining_day";
    static final String CHEAP_MODE_ALL_DAY = "all_day";
    static final String KEY_SHOW_ELECTRICITY_WIDGET = "mobile_show_electricity_widget";
    static final String KEY_SHOW_WARNINGS_WIDGET = "mobile_show_warnings_widget";
    static final String KEY_SHOW_SENSORS_WIDGET = "mobile_show_sensors_widget";
    static final String KEY_SHOW_TRAFFIC_WIDGET = "mobile_show_traffic_widget";
    static final String KEY_SHOW_GPS_SPEED_WIDGET = "mobile_show_gps_speed_widget";
    static final String KEY_SHOW_STEPS_WIDGET = "mobile_show_steps_widget";
    static final String KEY_STEPS_ENABLED = "mobile_steps_enabled";
    static final String KEY_SHOW_NEWS_WIDGET = "mobile_show_news_widget";
    static final String KEY_WIDGET_ORDER = "mobile_widget_order";
    static final String KEY_LAST_DEVICE_LATITUDE = "mobile_last_device_latitude";
    static final String KEY_LAST_DEVICE_LONGITUDE = "mobile_last_device_longitude";
    static final String KEY_LAST_DEVICE_LOCATION_TIME = "mobile_last_device_location_time";
    static final String WIDGET_WEATHER = "weather";
    static final String WIDGET_ELECTRICITY = "electricity";
    static final String WIDGET_WARNINGS = "warnings";
    static final String WIDGET_SENSORS = "sensors";
    static final String WIDGET_TRAFFIC = "traffic";
    static final String WIDGET_GPS_SPEED = "gps_speed";
    static final String WIDGET_STEPS = "steps";
    static final String WIDGET_NEWS = "news";
    /** Per-lähde-uutiswidgetin id-etuliite: "news:<feedId>" (esim. "news:hs"). */
    static final String WIDGET_NEWS_FEED_PREFIX = "news:";
    /** Per-lähde-widgetin näkyvyysavaimen etuliite: "mobile_show_news_feed_<id>". */
    static final String KEY_SHOW_NEWS_FEED_PREFIX = "mobile_show_news_feed_";

    static boolean isNewsFeedWidget(String id) {
        return id != null && id.startsWith(WIDGET_NEWS_FEED_PREFIX);
    }

    static String newsFeedIdFromWidget(String widgetId) {
        return isNewsFeedWidget(widgetId)
                ? widgetId.substring(WIDGET_NEWS_FEED_PREFIX.length()) : null;
    }

    static String newsFeedVisibilityKey(String feedId) {
        return KEY_SHOW_NEWS_FEED_PREFIX + feedId;
    }

    static final String DEFAULT_WIDGET_ORDER = WIDGET_WEATHER + ","
            + WIDGET_ELECTRICITY + ","
            + WIDGET_WARNINGS + ","
            + WIDGET_SENSORS + ","
            + WIDGET_TRAFFIC + ","
            + WIDGET_NEWS + ","
            + WIDGET_GPS_SPEED + ","
            + WIDGET_STEPS;
    static final String KEY_USE_AUTOMATIC_LOCATION = "mobile_use_automatic_location";
    static final String KEY_INITIAL_LOCATION_PERMISSION_ASKED = "mobile_initial_location_permission_asked";
    static final String KEY_AUTO_LOCATION_DISPLAY_NAME = "mobile_auto_location_display_name";
    static final String KEY_SENSOR_NAME_BEDROOM = "mobile_sensor_name_bedroom";
    static final String KEY_SENSOR_NAME_LIVINGROOM = "mobile_sensor_name_livingroom";
    static final String KEY_SENSOR_NAME_BALCONY = "mobile_sensor_name_balcony";
    static final String KEY_UPDATE_INTERVAL_MINUTES = "mobile_update_interval_minutes";
    static final String DEFAULT_UPDATE_INTERVAL_MINUTES = "15";
    static final String VALUE_SYSTEM = "system";
    static final String VALUE_LIGHT = "light";
    static final String VALUE_DARK = "dark";

    private MobileThemeController() {}

    static void apply(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                context.getApplicationContext());
        applyValue(prefs.getString(KEY_THEME_MODE, VALUE_SYSTEM));
    }

    static void applyValue(String value) {
        AppCompatDelegate.setDefaultNightMode(nightModeFor(value));
    }

    /** Muuntaa teema-asetuksen ("light"/"dark"/"system") AppCompat-yötilavakioksi. */
    static int nightModeFor(String value) {
        if (VALUE_LIGHT.equals(value)) return AppCompatDelegate.MODE_NIGHT_NO;
        if (VALUE_DARK.equals(value)) return AppCompatDelegate.MODE_NIGHT_YES;
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    /** Tallennettu teema-asetus AppCompat-yötilavakiona, Activityn
     *  setLocalNightMode-pakotusta varten (ettei järjestelmän tumma vuoda läpi). */
    static int nightMode(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                context.getApplicationContext());
        return nightModeFor(prefs.getString(KEY_THEME_MODE, VALUE_SYSTEM));
    }
}
