package org.jrs82.fsclock;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

/** Kevyt Maanmittauslaitoksen käänteisgeokoodaus: koordinaatti → (kaupunki, kaupunginosa).
 *  Poimittu mobiilin MmlGeocodingClientistä (vain reverse-osa), koska Android Geocoder
 *  ei palauta Suomen kaupunginosaa. Vaatii MML_API_KEY:n ja Telia-juurivarmenteen
 *  (network_security_config). */
public final class MmlReverseGeocoder {

    private static final String BASE = "https://avoin-paikkatieto.maanmittauslaitos.fi/geocoding/v2";
    private static final int TIMEOUT_MS = 8000;

    private MmlReverseGeocoder() {}

    public static boolean isConfigured() {
        return BuildConfig.MML_API_KEY != null && !BuildConfig.MML_API_KEY.trim().isEmpty();
    }

    /** Palauttaa [kaupunki, kaupunginosa] tai null. Kaupunginosa voi olla tyhjä. */
    public static String[] reverse(double latitude, double longitude) throws Exception {
        if (!isConfigured()) return null;
        String url = BASE + "/pelias/reverse"
                + "?lang=fi"
                + "&sources=administrative-units,addresses,geographic-names"
                + "&boundary.circle.radius=2000"
                + "&point.lon=" + enc(String.format(Locale.US, "%.7f", longitude))
                + "&point.lat=" + enc(String.format(Locale.US, "%.7f", latitude));
        JSONObject root = new JSONObject(httpGet(url));
        JSONArray features = root.optJSONArray("features");
        if (features == null || features.length() == 0) return null;

        String city = "";
        String district = "";
        String fallbackName = "";
        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.optJSONObject(i);
            if (feature == null) continue;
            JSONObject props = feature.optJSONObject("properties");
            if (props == null) continue;

            if (city.isEmpty()) {
                city = municipalityName(props);
                if (city.isEmpty() && isMunicipalityFeature(props)) city = placeName(props);
            }
            if (fallbackName.isEmpty()) fallbackName = placeName(props);

            String candidate = firstNonEmpty(
                    props.optString("neighbourhood", ""),
                    props.optString("neighborhood", ""),
                    props.optString("borough", ""),
                    props.optString("district", ""));
            if (candidate.isEmpty() && isDistrictFeature(props)) candidate = placeName(props);
            if (!candidate.isEmpty() && !candidate.equalsIgnoreCase(city) && !candidate.matches("\\d+")) {
                district = candidate.trim();
                if (!city.isEmpty()) break;
            }
        }
        if (city.isEmpty()) city = fallbackName;
        if (city.isEmpty()) return null;
        if (district.equalsIgnoreCase(city)) district = "";
        return new String[]{ city.trim(), district.trim() };
    }

    private static boolean isMunicipalityFeature(JSONObject props) {
        if (isMunicipalityLayer(props.optString("source", ""), props.optString("layer", ""))) return true;
        if (containsAny(props.optString("nationalLevel", ""), "municip")) return true;
        if (containsWord(props.optString("placetype", ""), "kunta")
                || containsAny(props.optString("placetype", ""), "municip")) return true;
        if (containsWord(props.optString("label:placeType", ""), "kunta")
                || containsAny(props.optString("label:placeType", ""), "municip")) return true;
        return false;
    }

    private static boolean isMunicipalityLayer(String source, String layer) {
        return containsAny(source + " " + layer, "administrative", "municipality", "kunta");
    }

    private static boolean isDistrictFeature(JSONObject props) {
        int group = props.optInt("placeTypeGroup", -1);
        if (group == 301 || group == 302) return true;
        String text = props.optString("source", "") + " "
                + props.optString("layer", "") + " "
                + props.optString("placetype", "") + " "
                + props.optString("placetypegroup", "") + " "
                + props.optString("label:placeType", "") + " "
                + props.optString("label:placeTypeGroup", "") + " "
                + props.optString("label:placeTypeSubgroup", "") + " "
                + props.optString("source_id", "");
        if (!containsAny(text, "geographic", "names", "nimisto", "neighbourhood",
                "neighborhood", "suburb", "district", "kaupunginosa", "taajama",
                "kyl", "village")) {
            return false;
        }
        return !isMunicipalityFeature(props);
    }

    private static String municipalityName(JSONObject props) {
        String named = firstNonEmpty(
                props.optString("kuntanimiFin", ""),
                props.optString("label:municipality", ""),
                props.optString("locality", ""),
                props.optString("localadmin", ""),
                props.optString("municipality", ""));
        return named.matches("\\d+") ? "" : named;
    }

    private static String placeName(JSONObject props) {
        Object raw = props.opt("name");
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            String first = "";
            for (int i = 0; i < arr.length(); i++) {
                JSONObject row = arr.optJSONObject(i);
                if (row == null) continue;
                String spelling = row.optString("spelling", "").trim();
                if (spelling.isEmpty()) continue;
                if (first.isEmpty()) first = spelling;
                String lang = row.optString("language", "");
                int dominance = row.optInt("languageDominance", 0);
                if ("fin".equalsIgnoreCase(lang) && dominance == 1) return spelling;
            }
            return first;
        }
        return props.optString("name", "").trim();
    }

    private static boolean containsWord(String value, String word) {
        if (value == null || word == null) return false;
        String n = normalize(value);
        String w = normalize(word);
        if (n.isEmpty() || w.isEmpty()) return false;
        return n.matches(".*\\b" + java.util.regex.Pattern.quote(w) + "\\b.*");
    }

    private static boolean containsAny(String value, String... needles) {
        String n = normalize(value);
        for (String needle : needles) {
            if (n.contains(normalize(needle))) return true;
        }
        return false;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/geo+json, application/json");
        conn.setRequestProperty("Authorization", basicAuthHeader());
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                drainStream(conn.getErrorStream());
                throw new IOException("MML HTTP " + code + " " + conn.getResponseMessage());
            }
            try (InputStream is = conn.getInputStream();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                return baos.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void drainStream(InputStream is) {
        if (is == null) return;
        try (InputStream toClose = is) {
            byte[] buf = new byte[1024];
            while (toClose.read(buf) > 0) { /* discard */ }
        } catch (IOException ignored) { }
    }

    private static String basicAuthHeader() {
        String token = BuildConfig.MML_API_KEY.trim() + ":";
        return "Basic " + Base64.encodeToString(token.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }
}
