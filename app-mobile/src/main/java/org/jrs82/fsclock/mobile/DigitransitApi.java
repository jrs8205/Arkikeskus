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

/** Digitransit/HSL-reitittimen GraphQL-kyselyt: lähimmät pysäkit + lähdöt, linjahaku, vuoron
 *  aikajana (pysäkit + ajoneuvon live-sijainti) ja yksittäisen pysäkin lähdöt.
 *  Aikalogiikka: lähtöajat ovat sekunteja päivän keskiyöstä (serviceDay) → epoch = serviceDay + dep.
 *  Verkkomalli: {@link TrafficNoticesClient} (HttpURLConnection + gzip + errorStream-drain). */
final class DigitransitApi {

    private static final String ENDPOINT = "https://api.digitransit.fi/routing/v2/hsl/gtfs/v1";
    private static final int TIMEOUT_MS = 9000;

    // Yhteinen lähtökenttäjoukko (nearest + stop). "... on Stop" pakollinen nearestissa.
    private static final String STOPTIME_FIELDS =
            "scheduledDeparture realtimeDeparture realtime serviceDay headsign"
            + " trip { gtfsId routeShortName pattern { code } route { gtfsId mode } }";

    private static final String NEAREST_QUERY =
            "query Nearest($lat: Float!, $lon: Float!) {"
            + " nearest(lat: $lat, lon: $lon, maxResults: 12, maxDistance: 700,"
            + " filterByPlaceTypes: [STOP]) {"
            + " edges { node { distance place { ... on Stop {"
            + " gtfsId name code vehicleMode"
            + " stoptimesWithoutPatterns(numberOfDepartures: 3) { " + STOPTIME_FIELDS + " } } } } } } }";

    private static final String ROUTES_QUERY =
            "query Routes($name: String!) { routes(name: $name) {"
            + " gtfsId shortName longName mode } }";

    private static final String TIMELINE_QUERY =
            "query TL($trip: String!, $pat: String!) {"
            + " trip(id: $trip) { routeShortName tripHeadsign route { mode }"
            + " stoptimesForDate { scheduledDeparture realtimeDeparture realtime serviceDay"
            + " stop { gtfsId name code } } }"
            + " pattern(id: $pat) { vehiclePositions { trip { gtfsId }"
            + " stopRelationship { status stop { gtfsId } } } } }";

    private static final String STOP_QUERY =
            "query Stop($id: String!) { stop(id: $id) {"
            + " gtfsId name code vehicleMode"
            + " stoptimesWithoutPatterns(numberOfDepartures: 5) { " + STOPTIME_FIELDS + " } } }";

    private DigitransitApi() {}

    // --- Lähimmät lähdöt ---

    static List<NearbyStop> nearbyDepartures(double lat, double lon) throws Exception {
        JSONObject variables = new JSONObject();
        variables.put("lat", lat);
        variables.put("lon", lon);
        JSONObject data = postQuery(NEAREST_QUERY, variables);
        JSONObject nearest = data == null ? null : data.optJSONObject("nearest");
        JSONArray edges = nearest == null ? null : nearest.optJSONArray("edges");
        List<NearbyStop> out = new ArrayList<>();
        if (edges == null) return out;
        for (int i = 0; i < edges.length(); i++) {
            JSONObject node = edges.optJSONObject(i);
            node = node == null ? null : node.optJSONObject("node");
            if (node == null) continue;
            JSONObject place = node.optJSONObject("place");
            if (place == null || !place.has("stoptimesWithoutPatterns")) continue;
            double distance = node.optDouble("distance", Double.NaN);
            NearbyStop stop = parseStop(place, distance);
            if (stop != null && !stop.departures.isEmpty()) out.add(stop);
        }
        return out;
    }

    // --- Yksittäisen pysäkin lähdöt (suosikit) ---

    static NearbyStop stopDepartures(String stopGtfsId) throws Exception {
        JSONObject variables = new JSONObject();
        variables.put("id", stopGtfsId);
        JSONObject data = postQuery(STOP_QUERY, variables);
        JSONObject stop = data == null ? null : data.optJSONObject("stop");
        if (stop == null) return null;
        return parseStop(stop, Double.NaN);
    }

    private static NearbyStop parseStop(JSONObject place, double distance) {
        String name = place.optString("name", "");
        String code = place.optString("code", "");
        String vehicleMode = place.optString("vehicleMode", "");
        String gtfsId = place.optString("gtfsId", "");
        JSONArray times = place.optJSONArray("stoptimesWithoutPatterns");
        List<Departure> departures = new ArrayList<>();
        if (times != null) {
            for (int j = 0; j < times.length(); j++) {
                Departure d = parseStoptime(times.optJSONObject(j), vehicleMode, distance, name, gtfsId);
                if (d != null) departures.add(d);
            }
        }
        return new NearbyStop(gtfsId, name, code, vehicleMode, distance, departures);
    }

    private static Departure parseStoptime(JSONObject st, String stopMode, double distance,
                                           String stopName, String stopGtfsId) {
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
        String tripGtfsId = "";
        String patternCode = "";
        String routeGtfsId = "";
        JSONObject trip = st.optJSONObject("trip");
        if (trip != null) {
            routeShortName = trip.optString("routeShortName", "");
            tripGtfsId = trip.optString("gtfsId", "");
            JSONObject pattern = trip.optJSONObject("pattern");
            if (pattern != null) patternCode = pattern.optString("code", "");
            JSONObject route = trip.optJSONObject("route");
            if (route != null) {
                routeGtfsId = route.optString("gtfsId", "");
                String m = route.optString("mode", "");
                if (!m.isEmpty()) mode = m;
            }
        }
        return new Departure(routeShortName, headsign, mode, epoch, delay, realtime, distance,
                stopName, stopGtfsId, tripGtfsId, patternCode, routeGtfsId);
    }

