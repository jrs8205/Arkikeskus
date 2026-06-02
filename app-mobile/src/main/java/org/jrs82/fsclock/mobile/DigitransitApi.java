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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
            + " nearest(lat: $lat, lon: $lon, maxResults: 20, maxDistance: 700,"
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

    // Asema (station) on oma tyyppinsä: stop(id:) palauttaa null asema-id:lle → käytä station(id:).
    private static final String STATION_QUERY =
            "query Station($id: String!) { station(id: $id) {"
            + " gtfsId name code"
            + " stoptimesWithoutPatterns(numberOfDepartures: 6) { " + STOPTIME_FIELDS + " } } }";

    // Paikkahaun geokoodaus (Pelias-autocomplete). sources=gtfshsl → vain HSL-pysäkit/asemat,
    // jolloin addendum.GTFS.modes kertoo moodit (ikoneita varten).
    private static final String GEOCODE_URL =
            "https://api.digitransit.fi/geocoding/v1/autocomplete";

    private static final String ROUTE_PATTERNS_QUERY =
            "query RP($id: String!) { route(id: $id) {"
            + " shortName longName mode patterns { code directionId headsign } } }";

    private static final String PATTERN_TIMETABLE_QUERY =
            "query PT($id: String!) { pattern(id: $id) { headsign directionId"
            + " vehiclePositions { trip { gtfsId } stopRelationship { status stop { gtfsId } } }"
            + " stops { gtfsId name stoptimesForPatterns(numberOfDepartures: 1) {"
            + " stoptimes { scheduledDeparture realtimeDeparture realtime serviceDay } } } } }";

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

    // --- Aseman lähdöt (station aggregoi laiturit; moodi tulee per lähtö trip.route.mode:sta) ---

    static NearbyStop stationDepartures(String stationGtfsId) throws Exception {
        JSONObject variables = new JSONObject();
        variables.put("id", stationGtfsId);
        JSONObject data = postQuery(STATION_QUERY, variables);
        JSONObject station = data == null ? null : data.optJSONObject("station");
        if (station == null) return null;
        return parseStop(station, Double.NaN);
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

    // --- Paikkahaku: HSL-pysäkit ja -asemat moodeineen (ennakoiva, sources=gtfshsl) ---

    static List<PlaceHit> searchPlaces(String text, double lat, double lon) throws Exception {
        double flat = Double.isNaN(lat) ? 60.17 : lat;
        double flon = Double.isNaN(lon) ? 24.94 : lon;
        String url = GEOCODE_URL + "?text=" + URLEncoder.encode(text, "UTF-8")
                + "&lang=fi&size=10&sources=gtfshsl"
                + "&focus.point.lat=" + flat + "&focus.point.lon=" + flon;
        String raw = httpGet(url);
        JSONArray features = new JSONObject(raw).optJSONArray("features");
        List<PlaceHit> out = new ArrayList<>();
        if (features == null) return out;
        for (int i = 0; i < features.length(); i++) {
            JSONObject f = features.optJSONObject(i);
            JSONObject p = f == null ? null : f.optJSONObject("properties");
            if (p == null) continue;
            String layer = p.optString("layer", "");
            boolean station = "station".equals(layer);
            if (!station && !"stop".equals(layer)) continue;
            String name = p.optString("name", "");
            if (name.isEmpty()) continue;
            String gtfsId = gtfsIdFromGeocode(p.optString("id", ""));
            out.add(new PlaceHit(gtfsId, name, localityOf(p, name), station, modesOf(p)));
        }
        return out;
    }

    private static String localityOf(JSONObject p, String name) {
        String label = p.optString("label", "");
        if (label.startsWith(name + ", ")) return label.substring(name.length() + 2);
        String la = p.optString("localadmin", "");
        return la.isEmpty() ? p.optString("region", "") : la;
    }

    private static List<String> modesOf(JSONObject p) {
        List<String> modes = new ArrayList<>();
        JSONObject add = p.optJSONObject("addendum");
        JSONObject gtfs = add == null ? null : add.optJSONObject("GTFS");
        JSONArray arr = gtfs == null ? null : gtfs.optJSONArray("modes");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String c = canonMode(arr.optString(i, ""));
                if (c != null && !modes.contains(c)) modes.add(c);
            }
        }
        return modes;
    }

    /** RAWv2-tyyppiset moodit (BUS-LOCAL, BUS-EXPRESS…) kanonisoidaan ikoneja varten. */
    private static String canonMode(String m) {
        if (m == null || m.isEmpty()) return null;
        String u = m.toUpperCase(Locale.ROOT);
        if (u.startsWith("BUS")) return "BUS";
        if (u.startsWith("TRAM")) return "TRAM";
        if (u.startsWith("RAIL")) return "RAIL";
        if (u.startsWith("SUBWAY")) return "SUBWAY";
        if (u.startsWith("FERRY")) return "FERRY";
        return null;
    }

    /** "GTFS:HSL:2131551#E1331" → "HSL:2131551" (reititin-API:n stop/station-id). */
    private static String gtfsIdFromGeocode(String rawId) {
        if (rawId == null) return "";
        String s = rawId;
        int g = s.indexOf("GTFS:");
        if (g >= 0) s = s.substring(g + 5);
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        return s;
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
        List<Integer> vehIdx = new ArrayList<>();
        if (currentIndex >= 0) vehIdx.add(currentIndex);
        return new TripTimeline(routeShortName, headsign, mode, stops, vehIdx, boardIndex, incoming);
    }

    // --- Linjanäkymä: suunnat + suunnan aikataulu (seuraava lähtö per pysäkki) + live-ajoneuvot ---

    static RoutePatterns routePatterns(String routeGtfsId) throws Exception {
        JSONObject variables = new JSONObject();
        variables.put("id", routeGtfsId);
        JSONObject data = postQuery(ROUTE_PATTERNS_QUERY, variables);
        JSONObject route = data == null ? null : data.optJSONObject("route");
        if (route == null) return null;
        List<RoutePatterns.Pat> pats = new ArrayList<>();
        JSONArray arr = route.optJSONArray("patterns");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                pats.add(new RoutePatterns.Pat(p.optString("code", ""),
                        p.optInt("directionId", 0), p.optString("headsign", "")));
            }
        }
        return new RoutePatterns(route.optString("shortName", ""),
                route.optString("longName", ""), route.optString("mode", ""), pats);
    }

    static TripTimeline patternTimetable(String patternCode, String routeShortName, String mode)
            throws Exception {
        JSONObject variables = new JSONObject();
        variables.put("id", patternCode);
        JSONObject data = postQuery(PATTERN_TIMETABLE_QUERY, variables);
        JSONObject pattern = data == null ? null : data.optJSONObject("pattern");
        if (pattern == null) return null;
        String headsign = pattern.optString("headsign", "");

        List<TimelineStop> stops = new ArrayList<>();
        JSONArray sarr = pattern.optJSONArray("stops");
        if (sarr != null) {
            for (int i = 0; i < sarr.length(); i++) {
                JSONObject s = sarr.optJSONObject(i);
                if (s == null) continue;
                String sid = s.optString("gtfsId", "");
                String sname = s.optString("name", "");
                long epoch = 0L;
                boolean realtime = false;
                JSONArray groups = s.optJSONArray("stoptimesForPatterns");
                if (groups != null && groups.length() > 0) {
                    JSONObject g0 = groups.optJSONObject(0);
                    JSONArray times = g0 == null ? null : g0.optJSONArray("stoptimes");
                    if (times != null && times.length() > 0) {
                        JSONObject t0 = times.optJSONObject(0);
                        long sd = t0.optLong("serviceDay", 0L);
                        boolean r = t0.optBoolean("realtime", false);
                        int sch = t0.optInt("scheduledDeparture", -1);
                        int rtd = t0.optInt("realtimeDeparture", sch);
                        int chosen = r && rtd >= 0 ? rtd : sch;
                        if (chosen >= 0) { epoch = sd + chosen; realtime = r; }
                    }
                }
                stops.add(new TimelineStop(sid, sname, "", epoch, realtime));
            }
        }

        List<Integer> vehIdx = new ArrayList<>();
        JSONArray vps = pattern.optJSONArray("vehiclePositions");
        if (vps != null) {
            for (int i = 0; i < vps.length(); i++) {
                JSONObject vp = vps.optJSONObject(i);
                if (vp == null) continue;
                JSONObject rel = vp.optJSONObject("stopRelationship");
                if (rel == null) continue;
                JSONObject vstop = rel.optJSONObject("stop");
                String vsid = vstop == null ? "" : vstop.optString("gtfsId", "");
                for (int k = 0; k < stops.size(); k++) {
                    if (stops.get(k).gtfsId.equals(vsid)) {
                        if (!vehIdx.contains(k)) vehIdx.add(k);
                        break;
                    }
                }
            }
        }
        return new TripTimeline(routeShortName, headsign, mode, stops, vehIdx, -1, true);
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

    private static String httpGet(String urlString) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        conn.setRequestProperty("digitransit-subscription-key", BuildConfig.DIGITRANSIT_KEY);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                drainStream(conn.getErrorStream());
                throw new IOException("Geocoding HTTP " + code + " " + conn.getResponseMessage());
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
