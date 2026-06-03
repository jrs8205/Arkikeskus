package org.jrs82.fsclock;

import android.content.Context;

/** Muodostaa suomenkielisen tekstiselitteen WeatherCondition-mallista.
 *  Tekstit luetaan strings.xml:stä — ei kovakoodattuja merkkijonoja täällä.
 *  Käyttäjälle näkyvä sääseliste, joka toimii datalähteestä riippumatta
 *  (FMI SmartSymbol, WeatherSymbol3, wawa-havainto, fallback-päättely). */
public final class WeatherTextFormatter {

    private WeatherTextFormatter() {}

    /** Pitkä label etusivulle. Palauttaa aina ei-tyhjän merkkijonon. */
    public static String label(Context ctx, WeatherCondition c) {
        if (c == null) return ctx.getString(R.string.wt_unknown);
        switch (c.type) {
            case CLEAR:
                return ctx.getString(c.isNight ? R.string.wt_clear_night : R.string.wt_clear_day);
            case PARTLY_CLOUDY:
                return ctx.getString(c.isNight ? R.string.wt_partly_cloudy_night : R.string.wt_partly_cloudy_day);
            case CLOUDY:
                return ctx.getString(R.string.wt_cloudy);
            case FOG:
                return ctx.getString(R.string.wt_fog);
            case THUNDER:
                if (c.isShower) return ctx.getString(R.string.wt_thunder_shower);
                switch (c.intensity) {
                    case HEAVY: return ctx.getString(R.string.wt_thunder_heavy);
                    case LIGHT: return ctx.getString(R.string.wt_thunder_light);
                    default:    return ctx.getString(R.string.wt_thunder_moderate);
                }
            case RAIN:
                if (c.isShower) {
                    switch (c.intensity) {
                        case LIGHT: return ctx.getString(R.string.wt_rain_shower_light);
                        case HEAVY: return ctx.getString(R.string.wt_rain_shower_heavy);
                        default:    return ctx.getString(R.string.wt_rain_shower_moderate);
                    }
                }
                switch (c.intensity) {
                    case LIGHT: return ctx.getString(R.string.wt_rain_light);
                    case HEAVY: return ctx.getString(R.string.wt_rain_heavy);
                    default:    return ctx.getString(R.string.wt_rain_moderate);
                }
            case SNOW:
                if (c.isShower) {
                    switch (c.intensity) {
                        case LIGHT: return ctx.getString(R.string.wt_snow_shower_light);
                        case HEAVY: return ctx.getString(R.string.wt_snow_shower_heavy);
                        default:    return ctx.getString(R.string.wt_snow_shower_moderate);
                    }
                }
                switch (c.intensity) {
                    case LIGHT: return ctx.getString(R.string.wt_snow_light);
                    case HEAVY: return ctx.getString(R.string.wt_snow_heavy);
                    default:    return ctx.getString(R.string.wt_snow_moderate);
                }
            case SLEET:
                if (c.isShower) {
                    switch (c.intensity) {
                        case LIGHT: return ctx.getString(R.string.wt_sleet_shower_light);
                        case HEAVY: return ctx.getString(R.string.wt_sleet_shower_heavy);
                        default:    return ctx.getString(R.string.wt_sleet_shower_moderate);
                    }
                }
                switch (c.intensity) {
                    case LIGHT: return ctx.getString(R.string.wt_sleet_light);
                    case HEAVY: return ctx.getString(R.string.wt_sleet_heavy);
                    default:    return ctx.getString(R.string.wt_sleet_moderate);
                }
            case UNKNOWN:
            default:
                return ctx.getString(R.string.wt_unknown);
        }
    }

    /** Lyhyt label tuntisolun ahtaaseen tilaan. Intensiteettiä ei eritellä
     *  kuuroille (kaikki Sadekuuroja / Lumikuuroja / Räntäkuuroja). */
    public static String shortLabel(Context ctx, WeatherCondition c) {
        if (c == null) return ctx.getString(R.string.wts_unknown);
        switch (c.type) {
            case CLEAR:
                return ctx.getString(c.isNight ? R.string.wts_clear_night : R.string.wts_clear_day);
            case PARTLY_CLOUDY:
                return ctx.getString(R.string.wts_partly_cloudy);
            case CLOUDY:
                return ctx.getString(R.string.wts_cloudy);
            case FOG:
                return ctx.getString(R.string.wts_fog);
            case THUNDER:
                return ctx.getString(c.isShower ? R.string.wts_thunder_shower : R.string.wts_thunder);
            case RAIN:
                if (c.isShower) return ctx.getString(R.string.wts_rain_shower);
                switch (c.intensity) {
                    case LIGHT: return ctx.getString(R.string.wts_rain_light);
                    case HEAVY: return ctx.getString(R.string.wts_rain_heavy);
                    default:    return ctx.getString(R.string.wts_rain_moderate);
                }
            case SNOW:
                if (c.isShower) return ctx.getString(R.string.wts_snow_shower);
                switch (c.intensity) {
                    case LIGHT: return ctx.getString(R.string.wts_snow_light);
                    case HEAVY: return ctx.getString(R.string.wts_snow_heavy);
                    default:    return ctx.getString(R.string.wts_snow_moderate);
                }
            case SLEET:
                if (c.isShower) return ctx.getString(R.string.wts_sleet_shower);
                switch (c.intensity) {
                    case LIGHT: return ctx.getString(R.string.wts_sleet_light);
                    case HEAVY: return ctx.getString(R.string.wts_sleet_heavy);
                    default:    return ctx.getString(R.string.wts_sleet_moderate);
                }
            case UNKNOWN:
            default:
                return ctx.getString(R.string.wts_unknown);
        }
    }
}
