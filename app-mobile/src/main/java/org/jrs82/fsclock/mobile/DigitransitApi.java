package org.jrs82.fsclock.mobile;

import org.jrs82.fsclock.BuildConfig;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** Digitransit/HSL-reitittimen kysely lähimmistä pysäkeistä ja niiden seuraavista lähdöistä.
 *  POST GraphQL (yksi kutsu, `nearest`-tyyppi). Aikalogiikka: lähtöajat ovat sekunteja kyseisen
 *  päivän keskiyöstä (serviceDay), eivät epoch-aikaa → epoch = serviceDay + departure.
 *  Malli verkkokutsulle: {@link TrafficNoticesClient} (HttpURLConnection + gzip + errorStream-drain). */
final class DigitransitApi {

    private static final String ENDPOINT = "https://api.digitransit.fi/routing/v2/hsl/gtfs/v1";
    private static final int TIMEOUT_MS = 9000;

    // nearest palauttaa PlaceInterface-tyyppejä → "... on Stop" -inline-fragmentti pakollinen.
    private static final String NEAREST_QUERY =
            "query Nearest($lat: Float!, $lon: Float!) {"
            + " nearest(lat: $lat, lon: $lon, maxResults: 12, maxDistance: 700,"
            + " filterByPlaceTypes: [STOP]) {"
            + " edges { node { distance place { ... on Stop {"
            + " gtfsId name code vehicleMode"
            + " stoptimesWithoutPatterns(numberOfDepartures: 3) {"
            + " scheduledDeparture realtimeDeparture realtime serviceDay headsign"
            + " trip { routeShortName route { mode } } } } } } } } }";

    private DigitransitApi() {}

    static List<NearbyStop> nearbyDepartures(double lat, double lon) throws Exception {
        JSONObject variables = new JSONObject();
        variables.put("lat", lat);
        variables.put("lon", lon);
        JSONObject body = new JSONObject();
        body.put("query", NEAREST_QUERY);
        body.put("variables", variables);

        String raw = httpPost(body.toString());
        return parseNearest(raw);
    }

    private static List<NearbyStop> parseNearest(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        JSONArray errors = root.optJSONArray("errors");
        if (errors != null && errors.length() > 0) {
            JSONObject first = errors.optJSONObject(0);
            throw new IOException("Digitransit GraphQL: "
                    + (first == null ? "tuntematon virhe" : first.optString("message", "virhe")));
        }
        JSONObject data = root.optJSONObject("data");
        JSONObject nearest = data == null ? null : data.optJSONObject("nearest");
        JSONArray edges = nearest == null ? null : nearest.optJSONArray("edges");
        List<NearbyStop> out = new ArrayList<>();
        if (edges == null) return out;

        for (int i = 0; i < edges.length(); i++) {
            JSONObject node = edges.optJSONObject(i);
            node = node == null ? null : node.optJSONObject("node");
            if (node == null) continue;
            JSONObject place = node.optJSONObject("place");
            // place voi olla muu PlaceInterface kuin Stop → Stop-kentät puuttuvat.
            if (place == null || !place.has("stoptimesWithoutPatterns")) continue;

            double distance = node.optDouble("distance", Double.NaN);
            String name = place.optString("name", "");
            String code = place.optString("code", "");
            String vehicleMode = place.optString("vehicleMode", "");
            String gtfsId = place.optString("gtfsId", "");

            JSONArray times = place.optJSONArray("stoptimesWithoutPatterns");
            List<Departure> departures = new ArrayList<>();
            if (times != null) {
                for (int j = 0; j < times.length(); j++) {
                    Departure d = parseStoptime(times.optJSONObject(j), vehicleMode, distance, name);
                    if (d != null) departures.add(d);
                }
            }
            if (!departures.isEmpty()) {
                out.add(new NearbyStop(gtfsId, name, code, vehicleMode, distance, departures));
            }
        }
        return out;
    }

    private static Departure parseStoptime(JSONObject st, String stopMode,
                                           double distance, String stopName) {
        if (st == null) return null;
        long serviceDay = st.optLong("serviceDay", 0L);
        boolean realtime = st.optBoolean("realtime", false);
        int scheduled = st.optInt("scheduledDeparture", -1);
        int rt = st.optInt("realtimeDeparture", scheduled);
        if (scheduled < 0 && rt < 0) return null;
        int chosen = realtime && rt >= 0 ? rt : (scheduled >= 0 ? scheduled : rt);
        long epoch = serviceDay + chosen;
        int delay = (realtime && scheduled >= 0 && rt >= 0) ? (rt - scheduled) : 0;

        String headsign = st.optString("headsign", "");
        String routeShortName = "";
        String mode = stopMode;
        JSONObject trip = st.optJSONObject("trip");
        if (trip != null) {
            routeShortName = trip.optString("routeShortName", "");
            JSONObject route = trip.optJSONObject("route");
            if (route != null) {
                String m = route.optString("mode", "");
                if (!m.isEmpty()) mode = m;
            }
        }
        return new Departure(routeShortName, headsign, mode, epoch, delay, realtime, distance, stopName);
    }

    private static String httpPost(String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        conn.setRequestProperty("digitransit-subscription-key", BuildConfig.DIGITRANSIT_KEY);
        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                drainStream(conn.getErrorStream());
                throw new IOException("Digitransit HTTP " + code + " " + conn.getResponseMessage());
            }
            InputStream rawStream = conn.getInputStream();
            String encoding = conn.getContentEncoding();
            try (InputStream is = "gzip".equalsIgnoreCase(encoding)
                    ? new GZIPInputStream(rawStream) : rawStream;
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
}
