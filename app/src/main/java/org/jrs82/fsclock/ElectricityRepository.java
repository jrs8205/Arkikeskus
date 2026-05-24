package org.jrs82.fsclock;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** Sähkönhinnan muistivälimuisti. Pidetään muistissa kaksi NordPool-päivää
 *  (tänään + huominen) ja päivitetään fetchillä taustasäikeestä.
 *
 *  Huomispäivän julkaisu tapahtuu n. klo 14 EET. Kun käyttäjä haluaa nähdä
 *  huomenna heti kun saatavilla, ClockController kutsuu fetchTomorrowIfNeeded()
 *  toistuvasti, kunnes huomenna-vartteja löytyy. */
public final class ElectricityRepository {

    private static final String TAG = "ElectricityRepository";
    private static final long CACHE_TTL_MS = 55L * 60_000L;

    private static volatile ElectricityRepository instance;

    public static ElectricityRepository get(Context ctx) {
        if (instance == null) {
            synchronized (ElectricityRepository.class) {
                if (instance == null) {
                    instance = new ElectricityRepository(ctx.getApplicationContext());
                }
            }
        }
        return instance;
    }

    @SuppressWarnings("unused")
    private final Context appCtx;
    private final ElectricityClient client = new ElectricityClient();

    private ElectricityData data; // tänään + huominen yhdessä paketissa
    private long lastFetchOk = 0L;

    private ElectricityRepository(Context appCtx) {
        this.appCtx = appCtx;
    }

    /** Palauttaa muistissa olevan datan välittömästi (voi olla null). */
    public synchronized ElectricityData peek() {
        return data;
    }

    /** Hakee uuden datan, jos cache on vanha. Tämä on synkroninen verkkokutsu —
     *  soittajan vastuulla on ajaa taustasäikeessä. */
    public ElectricityData fetchIfStale() throws Exception {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (data != null && (now - lastFetchOk) < CACHE_TTL_MS) {
                return data;
            }
        }
        return fetchNow();
    }

    /** Pakotettu päivitys (esim. huomisen poll-yritys). */
    public ElectricityData fetchNow() throws Exception {
        TimeZone hel = TimeZone.getTimeZone("Europe/Helsinki");
        Calendar c = Calendar.getInstance(hel);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long startMs = c.getTimeInMillis();
        // Ikkuna kalenteripäivinä eikä millisekunteina, jotta DST-siirtymät
        // (25 h tai 23 h vrk) eivät tipota tai duplikoi vartteja.
        c.add(Calendar.DAY_OF_YEAR, 2);
        long endMs = c.getTimeInMillis();

        ElectricityData fresh = client.fetchRange(startMs, endMs);
        synchronized (this) {
            this.data = fresh;
            this.lastFetchOk = System.currentTimeMillis();
        }
        Log.d(TAG, "fetch ok, " + fresh.quarters.size() + " vartti(a)");
        return fresh;
    }

    /** True jos välimuistissa on yhtään huomispäivän varttia. */
    public synchronized boolean hasTomorrow() {
        if (data == null) return false;
        TimeZone hel = TimeZone.getTimeZone("Europe/Helsinki");
        Calendar c = Calendar.getInstance(hel);
        c.add(Calendar.DAY_OF_YEAR, 1);
        int tomY = c.get(Calendar.YEAR);
        int tomM = c.get(Calendar.MONTH) + 1;
        int tomD = c.get(Calendar.DAY_OF_MONTH);
        for (ElectricityData.Quarter q : data.quarters) {
            if (q.year == tomY && q.month == tomM && q.dayOfMonth == tomD) return true;
        }
        return false;
    }

    /** Palauttaa tämän hetken vartin (nykyaika EET) tai null jos cache ei kata
     *  nykyhetkeä. Ei palauteta vanhentunutta varttia, jotta headerissa ei näy
     *  eilistä/yli-ikäistä hintaa verkko-ongelmien tai päivänvaihdon yli. */
    public synchronized ElectricityData.Quarter currentQuarter() {
        if (data == null || data.quarters.isEmpty()) return null;
        long now = System.currentTimeMillis();
        for (ElectricityData.Quarter q : data.quarters) {
            if (q.timestamp <= now && q.timestamp + 15L * 60_000L > now) return q;
        }
        return null;
    }

    /** Annetun päivän vartit aikajärjestyksessä. */
    public synchronized List<ElectricityData.Quarter> dayQuarters(int year, int month, int day) {
        List<ElectricityData.Quarter> out = new ArrayList<>();
        if (data == null) return out;
        for (ElectricityData.Quarter q : data.quarters) {
            if (q.year == year && q.month == month && q.dayOfMonth == day) out.add(q);
        }
        return out;
    }
}
