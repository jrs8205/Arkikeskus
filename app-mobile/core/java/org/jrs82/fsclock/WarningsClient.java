package org.jrs82.fsclock;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Hakee MeteoAlarm-feedin Suomen sääoitukset JSON-muodossa.
 *  Endpoint sisältää FMI:n viralliset CAP-varoitukset, kuvaus suomeksi.
 *  Suodatetaan fi-FI, status=Actual, expires > nyt ja onset < nyt+24h. */
public class WarningsClient {

    private static final String TAG = "WarningsClient";
    private static final String URL_FI = "https://feeds.meteoalarm.org/api/v1/warnings/feeds-finland";
    private static final int TIMEOUT_MS = 15000;
    private static final long LOOKAHEAD_MS = 24L * 60L * 60_000L;

    public List<WeatherWarning> fetch() throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(URL_FI).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("MeteoAlarm HTTP " + code);
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
        long now = System.currentTimeMillis();
        long cutoff = now + LOOKAHEAD_MS;
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);

        JSONObject root = new JSONObject(json);
        JSONArray warnings = root.optJSONArray("warnings");
        List<WeatherWarning> out = new ArrayList<>();
        if (warnings == null) return out;

        for (int i = 0; i < warnings.length(); i++) {
            JSONObject wrap = warnings.optJSONObject(i);
            if (wrap == null) continue;
            JSONObject alert = wrap.optJSONObject("alert");
            if (alert == null) continue;
            String status = alert.optString("status", "");
            if (!"Actual".equalsIgnoreCase(status)) continue;
            String identifier = alert.optString("identifier", "");

            JSONArray infos = alert.optJSONArray("info");
            if (infos == null) continue;
            JSONObject info = null;
            for (int j = 0; j < infos.length(); j++) {
                JSONObject c = infos.optJSONObject(j);
                if (c == null) continue;
                if ("fi-FI".equalsIgnoreCase(c.optString("language", ""))) {
                    info = c;
                    break;
                }
            }
            if (info == null) continue;

            long onset = parseIso(iso, info.optString("onset", null));
            long expires = parseIso(iso, info.optString("expires", null));
            if (expires <= 0L || expires < now) continue;
            if (onset > 0L && onset > cutoff) continue;

            WeatherWarning.Level level = WeatherWarning.Level.UNKNOWN;
            JSONArray params = info.optJSONArray("parameter");
            if (params != null) {
                for (int k = 0; k < params.length(); k++) {
                    JSONObject p = params.optJSONObject(k);
                    if (p == null) continue;
                    if ("awareness_level".equalsIgnoreCase(p.optString("valueName", ""))) {
                        level = WeatherWarning.Level.fromAwareness(p.optString("value", ""));
                        break;
                    }
                }
            }

            StringBuilder areas = new StringBuilder();
            ArrayList<String> emmaIds = new ArrayList<>();
            JSONArray areaArr = info.optJSONArray("area");
            if (areaArr != null) {
                for (int a = 0; a < areaArr.length(); a++) {
                    JSONObject ar = areaArr.optJSONObject(a);
                    if (ar == null) continue;
                    String desc = ar.optString("areaDesc", "");
                    if (!desc.isEmpty()) {
                        if (areas.length() > 0) areas.append(", ");
                        areas.append(desc);
                    }
                    JSONArray gc = ar.optJSONArray("geocode");
                    if (gc != null) {
                        for (int g = 0; g < gc.length(); g++) {
                            JSONObject go = gc.optJSONObject(g);
                            if (go == null) continue;
                            if ("EMMA_ID".equalsIgnoreCase(go.optString("valueName", ""))) {
                                emmaIds.add(go.optString("value", ""));
                            }
                        }
                    }
                }
            }
            String event = info.optString("event", "");
            String areaStr = areas.toString();
            boolean marine = WeatherWarning.detectMarine(event, areaStr, emmaIds);

            WeatherWarning w = new WeatherWarning(
                    event,
                    info.optString("description", ""),
                    areaStr,
                    onset,
                    expires,
                    level,
                    identifier,
                    marine);
            out.add(w);
        }
        Log.d(TAG, "Parsed " + out.size() + " active fi-FI warnings");
        return out;
    }

    private static long parseIso(SimpleDateFormat iso, String s) {
        if (s == null || s.isEmpty()) return 0L;
        try {
            Date d = iso.parse(s);
            return d == null ? 0L : d.getTime();
        } catch (Exception e) {
            return 0L;
        }
    }
}
