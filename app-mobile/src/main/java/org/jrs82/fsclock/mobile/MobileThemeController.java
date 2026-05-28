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
    static final String KEY_SHOW_ROAD_CAMERAS_WIDGET = "mobile_show_road_cameras_widget";
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
    static final String WIDGET_ROAD_CAMERAS = "road_cameras";
    static final String WIDGET_NEWS = "news";
    static final String DEFAULT_WIDGET_ORDER = WIDGET_WEATHER + ","
            + WIDGET_ELECTRICITY + ","
            + WIDGET_WARNINGS + ","
            + WIDGET_SENSORS + ","
            + WIDGET_TRAFFIC + ","
            + WIDGET_NEWS + ","
            + WIDGET_GPS_SPEED + ","
            + WIDGET_ROAD_CAMERAS;
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
        int mode;
        if (VALUE_LIGHT.equals(value)) {
            mode = AppCompatDelegate.MODE_NIGHT_NO;
        } else if (VALUE_DARK.equals(value)) {
            mode = AppCompatDelegate.MODE_NIGHT_YES;
        } else {
            mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
        AppCompatDelegate.setDefaultNightMode(mode);
    }
}
