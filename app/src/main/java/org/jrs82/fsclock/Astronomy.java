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

    private static int calculateSunEventMinutes(int dayOfYear, double lat, double lon,
                                                TimeZone zone, boolean sunrise, Date date) {
        double lngHour = lon / 15.0;
        double t = dayOfYear + ((sunrise ? 6.0 : 18.0) - lngHour) / 24.0;
        double meanAnomaly = (0.9856 * t) - 3.289;
        double trueLong = meanAnomaly
                + (1.916 * Math.sin(rad(meanAnomaly)))
                + (0.020 * Math.sin(rad(2.0 * meanAnomaly)))
                + 282.634;
        trueLong = normalizeDegrees(trueLong);

        double rightAscension = deg(Math.atan(0.91764 * Math.tan(rad(trueLong))));
        rightAscension = normalizeDegrees(rightAscension);
        double lQuadrant = Math.floor(trueLong / 90.0) * 90.0;
        double raQuadrant = Math.floor(rightAscension / 90.0) * 90.0;
        rightAscension = (rightAscension + lQuadrant - raQuadrant) / 15.0;

        double sinDec = 0.39782 * Math.sin(rad(trueLong));
        double cosDec = Math.cos(Math.asin(sinDec));
        double cosH = (Math.cos(rad(ZENITH)) - (sinDec * Math.sin(rad(lat))))
                / (cosDec * Math.cos(rad(lat)));
        if (cosH > 1.0) return SunTimes.NEVER_RISES;
        if (cosH < -1.0) return SunTimes.NEVER_SETS;

        double hourAngle = sunrise
                ? 360.0 - deg(Math.acos(cosH))
                : deg(Math.acos(cosH));
        hourAngle /= 15.0;

        double localMeanTime = hourAngle + rightAscension - (0.06571 * t) - 6.622;
        double utcHours = normalizeHours(localMeanTime - lngHour);
        int offsetMinutes = zone.getOffset(date.getTime()) / 60_000;
        int localMinutes = (int) Math.round(utcHours * 60.0) + offsetMinutes;
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

    public static String format(SunMoon sm) {
        String sun;
        if (sm.sun.sunriseMinutes == SunTimes.NEVER_RISES) {
            sun = "Aurinko ei nouse";
        } else if (sm.sun.sunsetMinutes == SunTimes.NEVER_SETS) {
            sun = "Aurinko ei laske";
        } else {
            sun = "Aurinko " + formatMinutes(sm.sun.sunriseMinutes)
                    + "-" + formatMinutes(sm.sun.sunsetMinutes)
                    + " (" + formatDuration(sm.sun.dayLengthMinutes()) + ")";
        }
        int illum = (int) Math.round(sm.moon.illumination * 100.0);
        return sun + "   Kuu: " + sm.moon.label + " " + illum + " %";
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

    private static String formatMinutes(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        return String.format(FI, "%02d:%02d", h, m);
    }

    private static String formatDuration(int minutes) {
        if (minutes < 0) minutes += 1440;
        return String.format(FI, "%d h %02d min", minutes / 60, minutes % 60);
    }

    private static double rad(double deg) {
        return deg * Math.PI / 180.0;
    }

    private static double deg(double rad) {
        return rad * 180.0 / Math.PI;
    }

    private static double normalizeDegrees(double d) {
        d %= 360.0;
        if (d < 0) d += 360.0;
        return d;
    }

    private static double normalizeHours(double h) {
        h %= 24.0;
        if (h < 0) h += 24.0;
        return h;
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
