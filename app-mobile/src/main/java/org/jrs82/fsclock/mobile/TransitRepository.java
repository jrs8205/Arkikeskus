package org.jrs82.fsclock.mobile;

import java.util.List;

/** Joukkoliikennelähtöjen haku kevyellä ~20 s RAM-välimuistilla (Digitransitin subscription-
 *  kohtaisten rate-limitien suojaksi). {@link #fetch} kutsutaan taustasäikeestä. */
final class TransitRepository {

    private static final TransitRepository INSTANCE = new TransitRepository();
    static TransitRepository get() { return INSTANCE; }

    private static final long CACHE_MS = 20_000L;
    private static final double CACHE_DIST_M = 60.0;

    private List<NearbyStop> cached;
    private long cachedAt;
    private double cachedLat, cachedLon;

    private TransitRepository() {}

    List<NearbyStop> fetch(double lat, double lon) throws Exception {
        synchronized (this) {
            if (cached != null && System.currentTimeMillis() - cachedAt < CACHE_MS
                    && haversineMeters(lat, lon, cachedLat, cachedLon) < CACHE_DIST_M) {
                return cached;
            }
        }
        List<NearbyStop> fresh = DigitransitApi.nearbyDepartures(lat, lon);
        synchronized (this) {
            cached = fresh;
            cachedAt = System.currentTimeMillis();
            cachedLat = lat;
            cachedLon = lon;
        }
        return fresh;
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2.0) * Math.sin(dp / 2.0)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2.0) * Math.sin(dl / 2.0);
        return 2.0 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }
}
