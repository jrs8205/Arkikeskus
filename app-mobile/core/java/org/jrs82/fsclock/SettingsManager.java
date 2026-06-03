package org.jrs82.fsclock;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Yhden API:n takaa SharedPreferences-luvut. ClockController ja muut eivät
 *  kosketa SharedPreferenceseihin suoraan. */
public final class SettingsManager {

    public static final String KEY_HOME_PLACE = "home_place";
    public static final String KEY_HOME_LATITUDE = "home_latitude";
    public static final String KEY_HOME_LONGITUDE = "home_longitude";
    public static final String KEY_DAY_BRIGHTNESS = "day_brightness";
    public static final String KEY_NIGHT_BRIGHTNESS = "night_brightness";
    public static final String KEY_DAY_MORNING_HOUR = "day_morning_hour";
    public static final String KEY_NIGHT_EVENING_HOUR = "night_evening_hour";
    public static final String KEY_NIGHT_RED_TINT = "night_red_tint";
    public static final String KEY_LAST_FMI_UPDATE = "last_successful_fmi_update";
    public static final String KEY_TEST_MODE_TYPE = "test_mode_type";
    public static final String KEY_TEST_MODE_UNTIL = "test_mode_until";
    public static final String KEY_RETENTION_DAYS = "retention_days";
    public static final String KEY_FAVORITE_PLACES = "favorite_places";
    public static final String KEY_RUUVI_MAC_BEDROOM = "ruuvi_mac_bedroom";
    public static final String KEY_RUUVI_MAC_LIVINGROOM = "ruuvi_mac_livingroom";
    public static final String KEY_RUUVI_MAC_BALCONY = "ruuvi_mac_balcony";

    public static final String RUUVI_SLOT_BEDROOM = "bedroom";
    public static final String RUUVI_SLOT_LIVINGROOM = "livingroom";
    public static final String RUUVI_SLOT_BALCONY = "balcony";

    public static final int TEST_NONE = 0;
    public static final int TEST_DAY = 1;
    public static final int TEST_NIGHT = 2;
    public static final int TEST_WARNING = 3;
    public static final int TEST_OFFLINE = 4;

    public static final String DEFAULT_HOME_PLACE = "Vantaa";
    public static final int DEFAULT_DAY_BRIGHTNESS = 60;
    public static final int DEFAULT_NIGHT_BRIGHTNESS = 8;
    public static final int DEFAULT_MORNING_HOUR = 6;
    public static final int DEFAULT_EVENING_HOUR = 21;
    public static final long TEST_MODE_DURATION_MS = 30L * 60L * 1000L;
    public static final int DEFAULT_RETENTION_DAYS = 1095;

    private static SettingsManager instance;
    private SharedPreferences prefs;

    private SettingsManager() {}

    public static synchronized SettingsManager get() {
        if (instance == null) instance = new SettingsManager();
        return instance;
    }

    public synchronized void init(Context appCtx) {
        if (prefs == null) {
            prefs = PreferenceManager.getDefaultSharedPreferences(appCtx.getApplicationContext());
        }
    }

    private SharedPreferences sp() {
        if (prefs == null) {
            throw new IllegalStateException("SettingsManager.init(context) tulee kutsua ensin");
        }
        return prefs;
    }

    // ---- Paikkakunta ----
    public String getHomePlace() {
        return sp().getString(KEY_HOME_PLACE, DEFAULT_HOME_PLACE);
    }
    public void setHomePlace(String v) {
        sp().edit().putString(KEY_HOME_PLACE, v == null ? DEFAULT_HOME_PLACE : v).apply();
    }

    public void setHomeCoordinates(double latitude, double longitude) {
        sp().edit()
                .putFloat(KEY_HOME_LATITUDE, (float) latitude)
                .putFloat(KEY_HOME_LONGITUDE, (float) longitude)
                .apply();
    }

    public void clearHomeCoordinates() {
        sp().edit()
                .remove(KEY_HOME_LATITUDE)
                .remove(KEY_HOME_LONGITUDE)
                .apply();
    }

    public boolean hasHomeCoordinates() {
        return sp().contains(KEY_HOME_LATITUDE) && sp().contains(KEY_HOME_LONGITUDE);
    }

    public double getHomeLatitude() {
        return sp().getFloat(KEY_HOME_LATITUDE, Float.NaN);
    }

    public double getHomeLongitude() {
        return sp().getFloat(KEY_HOME_LONGITUDE, Float.NaN);
    }

