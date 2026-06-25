package org.jrs82.fsclock.mobile;

/**
 * Joukkoliikennealue Digitransit-reitittimelle. Sama GraphQL-rajapinta ja sama tilausavain
 * ({@link org.jrs82.fsclock.BuildConfig#DIGITRANSIT_KEY}) kaikille — vain reititin-endpoint,
 * geokoodauksen GTFS-lähde ja häiriösyötteen feedId eroavat.
 *
 * <p>HSL = pääkaupunkiseutu; TAMPERE = Nysse (Waltti-reititin, feedId "tampere"). Arvot varmistettu
 * suoralla avaintestillä (waltti-reititin: feeds → "tampere"; stopsByRadius + geocoding sources=gtfstampere).
 */
public enum TransitRegion {
    HSL("https://api.digitransit.fi/routing/v2/hsl/gtfs/v1", "gtfshsl", "HSL", "HSL", 60.17, 24.94, true),
    TAMPERE("https://api.digitransit.fi/routing/v2/waltti/gtfs/v1", "gtfstampere", "tampere", "Tampere", 61.498, 23.761, false);

    /** Reititin-endpoint (GraphQL POST). */
    public final String endpoint;
    /** Pelias-geokoodauksen sources-arvo, joka rajaa tulokset tämän alueen GTFS-pysäkkeihin/asemiin. */
    public final String geocodeSources;
    /** Häiriösyötteen feedId ({@code alerts(feeds:[...])}). */
    public final String alertFeed;
    /** Käyttäjälle näytettävä nimi (aluevalitsin). */
    public final String label;
    /** Geokoodauksen focus-piste kun laitteen sijaintia ei vielä ole luettu (alueen keskusta). */
    public final double focusLat;
    public final double focusLon;
    /**
     * Tarjoaako tämän alueen reititin live-ajoneuvosijainnit GraphQL:n {@code pattern.vehiclePositions}
     * -kentän kautta. HSL kyllä; Waltti (Tampere/Nysse) EI — Waltti palauttaa aina tyhjän taulukon, ja
     * Tampereen live-sijainnit ovat saatavilla vain Digitransitin MQTT-brokerista (GTFS-RT, topic
     * {@code /gtfsrt/vp/tampere/…}). Käytetään näyttämään rehellinen viesti sen sijaan että väitettäisiin
     * vuoron olevan liikkumatta. (Tampereen MQTT-live on mahdollinen myöhempi laajennus.)
     */
    public final boolean liveVehiclePositions;

    TransitRegion(String endpoint, String geocodeSources, String alertFeed, String label,
                  double focusLat, double focusLon, boolean liveVehiclePositions) {
        this.endpoint = endpoint;
        this.geocodeSources = geocodeSources;
        this.alertFeed = alertFeed;
        this.label = label;
        this.focusLat = focusLat;
        this.focusLon = focusLon;
        this.liveVehiclePositions = liveVehiclePositions;
    }

    /** SharedPreferences-tunnisteesta alueeksi; tuntematon/null → HSL (oletus). */
    public static TransitRegion fromKey(String key) {
        if (key != null) {
            for (TransitRegion r : values()) {
                if (r.name().equals(key)) return r;
            }
        }
        return HSL;
    }

    /** Karkea sijaintipohjainen oletus: Tampereen seutu → TAMPERE, muuten HSL. Käytetään vain
     *  ensioletuksena kun käyttäjä ei ole vielä valinnut aluetta manuaalisesti. */
    public static TransitRegion forLocation(double lat, double lon) {
        if (lat >= 61.30 && lat <= 61.75 && lon >= 23.45 && lon <= 24.10) return TAMPERE;
        return HSL;
    }

    /** Alue gtfsId:n syöte-prefiksistä (esim. "tampere:0015" → TAMPERE, "HSL:1234" → HSL). Suosikit ja
     *  avatut kohteet ovat globaaleja, joten oikea reititin päätellään aina id:stä. null/tuntematon → HSL. */
    public static TransitRegion forGtfsId(String id) {
        return (id != null && id.startsWith("tampere:")) ? TAMPERE : HSL;
    }
}
