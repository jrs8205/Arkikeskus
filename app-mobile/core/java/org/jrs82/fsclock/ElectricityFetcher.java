package org.jrs82.fsclock;

import android.os.SystemClock;

import java.io.IOException;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.function.LongSupplier;

/** Hakee pörssisähkön hinnat ensisijaisesta lähteestä (Elering) ja turvautuu varalähteeseen vain
 *  kun ensisijainen haku epäonnistuu tai siitä puuttuu huominen, vaikka se pitäisi jo olla julki.
 *  Kaikki hintahaut (sähkösivu, widgetit, ilmoitus) kulkevat yhteisen {@link #shared()}-olion
 *  kautta, jotta varalähteen pyyntöraja ja sen viimeisin vastaus ovat koko sovelluksen yhteisiä. */
public final class ElectricityFetcher {

    public interface Source {
        ElectricityData fetchRange(long startMs, long endMs) throws Exception;
    }

    /** Haun tulos: hinnat sekä tieto siitä, vastasiko yksikään lähde tällä hakukerralla. */
    public static final class Result {
        public final ElectricityData data;
        /** True jos kumpikaan lähde ei vastannut nyt ja {@link #data} on aiemmin säilytetty
         *  varalähteen vastaus. Hinnat ovat käyttökelpoisia, mutta haku itse epäonnistui, joten
         *  puuttuvaa huomista ei pidä esittää vielä julkaisemattomana. */
        public final boolean stale;

        Result(ElectricityData data, boolean stale) {
            this.data = data;
            this.stale = stale;
        }
    }

    /** Varalähteen vartit kutsujan välille ja tieto siitä, saatiinko ne tällä hakukerralla. */
    private static final class FallbackAnswer {
        final ElectricityData data;
        final boolean fresh;

        FallbackAnswer(ElectricityData data, boolean fresh) {
            this.data = data;
            this.fresh = fresh;
        }
    }

    /** Varalähde sallii yhden pyynnön minuutissa per IP-osoite; pysytään selvästi sen alla. */
    static final long FALLBACK_MIN_INTERVAL_MS = 2L * 60_000L;
    /** Tästä kellonajasta (Suomen aikaa) alkaen huomisen hintojen pitäisi olla julki. */
    static final int TOMORROW_EXPECTED_MINUTE_OF_DAY = 14 * 60 + 30;

    private static volatile ElectricityFetcher shared;

    public static ElectricityFetcher shared() {
        if (shared == null) {
            synchronized (ElectricityFetcher.class) {
                if (shared == null) {
                    shared = new ElectricityFetcher(
                            (startMs, endMs) -> new ElectricityClient().fetchRange(startMs, endMs),
                            new ElectricityFallbackClient(),
                            System::currentTimeMillis,
                            SystemClock::elapsedRealtime);
                }
            }
        }
        return shared;
    }

    private final Source primary;
    private final Source fallback;
    /** Seinäkello: päivämäärä ja julkaisuaika. */
    private final LongSupplier wallClock;
    /** Monotoninen kello: varalähteen pyyntöväli, johon laitteen kellon korjaus ei saa vaikuttaa. */
    private final LongSupplier monotonicClock;

    /** Suojaa alla olevat kentät ja pitää varalähdehaun yhden kerrallaan: haun aikana saapuva
     *  toinen kutsuja odottaa ja saa saman vastauksen eikä jää ilman. */
    private final Object fallbackLock = new Object();
    /** Varalähteen viimeisin onnistunut vastaus rajaamattomana; jokaiselle kutsujalle leikataan oma väli. */
    private ElectricityData fallbackResponse;
    private boolean fallbackAttempted = false;
    private long lastFallbackAttemptElapsedMs;

    public ElectricityFetcher(Source primary, Source fallback, LongSupplier wallClock, LongSupplier monotonicClock) {
        this.primary = primary;
        this.fallback = fallback;
        this.wallClock = wallClock;
        this.monotonicClock = monotonicClock;
    }

    /** Kuten {@link #fetchResult}, mutta vain hinnat. Kutsujille, joille riittää käyttökelpoinen
     *  data riippumatta siitä, saatiinko se juuri nyt. */
    public ElectricityData fetch(long startMs, long endMs) throws Exception {
        return fetchResult(startMs, endMs).data;
    }

