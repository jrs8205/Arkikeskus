package org.jrs82.fsclock;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Hakee FMI:n GeoServerin 5 vrk:n varoitukset ja parsii JOKAISEN featuren WeatherWarningiksi
 *  (areaDesc=maakunta, level=severity, event/awarenessType=warning_context, details=prob/physical).
 *  Sivun varoituslähde (korvaa MeteoAlarmin sivulla; MeteoAlarm jää ilmoituksiin). */
public class FmiWarningsClient {

    private static final String TAG = "FmiWarnings";
    private static final String URL =
        "https://www.ilmatieteenlaitos.fi/geoserver/alert/ows?service=WFS&version=2.0.0"
        + "&request=GetFeature&typeName=alert:weather_finland_active_all&outputFormat=application/json";
    private static final String FMI_WEB = "https://www.ilmatieteenlaitos.fi/varoitukset";
    private static final int TIMEOUT_MS = 15000;

    public List<WeatherWarning> fetch() throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(URL).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("FMI GeoServer HTTP " + code);
            in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return parse(sb.toString());
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    List<WeatherWarning> parse(String json) throws Exception {
        List<WeatherWarning> out = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray feats = root.optJSONArray("features");
        if (feats == null) return out;
        for (int i = 0; i < feats.length(); i++) {
            JSONObject f = feats.optJSONObject(i);
            if (f == null) continue;
            JSONObject p = f.optJSONObject("properties");
            if (p == null) continue;
            String context = p.optString("warning_context", "");
            if (context.isEmpty()) continue;

            String[] ev = eventFor(context); // {event, awarenessTypeName, marineFlag}
            WeatherWarning.AwarenessType type = WeatherWarning.AwarenessType.valueOf(ev[1]);
            boolean marine = "1".equals(ev[2]);
            WeatherWarning.Level level = WeatherWarning.Level.fromFmiSeverity(p.optString("severity", ""));
            String area = FmiCounties.regionForRef(p.optString("reference", ""));

            int prob = p.has("actualization_probability") && !p.isNull("actualization_probability")
                    ? p.optInt("actualization_probability", -1) : -1;
            double pv = p.has("physical_value") && !p.isNull("physical_value")
                    ? p.optDouble("physical_value", Double.NaN) : Double.NaN;
            String physicalText = FmiWarningDetailsClient.formatPhysical(pv, p.optString("physical_unit", ""));
            String desc = FmiWarningDetailsClient.decodeEntities(p.optString("info_fi", ""));
            long onset = FmiWarningDetailsClient.parseIsoUtc(p.optString("effective_from", null));
            long expires = FmiWarningDetailsClient.parseIsoUtc(p.optString("effective_until", null));
            String id = p.optString("identifier", context + "|" + area + "|" + onset);

            WeatherWarning w = new WeatherWarning(
                    ev[0], desc, area, onset, expires, level, id, marine,
                    type, "", "", "", 0L, "Ilmatieteen laitos", FMI_WEB,
                    new WarningDetails(prob, physicalText, ""));
            out.add(w);
        }
        Log.d(TAG, "Parsed " + out.size() + " FMI 5d warnings");
        return out;
    }

    /** warning_context → {event, AwarenessType.name(), marine("1"/"0")}. */
    static String[] eventFor(String context) {
        switch (context) {
            case "forest-fire-weather": return new String[]{"Maastopalovaroitus", "FOREST_FIRE", "0"};
            case "hot-weather":         return new String[]{"Hellevaroitus", "HIGH_TEMPERATURE", "0"};
            case "rain":                return new String[]{"Sadevaroitus", "RAIN", "0"};
            case "thunder-storm":       return new String[]{"Ukkosvaroitus", "THUNDERSTORM", "0"};
            case "sea-thunder-storm":   return new String[]{"Huomautus veneilijöille", "THUNDERSTORM", "1"};
            case "uv-note":             return new String[]{"UV-tiedote", "UV", "0"};
            case "cold-weather":        return new String[]{"Pakkasvaroitus", "LOW_TEMPERATURE", "0"};
            case "wind":                return new String[]{"Tuulivaroitus", "WIND", "0"};
            case "flood":               return new String[]{"Tulvavaroitus", "FLOOD", "0"};
            default:                    return new String[]{"Varoitus", "UNKNOWN", "0"};
        }
    }
}
