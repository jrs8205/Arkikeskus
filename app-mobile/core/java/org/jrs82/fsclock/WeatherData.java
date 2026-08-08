package org.jrs82.fsclock;

import java.util.ArrayList;
import java.util.List;

public class WeatherData {

    public static class Current {
        public double temperature = Double.NaN;
        public double feelsLike = Double.NaN;
        public double humidity = Double.NaN;
        public double windSpeed = Double.NaN;
        public double windGust = Double.NaN;
        public double windDirection = Double.NaN;
        public double cloudCover = Double.NaN; // 0-100 %
        public double radiationGlobal = Double.NaN;
        public double precip1h = Double.NaN; // viimeisin r_1h-havainto (mm/h)
        public double rain24h = Double.NaN;
        public boolean rain24hAllMissing = true;
        public WeatherCondition condition = WeatherCondition.unknown();
        public long timestamp = 0;
    }

    public static class Hour {
        public long timestamp;
        public int hour;
        public int dayOfMonth;
        public int month;
        public double temperature = Double.NaN;
        public double precipitation = Double.NaN;
        public double windSpeed = Double.NaN;
        public double windGust = Double.NaN;
        public double humidity = Double.NaN;
        public double radiationGlobal = Double.NaN;
        /** MET Norwayn myöhemmät päivät ovat 6 h -lohkoja: precipitation on silloin
         *  lohkon summa, ei tuntiarvo. FMI/OM-tunneilla aina 1. */
        public int blockHours = 1;
        public WeatherCondition condition = WeatherCondition.unknown();
    }

    public Current current = new Current();
    public List<Hour> hours = new ArrayList<>();
    public long fetchedAt = 0;
    /** Aikaleima viimeisestä onnistuneesta ennustehausta. 0 = ei vielä haettu.
     *  Käytetään päättämään, voiko ennusteen jättää uudelleenhakematta seuraavalla
     *  havaintosyklillä. */
    public long forecastFetchedAt = 0;

    /** Pyoristys: |x| < 0.5 -> 0.0, etta -0 C ei tule nakyviin. */
    public static double cleanZero(double v) {
        if (Double.isNaN(v)) return v;
        return Math.abs(v) < 0.5 ? 0.0 : v;
    }

    /** FMI:n virallinen "tuntuu kuin" (smartmet-library-newbase, FmiFeelsLikeTemperature):
     *  oma tuulisovite + Summer Simmer -lämpöindeksi + valinnainen säteilykorjaus.
     *  Toisin kuin aiempi wind chill / heat index -pari, tämä vaikuttaa myös
     *  välilämpötiloissa (10–27 °C), joten arvo eroaa mitatusta aina kun tuulee.
     *  Puuttuva tuuli käsitellään tyynenä (kaava palauttaa silloin tasan tC:n)
     *  ja puuttuva kosteus/säteily ohittaa kyseisen termin. */
    public static double computeFeelsLike(double tC, double windMs, double humidity,
                                          double radiation) {
        if (Double.isNaN(tC)) return Double.NaN;
        double wind = (Double.isNaN(windMs) || windMs < 0.0) ? 0.0 : windMs;
        double a = 15.0;   // sovitus: vastaa kanadalaista wind chilliä T=0:ssa
        double t0 = 37.0;  // hyytävyyskäyrä vaakasuora tässä lämpötilassa
        double chill = a + (1 - a / t0) * tC
                + a / t0 * Math.pow(wind + 1, 0.16) * (tC - t0);
        double heat = summerSimmerIndex(humidity, tC);
        double feels = tC + (chill - tC) + (heat - tC);
        if (!Double.isNaN(radiation) && radiation >= 0.0) {
            double absorption = 0.07; // rad=800 tyynellä → +4 °C, rad=50 → 0 °C
            feels += 0.7 * absorption * radiation / (wind + 10) - 0.25;
        }
        return feels;
    }

    /** Summer Simmer Index FMI:n käyttämässä muodossa (rh-referenssi 50 %). */
    private static double summerSimmerIndex(double rh, double t) {
        if (t <= 14.5 || Double.isNaN(rh)) return t;
        final double RH_REF = 0.5;
        double r = rh / 100.0;
        return (1.8 * t - 0.55 * (1 - r) * (1.8 * t - 26) - 0.55 * (1 - RH_REF) * 26)
                / (1.8 * (1 - 0.55 * (1 - RH_REF)));
    }

    public static String windDirToCompass(double deg) {
        if (Double.isNaN(deg)) return "";
        String[] dirs = {"Pohjoinen", "Koillinen", "Itä", "Kaakko",
                "Etelä", "Lounas", "Länsi", "Luode"};
        int idx = (int) Math.round(((deg % 360) / 45.0)) % 8;
        return dirs[idx];
    }
}
