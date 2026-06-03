package org.jrs82.fsclock;

import java.util.ArrayList;
import java.util.List;

/** Open-Meteon tuntipohjainen vastaus. Käytetään supersäässä rinnakkain
 *  FMI:n ennusteen kanssa, ja yhden tunnin poikkileikkaus voidaan kääntää
 *  {@link WeatherSnapshot}iksi etusivun nykytilan vertailuun. */
public class OpenMeteoData {

    public final String placeName;
    public long fetchedAt;
    public final List<Hour> hours = new ArrayList<>();

    public OpenMeteoData(String placeName) {
        this.placeName = placeName;
    }

    public static class Hour {
        public long timestamp;          // ms epoch UTC
        public int hour;                // paikallinen tunti 0..23
        public int dayOfMonth;          // paikallinen 1..31
        public int month;               // paikallinen 1..12
        public Double temperature;      // °C
        public Double feelsLike;        // °C (apparent_temperature)
        public Double humidity;         // %
        public Double windSpeed;        // m/s
        public Double windGust;         // m/s
        public Double windDirection;    // °
        public Double cloudCover;       // %
        public Double precipitation;    // mm/h
        public Double radiationGlobal;  // W/m²
        public WeatherCondition condition = WeatherCondition.unknown();
    }
}
