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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/** Digitransit/HSL-reitittimen GraphQL-kyselyt: lähimmät pysäkit + lähdöt, linjahaku, vuoron
 *  aikajana (pysäkit + ajoneuvon live-sijainti) ja yksittäisen pysäkin lähdöt.
 *  Aikalogiikka: lähtöajat ovat sekunteja päivän keskiyöstä (serviceDay) → epoch = serviceDay + dep.
 *  Verkkomalli: {@link TrafficNoticesClient} (HttpURLConnection + gzip + errorStream-drain). */
public final class DigitransitApi {

    private static final int TIMEOUT_MS = 9000;

    // Yhteinen lähtökenttäjoukko (nearest + stop). "... on Stop" pakollinen nearestissa.
    private static final String STOPTIME_FIELDS =
            "scheduledDeparture realtimeDeparture realtime serviceDay headsign"
            + " trip { gtfsId routeShortName pattern { code stops { name } } route { gtfsId mode } }";

    // Häiriö-/poikkeustiedotteet (stop / route / leg.route). Severity: INFO/WARNING/SEVERE/UNKNOWN.
    private static final String ALERT_FIELDS =
            "alertHeaderText alertDescriptionText alertSeverityLevel alertEffect"
            + " effectiveStartDate effectiveEndDate";

    private static final String NEAREST_QUERY =
            "query Nearest($lat: Float!, $lon: Float!) {"
            + " nearest(lat: $lat, lon: $lon, maxResults: 20, maxDistance: 700,"
            + " filterByPlaceTypes: [STOP]) {"
            + " edges { node { distance place { ... on Stop {"
            + " gtfsId name code zoneId vehicleMode"
            + " alerts { " + ALERT_FIELDS + " }"
            + " stoptimesWithoutPatterns(numberOfDepartures: 5) { " + STOPTIME_FIELDS + " } } } } } } }";

    private static final String ROUTES_QUERY =
            "query Routes($name: String!) { routes(name: $name) {"
            + " gtfsId shortName longName mode } }";

    private static final String TIMELINE_QUERY =
            "query TL($trip: String!, $pat: String!) {"
            + " trip(id: $trip) { routeShortName tripHeadsign route { mode }"
            + " stoptimesForDate { scheduledDeparture realtimeDeparture realtime serviceDay"
            + " stop { gtfsId name code lat lon } } }"
            + " pattern(id: $pat) { patternGeometry { points }"
            + " vehiclePositions { vehicleId trip { gtfsId }"
            + " stopRelationship { status stop { gtfsId } } } } }";

    private static final String STOP_QUERY =
            "query Stop($id: String!) { stop(id: $id) {"
            + " gtfsId name code zoneId vehicleMode"
            + " alerts { " + ALERT_FIELDS + " }"
            + " stoptimesWithoutPatterns(numberOfDepartures: 5) { " + STOPTIME_FIELDS + " } } }";

    // Asema (station) on oma tyyppinsä: stop(id:) palauttaa null asema-id:lle → käytä station(id:).
    private static final String STATION_QUERY =
            "query Station($id: String!) { station(id: $id) {"
            + " gtfsId name code zoneId"
            + " alerts { " + ALERT_FIELDS + " }"
            + " stoptimesWithoutPatterns(numberOfDepartures: 6) { " + STOPTIME_FIELDS + " } } }";

    // Koko päivän aikataulu yhdelle pysäkille/asemalle: timeRange 24 h, jopa 100 lähtöä.
    private static final String STOP_FULLDAY_QUERY =
            "query Stop($id: String!) { stop(id: $id) {"
            + " gtfsId name code zoneId vehicleMode"
            + " alerts { " + ALERT_FIELDS + " }"
            + " stoptimesWithoutPatterns(numberOfDepartures: 100, timeRange: 86400) { "
            + STOPTIME_FIELDS + " } } }";

    private static final String STATION_FULLDAY_QUERY =
            "query Station($id: String!) { station(id: $id) {"
            + " gtfsId name code zoneId"
            + " alerts { " + ALERT_FIELDS + " }"
            + " stoptimesWithoutPatterns(numberOfDepartures: 100, timeRange: 86400) { "
            + STOPTIME_FIELDS + " } } }";

