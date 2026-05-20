package org.jrs82.fsclock;

import android.content.Context;
import android.util.Log;

import org.jrs82.fsclock.db.HistoryRepository;
import org.jrs82.fsclock.db.WeatherSample;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** FmiClientin ympärille kääritty kerros, joka yhdistää hakulogiikan
 *  ja DB-tallennuksen. Vain kotipaikkakunnan data tallennetaan
 *  (kanava {@link SettingsManager#homeChannel()}). Selailupaikkakunnan
 *  data jää muistivälimuistiin enintään 10 minuutiksi. */
public final class FmiRepository {

    private static final String TAG = "FmiRepository";
    private static final long BROWSE_CACHE_TTL_MS = 10L * 60_000L;

    private static volatile FmiRepository instance;

    public static FmiRepository get(Context context) {
        if (instance == null) {
            synchronized (FmiRepository.class) {
                if (instance == null) {
                    instance = new FmiRepository(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final HistoryRepository history;
    private final Map<String, BrowseCacheEntry> browseCache = new HashMap<>();

    private FmiRepository(Context appContext) {
        this.history = HistoryRepository.get(appContext);
    }

    /** Hae kotipaikkakunnan sää ja tallenna havainto DB:hen kanavalle
     *  {@link SettingsManager#homeChannel()}. */
    public WeatherData fetchHome(WeatherData cached) throws Exception {
        SettingsManager sm = SettingsManager.get();
        String place = sm.getHomePlace();
        WeatherData data = new FmiClient(place).fetch(cached);
        persistObservation(sm.homeChannel(), data);
        return data;
    }

    /** Hae minkä tahansa paikkakunnan sää selailua varten. Ei tallenna DB:hen.
     *  Sama paikka palautetaan 10 min muisticachesta jos sitä on saatavilla.
     *  Kutsuva taho saa palautuksen samalle säikeelle, joten kutsu on tehtävä
     *  taustasäikeestä (verkkokutsu tarvittaessa). */
    public WeatherData fetchBrowse(String place, WeatherData cached) throws Exception {
        if (place == null || place.trim().isEmpty()) {
            throw new IllegalArgumentException("place tyhjä");
        }
        String key = place.trim().toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        synchronized (browseCache) {
            BrowseCacheEntry hit = browseCache.get(key);
            if (hit != null && (now - hit.fetchedAt) < BROWSE_CACHE_TTL_MS) {
                Log.d(TAG, "browse cache HIT " + place + " (" + (now - hit.fetchedAt) / 1000L + " s)");
                return hit.data;
            }
        }
        WeatherData data = new FmiClient(place).fetch(cached);
        synchronized (browseCache) {
            browseCache.put(key, new BrowseCacheEntry(data, now));
        }
        return data;
    }

    private void persistObservation(String channel, WeatherData data) {
        if (data == null || data.current == null) return;
        if (data.current.timestamp <= 0) return;
        if (Double.isNaN(data.current.temperature)) return; // ei sample-pohjaa

        final WeatherSample s = new WeatherSample();
        s.channel = channel;
        s.timestamp = data.current.timestamp;
        s.temperature = data.current.temperature;
        s.humidity = nullIfNan(data.current.humidity);
        s.windSpeed = nullIfNan(data.current.windSpeed);
        s.windGust = nullIfNan(data.current.windGust);
        s.windDirection = nullIfNan(data.current.windDirection);
        s.precipitation1h = nullIfNan(data.current.precip1h);
        s.cloudCover = nullIfNan(data.current.cloudCover);
        s.radiationGlobal = nullIfNan(data.current.radiationGlobal);
        s.weatherSymbol = (data.current.condition != null)
                ? data.current.condition.rawSmartSymbol : null;
        s.batteryLevel = null;
        // pressure ei tällä hetkellä haeta FMI:ltä

        history.io().execute(() -> {
            try {
                history.saveSample(s);
            } catch (Exception e) {
                Log.w(TAG, "saveSample failed", e);
            }
        });
    }

    private static Double nullIfNan(double v) {
        return Double.isNaN(v) ? null : v;
    }

    private static final class BrowseCacheEntry {
        final WeatherData data;
        final long fetchedAt;
        BrowseCacheEntry(WeatherData data, long fetchedAt) {
            this.data = data;
            this.fetchedAt = fetchedAt;
        }
    }
}
