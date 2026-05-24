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
 *  data jää muistivälimuistiin enintään 10 minuutiksi.
 *
 *  Bucketing (v1.6.4): kotipaikkakunnan havainnot pyöristetään 10 min
 *  slot-aikaleimaan ennen DB-tallennusta. Jos saman slotin sample on jo
 *  DB:ssä eikä ennuste-cache ole vanhentunut, fetchHome palauttaa tallennetun
 *  arvon ilman uutta FMI-pyyntöä. Tämä yhdenmukaistaa useilla laitteilla
 *  näkyvän havainnon (sama slot → sama "Sää päivitetty: HH:MM"). */
public final class FmiRepository {

    private static final String TAG = "FmiRepository";
    private static final long BROWSE_CACHE_TTL_MS = 10L * 60_000L;
    /** Slot-pituus bucketingissa: 10 min tasakymmenten välein. */
    static final long SLOT_MS = 10L * 60_000L;
    /** Sama vakio kuin FmiClient.FORECAST_MAX_AGE_MS — pidetty kahdessa paikassa
     *  jotta DB-cache-polku ei vaadi FmiClientin sisäisen vakion julkistamista. */
    private static final long FORECAST_MAX_AGE_MS = 55L * 60_000L;

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
     *  {@link SettingsManager#homeChannel()}.
     *
     *  Bucketing: ennen FMI-pyyntöä tarkistetaan, onko nykyiselle 10 min slotille
     *  jo DB:ssä sample ja onko cached-ennuste edelleen tuore. Jos kumpikin pätee,
     *  palautetaan rakennettu WeatherData ilman verkkokutsua. Muussa tapauksessa
     *  haetaan FMI:ltä ja pyöristetään havainnon timestamp slotin alkuun ennen
     *  DB-tallennusta. */
    public WeatherData fetchHome(WeatherData cached) throws Exception {
        SettingsManager sm = SettingsManager.get();
        String channel = sm.homeChannel();
        long now = System.currentTimeMillis();
        long slotStart = slotStartFor(now);

        if (canServeFromDb(cached, now)) {
            WeatherSample slotSample = history.getLatestInSlot(channel, slotStart, slotStart + SLOT_MS);
            if (slotSample != null) {
                Log.d(TAG, "fetchHome slot " + slotStart + " löytyi DB:stä, ei FMI-pyyntöä");
                return buildFromSample(cached, slotSample, now);
            }
        }

        String place = sm.getHomePlace();
        WeatherData data = new FmiClient(place).fetch(cached);
        roundCurrentToSlot(data);
        persistObservation(channel, data);
        return data;
    }

    /** Voiko paluuarvon koota DB-samplesta? Vaatii että cached-ennuste on
     *  käytettävissä eikä yli {@link #FORECAST_MAX_AGE_MS} vanha (muuten ennuste
     *  pitää joka tapauksessa hakea FMI:ltä, jolloin DB-cache ei säästä mitään). */
    private boolean canServeFromDb(WeatherData cached, long nowMs) {
        if (cached == null) return false;
        if (cached.hours == null || cached.hours.isEmpty()) return false;
        if (cached.forecastFetchedAt <= 0L) return false;
        return (nowMs - cached.forecastFetchedAt) <= FORECAST_MAX_AGE_MS;
    }

    /** Pyöristä havainnon timestamp 10 min slot-alkuun. UNIQUE-indeksi
     *  (channel, timestamp) takaa ettei sama slot kirjoitu kahdesti, vaikka
     *  saman tickin aikana tulisi useita rinnakkaisia kutsuja. */
    private static void roundCurrentToSlot(WeatherData data) {
        if (data == null || data.current == null) return;
        if (data.current.timestamp <= 0) return;
        data.current.timestamp = slotStartFor(data.current.timestamp);
    }

    private static long slotStartFor(long ms) {
        return (ms / SLOT_MS) * SLOT_MS;
    }

    /** Rakentaa palautettavan WeatherData-olion cached-ennusteesta ja DB-samplesta.
     *  Ennuste säilyy cachedista (sama instanssi, vain mennyt suodatus tehdään
     *  matalan vaikutuksen takia). current täytetään slot-samplen kentistä. */
    private WeatherData buildFromSample(WeatherData cached, WeatherSample s, long nowMs) {
        WeatherData out = new WeatherData();
        out.fetchedAt = nowMs;
        out.forecastFetchedAt = cached.forecastFetchedAt;

        long minTs = nowMs - 60L * 60_000L;
        for (WeatherData.Hour h : cached.hours) {
            if (h.timestamp >= minTs) out.hours.add(h);
        }

        WeatherData.Current c = out.current;
        c.timestamp = s.timestamp;
        c.temperature = s.temperature;
        c.humidity = (s.humidity != null) ? s.humidity : Double.NaN;
        c.windSpeed = (s.windSpeed != null) ? s.windSpeed : Double.NaN;
        c.windGust = (s.windGust != null) ? s.windGust : Double.NaN;
        c.windDirection = (s.windDirection != null) ? s.windDirection : Double.NaN;
        c.cloudCover = (s.cloudCover != null) ? s.cloudCover : Double.NaN;
        c.radiationGlobal = (s.radiationGlobal != null) ? s.radiationGlobal : Double.NaN;
        c.precip1h = (s.precipitation1h != null) ? s.precipitation1h : Double.NaN;
        // rain24h:tä ei voi rekonstruoida yhdestä rivistä — pidetään cached-arvo
        // jos saatavilla, muuten merkitään puuttuvaksi.
        if (cached.current != null && !cached.current.rain24hAllMissing) {
            c.rain24h = cached.current.rain24h;
            c.rain24hAllMissing = false;
        }
        // wawa-koodi → WeatherCondition. FmiClient.applyCurrentCondition rakentaisi
        // tämän, mutta DB-polulla luotetaan cachediin (sama slot → sama tila).
        if (cached.current != null && cached.current.condition != null) {
            c.condition = cached.current.condition;
        }
        if (!Double.isNaN(c.temperature)) {
            c.feelsLike = WeatherData.computeFeelsLike(c.temperature, c.windSpeed, c.humidity);
        }
        return out;
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
            // Sweepataan vanhentuneet ennen insertointia, jotta cache ei kasva
            // rajattomasti pitkän kayttoajan aikana (B5-korjaus).
            sweepExpiredLocked(now);
            browseCache.put(key, new BrowseCacheEntry(data, now));
        }
        return data;
    }

    private void sweepExpiredLocked(long now) {
        java.util.Iterator<Map.Entry<String, BrowseCacheEntry>> it = browseCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, BrowseCacheEntry> e = it.next();
            if ((now - e.getValue().fetchedAt) >= BROWSE_CACHE_TTL_MS) {
                it.remove();
            }
        }
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
        // weatherSymbol jätetään uusille riveille NULL:ksi: legacy-kenttä tuli
        // ennusteen rawSmartSymbol-arvosta ja heilahti laitteittain. observedWawa
        // on uusi totuusarvo (havainnon wawa-koodi).
        s.weatherSymbol = null;
        // observedWawa: deterministinen havainnon wawa-koodi, ei riipu ennusteesta.
        // FmiClient.parseObservations asettaa data.current.condition.rawWawan ennen
        // applyCurrentCondition-yhdistämistä, joten arvo on suoraan havainnoista.
        s.observedWawa = (data.current.condition != null)
                ? data.current.condition.rawWawa : null;
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