    // Paikkahaun geokoodaus (Pelias-autocomplete). sources=gtfshsl → vain HSL-pysäkit/asemat,
    // jolloin addendum.GTFS.modes kertoo moodit (ikoneita varten).
    private static final String GEOCODE_URL =
            "https://api.digitransit.fi/geocoding/v1/autocomplete";
    // Pelias-search löytää pysäkkikoodit (esim. "V1701"), joita autocomplete EI löydä.
    private static final String GEOCODE_SEARCH_URL =
            "https://api.digitransit.fi/geocoding/v1/search";

    private static final String ROUTE_PATTERNS_QUERY =
            "query RP($id: String!) { route(id: $id) {"
            + " shortName longName mode alerts { " + ALERT_FIELDS + " }"
            + " patterns { code directionId headsign stops { name } } } }";

    private static final String PATTERN_TIMETABLE_QUERY =
            "query PT($id: String!) { pattern(id: $id) { headsign directionId"
            + " vehiclePositions { trip { gtfsId } stopRelationship { status stop { gtfsId } } }"
            + " stops { gtfsId name stoptimesForPatterns(numberOfDepartures: 1) {"
            + " stoptimes { scheduledDeparture realtimeDeparture realtime serviceDay } } } } }";

    private DigitransitApi() {}

    // --- Lähimmät lähdöt ---

    public static List<NearbyStop> nearbyDepartures(double lat, double lon, TransitRegion region) throws Exception {
        JSONObject variables = new JSONObject();
        variables.put("lat", lat);
        variables.put("lon", lon);
        JSONObject data = postQuery(NEAREST_QUERY, variables, region);
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

    public static NearbyStop stopDepartures(String stopGtfsId, TransitRegion region) throws Exception {
        JSONObject variables = new JSONObject();
        variables.put("id", stopGtfsId);
        JSONObject data = postQuery(STOP_QUERY, variables, region);
        JSONObject stop = data == null ? null : data.optJSONObject("stop");
        if (stop == null) return null;
        return parseStop(stop, Double.NaN);
    }

    // --- Aseman lähdöt (station aggregoi laiturit; moodi tulee per lähtö trip.route.mode:sta) ---

    // public: lähtö-widgetin worker (alipaketti .widget) tarvitsee tämän suosikkiaseman lähtöihin.
    public static NearbyStop stationDepartures(String stationGtfsId, TransitRegion region) throws Exception {
        JSONObject variables = new JSONObject();
        variables.put("id", stationGtfsId);
        JSONObject data = postQuery(STATION_QUERY, variables, region);
        JSONObject station = data == null ? null : data.optJSONObject("station");
        if (station == null) return null;
        return parseStop(station, Double.NaN);
    }

    // --- Koko päivän aikataulu (timeRange 24 h, jopa 100 lähtöä) ---

    static NearbyStop stopDeparturesFullDay(String stopGtfsId, TransitRegion region) throws Exception {
        JSONObject variables = new JSONObject();
        variables.put("id", stopGtfsId);
        JSONObject data = postQuery(STOP_FULLDAY_QUERY, variables, region);
        JSONObject stop = data == null ? null : data.optJSONObject("stop");
        if (stop == null) return null;
        return parseStop(stop, Double.NaN);
    }

    static NearbyStop stationDeparturesFullDay(String stationGtfsId, TransitRegion region) throws Exception {
        JSONObject variables = new JSONObject();
        variables.put("id", stationGtfsId);
        JSONObject data = postQuery(STATION_FULLDAY_QUERY, variables, region);
        JSONObject station = data == null ? null : data.optJSONObject("station");
        if (station == null) return null;
        return parseStop(station, Double.NaN);
    }

    // --- Kaikki HSL-häiriöt ("Häiriöt ja muutokset" -sivu) ---

    /** Koko HSL-syötteen aktiiviset häiriöt linja-/pysäkkitietoineen. Poistaa tarkat tuplat
     *  (sama otsikko+kuvaus+linja+pysäkki) ja järjestää: vakavin ensin, sitten uusin alkamisaika. */
    static List<TransitAlert> serviceAlerts(TransitRegion region) throws Exception {
        String serviceAlertsQuery = "{ alerts(feeds:[\"" + region.alertFeed + "\"]) { " + ALERT_FIELDS
                + " route { shortName mode } stop { name } } }";
        JSONObject data = postQuery(serviceAlertsQuery, new JSONObject(), region);
        JSONArray arr = data == null ? null : data.optJSONArray("alerts");
        List<TransitAlert> out = new ArrayList<>();
        if (arr == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null) continue;
            String header = a.isNull("alertHeaderText") ? "" : a.optString("alertHeaderText", "");
            String desc = a.isNull("alertDescriptionText") ? "" : a.optString("alertDescriptionText", "");
            if (header.isEmpty() && desc.isEmpty()) continue;
            String sev = a.isNull("alertSeverityLevel") ? "" : a.optString("alertSeverityLevel", "");
            String eff = a.isNull("alertEffect") ? "" : a.optString("alertEffect", "");
            long start = a.optLong("effectiveStartDate", 0L);
            long end = a.optLong("effectiveEndDate", 0L);
            JSONObject route = a.optJSONObject("route");
            String rsn = (route == null || route.isNull("shortName")) ? "" : route.optString("shortName", "");
            String mode = (route == null || route.isNull("mode")) ? "" : route.optString("mode", "");
            JSONObject stop = a.optJSONObject("stop");
            String stopName = (stop == null || stop.isNull("name")) ? "" : stop.optString("name", "");
            if (!seen.add(header + "|" + desc + "|" + rsn + "|" + stopName)) continue;
            out.add(new TransitAlert(header, desc, sev, eff, start, end, rsn, mode, stopName));
        }
        out.sort((x, y) -> {
            int bySev = Integer.compare(y.severityRank(), x.severityRank());
            return bySev != 0 ? bySev : Long.compare(y.startEpochSec, x.startEpochSec);
        });
        return out;
    }

