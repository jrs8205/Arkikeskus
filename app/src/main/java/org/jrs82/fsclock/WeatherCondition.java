package org.jrs82.fsclock;

/** Sisäinen säämalli. UI ja WeatherIconView käyttävät tätä, eivät FMI:n raakanumeroita. */
public class WeatherCondition {

    public enum Type {
        CLEAR, PARTLY_CLOUDY, CLOUDY, RAIN, SNOW, SLEET, THUNDER, FOG, UNKNOWN
    }

    public enum Intensity {
        NONE, LIGHT, MODERATE, HEAVY
    }

    public Type type = Type.UNKNOWN;
    public Intensity intensity = Intensity.NONE;
    public boolean isShower = false;
    public boolean isNight = false;
    public Integer rawSmartSymbol = null;
    public Integer rawWeatherSymbol3 = null;
    public Integer rawWawa = null;

    public WeatherCondition() {}

    public WeatherCondition(Type t, Intensity i) {
        this.type = t;
        this.intensity = i;
    }

    public static WeatherCondition unknown() {
        return new WeatherCondition(Type.UNKNOWN, Intensity.NONE);
    }

    public static WeatherCondition cloudy() {
        return new WeatherCondition(Type.CLOUDY, Intensity.NONE);
    }

    // ============================================================
    // SmartSymbol-kartoitus (ensisijainen)
    // Lähde: https://en.ilmatieteenlaitos.fi/weather-symbols
    //   1 clear, 2 mostly clear, 4 partly cloudy, 6 mostly cloudy, 7 overcast
    //   9 fog
    //   11 drizzle, 14 freezing drizzle, 17 freezing rain
    //   21/24/27 isolated/scattered/showers (sadekuurot)
    //   31-39 rain (kolme pilvisyystaustaa × kolme intensiteettiä)
    //   41-46 sleet showers, 47-49 sleet
    //   51-56 snow showers, 57-59 snowfall
    //   61/64/67 hail showers (ei HAIL-tyyppiä → SLEET shower fallback)
    //   71/74/77 thundershowers
    //   +100 = yöversio
    // ============================================================
    public static WeatherCondition fromSmartSymbol(int smartSymbol) {
        WeatherCondition c = new WeatherCondition();
        c.rawSmartSymbol = smartSymbol;

        int n = smartSymbol;
        if (n >= 100) {
            c.isNight = true;
            n = n - 100;
        }

        switch (n) {
            // Pilvisyys ja sumu
            case 1: c.type = Type.CLEAR; break;
            case 2: c.type = Type.PARTLY_CLOUDY; break; // mostly clear
            case 4: c.type = Type.PARTLY_CLOUDY; break;
            case 6: c.type = Type.CLOUDY; break; // mostly cloudy
            case 7: c.type = Type.CLOUDY; break; // overcast
            case 9: c.type = Type.FOG; c.intensity = Intensity.LIGHT; break;

            // Tihku ja jäätävät sateet
            case 11: c.type = Type.RAIN; c.intensity = Intensity.LIGHT; break;  // drizzle
            case 14: c.type = Type.RAIN; c.intensity = Intensity.LIGHT; break;  // freezing drizzle
            case 17: c.type = Type.RAIN; c.intensity = Intensity.MODERATE; break; // freezing rain

            // Sadekuurot 21/24/27 = isolated / scattered / showers
            case 21: c.type = Type.RAIN; c.intensity = Intensity.LIGHT; c.isShower = true; break;
            case 24: c.type = Type.RAIN; c.intensity = Intensity.MODERATE; c.isShower = true; break;
            case 27: c.type = Type.RAIN; c.intensity = Intensity.HEAVY; c.isShower = true; break;

            // Vesisade 31-39: partly cloudy+rain (31-33), mostly cloudy+rain (34-36),
            // overcast rain (37-39). Intensiteetti 1/2/3 = LIGHT/MODERATE/HEAVY.
            case 31: c.type = Type.RAIN; c.intensity = Intensity.LIGHT; break;
            case 32: c.type = Type.RAIN; c.intensity = Intensity.MODERATE; break;
            case 33: c.type = Type.RAIN; c.intensity = Intensity.HEAVY; break;
            case 34: c.type = Type.RAIN; c.intensity = Intensity.LIGHT; break;
            case 35: c.type = Type.RAIN; c.intensity = Intensity.MODERATE; break;
            case 36: c.type = Type.RAIN; c.intensity = Intensity.HEAVY; break;
            case 37: c.type = Type.RAIN; c.intensity = Intensity.LIGHT; break;
            case 38: c.type = Type.RAIN; c.intensity = Intensity.MODERATE; break;
            case 39: c.type = Type.RAIN; c.intensity = Intensity.HEAVY; break;

            // Räntäkuurot 41-46 (isolated/scattered, kevyt/kohtalainen/voimakas)
            case 41: c.type = Type.SLEET; c.intensity = Intensity.LIGHT; c.isShower = true; break;
            case 42: c.type = Type.SLEET; c.intensity = Intensity.MODERATE; c.isShower = true; break;
            case 43: c.type = Type.SLEET; c.intensity = Intensity.HEAVY; c.isShower = true; break;
            case 44: c.type = Type.SLEET; c.intensity = Intensity.LIGHT; c.isShower = true; break;
            case 45: c.type = Type.SLEET; c.intensity = Intensity.MODERATE; c.isShower = true; break;
            case 46: c.type = Type.SLEET; c.intensity = Intensity.HEAVY; c.isShower = true; break;
            // Räntä (jatkuva) 47-49
            case 47: c.type = Type.SLEET; c.intensity = Intensity.LIGHT; break;
            case 48: c.type = Type.SLEET; c.intensity = Intensity.MODERATE; break;
            case 49: c.type = Type.SLEET; c.intensity = Intensity.HEAVY; break;

            // Lumikuurot 51-56
            case 51: c.type = Type.SNOW; c.intensity = Intensity.LIGHT; c.isShower = true; break;
            case 52: c.type = Type.SNOW; c.intensity = Intensity.MODERATE; c.isShower = true; break;
            case 53: c.type = Type.SNOW; c.intensity = Intensity.HEAVY; c.isShower = true; break;
            case 54: c.type = Type.SNOW; c.intensity = Intensity.LIGHT; c.isShower = true; break;
            case 55: c.type = Type.SNOW; c.intensity = Intensity.MODERATE; c.isShower = true; break;
            case 56: c.type = Type.SNOW; c.intensity = Intensity.HEAVY; c.isShower = true; break;
            // Lumisade (jatkuva) 57-59
            case 57: c.type = Type.SNOW; c.intensity = Intensity.LIGHT; break;
            case 58: c.type = Type.SNOW; c.intensity = Intensity.MODERATE; break;
            case 59: c.type = Type.SNOW; c.intensity = Intensity.HEAVY; break;

            // Raekuurot 61/64/67 — ei HAIL-tyyppiä, käytetään SLEET-kuuroa fallbackina
            // TODO: lisää HAIL-tyyppi jos rakeille halutaan oma ikoni
            case 61: c.type = Type.SLEET; c.intensity = Intensity.LIGHT; c.isShower = true; break;
            case 64: c.type = Type.SLEET; c.intensity = Intensity.MODERATE; c.isShower = true; break;
            case 67: c.type = Type.SLEET; c.intensity = Intensity.HEAVY; c.isShower = true; break;

            // Ukkoskuurot 71/74/77
            case 71: c.type = Type.THUNDER; c.intensity = Intensity.LIGHT; c.isShower = true; break;
            case 74: c.type = Type.THUNDER; c.intensity = Intensity.MODERATE; c.isShower = true; break;
            case 77: c.type = Type.THUNDER; c.intensity = Intensity.HEAVY; c.isShower = true; break;

            default:
                c.type = Type.UNKNOWN;
                c.intensity = Intensity.NONE;
        }
        return c;
    }

