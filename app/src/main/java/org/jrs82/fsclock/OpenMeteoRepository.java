package org.jrs82.fsclock;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Open-Meteon vastausten muistivälimuisti. Roomia ei käytetä 4C:ssä
 *  — supersää lukee aina muistista, ja jos cache on tyhjä/vanhentunut,
 *  haetaan rajapinta uudelleen. */
public final class OpenMeteoRepository {

    private static final String TAG = "OpenMeteoRepository";
    /** Sama TTL kuin FMI:n ennusteella: ei ole järkeä päivittää useammin
     *  kuin lähdettä (Open-Meteo-mallit päivitetään max kerran tunnissa). */
    private static final long CACHE_TTL_MS = 55L * 60_000L;

    private static volatile OpenMeteoRepository instance;

    public static OpenMeteoRepository get(Context ctx) {
        if (instance == null) {
            synchronized (OpenMeteoRepository.class) {
                if (instance == null) {
                    instance = new OpenMeteoRepository(ctx.getApplicationContext());
                }
            }
        }
        return instance;
    }

    @SuppressWarnings("unused")
    private final Context appCtx;
    private final Map<String, CacheEntry> cache = new HashMap<>();

    private OpenMeteoRepository(Context appCtx) {
        this.appCtx = appCtx;
    }

    /** Palauttaa cachen tuoreen tuloksen tai hakee uuden. Soittajan vastuulla
     *  on pyörittää tämä taustasäikeessä — fetch tekee verkkokutsun.  */
    public OpenMeteoData fetch(String placeName) throws Exception {
        return fetch(placeName, false);
    }

    public OpenMeteoData fetch(String placeName, boolean forceNetwork) throws Exception {
        if (placeName == null || placeName.trim().isEmpty()) {
            throw new IllegalArgumentException("placeName tyhjä");
        }
        String key = placeName.trim().toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        if (!forceNetwork) {
            synchronized (cache) {
                CacheEntry hit = cache.get(key);
                if (hit != null && (now - hit.data.fetchedAt) < CACHE_TTL_MS) {
                    Log.d(TAG, "cache HIT " + placeName
                            + " (" + (now - hit.data.fetchedAt) / 1000L + " s)");
                    return hit.data;
                }
            }
        }
        GeoPlace place = resolvePlace(placeName);
        OpenMeteoData data = new OpenMeteoClient(place).fetch();
        synchronized (cache) {
            sweepExpiredLocked(now);
            cache.put(key, new CacheEntry(data));
        }
        return data;
    }

    /** Sweep ennen insertointia, jotta cache ei kasva rajattomasti pitkän
     *  käyttöajan aikana (sama B5-korjaus kuin FmiRepository.browseCache). */
    private void sweepExpiredLocked(long now) {
        java.util.Iterator<Map.Entry<String, CacheEntry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            if ((now - it.next().getValue().data.fetchedAt) >= CACHE_TTL_MS) {
                it.remove();
            }
        }
    }

    /** Hae ennuste EKSPLISIITTISILLÄ koordinaateilla. Mobiilin etusivu käyttää tätä, jottei
     *  paikan koordinaatteja johdeta nimestä (muistissa elävä GeoPlace-rekisteri katoaa
     *  prosessin kuollessa → väärä paikka / hiljainen Vantaa-fallback). {@code label} on vain
     *  välimuistiavain + näyttönimi; sijainnin määräävät {@code latitude}/{@code longitude}.
     *  Heittää poikkeuksen jos koordinaatit puuttuvat — soittaja päättää jättää haun väliin. */
    public OpenMeteoData fetch(String label, double latitude, double longitude, boolean forceNetwork)
            throws Exception {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            throw new IllegalArgumentException("koordinaatit puuttuvat");
        }
        String name = (label == null || label.trim().isEmpty()) ? "Sijainti" : label.trim();
        String key = name.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        if (!forceNetwork) {
            synchronized (cache) {
                CacheEntry hit = cache.get(key);
                if (hit != null && (now - hit.data.fetchedAt) < CACHE_TTL_MS) {
                    Log.d(TAG, "cache HIT " + name
                            + " (" + (now - hit.data.fetchedAt) / 1000L + " s)");
                    return hit.data;
                }
            }
        }
        OpenMeteoData data = new OpenMeteoClient(GeoPlace.custom(name, latitude, longitude)).fetch();
        synchronized (cache) {
            sweepExpiredLocked(now);
            cache.put(key, new CacheEntry(data));
        }
        return data;
    }

    /** Palauttaa muistissa olevan version välittömästi (null jos ei haettu vielä). */
    public OpenMeteoData peek(String placeName) {
        if (placeName == null) return null;
        String key = placeName.trim().toLowerCase(Locale.ROOT);
        synchronized (cache) {
            CacheEntry hit = cache.get(key);
            return hit != null ? hit.data : null;
        }
    }

    private GeoPlace resolvePlace(String placeName) throws Exception {
        SettingsManager sm = SettingsManager.get();
        String trimmed = placeName == null ? "" : placeName.trim();
        String home = sm.getHomePlace() == null ? "" : sm.getHomePlace().trim();
        if (sm.hasHomeCoordinates()
                && !trimmed.isEmpty()
                && (trimmed.equalsIgnoreCase(home)
                || trimmed.toLowerCase(Locale.ROOT).startsWith(home.toLowerCase(Locale.ROOT) + ","))) {
            return GeoPlace.custom(trimmed, sm.getHomeLatitude(), sm.getHomeLongitude());
        }
        GeoPlace known = GeoPlace.tryForPlace(trimmed);
        if (known != null) return known;

        // FMI:n kaupunkihaku rekisteröi myös listan ulkopuoliset kunnat GeoPlaceen.
        FmiPlaceSearch.fetchCityNames();
        known = GeoPlace.tryForPlace(trimmed);
        if (known != null) return known;
        throw new IllegalArgumentException("Tuntematon Open-Meteo-paikka: " + trimmed);
    }

    private static final class CacheEntry {
        final OpenMeteoData data;
        CacheEntry(OpenMeteoData data) { this.data = data; }
    }
}
