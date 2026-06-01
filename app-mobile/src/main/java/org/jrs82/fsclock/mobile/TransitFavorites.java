package org.jrs82.fsclock.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Joukkoliikenteen suosikit (linjat + pysäkit) SharedPreferenceseissä JSON-listoina.
 *  Kevyt; ei Roomia/migraatiota (vrt. sovelluksen muu SharedPreferences-asetustyyli). */
final class TransitFavorites {

    private static final String KEY_LINES = "transit_fav_lines";
    private static final String KEY_STOPS = "transit_fav_stops";

    static final class FavStop {
        final String gtfsId;
        final String name;
        FavStop(String gtfsId, String name) { this.gtfsId = gtfsId; this.name = name; }
    }

    private TransitFavorites() {}

    private static SharedPreferences prefs(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    // --- Linjat ---

    static List<RouteHit> getLines(Context ctx) {
        List<RouteHit> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY_LINES, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new RouteHit(o.optString("id", ""), o.optString("short", ""),
                        o.optString("long", ""), o.optString("mode", "")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    static boolean isLineFav(Context ctx, String routeGtfsId) {
        if (routeGtfsId == null || routeGtfsId.isEmpty()) return false;
        for (RouteHit r : getLines(ctx)) if (routeGtfsId.equals(r.gtfsId)) return true;
        return false;
    }

    /** Lisää/poistaa suosikkilinjan. Palauttaa uuden tilan (true = suosikki). */
    static boolean toggleLineFav(Context ctx, String routeGtfsId, String shortName,
                                 String longName, String mode) {
        if (routeGtfsId == null || routeGtfsId.isEmpty()) return false;
        List<RouteHit> lines = getLines(ctx);
        boolean removed = false;
        JSONArray arr = new JSONArray();
        for (RouteHit r : lines) {
            if (routeGtfsId.equals(r.gtfsId)) { removed = true; continue; }
            arr.put(lineJson(r.gtfsId, r.shortName, r.longName, r.mode));
        }
        boolean nowFav = !removed;
        if (nowFav) arr.put(lineJson(routeGtfsId, shortName, longName, mode));
        prefs(ctx).edit().putString(KEY_LINES, arr.toString()).apply();
        return nowFav;
    }

    private static JSONObject lineJson(String id, String s, String l, String mode) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id == null ? "" : id);
            o.put("short", s == null ? "" : s);
            o.put("long", l == null ? "" : l);
            o.put("mode", mode == null ? "" : mode);
        } catch (Exception ignored) { }
        return o;
    }

    // --- Pysäkit ---

    static List<FavStop> getStops(Context ctx) {
        List<FavStop> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY_STOPS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new FavStop(o.optString("id", ""), o.optString("name", "")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    static boolean isStopFav(Context ctx, String stopGtfsId) {
        if (stopGtfsId == null || stopGtfsId.isEmpty()) return false;
        for (FavStop s : getStops(ctx)) if (stopGtfsId.equals(s.gtfsId)) return true;
        return false;
    }

    /** Lisää/poistaa suosikkipysäkin. Palauttaa uuden tilan (true = suosikki). */
    static boolean toggleStopFav(Context ctx, String stopGtfsId, String name) {
        if (stopGtfsId == null || stopGtfsId.isEmpty()) return false;
        List<FavStop> stops = getStops(ctx);
        boolean removed = false;
        JSONArray arr = new JSONArray();
        for (FavStop s : stops) {
            if (stopGtfsId.equals(s.gtfsId)) { removed = true; continue; }
            arr.put(stopJson(s.gtfsId, s.name));
        }
        boolean nowFav = !removed;
        if (nowFav) arr.put(stopJson(stopGtfsId, name));
        prefs(ctx).edit().putString(KEY_STOPS, arr.toString()).apply();
        return nowFav;
    }

    private static JSONObject stopJson(String id, String name) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id == null ? "" : id);
            o.put("name", name == null ? "" : name);
        } catch (Exception ignored) { }
        return o;
    }
}
