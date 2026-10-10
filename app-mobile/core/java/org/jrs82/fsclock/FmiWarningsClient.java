package org.jrs82.fsclock;

import android.util.Log;

import androidx.annotation.StringRes;

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
 *  Sivun varoituslähde (korvaa MeteoAlarmin sivulla; MeteoAlarm jää ilmoituksiin).
 *  {@code event} on FMI:n konetunniste (esim. "hot-weather"); näyttönimi {@link #eventFor}.nameRes. */
public class FmiWarningsClient {

    private static final String TAG = "FmiWarnings";
    private static final String URL =
        "https://www.ilmatieteenlaitos.fi/geoserver/alert/ows?service=WFS&version=2.0.0"
        + "&request=GetFeature&typeName=alert:weather_finland_active_all&outputFormat=application/json";
    private static final String FMI_WEB_FI = "https://www.ilmatieteenlaitos.fi/varoitukset";
    private static final String FMI_WEB_SV = "https://sv.ilmatieteenlaitos.fi/varningar";
    private static final String FMI_WEB_EN = "https://en.ilmatieteenlaitos.fi/warnings";
    private static final int TIMEOUT_MS = 15000;

    /** FMI-varoituslaji: näyttönimi, ilmiötyyppi ja merivaroituslippu. */
    public static final class FmiEvent {
        @StringRes public final int nameRes;
        public final WeatherWarning.AwarenessType type;
        public final boolean marine;

        FmiEvent(int nameRes, WeatherWarning.AwarenessType type, boolean marine) {
            this.nameRes = nameRes;
            this.type = type;
            this.marine = marine;
        }
    }

    /** @param infoKey tekstikenttä {@code info_fi|info_sv|info_en} (AppLocale.fmiInfoKey). */
    public List<WeatherWarning> fetch(String infoKey) throws Exception {
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
            return parse(sb.toString(), infoKey);
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    List<WeatherWarning> parse(String json, String infoKey) throws Exception {
        List<WeatherWarning> out = new ArrayList<>();
        String web = webFor(infoKey);
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

            FmiEvent ev = eventFor(context);
            WeatherWarning.Level level = WeatherWarning.Level.fromFmiSeverity(p.optString("severity", ""));
            String area = FmiCounties.regionForRef(p.optString("reference", ""));

            int prob = p.has("actualization_probability") && !p.isNull("actualization_probability")
                    ? p.optInt("actualization_probability", -1) : -1;
            double pv = p.has("physical_value") && !p.isNull("physical_value")
                    ? p.optDouble("physical_value", Double.NaN) : Double.NaN;
            String unit = p.isNull("physical_unit") ? "" : p.optString("physical_unit", "");
            String desc = FmiWarningDetailsClient.infoText(p, infoKey);
            long onset = FmiWarningDetailsClient.parseIsoUtc(p.optString("effective_from", null));
            long expires = FmiWarningDetailsClient.parseIsoUtc(p.optString("effective_until", null));
            String id = p.optString("identifier", context + "|" + area + "|" + onset);

            WeatherWarning w = new WeatherWarning(
                    context, desc, area, onset, expires, level, id, ev.marine,
                    ev.type, "", "", "", 0L, "", web,
                    new WarningDetails(prob, pv, unit, ""));
            out.add(w);
        }
        Log.d(TAG, "Parsed " + out.size() + " FMI 5d warnings");
        return out;
    }

    /** warning_context → näyttönimi + tyyppi + merilippu. Tuntematon laji → yleinen "Varoitus". */
    public static FmiEvent eventFor(String context) {
        switch (context == null ? "" : context) {
            case "forest-fire-weather": return new FmiEvent(R.string.warning_event_forest_fire, WeatherWarning.AwarenessType.FOREST_FIRE, false);
            case "hot-weather":         return new FmiEvent(R.string.warning_event_heat, WeatherWarning.AwarenessType.HIGH_TEMPERATURE, false);
            case "rain":                return new FmiEvent(R.string.warning_event_rain, WeatherWarning.AwarenessType.RAIN, false);
            case "thunder-storm":       return new FmiEvent(R.string.warning_event_thunderstorm, WeatherWarning.AwarenessType.THUNDERSTORM, false);
            case "sea-thunder-storm":   return new FmiEvent(R.string.warning_event_boaters, WeatherWarning.AwarenessType.THUNDERSTORM, true);
            case "uv-note":             return new FmiEvent(R.string.warning_event_uv, WeatherWarning.AwarenessType.UV, false);
            case "cold-weather":        return new FmiEvent(R.string.warning_event_cold, WeatherWarning.AwarenessType.LOW_TEMPERATURE, false);
            case "wind":                return new FmiEvent(R.string.warning_event_wind, WeatherWarning.AwarenessType.WIND, false);
            case "flood":               return new FmiEvent(R.string.warning_event_flood, WeatherWarning.AwarenessType.FLOOD, false);
            default:                    return new FmiEvent(R.string.warning_event_generic, WeatherWarning.AwarenessType.UNKNOWN, false);
        }
    }

    /** FMI:n varoitussivu samalla kielellä kuin teksti. */
    static String webFor(String infoKey) {
        if ("info_sv".equals(infoKey)) return FMI_WEB_SV;
        if ("info_en".equals(infoKey)) return FMI_WEB_EN;
        return FMI_WEB_FI;
    }
}
