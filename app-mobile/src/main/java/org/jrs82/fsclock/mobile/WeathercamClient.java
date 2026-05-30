package org.jrs82.fsclock.mobile;

import org.jrs82.fsclock.BuildConfig;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** Hakee Digitrafficin kelikamera-asemat (GeoJSON). Sama datamuoto ja header-tyyli kuin
 *  {@link TrafficNoticesClient}illa. Kamerakuvat ladataan erikseen {@link ImageLoader}illa
 *  — EI tätä clientia eikä Authorization-headeria (weathercam-kuvat palauttavat sille 400). */
final class WeathercamClient {

    private static final String STATIONS_URL =
            "https://tie.digitraffic.fi/api/weathercam/v1/stations";
    private static final String DIGITRAFFIC_USER = "Arkikeskus/" + BuildConfig.VERSION_NAME;
    private static final int TIMEOUT_MS = 15000;

    private WeathercamClient() {}

    /** Kaikki julkiset kelikamera-asemat. Lista muuttuu harvoin → välimuistita pitkään
     *  (ks. {@link WeathercamRepository}). */
    static List<WeathercamStation> fetchStations() throws Exception {
        JSONObject root = new JSONObject(httpGet(STATIONS_URL));
        JSONArray features = root.optJSONArray("features");
        List<WeathercamStation> out = new ArrayList<>();
        if (features == null) return out;
        for (int i = 0; i < features.length(); i++) {
            WeathercamStation st = parseFeature(features.optJSONObject(i));
            if (st != null) out.add(st);
        }
        return out;
    }

    private static WeathercamStation parseFeature(JSONObject feature) {
        if (feature == null) return null;
        JSONObject geometry = feature.optJSONObject("geometry");
        if (geometry == null) return null;
        JSONArray coords = geometry.optJSONArray("coordinates");
        if (coords == null || coords.length() < 2) return null;
        double lon = coords.optDouble(0, Double.NaN);
        double lat = coords.optDouble(1, Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon)) return null;

        String id = feature.optString("id", "");
        JSONObject props = feature.optJSONObject("properties");
        String name = props == null ? id : props.optString("name", id);

        List<WeathercamStation.WeathercamPreset> presets = new ArrayList<>();
        if (props != null) {
            JSONArray presetArr = props.optJSONArray("presets");
            if (presetArr != null) {
                for (int j = 0; j < presetArr.length(); j++) {
                    JSONObject p = presetArr.optJSONObject(j);
                    if (p == null) continue;
                    String presetId = p.optString("id", "");
                    if (presetId.isEmpty()) continue;
                    presets.add(new WeathercamStation.WeathercamPreset(
                            presetId, p.optString("presentationName", "")));
                }
            }
        }
        return new WeathercamStation(id, name, lat, lon, presets);
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        conn.setRequestProperty("Digitraffic-User", DIGITRAFFIC_USER);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                drain(conn.getErrorStream());
                throw new RuntimeException("Weathercam HTTP " + code);
            }
            InputStream is = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                is = new GZIPInputStream(is);
            }
            try (InputStream in = is; ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
                return baos.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Lukee ja sulkee virhebodyn 4xx/5xx-tilanteessa, jotta socket vapautuu poolille. */
    private static void drain(InputStream is) {
        if (is == null) return;
        try (InputStream in = is) {
            byte[] buf = new byte[4096];
            while (in.read(buf) > 0) { /* discard */ }
        } catch (Exception ignored) { }
    }
}