    // ============================================================
    // WeatherSymbol3-kartoitus (fallback)
    // ============================================================
    public static WeatherCondition fromWeatherSymbol3(int sym, boolean night) {
        WeatherCondition c = new WeatherCondition();
        c.rawWeatherSymbol3 = sym;
        c.isNight = night;

        switch (sym) {
            case 1: c.type = Type.CLEAR; break;
            case 2: c.type = Type.PARTLY_CLOUDY; break;
            case 3: c.type = Type.CLOUDY; break;

            // Sadekuurot
            case 21: c.type = Type.RAIN; c.intensity = Intensity.LIGHT; c.isShower = true; break;
            case 22: c.type = Type.RAIN; c.intensity = Intensity.MODERATE; c.isShower = true; break;
            case 23: c.type = Type.RAIN; c.intensity = Intensity.HEAVY; c.isShower = true; break;

            // Vesisade (jatkuva)
            case 31: c.type = Type.RAIN; c.intensity = Intensity.LIGHT; break;
            case 32: c.type = Type.RAIN; c.intensity = Intensity.MODERATE; break;
            case 33: c.type = Type.RAIN; c.intensity = Intensity.HEAVY; break;

            // Lumikuurot
            case 41: c.type = Type.SNOW; c.intensity = Intensity.LIGHT; c.isShower = true; break;
            case 42: c.type = Type.SNOW; c.intensity = Intensity.MODERATE; c.isShower = true; break;
            case 43: c.type = Type.SNOW; c.intensity = Intensity.HEAVY; c.isShower = true; break;

            // Lumisade (jatkuva)
            case 51: c.type = Type.SNOW; c.intensity = Intensity.LIGHT; break;
            case 52: c.type = Type.SNOW; c.intensity = Intensity.MODERATE; break;
            case 53: c.type = Type.SNOW; c.intensity = Intensity.HEAVY; break;

            // Ukkonen
            case 61: c.type = Type.THUNDER; c.intensity = Intensity.MODERATE; c.isShower = true; break;
            case 62: c.type = Type.THUNDER; c.intensity = Intensity.HEAVY; c.isShower = true; break;
            case 63: c.type = Type.THUNDER; c.intensity = Intensity.MODERATE; break;
            case 64: c.type = Type.THUNDER; c.intensity = Intensity.HEAVY; break;

            // Räntäkuurot
            case 71: c.type = Type.SLEET; c.intensity = Intensity.LIGHT; c.isShower = true; break;
            case 72: c.type = Type.SLEET; c.intensity = Intensity.MODERATE; c.isShower = true; break;
            case 73: c.type = Type.SLEET; c.intensity = Intensity.HEAVY; c.isShower = true; break;

            // Räntä (jatkuva)
            case 81: c.type = Type.SLEET; c.intensity = Intensity.LIGHT; break;
            case 82: c.type = Type.SLEET; c.intensity = Intensity.MODERATE; break;
            case 83: c.type = Type.SLEET; c.intensity = Intensity.HEAVY; break;

            // Utu/sumu
            case 91:
            case 92: c.type = Type.FOG; c.intensity = Intensity.LIGHT; break;

            default:
                c.type = Type.UNKNOWN;
        }
        return c;
    }

