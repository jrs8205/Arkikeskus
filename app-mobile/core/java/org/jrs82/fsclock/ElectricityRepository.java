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
 *  Huomispäivän julkaisu päivittyy yleensä klo 14.30 jälkeen Suomen aikaa.
 *  Kun käyttäjä haluaa nähdä
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
                    instance = new ElectricityRepository(ElectricityFetcher.shared());
                }
            }
        }
        return instance;
    }

    private final ElectricityFetcher fetcher;

    private ElectricityData data; // tänään + huominen yhdessä paketissa
    private long lastFetchOk = 0L;
    private boolean lastFetchFailed = false;

    ElectricityRepository(ElectricityFetcher fetcher) {
        this.fetcher = fetcher;
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
            // Epäonnistunut viimeisin yritys ei ole koskaan tuore: silloin haetaan uudelleen, vaikka
            // sitä edeltänyt onnistunut haku olisi välimuistin iän sisällä.
            if (data != null && !lastFetchFailed && (now - lastFetchOk) < CACHE_TTL_MS) {
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

        ElectricityFetcher.Result result;
        try {
            result = fetcher.fetchResult(startMs, endMs);
        } catch (Exception e) {
            synchronized (this) {
                this.lastFetchFailed = true;
            }
            throw e;
        }
        ElectricityData fresh = result.data;
        ElectricityData current;
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (result.stale) {
                // Kumpikaan lähde ei vastannut: haku epäonnistui, vaikka aiemmin säilytetyt hinnat
                // saatiinkin. Ne otetaan käyttöön, jos ne kattavat tulevaa aikaa enemmän kuin
                // varaston oma data (esim. toisen kutsujan varalähteestä saama huominen). Dataa ei
                // merkitä tuoreeksi, jotta seuraava haku yrittää heti uudelleen.
                if (this.data == null || upcomingQuarters(fresh, now) > upcomingQuarters(this.data, now)) {
                    this.data = fresh;
                }
                this.lastFetchFailed = true;
            } else {
                // Julkaistut hinnat eivät muutu. Jos muistissa on jo huominen eikä tuoreessa
                // vastauksessa ole, vastaus on vajaa (lähteen hetkellinen häiriö tai rinnakkaisen
                // haun vanhempi tulos) eikä saa pudottaa huomista pois; muistissa oleva data
                // kattaa saman päivän kokonaan.
                boolean wouldLoseTomorrow = data != null
                        && ElectricityData.hasFullTomorrow(data.quarters, now)
                        && !ElectricityData.hasFullTomorrow(fresh.quarters, now);
                if (!wouldLoseTomorrow) this.data = fresh;
                this.lastFetchOk = now;
                this.lastFetchFailed = false;
            }
            current = this.data;
        }
        Log.d(TAG, "fetch " + (result.stale ? "stale" : "ok") + ", " + current.quarters.size() + " vartti(a)");
        return current;
    }

    /** Montako varttia datassa on, jotka eivät ole vielä päättyneet hetkellä {@code nowMs}. */
    private static int upcomingQuarters(ElectricityData data, long nowMs) {
        int n = 0;
        for (ElectricityData.Quarter q : data.quarters) {
            if (q.timestamp + 15L * 60_000L > nowMs) n++;
        }
        return n;
    }

    /** True jos viimeisin hakuyritys epäonnistui kummastakin lähteestä. Näkymä erottaa tällä
     *  tilan "haku epäonnistui" tilasta "hintoja ei ole vielä julkaistu". */
    public synchronized boolean lastFetchFailed() {
        return lastFetchFailed;
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

    /** True jos välimuistissa on huomispäivän hinnat iltaan asti ({@link ElectricityData#hasFullTomorrow}). */
    public synchronized boolean hasFullTomorrow() {
        return data != null && ElectricityData.hasFullTomorrow(data.quarters, System.currentTimeMillis());
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
