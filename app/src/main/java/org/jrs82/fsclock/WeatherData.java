package org.jrs82.fsclock;

import java.util.ArrayList;
import java.util.List;

public class WeatherData {

    public static class Current {
        public double temperature = Double.NaN;
        public double feelsLike = Double.NaN;
        public double humidity = Double.NaN;
        public double windSpeed = Double.NaN;
        public double windDirection = Double.NaN;
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

    public static double computeFeelsLike(double tC, double windMs, double humidity) {
        if (Double.isNaN(tC)) return Double.NaN;
        if (tC <= 10.0 && !Double.isNaN(windMs) && windMs > 1.4) {
            // Wind chill (Environment Canada)
            double v = Math.pow(windMs * 3.6, 0.16);
            return 13.12 + 0.6215 * tC - 11.37 * v + 0.3965 * tC * v;
        }
        if (tC >= 26.7 && !Double.isNaN(humidity) && humidity > 40.0) {
            // Heat index (Steadman, lyhenne)
            double T = tC * 9.0 / 5.0 + 32.0;
            double R = humidity;
            double hi = -42.379 + 2.04901523 * T + 10.14333127 * R
                    - 0.22475541 * T * R - 0.00683783 * T * T
                    - 0.05481717 * R * R + 0.00122874 * T * T * R
                    + 0.00085282 * T * R * R - 0.00000199 * T * T * R * R;
            return (hi - 32.0) * 5.0 / 9.0;
        }
        return tC;
    }

    public static String windDirToCompass(double deg) {
        if (Double.isNaN(deg)) return "";
        String[] dirs = {"Pohjoinen", "Koillinen", "Itä", "Kaakko",
                "Etelä", "Lounas", "Länsi", "Luode"};
        int idx = (int) Math.round(((deg % 360) / 45.0)) % 8;
        return dirs[idx];
    }
}
