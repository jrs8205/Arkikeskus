package org.jrs82.fsclock.mobile;

import android.util.Base64;
import android.util.Log;

import org.jrs82.fsclock.BuildConfig;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MmlGeocodingClient {

    private static final String TAG = "MmlGeocodingClient";
    private static final String BASE = "https://avoin-paikkatieto.maanmittauslaitos.fi/geocoding/v2";
    private static final int TIMEOUT_MS = 8000;
    private static final long SEARCH_CACHE_TTL_MS = 5L * 60_000L;
    private static final Map<String, SearchCacheEntry> SEARCH_CACHE = new HashMap<>();

    private MmlGeocodingClient() {}

    static boolean isConfigured() {
        return BuildConfig.MML_API_KEY != null && !BuildConfig.MML_API_KEY.trim().isEmpty();
    }

    static List<MmlPlace> searchPlaces(String query, int size) throws Exception {
        List<MmlPlace> out = new ArrayList<>();
        if (!isConfigured() || query == null || query.trim().length() < 3) return out;
        String cacheKey = normalize(query) + "|" + size;
        long now = System.currentTimeMillis();
        synchronized (SEARCH_CACHE) {
            SearchCacheEntry hit = SEARCH_CACHE.get(cacheKey);
            if (hit != null && !hit.places.isEmpty() && (now - hit.timestamp) < SEARCH_CACHE_TTL_MS) {
                return new ArrayList<>(hit.places);
            }
        }
        addAll(out, searchPelias(query.trim(), Math.max(10, size)));
        // Älä kutsu hidasta similar-hakua jos pääkutsusta saatiin jo edes yksi tulos.
        // Tämä korjasi "vakkola"-haun n. 10 s -> ~3 s.
        if (out.isEmpty() && query.trim().length() >= 6) {
            for (String term : similarTerms(query.trim(), size)) {
                if (!out.isEmpty()) break;
                addAll(out, searchPelias(term, size));
            }
        }
        List<MmlPlace> result = out.size() > size ? new ArrayList<>(out.subList(0, size)) : out;
        if (!result.isEmpty()) {
            synchronized (SEARCH_CACHE) {
                SEARCH_CACHE.put(cacheKey, new SearchCacheEntry(new ArrayList<>(result), now));
            }
        }
        return result;
    }

    static MmlPlace reversePlace(double latitude, double longitude) throws Exception {
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
                if (city.isEmpty() && isMunicipalityFeature(props)) {
                    city = placeName(props);
                }
            }

            if (fallbackName.isEmpty()) {
                fallbackName = placeName(props);
            }

            String candidate = firstNonEmpty(
                    props.optString("neighbourhood", ""),
                    props.optString("neighborhood", ""),
                    props.optString("borough", ""),
                    props.optString("district", ""));
            if (candidate.isEmpty() && isDistrictFeature(props)) {
                candidate = placeName(props);
            }
            if (!candidate.isEmpty()
                    && !candidate.equalsIgnoreCase(city)
                    && !candidate.matches("\\d+")) {
                district = candidate.trim();
                break;
            }
        }

        if (city.isEmpty()) city = cityFromLabelOrName(null, fallbackName, "", "", "");
        if (city.isEmpty()) return null;
        String display = (!district.isEmpty() && !district.equalsIgnoreCase(city))
                ? city.trim() + ", " + district.trim()
                : city.trim();
        return new MmlPlace(city.trim(), display, latitude, longitude);
    }

    private static List<MmlPlace> searchPelias(String query, int size) throws Exception {
        String url = BASE + "/pelias/search"
                + "?lang=fi"
                + "&sources=geographic-names,administrative-units"
                + "&sort=placetypegroup"
                + "&size=" + Math.max(1, Math.min(20, size))
                + "&text=" + enc(query);
        JSONObject root = new JSONObject(httpGet(url));
        JSONArray features = root.optJSONArray("features");
        List<MmlPlace> out = new ArrayList<>();
        if (features == null) return out;
        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.optJSONObject(i);
            if (feature == null) continue;
            JSONObject props = feature.optJSONObject("properties");
            if (props == null || !isSearchResultFeature(props)) continue;
            MmlPlace place = placeFromFeature(feature);
            if (place != null) addUnique(out, place);
        }
        return out;
    }

    private static List<String> similarTerms(String query, int size) {
        List<String> out = new ArrayList<>();
        try {
            String url = BASE + "/searchterm/similar"
                    + "?size=" + Math.max(1, Math.min(10, size))
                    + "&text=" + enc(query);
            collectTerms(new JSONObject(httpGet(url)), out);
        } catch (Exception e) {
            Log.d(TAG, "MML similar search failed: " + e.getMessage());
        }
        return out;
    }

    private static void collectTerms(Object node, List<String> out) throws Exception {
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) collectTerms(arr.get(i), out);
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject obj = (JSONObject) node;
        for (String key : new String[]{"term", "text", "name", "value"}) {
            String value = obj.optString(key, "");
            if (!value.trim().isEmpty()) addTerm(out, value.trim());
        }
        for (String key : new String[]{"terms", "results", "features", "items", "suggestions"}) {
            Object child = obj.opt(key);
            if (child != null) collectTerms(child, out);
        }
    }

    private static MmlPlace placeFromFeature(JSONObject feature) {
        if (feature == null) return null;
        JSONObject props = feature.optJSONObject("properties");
        if (props == null) return null;
        String name = placeName(props);
        String label = props.optString("label", "").trim();
        if (name.isEmpty()) name = firstLabelPart(label);
        if (name.isEmpty()) return null;

        String region = firstNonEmpty(
                props.optString("label:region", ""),
                props.optString("region", ""),
                props.optString("county", ""));
        String city = municipalityName(props);
        if (city.isEmpty()) city = cityFromLabelOrName(label, name, region,
                props.optString("source", ""), props.optString("layer", ""));
        if (city.isEmpty()) city = name;

        boolean district = isDistrictFeature(props) && !name.equalsIgnoreCase(city);
        String display = district ? city.trim() + ", " + name.trim() : city.trim();

        double[] point = pointFromFeature(feature);
        return new MmlPlace(city.trim(), display, point[0], point[1]);
    }

    private static double[] pointFromFeature(JSONObject feature) {
        JSONObject geometry = feature.optJSONObject("geometry");
        if (geometry != null) {
            JSONArray coords = geometry.optJSONArray("coordinates");
            if (coords != null && coords.length() >= 2) {
                double lon = coords.optDouble(0, Double.NaN);
                double lat = coords.optDouble(1, Double.NaN);
                return new double[]{lat, lon};
            }
        }
        return new double[]{Double.NaN, Double.NaN};
    }

    private static String cityFromLabelOrName(String label, String name, String region,
                                              String source, String layer) {
        if (isMunicipalityLayer(source, layer)) return name == null ? "" : name.trim();
        if (label == null || label.trim().isEmpty()) return name == null ? "" : name.trim();
        String[] parts = label.split(",");
        if (parts.length >= 2) {
            String second = parts[1].trim();
            if (!second.isEmpty() && !second.equalsIgnoreCase(region)) return second;
        }
        return name == null ? "" : name.trim();
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

    private static boolean containsWord(String value, String word) {
        if (value == null || word == null) return false;
        String n = normalize(value);
        String w = normalize(word);
        if (n.isEmpty() || w.isEmpty()) return false;
        return n.matches(".*\\b" + java.util.regex.Pattern.quote(w) + "\\b.*");
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

    private static boolean isSearchResultFeature(JSONObject props) {
        if (isMunicipalityFeature(props)) return true;
        int group = props.optInt("placeTypeGroup", -1);
        if (group == 301 || group == 302) return true;
        String text = props.optString("label:placeType", "") + " "
                + props.optString("label:placeTypeGroup", "");
        return containsAny(text, "kaupunkialueen tai taajaman osa", "taajamanosat",
                "kylä", "kyla", "kulmakunta");
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

    private static boolean containsAny(String value, String... needles) {
        String n = normalize(value);
        for (String needle : needles) {
            if (n.contains(normalize(needle))) return true;
        }
        return false;
    }

    private static void addAll(List<MmlPlace> out, List<MmlPlace> places) {
        for (MmlPlace place : places) addUnique(out, place);
    }

    private static void addUnique(List<MmlPlace> out, MmlPlace place) {
        if (place == null || place.dataPlace.isEmpty() || place.displayPlace.isEmpty()) return;
        String key = normalize(place.displayPlace);
        for (MmlPlace existing : out) {
            if (normalize(existing.displayPlace).equals(key)) return;
        }
        out.add(place);
    }

    private static void addTerm(List<String> out, String term) {
        String key = normalize(term);
        for (String existing : out) {
            if (normalize(existing).equals(key)) return;
        }
        out.add(term);
    }

    private static String firstLabelPart(String label) {
        if (label == null) return "";
        int comma = label.indexOf(',');
        String first = comma >= 0 ? label.substring(0, comma) : label;
        return first.trim();
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
                // Kuluta errorStream jotta socket vapautuu yhdistyspooliin.
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
        return "Basic " + Base64.encodeToString(
                token.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    static final class MmlPlace {
        final String dataPlace;
        final String displayPlace;
        final double latitude;
        final double longitude;

        MmlPlace(String dataPlace, String displayPlace, double latitude, double longitude) {
            this.dataPlace = dataPlace == null ? "" : dataPlace.trim();
            this.displayPlace = displayPlace == null ? "" : displayPlace.trim();
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private static final class SearchCacheEntry {
        final List<MmlPlace> places;
        final long timestamp;

        SearchCacheEntry(List<MmlPlace> places, long timestamp) {
            this.places = places;
            this.timestamp = timestamp;
        }
    }
}