    private static NearbyStop parseStop(JSONObject place, double distance) {
        String name = place.optString("name", "");
        // optString palauttaa merkkijonon "null" jos arvo on JSONObject.NULL → suojaa isNull():lla.
        String code = place.isNull("code") ? "" : place.optString("code", "");
        String zoneId = place.isNull("zoneId") ? "" : place.optString("zoneId", "");
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
        List<TransitAlert> alerts = parseAlerts(place.optJSONArray("alerts"));
        return new NearbyStop(gtfsId, name, code, zoneId, vehicleMode, distance, departures, alerts);
    }

    /** Parsii Digitransitin {@code alerts}-taulukon listaksi. Ohittaa tyhjät ja samat tuplat. */
    private static List<TransitAlert> parseAlerts(JSONArray arr) {
        List<TransitAlert> out = new ArrayList<>();
        if (arr == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null) continue;
            String header = a.isNull("alertHeaderText") ? "" : a.optString("alertHeaderText", "");
            String desc = a.isNull("alertDescriptionText") ? "" : a.optString("alertDescriptionText", "");
            if (header.isEmpty() && desc.isEmpty()) continue;
            if (!seen.add(header + "" + desc)) continue;   // sama tiedote useammalta linjalta → kerran
            String sev = a.isNull("alertSeverityLevel") ? "" : a.optString("alertSeverityLevel", "");
            String eff = a.isNull("alertEffect") ? "" : a.optString("alertEffect", "");
            long start = a.optLong("effectiveStartDate", 0L);
            long end = a.optLong("effectiveEndDate", 0L);
            out.add(new TransitAlert(header, desc, sev, eff, start, end));
        }
        return out;
    }

    private static Departure parseStoptime(JSONObject st, String stopMode, double distance,
                                           String stopName, String stopGtfsId) {
        if (st == null) return null;
        long serviceDay = st.optLong("serviceDay", 0L);
        boolean realtime = st.optBoolean("realtime", false);
        int scheduled = st.optInt("scheduledDeparture", -1);
        int rt = st.optInt("realtimeDeparture", scheduled);
        // serviceDay puuttuu/null → epoch jäisi ~1970:ksi ja näkyisi virheellisenä lähtönä.
        if (serviceDay <= 0 || (scheduled < 0 && rt < 0)) return null;
        int chosen = realtime && rt >= 0 ? rt : (scheduled >= 0 ? scheduled : rt);
        long epoch = serviceDay + chosen;
        int delay = (realtime && scheduled >= 0 && rt >= 0) ? (rt - scheduled) : 0;

        String headsign = st.optString("headsign", "");
        String routeShortName = "";
        String mode = stopMode;
        String tripGtfsId = "";
        String patternCode = "";
        String routeGtfsId = "";
        String patternFirstStop = "";
        String patternLastStop = "";
        JSONObject trip = st.optJSONObject("trip");
        if (trip != null) {
            routeShortName = trip.optString("routeShortName", "");
            tripGtfsId = trip.optString("gtfsId", "");
            JSONObject pattern = trip.optJSONObject("pattern");
            if (pattern != null) {
                patternCode = pattern.optString("code", "");
                JSONArray stops = pattern.optJSONArray("stops");
                if (stops != null && stops.length() > 0) {
                    JSONObject first = stops.optJSONObject(0);
                    JSONObject last = stops.optJSONObject(stops.length() - 1);
                    patternFirstStop = first == null ? "" : first.optString("name", "");
                    patternLastStop = last == null ? "" : last.optString("name", "");
                }
            }
            JSONObject route = trip.optJSONObject("route");
            if (route != null) {
                routeGtfsId = route.optString("gtfsId", "");
                String m = route.optString("mode", "");
                if (!m.isEmpty()) mode = m;
            }
        }
        return new Departure(routeShortName, headsign, mode, epoch, delay, realtime, distance,
                stopName, stopGtfsId, tripGtfsId, patternCode, routeGtfsId,
                patternFirstStop, patternLastStop);
    }

    // --- Linjahaku ---

    static List<RouteHit> searchRoutes(String name, TransitRegion region) throws Exception {
        JSONObject variables = new JSONObject();
        variables.put("name", name);
        JSONObject data = postQuery(ROUTES_QUERY, variables, region);
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

    static List<PlaceHit> searchPlaces(String text, double lat, double lon, TransitRegion region) throws Exception {
        List<PlaceHit> out = geocodeStopPlaces(GEOCODE_URL, text, lat, lon, region);
        // Autocomplete ei löydä pysäkkikoodeja (esim. "V1701") → fallback /search-endpointtiin.
        if (out.isEmpty()) {
            out = geocodeStopPlaces(GEOCODE_SEARCH_URL, text, lat, lon, region);
        }
        return out;
    }

    private static List<PlaceHit> geocodeStopPlaces(String baseUrl, String text, double lat, double lon,
                                                    TransitRegion region)
            throws Exception {
        double flat = Double.isNaN(lat) ? 60.17 : lat;
        double flon = Double.isNaN(lon) ? 24.94 : lon;
        String url = baseUrl + "?text=" + URLEncoder.encode(text, "UTF-8")
                + "&lang=fi&size=10&sources=" + region.geocodeSources
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
            out.add(new PlaceHit(gtfsId, name, localityOf(p, name), codeOf(p), station, modesOf(p)));
        }
        return out;
    }

    /** Pysäkkikoodi (esim. "V1701") geokoodausvastauksen addendum.GTFS.code-kentästä. */
    private static String codeOf(JSONObject p) {
        JSONObject add = p.optJSONObject("addendum");
        JSONObject gtfs = add == null ? null : add.optJSONObject("GTFS");
        return gtfs == null ? "" : gtfs.optString("code", "");
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

    // --- Reittihaku: määränpääpaikat (oletuslähteet, koordinaatit) + matkasuunnittelu ---

    /** Geokoodaus reittihaun Mistä/Minne-valintaan: oletuslähteet (paikat, osoitteet, POI:t,
     *  pysäkit) koordinaatteineen. Eri kuin {@link #searchPlaces} (joka rajaa gtfshsl-pysäkkeihin). */
    static List<GeoPlace> geocodePlaces(String text, double lat, double lon, TransitRegion region) throws Exception {
        double flat = Double.isNaN(lat) ? region.focusLat : lat;
        double flon = Double.isNaN(lon) ? region.focusLon : lon;
        String url = GEOCODE_URL + "?text=" + URLEncoder.encode(text, "UTF-8")
                + "&lang=fi&size=8"
                + "&focus.point.lat=" + flat + "&focus.point.lon=" + flon;
        String raw = httpGet(url);
        JSONArray features = new JSONObject(raw).optJSONArray("features");
        List<GeoPlace> out = new ArrayList<>();
        if (features == null) return out;
        for (int i = 0; i < features.length(); i++) {
            JSONObject f = features.optJSONObject(i);
            if (f == null) continue;
            JSONObject p = f.optJSONObject("properties");
            JSONObject geo = f.optJSONObject("geometry");
            JSONArray coord = geo == null ? null : geo.optJSONArray("coordinates");
            if (p == null || coord == null || coord.length() < 2) continue;
            double plon = coord.optDouble(0, Double.NaN);  // GeoJSON: [lon, lat]
            double plat = coord.optDouble(1, Double.NaN);
            if (Double.isNaN(plat) || Double.isNaN(plon)) continue;
            String name = p.optString("name", "");
            if (name.isEmpty()) name = p.optString("label", "");
            if (name.isEmpty()) continue;
            out.add(new GeoPlace(name, localityOf(p, name), plat, plon, p.optString("layer", "")));
        }
        return out;
    }

    /** Matkasuunnittelu A→B (planConnection). dateTimeIso = ISO-aika offsetilla; arriveBy=true →
     *  "perillä viimeistään", false → "lähde aikaisintaan". Palauttaa reittiehdotukset. */
    static List<Itinerary> planRoutes(double fromLat, double fromLon, double toLat, double toLon,
                                      String dateTimeIso, boolean arriveBy, int first,
                                      TransitRegion region) throws Exception {
        return planRoutes(fromLat, fromLon, toLat, toLon, dateTimeIso, arriveBy, first, null, region);
    }

    /** transitMode = null/tyhjä → kaikki kulkuvälineet; muuten esim. BUS/RAIL/TRAM/SUBWAY/FERRY
     *  (planConnectionin modes-argumentti rajaa joukkoliikenneosuudet tähän moodiin). */
    static List<Itinerary> planRoutes(double fromLat, double fromLon, double toLat, double toLon,
                                      String dateTimeIso, boolean arriveBy, int first,
                                      String transitMode, TransitRegion region) throws Exception {
        String dtField = arriveBy ? "latestArrival" : "earliestDeparture";
        String modes = (transitMode == null || transitMode.isEmpty())
                ? "" : "modes:{transit:{transit:[{mode:" + transitMode + "}]}},";
        String q = "query{planConnection("
                + "origin:{location:{coordinate:{latitude:" + fromLat + ",longitude:" + fromLon + "}}},"
                + "destination:{location:{coordinate:{latitude:" + toLat + ",longitude:" + toLon + "}}},"
                + "dateTime:{" + dtField + ":\"" + dateTimeIso + "\"}," + modes + "first:" + first + "){"
                + "edges{node{duration numberOfTransfers start end walkDistance "
                + "legs{mode duration distance start{scheduledTime estimated{time}} "
                + "end{scheduledTime estimated{time}} from{name stop{code platformCode}} to{name} "
                + "route{shortName alerts{" + ALERT_FIELDS + "}} "
                + "trip{tripHeadsign gtfsId pattern{code}} stopCalls{stopLocation{... on Stop{lat lon}}}"
                + " legGeometry{points}}}}}}";
        JSONObject data = postQuery(q, new JSONObject(), region);
        List<Itinerary> out = new ArrayList<>();
        JSONObject pc = data == null ? null : data.optJSONObject("planConnection");
        JSONArray edges = pc == null ? null : pc.optJSONArray("edges");
        if (edges == null) return out;
        for (int i = 0; i < edges.length(); i++) {
            JSONObject node = edges.optJSONObject(i);
            node = node == null ? null : node.optJSONObject("node");
            if (node == null) continue;
            List<Leg> legs = new ArrayList<>();
            JSONArray la = node.optJSONArray("legs");
            if (la != null) {
                for (int j = 0; j < la.length(); j++) {
                    JSONObject lg = la.optJSONObject(j);
                    if (lg != null) legs.add(parseLeg(lg));
                }
            }
            out.add(new Itinerary(epochMs(node.optString("start", "")),
                    epochMs(node.optString("end", "")),
                    (int) Math.round(node.optDouble("duration", 0)),
                    node.optInt("numberOfTransfers", 0),
                    (int) Math.round(node.optDouble("walkDistance", 0)), legs));
        }
        return out;
    }

    private static Leg parseLeg(JSONObject lg) {
        long[] st = legTime(lg.optJSONObject("start"));
        long[] en = legTime(lg.optJSONObject("end"));
        JSONObject from = lg.optJSONObject("from");
        JSONObject to = lg.optJSONObject("to");
        JSONObject route = lg.optJSONObject("route");
        JSONObject trip = lg.optJSONObject("trip");
        JSONObject fromStop = from == null ? null : from.optJSONObject("stop");
        String fCode = (fromStop == null || fromStop.isNull("code")) ? "" : fromStop.optString("code", "");
        String fPlat = (fromStop == null || fromStop.isNull("platformCode"))
                ? "" : fromStop.optString("platformCode", "");
        JSONObject geo = lg.optJSONObject("legGeometry");
        List<double[]> geometry = geo == null
                ? java.util.Collections.emptyList() : PolylineDecoder.decode(geo.optString("points", ""));
        String tripGtfsId = trip == null ? "" : trip.optString("gtfsId", "");
        JSONObject pat = trip == null ? null : trip.optJSONObject("pattern");
        String patternCode = pat == null ? "" : pat.optString("code", "");
        List<double[]> stops = new ArrayList<>();
        JSONArray sc = lg.optJSONArray("stopCalls");
        if (sc != null) {
            for (int i = 0; i < sc.length(); i++) {
                JSONObject call = sc.optJSONObject(i);
                JSONObject sl = call == null ? null : call.optJSONObject("stopLocation");
                if (sl == null) continue;
                double slat = sl.optDouble("lat", Double.NaN);
                double slon = sl.optDouble("lon", Double.NaN);
                if (!Double.isNaN(slat) && !Double.isNaN(slon)) stops.add(new double[]{slat, slon});
            }
        }
        List<TransitAlert> alerts = parseAlerts(route == null ? null : route.optJSONArray("alerts"));
        return new Leg(lg.optString("mode", ""), st[0], en[0],
                (int) Math.round(lg.optDouble("duration", 0)),
                (int) Math.round(lg.optDouble("distance", 0)),
                from == null ? "" : from.optString("name", ""),
                to == null ? "" : to.optString("name", ""),
                route == null ? "" : route.optString("shortName", ""),
                trip == null ? "" : trip.optString("tripHeadsign", ""),
                st[1] == 1L, fCode, fPlat, geometry, tripGtfsId, patternCode, stops, alerts);
    }

    /** Etsii vuorolla (tripGtfsId) tällä hetkellä liikkeellä olevan ajoneuvon vehicleId:n
     *  ("HSL:oper/veh") MQTT-livetilausta varten, tai "" jos vuoro ei ole liikkeellä. */
    static String vehicleForTrip(String patternCode, String tripGtfsId, TransitRegion region) throws Exception {
        if (patternCode == null || patternCode.isEmpty() || tripGtfsId == null || tripGtfsId.isEmpty()) return "";
        JSONObject variables = new JSONObject();
        variables.put("pat", patternCode);
        JSONObject data = postQuery(
                "query VT($pat: String!) { pattern(id: $pat) {"
                + " vehiclePositions { vehicleId trip { gtfsId } } } }", variables, region);
        JSONObject pattern = data == null ? null : data.optJSONObject("pattern");
        JSONArray vps = pattern == null ? null : pattern.optJSONArray("vehiclePositions");
        if (vps == null) return "";
        for (int i = 0; i < vps.length(); i++) {
            JSONObject vp = vps.optJSONObject(i);
            if (vp == null) continue;
            JSONObject t = vp.optJSONObject("trip");
            if (t != null && tripGtfsId.equals(t.optString("gtfsId", ""))) {
                return vp.optString("vehicleId", "");
            }
        }
        return "";
    }

    /** start/end-objektista [epochMs, realtimeFlag]; käyttää reaaliaika-arviota jos saatavilla. */
    private static long[] legTime(JSONObject t) {
        if (t == null) return new long[]{0L, 0L};
        String iso = "";
        boolean rt = false;
        JSONObject est = t.optJSONObject("estimated");
        if (est != null) {
            String e = est.optString("time", "");
            if (!e.isEmpty()) { iso = e; rt = true; }
        }
        if (iso.isEmpty()) iso = t.optString("scheduledTime", "");
        return new long[]{epochMs(iso), rt ? 1L : 0L};
    }

    private static long epochMs(String iso) {
        if (iso == null || iso.isEmpty()) return 0L;
        try { return OffsetDateTime.parse(iso).toInstant().toEpochMilli(); }
        catch (Exception e) { return 0L; }
    }

    // --- Vuoron aikajana + ajoneuvon live-sijainti ---

    static TripTimeline tripTimeline(String tripGtfsId, String patternCode, String boardStopGtfsId,
            TransitRegion region) throws Exception {
        JSONObject variables = new JSONObject();
        variables.put("trip", tripGtfsId == null ? "" : tripGtfsId);
        variables.put("pat", patternCode == null ? "" : patternCode);
        JSONObject data = postQuery(TIMELINE_QUERY, variables, region);
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
                int chosen = realtime && rt >= 0 ? rt : (scheduled >= 0 ? scheduled : rt);
                JSONObject stop = st.optJSONObject("stop");
                String sid = stop == null ? "" : stop.optString("gtfsId", "");
                String sname = stop == null ? "" : stop.optString("name", "");
                String scode = (stop == null || stop.isNull("code")) ? "" : stop.optString("code", "");
                double slat = stop == null ? Double.NaN : stop.optDouble("lat", Double.NaN);
                double slon = stop == null ? Double.NaN : stop.optDouble("lon", Double.NaN);
                stops.add(new TimelineStop(sid, sname, scode,
                        (chosen >= 0 && serviceDay > 0) ? serviceDay + chosen : 0L, realtime, slat, slon));
                if (boardStopGtfsId != null && boardStopGtfsId.equals(sid)) boardIndex = stops.size() - 1;
            }
        }

        // Ajoneuvon sijainti: etsi vehiclePositions josta trip.gtfsId täsmää tähän vuoroon.
        int currentIndex = -1;
        boolean incoming = true;
        String matchedVehicleId = "";   // "HSL:oper/veh" → MQTT-livetilausta varten (A-strategia)
        JSONObject pattern = data.optJSONObject("pattern");
        JSONArray vps = pattern == null ? null : pattern.optJSONArray("vehiclePositions");
        if (vps != null) {
            for (int i = 0; i < vps.length(); i++) {
                JSONObject vp = vps.optJSONObject(i);
                if (vp == null) continue;
                JSONObject vtrip = vp.optJSONObject("trip");
                String vtid = vtrip == null ? "" : vtrip.optString("gtfsId", "");
                if (tripGtfsId == null || !tripGtfsId.equals(vtid)) continue;
                matchedVehicleId = vp.optString("vehicleId", "");
                JSONObject rel = vp.optJSONObject("stopRelationship");
                if (rel == null) break;
                String status = rel.optString("status", "");
                // VehicleStopStatus (GTFS-RT / Digitransit): STOPPED_AT = pysäkillä;
                // IN_TRANSIT_TO / INCOMING_AT = lähestyy pysäkkiä (ei vielä saapunut sille).
                incoming = !"STOPPED_AT".equalsIgnoreCase(status);
                JSONObject vstop = rel.optJSONObject("stop");
                String vsid = vstop == null ? "" : vstop.optString("gtfsId", "");
                for (int k = 0; k < stops.size(); k++) {
                    if (stops.get(k).gtfsId.equals(vsid)) { currentIndex = k; break; }
                }
                break;
            }
        }
        // Reitin muoto (encoded polyline) bussin GPS:n projisointiin (V2).
        List<double[]> shape = new ArrayList<>();
        JSONObject pg = pattern == null ? null : pattern.optJSONObject("patternGeometry");
        if (pg != null) shape = PolylineDecoder.decode(pg.optString("points", ""));
        List<Integer> vehIdx = new ArrayList<>();
        if (currentIndex >= 0) vehIdx.add(currentIndex);
        return new TripTimeline(routeShortName, headsign, mode, stops, vehIdx, boardIndex, incoming,
                matchedVehicleId, shape);
    }

    // --- Linjanäkymä: suunnat + suunnan aikataulu (seuraava lähtö per pysäkki) + live-ajoneuvot ---

    static RoutePatterns routePatterns(String routeGtfsId, TransitRegion region) throws Exception {
        JSONObject variables = new JSONObject();
        variables.put("id", routeGtfsId);
        JSONObject data = postQuery(ROUTE_PATTERNS_QUERY, variables, region);
        JSONObject route = data == null ? null : data.optJSONObject("route");
        if (route == null) return null;
        List<RoutePatterns.Pat> pats = new ArrayList<>();
        JSONArray arr = route.optJSONArray("patterns");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                String firstStop = "";
                String lastStop = "";
                JSONArray stops = p.optJSONArray("stops");
                if (stops != null && stops.length() > 0) {
                    JSONObject first = stops.optJSONObject(0);
                    JSONObject last = stops.optJSONObject(stops.length() - 1);
                    firstStop = first == null ? "" : first.optString("name", "");
                    lastStop = last == null ? "" : last.optString("name", "");
                }
                pats.add(new RoutePatterns.Pat(p.optString("code", ""),
                        p.optInt("directionId", 0), p.optString("headsign", ""),
                        firstStop, lastStop));
            }
        }
        List<TransitAlert> alerts = parseAlerts(route.optJSONArray("alerts"));
        return new RoutePatterns(route.optString("shortName", ""),
                route.optString("longName", ""), route.optString("mode", ""), pats, alerts);
    }

    static TripTimeline patternTimetable(String patternCode, String routeShortName, String mode,
            TransitRegion region) throws Exception {
        JSONObject variables = new JSONObject();
        variables.put("id", patternCode);
        JSONObject data = postQuery(PATTERN_TIMETABLE_QUERY, variables, region);
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
                        if (t0 != null) {
                            long sd = t0.optLong("serviceDay", 0L);
                            boolean r = t0.optBoolean("realtime", false);
                            int sch = t0.optInt("scheduledDeparture", -1);
                            int rtd = t0.optInt("realtimeDeparture", sch);
                            int chosen = r && rtd >= 0 ? rtd : (sch >= 0 ? sch : rtd);
                            if (chosen >= 0 && sd > 0) { epoch = sd + chosen; realtime = r; }
                        }
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

    private static JSONObject postQuery(String query, JSONObject variables, TransitRegion region) throws Exception {
        JSONObject body = new JSONObject();
        body.put("query", query);
        body.put("variables", variables);
        String raw = httpPost(body.toString(), region);
        JSONObject root = new JSONObject(raw);
        JSONArray errors = root.optJSONArray("errors");
        if (errors != null && errors.length() > 0) {
            JSONObject first = errors.optJSONObject(0);
            throw new IOException("Digitransit GraphQL: "
                    + (first == null ? "tuntematon virhe" : first.optString("message", "virhe")));
        }
        return root.optJSONObject("data");
    }

    private static String httpPost(String jsonBody, TransitRegion region) throws Exception {
        // Ilman avainta Digitransit vastaa 401/403 → epää heti selkeällä virheellä (kutsujat ottavat kiinni).
        if (BuildConfig.DIGITRANSIT_KEY == null || BuildConfig.DIGITRANSIT_KEY.trim().isEmpty()) {
            throw new IOException("Digitransit-avain puuttuu");
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(region.endpoint).openConnection();
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
        // Ilman avainta Digitransit vastaa 401/403 → epää heti selkeällä virheellä (kutsujat ottavat kiinni).
        if (BuildConfig.DIGITRANSIT_KEY == null || BuildConfig.DIGITRANSIT_KEY.trim().isEmpty()) {
            throw new IOException("Digitransit-avain puuttuu");
        }
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
