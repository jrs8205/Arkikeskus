package org.jrs82.fsclock.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.jrs82.fsclock.ElectricityClient;
import org.jrs82.fsclock.ElectricityData;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Laskee pörssisähkön (Elering/Nord Pool FI, ALV 0 %) keskiarvot vertailua varten:
 * edellisen kokovuoden keskiarvo + kuluvan vuoden toteutuneet kuukausikeskiarvot.
 *
 * <p>Data haetaan {@link ElectricityClient#fetchRange} -metodilla kuukausi kerrallaan.
 * Keskiarvo lasketaan painottamattomana TUNTIkeskiarvona: ensin bucketoidaan tunneittain
 * ja lasketaan kunkin tunnin keskiarvo, sitten tuntien keskiarvo. Tämä vastaa pörssin
 * virallista keskihintaa myös sen jälkeen kun Nord Pool siirtyi 1.10.2025 vartteihin
 * (15 min) — muuten varttitunnit painottuisivat 4x tuntidatan tunteihin nähden ja
 * keskiarvo vääristyisi ylöspäin (2025 koko vuosi: 4,21 vs. oikea 4,05 snt/kWh).
 * Tulokset tallennetaan SharedPreferencesiin JSON-välimuistiin, jotta dataa ei haeta
 * joka avauksella.
 *
 * <p>Yksikkö snt/kWh, ALV 0 % — sama kuin sähkösivun muut hinnat.
 */
final class ElectricityAverages {

    private static final String TAG = "ElectricityAverages";
    // v2: tuntibucketointi (1.4.1) — vanha v1-cache oli varttipainotettu, hylätään.
    // v3: tunnin avain aikaleimasta (talviaikaan siirtymisen toistuva tunti omaksi tunnikseen) + vuosiarvo
    //     vain kaikista 12 kuukaudesta — v2:ssa saattoi olla kesken kuun tallennettuja lopullisia arvoja.
    private static final String PREFS = "mobile_electricity_averages_v3";
    private static final TimeZone HELSINKI = TimeZone.getTimeZone("Europe/Helsinki");

    /** Yhden kuukauden keskiarvo. */
    static final class MonthAverage {
        final int year;
        final int month;   // 1..12
        final double avgSntPerKwh;
        final int sampleCount;

        MonthAverage(int year, int month, double avg, int count) {
            this.year = year;
            this.month = month;
            this.avgSntPerKwh = avg;
            this.sampleCount = count;
        }
    }

    private ElectricityAverages() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String monthKey(int year, int month) {
        return "avg_" + year + "_" + String.format(java.util.Locale.US, "%02d", month);
    }

    /**
     * Palauttaa kuukauden keskiarvon. Käyttää välimuistia jos:
     * - kuukausi on jo päättynyt (lopullinen arvo, ei vanhene), TAI
     * - kuluva kuukausi ja välimuisti on alle 12 h vanha.
     * Muuten hakee Eleringistä ja tallentaa.
     */
    static MonthAverage monthAverage(Context ctx, int year, int month, boolean allowNetwork) {
        SharedPreferences p = prefs(ctx);
        String key = monthKey(year, month);
        String cached = p.getString(key, null);

        boolean monthEnded = monthHasEnded(year, month);
        long[] range = monthRangeMs(year, month);
        if (cached != null) {
            try {
                JSONObject o = new JSONObject(cached);
                long savedAt = o.optLong("savedAt", 0L);
                int count = o.optInt("count", 0);
                double avg = o.optDouble("avg", Double.NaN);
                boolean fresh = cacheUsable(monthEnded, savedAt, range[1], System.currentTimeMillis());
                if (!Double.isNaN(avg) && count > 0 && fresh) {
                    return new MonthAverage(year, month, avg, count);
                }
            } catch (Exception ignored) {
            }
        }
        if (!allowNetwork) {
            return null;
        }

        // Hae kuukauden aikaväli [kuukauden alku, seuraavan kuukauden alku) Helsingin ajassa.
        try {
            ElectricityData data = new ElectricityClient().fetchRange(range[0], range[1]);
            MonthAverage result = hourlyAverage(data.quarters, year, month);
            if (result == null) return null;

            JSONObject o = new JSONObject();
            o.put("avg", result.avgSntPerKwh);
            o.put("count", result.sampleCount);
            o.put("savedAt", System.currentTimeMillis());
            p.edit().putString(key, o.toString()).apply();

            return result;
        } catch (Exception e) {
            Log.w(TAG, "monthAverage " + year + "-" + month + " failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Kuukauden tuntien painottamaton keskiarvo: ensin kunkin tunnin keskiarvo (1 tuntihinta tai
     * 4 varttia), sitten tuntien keskiarvo. Näin sekamuotoinen data (tuntihinnat + 1.10.2025 jälkeen
     * varttihinnat) ei vääristä lukua, koska pörssin virallinen kuukausikeskiarvo on tuntipohjainen.
     * Tunnin avain on aikaleima eikä paikallinen tunti: talviaikaan siirryttäessä tunti 03 on kahdesti
     * ja paikallisella avaimella nämä kaksi tuntia sulautuisivat yhdeksi. null jos kuulta ei ole hintoja.
     */
    static MonthAverage hourlyAverage(java.util.List<ElectricityData.Quarter> quarters, int year, int month) {
        java.util.HashMap<Long, double[]> hourBuckets = new java.util.HashMap<>();
        for (ElectricityData.Quarter q : quarters) {
            // Varmista että hinta kuuluu pyydettyyn kuukauteen (Helsingin aika).
            if (q.year == year && q.month == month) {
                long hourKey = Math.floorDiv(q.timestamp, 3_600_000L);
                double[] acc = hourBuckets.get(hourKey);
                if (acc == null) {
                    acc = new double[2];
                    hourBuckets.put(hourKey, acc);
                }
                acc[0] += q.sntPerKwh;
                acc[1] += 1.0;
            }
        }
        if (hourBuckets.isEmpty()) return null;
        double sum = 0.0;
        for (double[] acc : hourBuckets.values()) {
            sum += acc[0] / acc[1];
        }
        int count = hourBuckets.size();
        return new MonthAverage(year, month, sum / count, count);
    }

    /** Edellisen kokovuoden keskiarvo, painotettuna kuukausien tuntimäärillä
     *  (= sama kuin koko vuoden kaikkien tuntien keskiarvo). */
    static MonthAverage previousYearAverage(Context ctx, boolean allowNetwork) {
        Calendar now = Calendar.getInstance(HELSINKI);
        int prevYear = now.get(Calendar.YEAR) - 1;
        SharedPreferences p = prefs(ctx);
        String key = "avg_year_" + prevYear;
        String cached = p.getString(key, null);
        if (cached != null) {
            try {
                JSONObject o = new JSONObject(cached);
                double avg = o.optDouble("avg", Double.NaN);
                int count = o.optInt("count", 0);
                if (!Double.isNaN(avg) && count > 0) {
                    // Päättynyt vuosi → lopullinen, ei vanhene.
                    return new MonthAverage(prevYear, 0, avg, count);
                }
            } catch (Exception ignored) {
            }
        }
        if (!allowNetwork) return null;

        MonthAverage[] months = new MonthAverage[12];
        for (int m = 1; m <= 12; m++) {
            months[m - 1] = monthAverage(ctx, prevYear, m, true);
            if (months[m - 1] == null) return null;
        }
        MonthAverage result = yearFromMonths(prevYear, months);
        if (result == null) return null;
        try {
            JSONObject o = new JSONObject();
            o.put("avg", result.avgSntPerKwh);
            o.put("count", result.sampleCount);
            o.put("savedAt", System.currentTimeMillis());
            p.edit().putString(key, o.toString()).apply();
        } catch (Exception ignored) {
        }
        return result;
    }

    /** Vuosikeskiarvo kuukausista tuntimäärillä painotettuna. null jos yksikin kuukausi puuttuu:
     *  vuosiarvo tallennetaan lopullisena, joten vajaasta vuodesta laskettu luku jäisi pysyväksi. */
    static MonthAverage yearFromMonths(int year, MonthAverage[] months) {
        if (months.length != 12) return null;
        double sum = 0.0;
        int count = 0;
        for (MonthAverage ma : months) {
            if (ma == null || ma.sampleCount <= 0) return null;
            sum += ma.avgSntPerKwh * ma.sampleCount;
            count += ma.sampleCount;
        }
        return new MonthAverage(year, 0, sum / count, count);
    }

    /** Kelpaako välimuistin keskiarvo. Päättyneen kuun arvo on lopullinen vain jos se on tallennettu
     *  kuun päätyttyä — kesken kuun tallennettu kattaa vain alkukuun ja jäisi muuten pysyvästi vääräksi.
     *  Kuluvan kuun arvo vanhenee 12 tunnissa. */
    static boolean cacheUsable(boolean monthEnded, long savedAt, long monthEndMs, long nowMs) {
        if (savedAt > nowMs) return false; // kelloa siirretty taaksepäin → tallennusaikaan ei voi luottaa
        if (monthEnded) return savedAt >= monthEndMs;
        return (nowMs - savedAt) < 12L * 3600_000L;
    }

    private static boolean monthHasEnded(int year, int month) {
        Calendar now = Calendar.getInstance(HELSINKI);
        int curYear = now.get(Calendar.YEAR);
        int curMonth = now.get(Calendar.MONTH) + 1;
        return (year < curYear) || (year == curYear && month < curMonth);
    }

    /** [kuukauden alku ms, seuraavan kuukauden alku ms) Helsingin ajassa. */
    private static long[] monthRangeMs(int year, int month) {
        Calendar start = Calendar.getInstance(HELSINKI);
        start.clear();
        start.set(year, month - 1, 1, 0, 0, 0);
        Calendar end = Calendar.getInstance(HELSINKI);
        end.clear();
        if (month == 12) {
            end.set(year + 1, 0, 1, 0, 0, 0);
        } else {
            end.set(year, month, 1, 0, 0, 0);
        }
        return new long[]{ start.getTimeInMillis(), end.getTimeInMillis() };
    }
}
