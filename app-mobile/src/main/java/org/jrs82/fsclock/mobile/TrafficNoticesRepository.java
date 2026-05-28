package org.jrs82.fsclock.mobile;

import java.util.ArrayList;
import java.util.List;

final class TrafficNoticesRepository {

    private static final long CACHE_TTL_MS = 10L * 60_000L;
    private static final double NEARBY_RADIUS_METERS = 50_000.0;
    private static final long MAX_UPCOMING_MS = 30L * 24L * 60L * 60_000L;
    private static final long INCIDENT_MAX_AGE_MS = 12L * 60L * 60_000L;

    private List<TrafficNotice> cache = new ArrayList<>();
    private long cacheAtMs;
    private double cacheLat = Double.NaN;
    private double cacheLon = Double.NaN;

    synchronized List<TrafficNotice> fetchNearby(double lat, double lon,
                                                 TrafficNotice.Kind kind,
                                                 boolean forceNetwork) throws Exception {
        long now = System.currentTimeMillis();
        if (forceNetwork || cacheExpired(now) || locationChanged(lat, lon)) {
            cache = TrafficNoticesClient.fetchAll(lat, lon);
            cacheAtMs = now;
            cacheLat = lat;
            cacheLon = lon;
        }
        return filter(cache, kind, lat, lon, now);
    }

    synchronized List<TrafficNotice> peekNearby(double lat, double lon, TrafficNotice.Kind kind) {
        return filter(cache, kind, lat, lon, System.currentTimeMillis());
    }

    synchronized long cacheTime() {
        return cacheAtMs;
    }

    private boolean cacheExpired(long now) {
        return cacheAtMs <= 0L || (now - cacheAtMs) > CACHE_TTL_MS;
    }

    private boolean locationChanged(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) return false;
        if (Double.isNaN(cacheLat) || Double.isNaN(cacheLon)) return true;
        return distanceMeters(lat, lon, cacheLat, cacheLon) > 5000.0;
    }

    private static List<TrafficNotice> filter(List<TrafficNotice> source,
                                              TrafficNotice.Kind kind,
                                              double lat,
                                              double lon,
                                              long now) {
        List<TrafficNotice> out = new ArrayList<>();
        boolean hasLocation = !Double.isNaN(lat) && !Double.isNaN(lon);
        for (TrafficNotice notice : source) {
            if (notice == null) continue;
            if (kind != null && kind != TrafficNotice.Kind.ALL && notice.kind != kind) continue;
            if (notice.endTimeMs > 0L && notice.endTimeMs < now) continue;
            if (isShortLivedKind(notice.kind)) {
                if (notice.startTimeMs > 0L && now - notice.startTimeMs > INCIDENT_MAX_AGE_MS) continue;
            } else {
                if (notice.startTimeMs > 0L && notice.startTimeMs - now > MAX_UPCOMING_MS) continue;
            }
            if (hasLocation) {
                if (Double.isNaN(notice.distanceMeters)
                        || notice.distanceMeters > NEARBY_RADIUS_METERS) {
                    continue;
                }
            }
            out.add(notice);
        }
        return out;
    }

    private static boolean isShortLivedKind(TrafficNotice.Kind kind) {
        return kind == TrafficNotice.Kind.ACCIDENT
                || kind == TrafficNotice.Kind.INCIDENT
                || kind == TrafficNotice.Kind.CONGESTION;
    }

    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
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
