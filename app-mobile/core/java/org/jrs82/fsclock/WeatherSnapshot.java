package org.jrs82.fsclock;

/** Lähdeneutraali sääpoikkileikkaus. FMI- ja Open-Meteo-tiedot
 *  abstrahoidaan tähän, jotta etusivu ja supersää voivat näyttää molempia
 *  ilman lähdekohtaista koodia. Boxed-Doublet ilmaisevat puuttuvan arvon
 *  null-arvolla; primitiivit eivät erota NaN:ia oikeasta nollasta. */
public final class WeatherSnapshot {

    public enum Source { FMI, OPEN_METEO }

    public final Source source;
    public final String placeName;
    public final long timestamp;
    public final long fetchedAt;

    public final Double temperature;
    public final Double feelsLike;
    public final Double humidity;
    public final Double windSpeed;
    public final Double windGust;
    public final Double windDirection;
    public final Double cloudCover;
    public final Double radiationGlobal;
    public final Double precip1h;
    public final Double rain24h;
    public final WeatherCondition condition;

    private WeatherSnapshot(Builder b) {
        this.source = b.source;
        this.placeName = b.placeName;
        this.timestamp = b.timestamp;
        this.fetchedAt = b.fetchedAt;
        this.temperature = b.temperature;
        this.feelsLike = b.feelsLike;
        this.humidity = b.humidity;
        this.windSpeed = b.windSpeed;
        this.windGust = b.windGust;
        this.windDirection = b.windDirection;
        this.cloudCover = b.cloudCover;
        this.radiationGlobal = b.radiationGlobal;
        this.precip1h = b.precip1h;
        this.rain24h = b.rain24h;
        this.condition = b.condition != null ? b.condition : WeatherCondition.unknown();
    }

    /** FMI-mallin (WeatherData.Current) muunnos neutraaliin malliin.
     *  Kutsutaan WeatherRepositoryssa kun havaintosykli on valmis. */
    public static WeatherSnapshot fromFmi(WeatherData.Current c, String placeName, long fetchedAt) {
        return new Builder(Source.FMI, placeName, c.timestamp, fetchedAt)
                .temperature(boxed(c.temperature))
                .feelsLike(boxed(c.feelsLike))
                .humidity(boxed(c.humidity))
                .windSpeed(boxed(c.windSpeed))
                .windGust(boxed(c.windGust))
                .windDirection(boxed(c.windDirection))
                .cloudCover(boxed(c.cloudCover))
                .radiationGlobal(boxed(c.radiationGlobal))
                .precip1h(boxed(c.precip1h))
                .rain24h(c.rain24hAllMissing ? null : boxed(c.rain24h))
                .condition(c.condition)
                .build();
    }

    private static Double boxed(double v) {
        return Double.isNaN(v) ? null : v;
    }

    /** Open-Meteon tuntiriviin perustuva neutraali snapshot. Käytetään supersäässä
     *  yhden tunnin vertailussa FMI-snapshotin rinnalla. rain24h jätetään nulliksi,
     *  koska Open-Meteon yksittäisellä tunnilla ei ole 24h-akkumulaa. */
    public static WeatherSnapshot fromOpenMeteo(OpenMeteoData.Hour h, String placeName,
                                                 long fetchedAt) {
        return new Builder(Source.OPEN_METEO, placeName, h.timestamp, fetchedAt)
                .temperature(h.temperature)
                .feelsLike(h.feelsLike)
                .humidity(h.humidity)
                .windSpeed(h.windSpeed)
                .windGust(h.windGust)
                .windDirection(h.windDirection)
                .cloudCover(h.cloudCover)
                .radiationGlobal(h.radiationGlobal)
                .precip1h(h.precipitation)
                .rain24h(null)
                .condition(h.condition)
                .build();
    }

    public static final class Builder {
        private final Source source;
        private final String placeName;
        private final long timestamp;
        private final long fetchedAt;
        private Double temperature;
        private Double feelsLike;
        private Double humidity;
        private Double windSpeed;
        private Double windGust;
        private Double windDirection;
        private Double cloudCover;
        private Double radiationGlobal;
        private Double precip1h;
        private Double rain24h;
        private WeatherCondition condition;

        public Builder(Source source, String placeName, long timestamp, long fetchedAt) {
            this.source = source;
            this.placeName = placeName;
            this.timestamp = timestamp;
            this.fetchedAt = fetchedAt;
        }

        public Builder temperature(Double v)     { this.temperature = v;     return this; }
        public Builder feelsLike(Double v)       { this.feelsLike = v;       return this; }
        public Builder humidity(Double v)        { this.humidity = v;        return this; }
        public Builder windSpeed(Double v)       { this.windSpeed = v;       return this; }
        public Builder windGust(Double v)        { this.windGust = v;        return this; }
        public Builder windDirection(Double v)   { this.windDirection = v;   return this; }
        public Builder cloudCover(Double v)      { this.cloudCover = v;      return this; }
        public Builder radiationGlobal(Double v) { this.radiationGlobal = v; return this; }
        public Builder precip1h(Double v)        { this.precip1h = v;        return this; }
        public Builder rain24h(Double v)         { this.rain24h = v;         return this; }
        public Builder condition(WeatherCondition c) { this.condition = c;   return this; }

        public WeatherSnapshot build() { return new WeatherSnapshot(this); }
    }
}