    // ============================================================
    // wawa-havaintokoodi (tarkennus, ei pakota nykyikonia muuttumaan)
    // ============================================================
    public static WeatherCondition fromWawa(int wawa, WeatherCondition fallback) {
        WeatherCondition c = new WeatherCondition();
        c.rawWawa = wawa;
        if (fallback != null) {
            c.isNight = fallback.isNight;
            c.rawSmartSymbol = fallback.rawSmartSymbol;
            c.rawWeatherSymbol3 = fallback.rawWeatherSymbol3;
        }

        if (wawa == 0) {
            if (fallback != null) {
                c.type = fallback.type;
                c.intensity = fallback.intensity;
                c.isShower = fallback.isShower;
            } else {
                c.type = Type.CLEAR;
            }
            return c;
        }

        if (wawa == 4 || wawa == 5 || wawa == 10) {
            c.type = Type.FOG; c.intensity = Intensity.LIGHT; return c;
        }

        if (wawa >= 20 && wawa <= 25) {
            // ilmiö ollut edellisen tunnin aikana — ei pakoteta nykyhetkeä sateeksi
            if (fallback != null) {
                c.type = fallback.type;
                c.intensity = fallback.intensity;
                c.isShower = fallback.isShower;
            } else {
                c.type = Type.CLOUDY;
            }
            return c;
        }

        if (wawa >= 30 && wawa <= 35) {
            c.type = Type.FOG; c.intensity = Intensity.MODERATE; return c;
        }

        if (wawa >= 40 && wawa <= 42) {
            c.type = Type.RAIN; c.intensity = Intensity.MODERATE; return c;
        }

        if (wawa >= 50 && wawa <= 56) {
            // tihku / jäätävä tihku = kevyt sade
            c.type = Type.RAIN; c.intensity = Intensity.LIGHT; return c;
        }

        if (wawa >= 60 && wawa <= 66) {
            c.type = Type.RAIN;
            if (wawa == 61 || wawa == 64) c.intensity = Intensity.LIGHT;
            else if (wawa == 62 || wawa == 65) c.intensity = Intensity.MODERATE;
            else if (wawa == 63 || wawa == 66) c.intensity = Intensity.HEAVY;
            else c.intensity = Intensity.MODERATE;
            return c;
        }

        if (wawa == 67) { c.type = Type.SLEET; c.intensity = Intensity.LIGHT; return c; }
        if (wawa == 68) { c.type = Type.SLEET; c.intensity = Intensity.MODERATE; return c; }

        if (wawa >= 70 && wawa <= 79) {
            c.type = Type.SNOW;
            if (wawa == 71 || wawa == 74 || wawa == 77 || wawa == 78) c.intensity = Intensity.LIGHT;
            else if (wawa == 72 || wawa == 75) c.intensity = Intensity.MODERATE;
            else if (wawa == 73 || wawa == 76) c.intensity = Intensity.HEAVY;
            else c.intensity = Intensity.MODERATE;
            return c;
        }

        if (wawa >= 80 && wawa <= 84) {
            c.type = Type.RAIN; c.isShower = true;
            if (wawa == 81) c.intensity = Intensity.LIGHT;
            else if (wawa == 82) c.intensity = Intensity.MODERATE;
            else c.intensity = Intensity.HEAVY;
            return c;
        }

        if (wawa >= 85 && wawa <= 87) {
            c.type = Type.SNOW; c.isShower = true;
            if (wawa == 85) c.intensity = Intensity.LIGHT;
            else if (wawa == 86) c.intensity = Intensity.MODERATE;
            else c.intensity = Intensity.HEAVY;
            return c;
        }

        if (wawa == 89) {
            // TODO: 89 = raekuuro voi olla joko vesisateen tai ukkosen yhteydessä
            if (fallback != null && fallback.type == Type.THUNDER) {
                c.type = Type.THUNDER; c.intensity = Intensity.HEAVY; c.isShower = true;
            } else {
                c.type = Type.RAIN; c.intensity = Intensity.HEAVY; c.isShower = true;
            }
            return c;
        }

        if (fallback != null) {
            c.type = fallback.type;
            c.intensity = fallback.intensity;
            c.isShower = fallback.isShower;
        } else {
            c.type = Type.UNKNOWN;
        }
        return c;
    }