    // --- Linjahaku ---

    static List<RouteHit> searchRoutes(String name) throws Exception {
        JSONObject variables = new JSONObject();
        variables.put("name", name);
        JSONObject data = postQuery(ROUTES_QUERY, variables);
        JSONArray routes = data == null ? null : data.optJSONArray("routes");
        List<RouteHit> out = new ArrayList<>();
        if (routes == null) return out;
        for (int i = 0; i < routes.length(); i++) {
            JSONObject r = routes.optJSONObject(i);
            if (r == null) continue;
            out.add(new RouteHit(r.optString("gtfsId", ""), r.optString("shortName", ""),
                    r.optString("longName", ""), r.optString("mode", "")));
        }
        out.sort((a, b) -> {
            int byLen = Integer.compare(a.shortName.length(), b.shortName.length());
            return byLen != 0 ? byLen : a.shortName.compareToIgnoreCase(b.shortName);
        });
        return out;
    }

    // --- Vuoron aikajana + ajoneuvon live-sijainti ---

    static TripTimeline tripTimeline(String tripGtfsId, String patternCode, String boardStopGtfsId)
            throws Exception {
        JSONObject variables = new JSONObject();
        variables.put("trip", tripGtfsId == null ? "" : tripGtfsId);
        variables.put("pat", patternCode == null ? "" : patternCode);
        JSONObject data = postQuery(TIMELINE_QUERY, variables);
        if (data == null) return null;
        JSONObject trip = data.optJSONObject("trip");
        if (trip == null) return null;

        String routeShortName = trip.optString("routeShortName", "");
        String headsign = trip.optString("tripHeadsign", "");
        String mode = "";
        JSONObject route = trip.optJSONObject("route");
        if (route != null) mode = route.optString("mode", "");

        List<TimelineStop> stops = new ArrayList<>();
        int boardIndex = -1;
        JSONArray arr = trip.optJSONArray("stoptimesForDate");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject st = arr.optJSONObject(i);
                if (st == null) continue;
                long serviceDay = st.optLong("serviceDay", 0L);
                boolean realtime = st.optBoolean("realtime", false);
                int scheduled = st.optInt("scheduledDeparture", -1);
                int rt = st.optInt("realtimeDeparture", scheduled);
                int chosen = realtime && rt >= 0 ? rt : scheduled;
                JSONObject stop = st.optJSONObject("stop");
                String sid = stop == null ? "" : stop.optString("gtfsId", "");
                String sname = stop == null ? "" : stop.optString("name", "");
                String scode = stop == null ? "" : stop.optString("code", "");
                stops.add(new TimelineStop(sid, sname, scode,
                        chosen >= 0 ? serviceDay + chosen : 0L, realtime));
                if (boardStopGtfsId != null && boardStopGtfsId.equals(sid)) boardIndex = stops.size() - 1;
            }
        }

        // Ajoneuvon sijainti: etsi vehiclePositions josta trip.gtfsId täsmää tähän vuoroon.
        int currentIndex = -1;
        boolean incoming = true;
        JSONObject pattern = data.optJSONObject("pattern");
        JSONArray vps = pattern == null ? null : pattern.optJSONArray("vehiclePositions");
        if (vps != null) {
            for (int i = 0; i < vps.length(); i++) {
                JSONObject vp = vps.optJSONObject(i);
                if (vp == null) continue;
                JSONObject vtrip = vp.optJSONObject("trip");
                String vtid = vtrip == null ? "" : vtrip.optString("gtfsId", "");
                if (tripGtfsId == null || !tripGtfsId.equals(vtid)) continue;
                JSONObject rel = vp.optJSONObject("stopRelationship");
                if (rel == null) break;
                String status = rel.optString("status", "");
                incoming = !"AT_POSITION".equalsIgnoreCase(status) && !"ARRIVED".equalsIgnoreCase(status);
                JSONObject vstop = rel.optJSONObject("stop");
                String vsid = vstop == null ? "" : vstop.optString("gtfsId", "");
                for (int k = 0; k < stops.size(); k++) {
                    if (stops.get(k).gtfsId.equals(vsid)) { currentIndex = k; break; }
                }
                break;
            }
        }
        return new TripTimeline(routeShortName, headsign, mode, stops, currentIndex, boardIndex, incoming);
    }

    // --- HTTP ---

    private static JSONObject postQuery(String query, JSONObject variables) throws Exception {
        JSONObject body = new JSONObject();
        body.put("query", query);
        body.put("variables", variables);
        String raw = httpPost(body.toString());
        JSONObject root = new JSONObject(raw);
        JSONArray errors = root.optJSONArray("errors");
        if (errors != null && errors.length() > 0) {
            JSONObject first = errors.optJSONObject(0);
            throw new IOException("Digitransit GraphQL: "
                    + (first == null ? "tuntematon virhe" : first.optString("message", "virhe")));
        }
        return root.optJSONObject("data");
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
