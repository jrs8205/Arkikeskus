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
import java.util.TimeZone;

/** Hakee FMI:n oman GeoServerin varoituslayerin (alert:weather_finland_active_all) ja parsii
 *  per-feature-detaljit (todennäköisyys, fyysinen arvo, info_fi). Käytetään MeteoAlarm-varoitusten
 *  RIKASTAMISEEN (ei korvaa MeteoAlarmia: maakuntanimet/tyyppi/taso tulevat sieltä). */
public class FmiWarningDetailsClient {

    private static final String TAG = "FmiWarnDetails";
    private static final String URL =
        "https://www.ilmatieteenlaitos.fi/geoserver/alert/ows?service=WFS&version=2.0.0"
        + "&request=GetFeature&typeName=alert:weather_finland_active_all&outputFormat=application/json";
    private static final int TIMEOUT_MS = 15000;

    public List<FmiWarningDetail> fetch() throws Exception {
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

    List<FmiWarningDetail> parse(String json) throws Exception {
        List<FmiWarningDetail> out = new ArrayList<>();
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
            int prob = p.has("actualization_probability") && !p.isNull("actualization_probability")
                    ? p.optInt("actualization_probability", -1) : -1;
            double pv = p.has("physical_value") && !p.isNull("physical_value")
                    ? p.optDouble("physical_value", Double.NaN) : Double.NaN;
            String unit = p.optString("physical_unit", "");
            String physicalText = formatPhysical(pv, unit);
            String detailText = decodeEntities(p.optString("info_fi", ""));
            long from = parseIsoUtc(p.optString("effective_from", null));
            long until = parseIsoUtc(p.optString("effective_until", null));
            out.add(new FmiWarningDetail(context, from, until, prob, pv, physicalText, detailText));
        }
        Log.d(TAG, "Parsed " + out.size() + " FMI warning details");
        return out;
    }

    /** Muotoilee fyysisen arvon suomeksi yksikön perusteella. */
    public static String formatPhysical(double value, String unit) {
        if (Double.isNaN(value) || unit == null || unit.isEmpty()) return "";
        String v = (value == Math.rint(value)) ? String.valueOf((long) value) : String.valueOf(value);
        switch (unit) {
            case "celsius": return "Lämpötila jopa " + v + " °C";
            case "mm/h":    return "Sademäärä jopa " + v + " mm/h";
            case "m/s":     return "Tuulen puuskat jopa " + v + " m/s";
            case "index":   return "UV-indeksi " + v;
            default:        return v + " " + unit;
        }
    }

    /** Purkaa FMI:n käyttämät HTML-entiteetit (suomalaiset + perus + numeeriset). */
    public static String decodeEntities(String s) {
        if (s == null || s.isEmpty()) return "";
        String r = s
            .replace("&auml;", "ä").replace("&Auml;", "Ä")
            .replace("&ouml;", "ö").replace("&Ouml;", "Ö")
            .replace("&aring;", "å").replace("&Aring;", "Å")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        // numeeriset &#NNN;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("&#(\\d+);").matcher(r);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            try { m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1))))); }
            catch (Exception e) { m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(0))); }
        }
        m.appendTail(sb);
        // &amp; viimeisenä jottei tuplapurkua
        return sb.toString().replace("&amp;", "&");
    }

    static long parseIsoUtc(String s) {
        if (s == null || s.isEmpty()) return 0L;
        String[] patterns = { "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX" };
        for (String pat : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(pat, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = f.parse(s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) { }
        }
        return 0L;
    }
}
