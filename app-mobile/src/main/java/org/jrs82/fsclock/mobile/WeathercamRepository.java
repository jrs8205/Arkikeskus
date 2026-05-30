package org.jrs82.fsclock.mobile;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Välimuistittaa kelikamera-asemalistan (muuttuu harvoin → 24 h TTL) ja tarjoaa
 *  lähimmät/haku-suodatuksen. Mallina {@link TrafficNoticesRepository}. */
final class WeathercamRepository {

    interface Callback {
        void onResult(List<WeathercamStation> stations, String error);
    }

    private static final long TTL_MS = 24L * 60L * 60L * 1000L;
    private static volatile WeathercamRepository INSTANCE;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private List<WeathercamStation> cache;
    private long fetchedAt = 0L;
    private boolean inFlight = false;

    private WeathercamRepository() {}

    static WeathercamRepository get() {
        if (INSTANCE == null) {
            synchronized (WeathercamRepository.class) {
                if (INSTANCE == null) INSTANCE = new WeathercamRepository();
            }
        }
        return INSTANCE;
    }

    /** Palauttaa välimuistin heti jos tuore; muuten hakee taustalla ja kutsuu callbackin
     *  pääsäikeessä. */
    void load(boolean forced, Callback cb) {
        List<WeathercamStation> cached = cache;
        boolean fresh = cached != null && (System.currentTimeMillis() - fetchedAt) < TTL_MS;
        if (!forced && fresh) {
            cb.onResult(cached, null);
            return;
        }
        if (inFlight) {
            if (cached != null) cb.onResult(cached, null);
            return;
        }
        inFlight = true;
        io.execute(() -> {
            List<WeathercamStation> result = null;
            String error = null;
            try {
                result = WeathercamClient.fetchStations();
            } catch (Exception e) {
                error = e.getMessage();
            }
            final List<WeathercamStation> fr = result;
            final String fe = error;
            main.post(() -> {
                inFlight = false;
                if (fr != null && !fr.isEmpty()) {
                    cache = fr;
                    fetchedAt = System.currentTimeMillis();
                }
                cb.onResult(cache, (fr == null || fr.isEmpty()) ? fe : null);
            });
        });
    }

    List<WeathercamStation> peek() {
        return cache;
    }

    /** N lähintä asemaa annetusta pisteestä, etäisyys täytettynä (metreinä). */
    List<WeathercamStation> nearest(double lat, double lon, int n) {
        if (cache == null || Double.isNaN(lat) || Double.isNaN(lon)) return new ArrayList<>();
        List<WeathercamStation> copy = new ArrayList<>(cache);
        for (WeathercamStation s : copy) {
            s.distanceMeters = haversineMeters(lat, lon, s.lat, s.lon);
        }
        Collections.sort(copy, (a, b) -> Double.compare(a.distanceMeters, b.distanceMeters));
        return copy.size() > n ? new ArrayList<>(copy.subList(0, n)) : copy;
    }

    /** Suodattaa asemat nimellä (sisältää tienumeron ja paikkakunnan). ä/ö normalisoidaan. */
    List<WeathercamStation> search(String query) {
        List<WeathercamStation> out = new ArrayList<>();
        if (cache == null || query == null) return out;
        String q = normalize(query);
        if (q.isEmpty()) return out;
        for (WeathercamStation s : cache) {
            if (normalize(s.name).contains(q)) out.add(s);
        }
        return out;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT).trim();
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            switch (c) {
                case 'ä': case 'å': sb.append('a'); break;
                case 'ö': sb.append('o'); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
