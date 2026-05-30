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
    private static final String PREFS = "mobile_electricity_averages_v2";
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
        if (cached != null) {
            try {
                JSONObject o = new JSONObject(cached);
                long savedAt = o.optLong("savedAt", 0L);
                int count = o.optInt("count", 0);
                double avg = o.optDouble("avg", Double.NaN);
                boolean fresh = monthEnded
                        || (System.currentTimeMillis() - savedAt) < 12L * 3600_000L;
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
        long[] range = monthRangeMs(year, month);
        try {
            ElectricityData data = new ElectricityClient().fetchRange(range[0], range[1]);
            // Bucketoi tunneittain (avain = päivä*100 + tunti, Helsingin aika): laske kunkin
            // tunnin keskiarvo ja sitten tuntien painottamaton keskiarvo. Näin sekamuotoinen
            // data (tuntihinnat + 1.10.2025 jälkeen varttihinnat) ei vääristä lukua, koska
            // pörssin virallinen kuukausikeskiarvo on tuntipohjainen. Vrt. FMI r_1h -bucketointi.
            java.util.HashMap<Integer, double[]> hourBuckets = new java.util.HashMap<>();
            for (ElectricityData.Quarter q : data.quarters) {
                // Varmista että hinta kuuluu pyydettyyn kuukauteen (Helsingin aika).
                if (q.year == year && q.month == month) {
                    int hourKey = q.dayOfMonth * 100 + q.hour;
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
                sum += acc[0] / acc[1];   // yhden tunnin keskiarvo (1 tuntihinta tai 4 varttia)
            }
            int count = hourBuckets.size();   // tuntien lukumäärä kuukaudessa
            double avg = sum / count;         // tuntien painottamaton keskiarvo

            JSONObject o = new JSONObject();
            o.put("avg", avg);
            o.put("count", count);
            o.put("savedAt", System.currentTimeMillis());
            p.edit().putString(key, o.toString()).apply();

            return new MonthAverage(year, month, avg, count);
        } catch (Exception e) {
            Log.w(TAG, "monthAverage " + year + "-" + month + " failed: " + e.getMessage());
            return null;
        }
    }

    /** Edellisen kokovuoden keskiarvo, painotettuna kuukausien näytemäärillä
     *  (= sama kuin koko vuoden kaikkien varttien keskiarvo). */
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

        double sum = 0.0;
        int count = 0;
        for (int m = 1; m <= 12; m++) {
            MonthAverage ma = monthAverage(ctx, prevYear, m, true);
            if (ma != null) {
                sum += ma.avgSntPerKwh * ma.sampleCount;
                count += ma.sampleCount;
            }
        }
        if (count == 0) return null;
        double avg = sum / count;
        try {
            JSONObject o = new JSONObject();
            o.put("avg", avg);
            o.put("count", count);
            o.put("savedAt", System.currentTimeMillis());
            p.edit().putString(key, o.toString()).apply();
        } catch (Exception ignored) {
        }
        return new MonthAverage(prevYear, 0, avg, count);
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