    /** Kotipaikkakunnan DB-kanavanimi. "Vantaa" -> "fmi_vantaa". */
    public String homeChannel() {
        return channelForPlace(getHomePlace());
    }

    /** Normalisoi paikkakunnan nimen DB-kanavanimeksi.
     *  Pienet kirjaimet, ä/Ä -> a, ö/Ö -> o, å/Å -> a, välilyönnit alaviivoiksi,
     *  prefix "fmi_". Tyhjästä tai null:sta tulee "fmi_vantaa" (oletuspaikkakunta). */
    public static String channelForPlace(String place) {
        if (place == null) place = DEFAULT_HOME_PLACE;
        String trimmed = place.trim();
        if (trimmed.isEmpty()) trimmed = DEFAULT_HOME_PLACE;
        StringBuilder sb = new StringBuilder("fmi_");
        for (int i = 0; i < trimmed.length(); i++) {
            char c = Character.toLowerCase(trimmed.charAt(i));
            switch (c) {
                case 'ä': case 'å': sb.append('a'); break;
                case 'ö': sb.append('o'); break;
                case ' ': case '\t': sb.append('_'); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---- Kirkkaus ----
    public int getDayBrightness() {
        return clampInt(sp().getInt(KEY_DAY_BRIGHTNESS, DEFAULT_DAY_BRIGHTNESS), 1, 100);
    }
    public void setDayBrightness(int v) {
        sp().edit().putInt(KEY_DAY_BRIGHTNESS, clampInt(v, 1, 100)).apply();
    }
    public int getNightBrightness() {
        return clampInt(sp().getInt(KEY_NIGHT_BRIGHTNESS, DEFAULT_NIGHT_BRIGHTNESS), 1, 100);
    }
    public void setNightBrightness(int v) {
        sp().edit().putInt(KEY_NIGHT_BRIGHTNESS, clampInt(v, 1, 100)).apply();
    }

    // ---- Päivän/yön alkamisajat (tunti 0-23) ----
    public int getMorningHour() {
        return clampInt(sp().getInt(KEY_DAY_MORNING_HOUR, DEFAULT_MORNING_HOUR), 0, 23);
    }
    public void setMorningHour(int v) {
        sp().edit().putInt(KEY_DAY_MORNING_HOUR, clampInt(v, 0, 23)).apply();
    }
    public int getEveningHour() {
        return clampInt(sp().getInt(KEY_NIGHT_EVENING_HOUR, DEFAULT_EVENING_HOUR), 0, 23);
    }
    public void setEveningHour(int v) {
        sp().edit().putInt(KEY_NIGHT_EVENING_HOUR, clampInt(v, 0, 23)).apply();
    }

    // ---- Yön punainen sävy (toteutus Vaihe 4:ssä, asetus tallennetaan jo nyt) ----
    public boolean isNightRedTint() {
        return sp().getBoolean(KEY_NIGHT_RED_TINT, false);
    }

    // ---- Viimeisin onnistunut FMI-päivitys ----
    public long getLastSuccessfulFmiUpdate() {
        return sp().getLong(KEY_LAST_FMI_UPDATE, 0L);
    }
    public void setLastSuccessfulFmiUpdate(long timestampMs) {
        sp().edit().putLong(KEY_LAST_FMI_UPDATE, timestampMs).apply();
    }

    // ---- Testitila ----
    public int getActiveTestMode() {
        long until = sp().getLong(KEY_TEST_MODE_UNTIL, 0L);
        if (until <= System.currentTimeMillis()) return TEST_NONE;
        return sp().getInt(KEY_TEST_MODE_TYPE, TEST_NONE);
    }
    public long getTestModeUntil() {
        return sp().getLong(KEY_TEST_MODE_UNTIL, 0L);
    }
    public void setTestMode(int type) {
        if (type == TEST_NONE) {
            clearTestMode();
            return;
        }
        sp().edit()
                .putInt(KEY_TEST_MODE_TYPE, type)
                .putLong(KEY_TEST_MODE_UNTIL, System.currentTimeMillis() + TEST_MODE_DURATION_MS)
                .apply();
    }
    public void clearTestMode() {
        sp().edit()
                .putInt(KEY_TEST_MODE_TYPE, TEST_NONE)
                .putLong(KEY_TEST_MODE_UNTIL, 0L)
                .apply();
    }

    // ---- Säilytysaika (päivinä) ----
    public int getRetentionDays() {
        // ListPreference tallentaa String-arvona
        String raw = sp().getString(KEY_RETENTION_DAYS, String.valueOf(DEFAULT_RETENTION_DAYS));
        try {
            int v = Integer.parseInt(raw);
            return v > 0 ? v : DEFAULT_RETENTION_DAYS;
        } catch (NumberFormatException e) {
            return DEFAULT_RETENTION_DAYS;
        }
    }

    // ---- Suosikkipaikkakunnat ----
    /** Lukee suosikit lisäysjärjestyksessä. Tallennusmuoto = '|'-erotettu merkkijono.  */
    public List<String> getFavoritePlaces() {
        String raw = sp().getString(KEY_FAVORITE_PLACES, "");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String s : raw.split("\\|")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
    public boolean isFavoritePlace(String name) {
        if (name == null) return false;
        String key = name.trim().toLowerCase(Locale.ROOT);
        for (String f : getFavoritePlaces()) {
            if (f.toLowerCase(Locale.ROOT).equals(key)) return true;
        }
        return false;
    }
    /** Lisää uuden suosikin jos puuttuu. Palauttaa true jos lista muuttui. */
    public boolean addFavoritePlace(String name) {
        if (name == null) return false;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return false;
        LinkedHashSet<String> set = new LinkedHashSet<>();
        String key = trimmed.toLowerCase(Locale.ROOT);
        for (String f : getFavoritePlaces()) {
            if (!f.toLowerCase(Locale.ROOT).equals(key)) set.add(f);
        }
        set.add(trimmed);
        saveFavorites(set);
        return true;
    }
    /** Poistaa suosikin. Palauttaa true jos lista muuttui. */
    public boolean removeFavoritePlace(String name) {
        if (name == null) return false;
        String key = name.trim().toLowerCase(Locale.ROOT);
        LinkedHashSet<String> set = new LinkedHashSet<>();
        boolean removed = false;
        for (String f : getFavoritePlaces()) {
            if (f.toLowerCase(Locale.ROOT).equals(key)) removed = true;
            else set.add(f);
        }
        if (removed) saveFavorites(set);
        return removed;
    }
    private void saveFavorites(LinkedHashSet<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) sb.append('|');
            sb.append(s);
        }
        sp().edit().putString(KEY_FAVORITE_PLACES, sb.toString()).apply();
    }

    // ---- Ruuvi-slotit ----
    private static String slotKey(String slot) {
        if (RUUVI_SLOT_BEDROOM.equals(slot)) return KEY_RUUVI_MAC_BEDROOM;
        if (RUUVI_SLOT_LIVINGROOM.equals(slot)) return KEY_RUUVI_MAC_LIVINGROOM;
        if (RUUVI_SLOT_BALCONY.equals(slot)) return KEY_RUUVI_MAC_BALCONY;
        return null;
    }
    public String getRuuviMac(String slot) {
        String k = slotKey(slot);
        return k == null ? null : sp().getString(k, null);
    }
    public void setRuuviMac(String slot, String mac) {
        String k = slotKey(slot);
        if (k == null) return;
        if (mac == null || mac.trim().isEmpty()) sp().edit().remove(k).apply();
        else sp().edit().putString(k, mac.trim().toUpperCase(Locale.ROOT)).apply();
    }
    /** Palauttaa slotin nimen jolle MAC on määritetty, tai null jos vapaa. */
    public String slotForMac(String mac) {
        if (mac == null) return null;
        String up = mac.trim().toUpperCase(Locale.ROOT);
        if (up.equals(sp().getString(KEY_RUUVI_MAC_BEDROOM, null))) return RUUVI_SLOT_BEDROOM;
        if (up.equals(sp().getString(KEY_RUUVI_MAC_LIVINGROOM, null))) return RUUVI_SLOT_LIVINGROOM;
        if (up.equals(sp().getString(KEY_RUUVI_MAC_BALCONY, null))) return RUUVI_SLOT_BALCONY;
        return null;
    }

    // ---- Listenerit ----
    public void registerListener(SharedPreferences.OnSharedPreferenceChangeListener l) {
        sp().registerOnSharedPreferenceChangeListener(l);
    }
    public void unregisterListener(SharedPreferences.OnSharedPreferenceChangeListener l) {
        sp().unregisterOnSharedPreferenceChangeListener(l);
    }

    private static int clampInt(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
