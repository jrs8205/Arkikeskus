package org.jrs82.fsclock;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class Astronomy {

    private static final Locale FI = new Locale("fi", "FI");
    private static final double ZENITH = 90.833;
    private static final double SYNODIC_MONTH_DAYS = 29.53058867;
    private static final long KNOWN_NEW_MOON_UTC_MS = 947182440000L; // 2000-01-06 18:14 UTC

    private Astronomy() {}

    public static SunMoon calculate(Date date, double latitude, double longitude, TimeZone zone) {
        SunTimes sun = calculateSun(date, latitude, longitude, zone);
        MoonPhase moon = calculateMoon(date);
        return new SunMoon(sun, moon);
    }

    private static SunTimes calculateSun(Date date, double latitude, double longitude, TimeZone zone) {
        Calendar cal = Calendar.getInstance(zone, FI);
        cal.setTime(date);
        int dayOfYear = cal.get(Calendar.DAY_OF_YEAR);
        int rise = calculateSunEventMinutes(dayOfYear, latitude, longitude, zone, true, date);
        int set = calculateSunEventMinutes(dayOfYear, latitude, longitude, zone, false, date);
        return new SunTimes(rise, set);
    }

    /** NOAA:n auringonlaskentayhtälöt (General Solar Position Calculations,
     *  NOAA Global Monitoring Division). Tarkkuus tyypillisesti ±1 min — korvasi
     *  aiemman Williams-algoritmin, jonka virhe kasvoi korkeilla leveysasteilla. */
    private static int calculateSunEventMinutes(int dayOfYear, double lat, double lon,
                                                TimeZone zone, boolean sunrise, Date date) {
        // Vuoden kulma (fractional year) keskipäivän kohdalta.
        double gamma = 2.0 * Math.PI / 365.0 * (dayOfYear - 1 + 0.5);

        // Ajantasauskaava (equation of time), minuutteina.
        double eqTime = 229.18 * (0.000075
                + 0.001868 * Math.cos(gamma)
                - 0.032077 * Math.sin(gamma)
                - 0.014615 * Math.cos(2.0 * gamma)
                - 0.040849 * Math.sin(2.0 * gamma));

        // Auringon deklinaatio, radiaaneina.
        double decl = 0.006918
                - 0.399912 * Math.cos(gamma)
                + 0.070257 * Math.sin(gamma)
                - 0.006758 * Math.cos(2.0 * gamma)
                + 0.000907 * Math.sin(2.0 * gamma)
                - 0.002697 * Math.cos(3.0 * gamma)
                + 0.00148 * Math.sin(3.0 * gamma);

        double latRad = rad(lat);
        double cosHa = (Math.cos(rad(ZENITH)) / (Math.cos(latRad) * Math.cos(decl)))
                - Math.tan(latRad) * Math.tan(decl);
        if (cosHa > 1.0) return SunTimes.NEVER_RISES;   // aurinko ei nouse (kaamos)
        if (cosHa < -1.0) return SunTimes.NEVER_SETS;   // aurinko ei laske (yötön yö)

        double haDeg = deg(Math.acos(cosHa));
        // UTC-minuutit: 720 − 4·(lon ± ha) − eqTime, lon positiivinen itään.
        double utcMinutes = sunrise
                ? 720.0 - 4.0 * (lon + haDeg) - eqTime
                : 720.0 - 4.0 * (lon - haDeg) - eqTime;

        int offsetMinutes = zone.getOffset(date.getTime()) / 60_000;
        int localMinutes = (int) Math.round(utcMinutes) + offsetMinutes;
        localMinutes %= 1440;
        if (localMinutes < 0) localMinutes += 1440;
        return localMinutes;
    }

    private static MoonPhase calculateMoon(Date date) {
        double days = (date.getTime() - KNOWN_NEW_MOON_UTC_MS) / 86_400_000.0;
        double phase = days / SYNODIC_MONTH_DAYS;
        phase = phase - Math.floor(phase);
        double illumination = (1.0 - Math.cos(2.0 * Math.PI * phase)) / 2.0;
        return new MoonPhase(phase, illumination, moonLabel(phase));
    }

    /** Muotoilee auringon nousun, laskun ja paivan pituuden kolmelle riville.
     *  Kuu poistettu Vaihe 4C:ssa kayttajan pyynnosta. */
    public static String formatSun(android.content.Context ctx, SunTimes sun) {
        if (sun.sunriseMinutes == SunTimes.NEVER_RISES) {
            return ctx.getString(R.string.sun_never_rises);
        }
        if (sun.sunsetMinutes == SunTimes.NEVER_SETS) {
            return ctx.getString(R.string.sun_never_sets);
        }
        return String.format(FI, ctx.getString(R.string.sun_line_format),
                formatMinutes(sun.sunriseMinutes),
                formatMinutes(sun.sunsetMinutes),
                formatDuration(sun.dayLengthMinutes()));
    }

    private static String moonLabel(double phase) {
        if (phase < 0.03 || phase >= 0.97) return "uusikuu";
        if (phase < 0.22) return "kasvava sirppi";
        if (phase < 0.28) return "ens. neljännes";
        if (phase < 0.47) return "kasvava kuu";
        if (phase < 0.53) return "täysikuu";
        if (phase < 0.72) return "vähenevä kuu";
        if (phase < 0.78) return "viim. neljännes";
        return "vähenevä sirppi";
    }

    static String formatMinutes(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        return String.format(FI, "%02d:%02d", h, m);
    }

    static String formatDuration(int minutes) {
        if (minutes < 0) minutes += 1440;
        return String.format(FI, "%d h %02d min", minutes / 60, minutes % 60);
    }

    public static String formatSunrise(SunTimes sun) {
        if (sun.sunriseMinutes == SunTimes.NEVER_RISES) return "—";
        return formatMinutes(sun.sunriseMinutes);
    }

    public static String formatSunset(SunTimes sun) {
        if (sun.sunsetMinutes == SunTimes.NEVER_SETS) return "—";
        return formatMinutes(sun.sunsetMinutes);
    }

    public static String formatDayLength(SunTimes sun) {
        if (sun.sunriseMinutes == SunTimes.NEVER_RISES
                || sun.sunsetMinutes == SunTimes.NEVER_SETS) return "";
        return formatDuration(sun.dayLengthMinutes());
    }

    private static double rad(double deg) {
        return deg * Math.PI / 180.0;
    }

    private static double deg(double rad) {
        return rad * 180.0 / Math.PI;
    }


    public static final class SunMoon {
        public final SunTimes sun;
        public final MoonPhase moon;

        SunMoon(SunTimes sun, MoonPhase moon) {
            this.sun = sun;
            this.moon = moon;
        }
    }

    public static final class SunTimes {
        public static final int NEVER_RISES = -1;
        public static final int NEVER_SETS = -2;

        public final int sunriseMinutes;
        public final int sunsetMinutes;

        SunTimes(int sunriseMinutes, int sunsetMinutes) {
            this.sunriseMinutes = sunriseMinutes;
            this.sunsetMinutes = sunsetMinutes;
        }

        public int dayLengthMinutes() {
            if (sunriseMinutes < 0 || sunsetMinutes < 0) return 0;
            int len = sunsetMinutes - sunriseMinutes;
            if (len < 0) len += 1440;
            return len;
        }
    }

    public static final class MoonPhase {
        public final double phase;
        public final double illumination;
        public final String label;

        MoonPhase(double phase, double illumination, String label) {
            this.phase = phase;
            this.illumination = illumination;
            this.label = label;
        }
    }
}