    // ============================================================
    // WMO weather_code → Type (Open-Meteo)
    // Lähde: https://open-meteo.com/en/docs (WMO Weather interpretation codes)
    // ============================================================
    public static WeatherCondition fromWmoCode(int code, boolean night) {
        WeatherCondition c = new WeatherCondition();
        c.rawWeatherSymbol3 = code;
        c.isNight = night;
        switch (code) {
            case 0:  c.type = Type.CLEAR;          c.intensity = Intensity.NONE;     break;
            case 1:  c.type = Type.CLEAR;          c.intensity = Intensity.NONE;     break;
            case 2:  c.type = Type.PARTLY_CLOUDY;  c.intensity = Intensity.NONE;     break;
            case 3:  c.type = Type.CLOUDY;         c.intensity = Intensity.NONE;     break;
            case 45: case 48:
                     c.type = Type.FOG;            c.intensity = Intensity.NONE;     break;
            case 51: c.type = Type.RAIN;           c.intensity = Intensity.LIGHT;    break;
            case 53: c.type = Type.RAIN;           c.intensity = Intensity.LIGHT;    break;
            case 55: c.type = Type.RAIN;           c.intensity = Intensity.MODERATE; break;
            case 56: c.type = Type.SLEET;          c.intensity = Intensity.LIGHT;    break;
            case 57: c.type = Type.SLEET;          c.intensity = Intensity.MODERATE; break;
            case 61: c.type = Type.RAIN;           c.intensity = Intensity.LIGHT;    break;
            case 63: c.type = Type.RAIN;           c.intensity = Intensity.MODERATE; break;
            case 65: c.type = Type.RAIN;           c.intensity = Intensity.HEAVY;    break;
            case 66: c.type = Type.SLEET;          c.intensity = Intensity.LIGHT;    break;
            case 67: c.type = Type.SLEET;          c.intensity = Intensity.HEAVY;    break;
            case 71: c.type = Type.SNOW;           c.intensity = Intensity.LIGHT;    break;
            case 73: c.type = Type.SNOW;           c.intensity = Intensity.MODERATE; break;
            case 75: c.type = Type.SNOW;           c.intensity = Intensity.HEAVY;    break;
            case 77: c.type = Type.SNOW;           c.intensity = Intensity.LIGHT;    break;
            case 80: c.type = Type.RAIN;           c.intensity = Intensity.LIGHT;    c.isShower = true; break;
            case 81: c.type = Type.RAIN;           c.intensity = Intensity.MODERATE; c.isShower = true; break;
            case 82: c.type = Type.RAIN;           c.intensity = Intensity.HEAVY;    c.isShower = true; break;
            case 85: c.type = Type.SNOW;           c.intensity = Intensity.LIGHT;    c.isShower = true; break;
            case 86: c.type = Type.SNOW;           c.intensity = Intensity.HEAVY;    c.isShower = true; break;
            case 95: c.type = Type.THUNDER;        c.intensity = Intensity.MODERATE; break;
            case 96: c.type = Type.THUNDER;        c.intensity = Intensity.MODERATE; break;
            case 99: c.type = Type.THUNDER;        c.intensity = Intensity.HEAVY;    break;
            default: c.type = Type.UNKNOWN;        c.intensity = Intensity.NONE;     break;
        }
        return c;
    }