    /** Heittää ensisijaisen lähteen virheen, jos hintoja ei ole saatavilla mistään. */
    public Result fetchResult(long startMs, long endMs) throws Exception {
        long now = wallClock.getAsLong();
        ElectricityData fromPrimary = null;
        Exception primaryError = null;
        try {
            fromPrimary = primary.fetchRange(startMs, endMs);
            if (fromPrimary == null || fromPrimary.quarters.isEmpty()) {
                fromPrimary = null;
                primaryError = new IOException("ensisijainen lähde ei palauttanut hintoja");
            }
        } catch (Exception e) {
            primaryError = e;
        }

        if (fromPrimary != null && ElectricityData.hasFullTomorrow(fromPrimary.quarters, now)) {
            return new Result(fromPrimary, false);
        }

        // Ensisijainen epäonnistui tai siitä puuttuu huominen. Varalähteeltä jo saatu täydempi
        // vastaus käytetään kellonajasta riippumatta; kellonaika rajaa vain UUSIA pyyntöjä, jottei
        // varalähdettä kuormiteta joka aamupäivä ennen kuin huomisen kuuluukaan olla julki.
        boolean mayRequest = fromPrimary == null || tomorrowExpected(now);
        FallbackAnswer fromFallback = fromFallback(startMs, endMs, now, mayRequest);
        if (fromPrimary == null) {
            if (fromFallback.data == null) throw primaryError;
            return new Result(fromFallback.data, !fromFallback.fresh);
        }
        boolean fallbackHasTomorrow = fromFallback.data != null
                && ElectricityData.hasFullTomorrow(fromFallback.data.quarters, now);
        return new Result(fallbackHasTomorrow ? fromFallback.data : fromPrimary, false);
    }

    /** Varalähteen vartit väliltä [startMs, endMs) (null, jos niitä ei ole). Uusi pyyntö tehdään
     *  vain, jos kutsuja sallii sen ({@code mayRequest}), aiempi vastaus ei riitä (välille ei ole
     *  vartteja, tai huominen puuttuu vaikka sen pitäisi olla julki) ja pyyntöväli sallii; muuten
     *  käytetään aiempaa vastausta. */
    private FallbackAnswer fromFallback(long startMs, long endMs, long now, boolean mayRequest) {
        synchronized (fallbackLock) {
            ElectricityData known = slice(fallbackResponse, startMs, endMs);
            boolean sufficient = known != null
                    && (!tomorrowExpected(now) || ElectricityData.hasFullTomorrow(known.quarters, now));
            if (sufficient || !mayRequest) return new FallbackAnswer(known, false);

            long elapsed = monotonicClock.getAsLong();
            if (fallbackAttempted && elapsed - lastFallbackAttemptElapsedMs < FALLBACK_MIN_INTERVAL_MS) {
                return new FallbackAnswer(known, false);
            }
            fallbackAttempted = true;
            lastFallbackAttemptElapsedMs = elapsed;
            boolean fresh = false;
            try {
                ElectricityData response = fallback.fetchRange(Long.MIN_VALUE, Long.MAX_VALUE);
                if (response != null && !response.quarters.isEmpty()) {
                    fallbackResponse = response;
                    fresh = true;
                }
            } catch (Exception e) {
                // Aiempi vastaus kelpaa hinnoiksi (julkaistut hinnat eivät muutu), mutta ei tuoreena.
            }
            return new FallbackAnswer(slice(fallbackResponse, startMs, endMs), fresh);
        }
    }

    /** Uusi olio, jossa on vain välin [startMs, endMs) vartit; null jos niitä ei ole. */
    private static ElectricityData slice(ElectricityData all, long startMs, long endMs) {
        if (all == null) return null;
        ElectricityData out = new ElectricityData();
        out.source = all.source;
        out.fetchedAt = all.fetchedAt;
        for (ElectricityData.Quarter q : all.quarters) {
            if (q.timestamp >= startMs && q.timestamp < endMs) out.quarters.add(q);
        }
        return out.quarters.isEmpty() ? null : out;
    }

    private static boolean tomorrowExpected(long nowMs) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Europe/Helsinki"));
        c.setTimeInMillis(nowMs);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) >= TOMORROW_EXPECTED_MINUTE_OF_DAY;
    }
}
