package org.jrs82.fsclock;

import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

public final class FmiPlaceSearch {

    private static final String TAG = "FmiPlaceSearch";
    private static final String BASE = "https://opendata.fmi.fi/wfs";
    private static final int TIMEOUT_MS = 8000;
    private static final long CACHE_TTL_MS = 24L * 60L * 60_000L;

    private static String[] cachedNames;
    private static long cachedAt;

    private FmiPlaceSearch() {}

    public static synchronized String[] fetchCityNames() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedNames != null && (now - cachedAt) < CACHE_TTL_MS) {
            return cachedNames;
        }

        Map<String, CityPlace> fmiPlaces = fetchCitiesFromFmi();
        for (CityPlace p : fmiPlaces.values()) {
            GeoPlace.register(p.name, p.latitude, p.longitude);
        }
        TreeMap<String, String> merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : GeoPlace.placeNames()) {
            merged.put(normalizeKey(name), name);
        }
        for (CityPlace p : fmiPlaces.values()) {
            merged.put(normalizeKey(p.name), p.name);
        }
        cachedNames = merged.values().toArray(new String[0]);
        cachedAt = now;
        Log.d(TAG, "Loaded " + fmiPlaces.size() + " FMI city names");
        return cachedNames;
    }

    private static Map<String, CityPlace> fetchCitiesFromFmi() throws Exception {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        Date end = cal.getTime();
        cal.add(Calendar.HOUR, -3);
        Date start = cal.getTime();

        String url = BASE + "?service=WFS&version=2.0.0&request=getFeature"
                + "&storedquery_id=fmi::observations::weather::cities::multipointcoverage"
                + "&parameters=t2m"
                + "&starttime=" + iso.format(start)
                + "&endtime=" + iso.format(end)
                + "&timestep=60";

        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/xml");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("FMI city list HTTP " + code);
            }
            in = conn.getInputStream();
            return parseCities(in);
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) { }
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static Map<String, CityPlace> parseCities(InputStream in) throws Exception {
        XmlPullParser xpp = Xml.newPullParser();
        xpp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        xpp.setInput(in, "UTF-8");

        Map<String, String> pointToRegion = new HashMap<>();
        Map<String, CityPlace> places = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        boolean inLocation = false;
        boolean inPoint = false;
        String locationRegion = null;
        String locationPointId = null;
        String pointId = null;
        double pointLat = Double.NaN;
        double pointLon = Double.NaN;

        int ev = xpp.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            String name = xpp.getName();
            if (ev == XmlPullParser.START_TAG) {
                if (isTag(name, "Location")) {
                    inLocation = true;
                    locationRegion = null;
                    locationPointId = null;
                } else if (inLocation && isTag(name, "region")) {
                    locationRegion = cleanName(xpp.nextText());
                } else if (inLocation && isTag(name, "representativePoint")) {
                    locationPointId = stripHash(attribute(xpp, "href"));
                } else if (isTag(name, "Point")) {
                    inPoint = true;
                    pointId = attribute(xpp, "id");
                    pointLat = Double.NaN;
                    pointLon = Double.NaN;
                } else if (inPoint && isTag(name, "pos")) {
                    double[] latLon = parseLatLon(xpp.nextText());
                    pointLat = latLon[0];
                    pointLon = latLon[1];
                }
            } else if (ev == XmlPullParser.END_TAG) {
                if (isTag(name, "Location")) {
                    if (locationRegion != null && locationPointId != null) {
                        pointToRegion.put(locationPointId, locationRegion);
                    }
                    inLocation = false;
                } else if (isTag(name, "Point")) {
                    String region = pointToRegion.get(pointId);
                    if (region != null && !Double.isNaN(pointLat) && !Double.isNaN(pointLon)) {
                        places.putIfAbsent(normalizeKey(region),
                                new CityPlace(region, pointLat, pointLon));
                    }
                    inPoint = false;
                }
            }
            ev = xpp.next();
        }
        if (places.isEmpty()) {
            throw new Exception("FMI city list empty");
        }
        return places;
    }

    private static boolean isTag(String actual, String local) {
        return actual != null && (actual.equals(local) || actual.endsWith(":" + local));
    }

    private static String attribute(XmlPullParser xpp, String localName) {
        for (int i = 0; i < xpp.getAttributeCount(); i++) {
            String name = xpp.getAttributeName(i);
            if (isTag(name, localName)) {
                return xpp.getAttributeValue(i);
            }
        }
        return null;
    }

    private static String stripHash(String s) {
        if (s == null) return null;
        return s.startsWith("#") ? s.substring(1) : s;
    }

    private static String cleanName(String s) {
        if (s == null) return null;
        String cleaned = s.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static double[] parseLatLon(String raw) {
        double[] out = {Double.NaN, Double.NaN};
        if (raw == null) return out;
        String[] parts = raw.trim().split("\\s+");
        if (parts.length < 2) return out;
        try {
            out[0] = Double.parseDouble(parts[0]);
            out[1] = Double.parseDouble(parts[1]);
        } catch (NumberFormatException ignored) { }
        return out;
    }

    private static String normalizeKey(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.US);
    }

    private static final class CityPlace {
        final String name;
        final double latitude;
        final double longitude;

        CityPlace(String name, double latitude, double longitude) {
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
}
