package org.jrs82.fsclock.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

final class TrafficNoticesClient {

    private static final String BASE = "https://tie.digitraffic.fi/api/traffic-message/v2/";
    private static final String DIGITRAFFIC_USER = "Arkikeskus/1.1.1-mobile";
    private static final int TIMEOUT_MS = 9000;
    private static final double BBOX_RADIUS_M = 50_000.0;

    private TrafficNoticesClient() {}

    static List<TrafficNotice> fetchAll(double refLat, double refLon) throws Exception {
        String bbox = buildBboxQuery(refLat, refLon, BBOX_RADIUS_M);
        List<TrafficNotice> out = new ArrayList<>();
        out.addAll(fetchKind(TrafficNotice.Kind.ROAD_WORK, refLat, refLon, bbox));
        out.addAll(fetchKind(TrafficNotice.Kind.WEIGHT_RESTRICTION, refLat, refLon, bbox));
        out.addAll(fetchTrafficAnnouncements(refLat, refLon, bbox));
        out.sort(Comparator
                .comparingLong((TrafficNotice n) -> n.startTimeMs > 0
                        ? -n.startTimeMs : Long.MIN_VALUE)
                .thenComparing(n -> n.kind.title)
                .thenComparing(n -> n.title));
        return out;
    }

    private static String buildBboxQuery(double lat, double lon, double radiusM) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) return "";
        double dLat = radiusM / 111_320.0;
        double dLon = radiusM / (111_320.0 * Math.cos(Math.toRadians(lat)));
        return String.format(Locale.US, "xMin=%.4f&xMax=%.4f&yMin=%.4f&yMax=%.4f",
                lon - dLon, lon + dLon, lat - dLat, lat + dLat);
    }

    private static List<TrafficNotice> fetchTrafficAnnouncements(double refLat,
                                                                 double refLon,
                                                                 String bbox) throws Exception {
        String url = BASE + TrafficNotice.Kind.INCIDENT.endpoint
                + (bbox.isEmpty() ? "" : "?" + bbox);
        JSONObject root = new JSONObject(httpGet(url));
        JSONArray features = root.optJSONArray("features");
        List<TrafficNotice> out = new ArrayList<>();
        if (features == null) return out;
        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.optJSONObject(i);
            TrafficNotice notice = parseFeature(TrafficNotice.Kind.INCIDENT, feature, refLat, refLon);
            if (notice == null) continue;
            TrafficNotice.Kind resolved = resolveAnnouncementKind(notice, feature);
            if (resolved != TrafficNotice.Kind.INCIDENT) {
                notice = new TrafficNotice(resolved, notice.id,
                        notice.title, notice.location, notice.details, notice.severity,
                        notice.startTimeMs, notice.endTimeMs, notice.distanceMeters);
            }
            out.add(notice);
        }
        return out;
    }

    private static List<TrafficNotice> fetchKind(TrafficNotice.Kind kind,
                                                 double refLat,
                                                 double refLon,
                                                 String bbox) throws Exception {
        String url = BASE + kind.endpoint
                + (bbox.isEmpty() ? "" : "?" + bbox);
        JSONObject root = new JSONObject(httpGet(url));
        JSONArray features = root.optJSONArray("features");
        List<TrafficNotice> out = new ArrayList<>();
        if (features == null) return out;
        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.optJSONObject(i);
            TrafficNotice notice = parseFeature(kind, feature, refLat, refLon);
            if (notice != null) out.add(notice);
        }
        return out;
    }

    private static TrafficNotice parseFeature(TrafficNotice.Kind kind, JSONObject feature,
                                              double refLat, double refLon) {
        if (feature == null) return null;
        JSONObject props = feature.optJSONObject("properties");
        if (props == null) return null;
        JSONObject announcement = finnishAnnouncement(props.optJSONArray("announcements"));

        String id = firstNonEmpty(
                props.optString("situationId", ""),
                props.optString("id", ""),
                feature.optString("id", ""));
        String title = firstNonEmpty(
                announcement.optString("title", ""),
                props.optString("name", ""),
                kind.title);
        String location = firstNonEmpty(
                nestedString(announcement, "location", "description"),
                nestedString(props, "location", "description"),
                props.optString("locationDescription", ""));
        String details = firstNonEmpty(
                announcement.optString("comment", ""),
                announcement.optString("description", ""),
                props.optString("description", ""));
        String severity = firstNonEmpty(
                announcement.optString("severity", ""),
                props.optString("severity", ""),
                roadWorkSeverity(announcement),
                "");
        long start = parseTime(firstNonEmpty(
                nestedString(announcement, "timeAndDuration", "startTime"),
                props.optString("startTime", "")));
        long end = parseTime(firstNonEmpty(
                nestedString(announcement, "timeAndDuration", "endTime"),
                props.optString("endTime", "")));
        double distance = Double.NaN;
        if (!Double.isNaN(refLat) && !Double.isNaN(refLon)) {
            distance = minDistanceMeters(feature.opt("geometry"), refLat, refLon);
        }
        return new TrafficNotice(kind, id, clean(title), clean(location), clean(details),
                clean(severity), start, end, distance);
    }

    private static TrafficNotice.Kind resolveAnnouncementKind(TrafficNotice notice,
                                                              JSONObject feature) {
        JSONObject props = feature == null ? null : feature.optJSONObject("properties");
        if (props != null) {
            JSONArray announcements = props.optJSONArray("announcements");
            if (announcements != null) {
                for (int i = 0; i < announcements.length(); i++) {
                    JSONObject a = announcements.optJSONObject(i);
                    if (a == null) continue;
                    String type = a.optString("trafficAnnouncementType", "")
                            .toLowerCase(Locale.ROOT);
                    if (type.contains("accident")) return TrafficNotice.Kind.ACCIDENT;
                }
            }
        }
        String text = (notice.title + " " + notice.location + " "
                + notice.details + " " + notice.severity).toLowerCase(Locale.ROOT);
        if (text.contains("ruuhk") || text.contains("jono") || text.contains("jonou")
                || text.contains("hidast") || text.contains("slow traffic")
                || text.contains("queu")) {
            return TrafficNotice.Kind.CONGESTION;
        }
        if (text.contains("onnettomuus") || text.contains("kolari")
                || text.contains("accident") || text.contains("törmä")) {
            return TrafficNotice.Kind.ACCIDENT;
        }
        return TrafficNotice.Kind.INCIDENT;
    }

    private static JSONObject finnishAnnouncement(JSONArray announcements) {
        if (announcements == null || announcements.length() == 0) return new JSONObject();
        JSONObject first = announcements.optJSONObject(0);
        for (int i = 0; i < announcements.length(); i++) {
            JSONObject row = announcements.optJSONObject(i);
            if (row == null) continue;
            String lang = row.optString("language", "");
            if ("fi".equalsIgnoreCase(lang) || "fin".equalsIgnoreCase(lang)) return row;
        }
        return first == null ? new JSONObject() : first;
    }

    private static String roadWorkSeverity(JSONObject announcement) {
        JSONArray phases = announcement.optJSONArray("roadWorkPhases");
        if (phases == null || phases.length() == 0) return "";
        JSONObject first = phases.optJSONObject(0);
        return first == null ? "" : first.optString("severity", "");
    }

    private static String nestedString(JSONObject parent, String objectKey, String valueKey) {
        if (parent == null) return "";
        JSONObject child = parent.optJSONObject(objectKey);
        return child == null ? "" : child.optString(valueKey, "");
    }

    private static long parseTime(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        try {
            return Instant.parse(value.trim()).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return 0L;
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/geo+json, application/json");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        conn.setRequestProperty("Digitraffic-User", DIGITRAFFIC_USER);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("Digitraffic HTTP " + code + " " + conn.getResponseMessage());
            }
            InputStream raw = conn.getInputStream();
            String encoding = conn.getContentEncoding();
            try (InputStream is = "gzip".equalsIgnoreCase(encoding)
                    ? new GZIPInputStream(raw)
                    : raw;
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

    private static double minDistanceMeters(Object geometry, double refLat, double refLon) {
        if (!(geometry instanceof JSONObject)) return Double.NaN;
        JSONArray coordinates = ((JSONObject) geometry).optJSONArray("coordinates");
        if (coordinates == null) return Double.NaN;
        return minDistanceFromCoordinates(coordinates, refLat, refLon, Double.NaN);
    }

    private static double minDistanceFromCoordinates(JSONArray arr, double refLat, double refLon,
                                                     double best) {
        if (arr == null || arr.length() == 0) return best;
        Object first = arr.opt(0);
        if (first instanceof Number && arr.length() >= 2 && arr.opt(1) instanceof Number) {
            double lon = arr.optDouble(0, Double.NaN);
            double lat = arr.optDouble(1, Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) return best;
            double distance = haversineMeters(refLat, refLon, lat, lon);
            return Double.isNaN(best) ? distance : Math.min(best, distance);
        }
        for (int i = 0; i < arr.length(); i++) {
            Object child = arr.opt(i);
            if (child instanceof JSONArray) {
                best = minDistanceFromCoordinates((JSONArray) child, refLat, refLon, best);
            }
        }
        return best;
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2.0) * Math.sin(dp / 2.0)
                + Math.cos(p1) * Math.cos(p2)
                * Math.sin(dl / 2.0) * Math.sin(dl / 2.0);
        return 2.0 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }
}
