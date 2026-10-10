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
 *  Endpoint sisältää FMI:n viralliset CAP-varoitukset kolmella kielellä (fi-FI, sv-SE, en-GB).
 *  Suodatetaan status=Actual, expires > nyt ja onset < nyt+24h. Ajat, taso, tyyppi ja alueet
 *  luetaan fi-FI-lohkosta (alueiden nimet ovat suomeksi kaikissa lohkoissa ja merivaroituksen
 *  tunnistus nojaa suomenkieliseen tekstiin); näytettävät tekstit valitun kielen lohkosta. */
public class WarningsClient {

    private static final String TAG = "WarningsClient";
    private static final String URL_FI = "https://feeds.meteoalarm.org/api/v1/warnings/feeds-finland";
    private static final int TIMEOUT_MS = 15000;
    private static final long LOOKAHEAD_MS = 24L * 60L * 60_000L;

    /** @param language sovelluksen kieli {@code fi|sv|en} (AppLocale.language). */
    public List<WeatherWarning> fetch(String language) throws Exception {
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
            return parse(sb.toString(), language);
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    List<WeatherWarning> parse(String json, String language) throws Exception {
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
            String msgType = alert.optString("msgType", "");
            if ("Cancel".equalsIgnoreCase(msgType)) continue;

            JSONArray infos = alert.optJSONArray("info");
            if (infos == null) continue;
            JSONObject info = infoFor(infos, "fi");
            if (info == null) continue;
            JSONObject shown = infoFor(infos, language);
            if (shown == null) shown = info;

            long onset = parseIso(iso, info.optString("onset", null));
            long expires = parseIso(iso, info.optString("expires", null));
            if (expires <= 0L || expires < now) continue;
            if (onset > 0L && onset > cutoff) continue;

            WeatherWarning.Level level = WeatherWarning.Level.UNKNOWN;
            WeatherWarning.AwarenessType awarenessType = WeatherWarning.AwarenessType.UNKNOWN;
            JSONArray params = info.optJSONArray("parameter");
            if (params != null) {
                for (int k = 0; k < params.length(); k++) {
                    JSONObject p = params.optJSONObject(k);
                    if (p == null) continue;
                    String name = p.optString("valueName", "");
                    if ("awareness_level".equalsIgnoreCase(name)) {
                        level = WeatherWarning.Level.fromAwareness(p.optString("value", ""));
                    } else if ("awareness_type".equalsIgnoreCase(name)) {
                        awarenessType = WeatherWarning.AwarenessType.fromParam(p.optString("value", ""));
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
            String areaStr = areas.toString();
            boolean marine = WeatherWarning.detectMarine(info.optString("event", ""), areaStr, emmaIds);
            long effective = parseIso(iso, info.optString("effective", null));
            String severity = info.optString("severity", "");
            String certainty = info.optString("certainty", "");
            String urgency = info.optString("urgency", "");
            String event = textOr(shown, info, "event");
            String senderName = textOr(shown, info, "senderName");
            String web = textOr(shown, info, "web");

            WeatherWarning w = new WeatherWarning(
                    event,
                    textOr(shown, info, "description"),
                    areaStr,
                    onset,
                    expires,
                    level,
                    identifier,
                    marine,
                    awarenessType,
                    severity,
                    certainty,
                    urgency,
                    effective,
                    senderName,
                    web);
            out.add(w);
        }
        Log.d(TAG, "Parsed " + out.size() + " active warnings (" + language + ")");
        return out;
    }

    /** Info-lohko kielikoodin alun perusteella (sv osuu sv-SE:hen ja sv-FI:hin), null jos ei ole. */
    static JSONObject infoFor(JSONArray infos, String language) {
        String want = language == null || language.isEmpty() ? "fi" : language.toLowerCase(Locale.ROOT);
        for (int j = 0; j < infos.length(); j++) {
            JSONObject c = infos.optJSONObject(j);
            if (c == null) continue;
            String lang = c.optString("language", "").toLowerCase(Locale.ROOT);
            if (lang.equals(want) || lang.startsWith(want + "-")) return c;
        }
        return null;
    }

    /** Valitun kielen kenttä; tyhjä → fi-FI-lohkon arvo, jottei kortti jää tyhjäksi. */
    private static String textOr(JSONObject shown, JSONObject fi, String key) {
        String v = shown.isNull(key) ? "" : shown.optString(key, "");
        if (v.trim().isEmpty() && shown != fi) v = fi.isNull(key) ? "" : fi.optString(key, "");
        return v;
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
