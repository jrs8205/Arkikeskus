package org.jrs82.fsclock;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/** MET Norway -vastausten muistivälimuisti (malli: OpenMeteoRepository).
 *  MET vaatii aina koordinaatit — nimipohjaista hakua ei ole. */
public final class MetNorwayRepository {

    private static final String TAG = "MetNorwayRepository";
    /** MET:n käyttöehdot kieltävät turhan tiheän haun; malli päivittyy noin tunneittain,
     *  mutta lyhyempi TTL pitää Päivitä-napin tuntuman järkevänä. */
    private static final long CACHE_TTL_MS = 10L * 60_000L;

    private static volatile MetNorwayRepository instance;

    public static MetNorwayRepository get(Context ctx) {
        if (instance == null) {
            synchronized (MetNorwayRepository.class) {
                if (instance == null) {
                    instance = new MetNorwayRepository(ctx.getApplicationContext());
                }
            }
        }
        return instance;
    }

    @SuppressWarnings("unused")
    private final Context appCtx;
    private final Map<String, CacheEntry> cache = new HashMap<>();

    private MetNorwayRepository(Context appCtx) {
        this.appCtx = appCtx;
    }

    /** Palauttaa cachen tuoreen tuloksen tai hakee uuden. Soittajan vastuulla on
     *  pyörittää tämä taustasäikeessä — fetch tekee verkkokutsun. */
    public WeatherData fetch(String label, double latitude, double longitude, boolean forceNetwork)
            throws Exception {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            throw new IllegalArgumentException("koordinaatit puuttuvat");
        }
        String key = coordinateKey(label, latitude, longitude);
        long now = System.currentTimeMillis();
        if (!forceNetwork) {
            synchronized (cache) {
                CacheEntry hit = cache.get(key);
                if (hit != null && (now - hit.data.fetchedAt) < CACHE_TTL_MS) {
                    Log.d(TAG, "cache HIT " + key
                            + " (" + (now - hit.data.fetchedAt) / 1000L + " s)");
                    return hit.data;
                }
            }
        }
        WeatherData data = new MetNorwayClient(latitude, longitude).fetch();
        synchronized (cache) {
            sweepExpiredLocked(now);
            cache.put(key, new CacheEntry(data));
        }
        return data;
    }

    /** Vanhentuneet pois ennen inserttiä, ettei paikkaa vaihtava käyttäjä kasvata
     *  cachea rajatta (sama korjaus kuin tabletin OM-repossa 2.4.2). */
    private void sweepExpiredLocked(long now) {
        Iterator<Map.Entry<String, CacheEntry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            if ((now - it.next().getValue().data.fetchedAt) >= CACHE_TTL_MS) it.remove();
        }
    }

    private static String coordinateKey(String label, double latitude, double longitude) {
        String name = (label == null || label.trim().isEmpty()) ? "Sijainti" : label.trim();
        return name.toLowerCase(Locale.ROOT) + "|" + latitude + "|" + longitude;
    }

    private static final class CacheEntry {
        final WeatherData data;
        CacheEntry(WeatherData data) { this.data = data; }
    }
}