    /** Päättele sää lämpötilasta, sateesta ja pilvisyydestä, kun symbolia ei ole. */
    public static WeatherCondition inferFromValues(double temperatureC, double precipitation1h,
                                                    double totalCloudCoverPct, boolean night) {
        WeatherCondition c = new WeatherCondition();
        c.isNight = night;
        boolean hasPrecip = !Double.isNaN(precipitation1h) && precipitation1h >= 0.1;
        if (hasPrecip) {
            if (!Double.isNaN(temperatureC)) {
                if (temperatureC <= -1.0) c.type = Type.SNOW;
                else if (temperatureC >= 2.0) c.type = Type.RAIN;
                else c.type = Type.SLEET;
            } else {
                c.type = Type.RAIN;
            }
            if (precipitation1h < 0.5) c.intensity = Intensity.LIGHT;
            else if (precipitation1h < 2.0) c.intensity = Intensity.MODERATE;
            else c.intensity = Intensity.HEAVY;
            return c;
        }
        if (!Double.isNaN(totalCloudCoverPct)) {
            if (totalCloudCoverPct < 20.0) c.type = Type.CLEAR;
            else if (totalCloudCoverPct < 70.0) c.type = Type.PARTLY_CLOUDY;
            else c.type = Type.CLOUDY;
        } else {
            c.type = Type.UNKNOWN;
        }
        return c;
    }
}
