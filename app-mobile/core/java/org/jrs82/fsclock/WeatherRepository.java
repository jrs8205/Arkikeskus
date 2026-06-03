package org.jrs82.fsclock;

import android.annotation.SuppressLint;
import android.content.Context;

/** Säädatan UI-tason kosketuspinta. Tässä vaiheessa ohut wrapper, joka delegoi
 *  kotipaikkakunnan haun {@link FmiRepository}:lle. Tarkoituksena on, että
 *  ClockController saa yhden vakaan rajapinnan säädataan, jolloin retry-,
 *  scheduler- ja cache-logiikka voidaan myöhemmin siirtää tämän luokan
 *  sisään muuttamatta ClockControllerin kutsupaikkoja. */
public class WeatherRepository {

    // INSTANCE pitää referenssin sovelluskontekstiin (ks. get()), ei Activityyn,
    // joten lintin StaticFieldLeak on tässä false positive.
    @SuppressLint("StaticFieldLeak")
    private static WeatherRepository INSTANCE;

    private final Context appCtx;

    public static synchronized WeatherRepository get(Context ctx) {
        if (INSTANCE == null) {
            INSTANCE = new WeatherRepository(ctx.getApplicationContext());
        }
        return INSTANCE;
    }

    private WeatherRepository(Context appCtx) {
        this.appCtx = appCtx;
    }

    /** Hae kotipaikkakunnan sää ja tallenna havainto Room-tietokantaan
     *  (delegoi {@link FmiRepository#fetchHome(WeatherData)}:lle). */
    public WeatherData fetchHome(WeatherData cached) throws Exception {
        return FmiRepository.get(appCtx).fetchHome(cached);
    }

    public WeatherData fetchHome(WeatherData cached, boolean forceNetwork) throws Exception {
        return FmiRepository.get(appCtx).fetchHome(cached, forceNetwork);
    }

    /** Weather lookup for temporary place browsing. Does not persist to Room. */
    public WeatherData fetchBrowse(String place, WeatherData cached) throws Exception {
        return FmiRepository.get(appCtx).fetchBrowse(place, cached);
    }
}
