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
    HSL("https://api.digitransit.fi/routing/v2/hsl/gtfs/v1", "gtfshsl", "HSL", "HSL", 60.17, 24.94),
    TAMPERE("https://api.digitransit.fi/routing/v2/waltti/gtfs/v1", "gtfstampere", "tampere", "Tampere", 61.498, 23.761);

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

    TransitRegion(String endpoint, String geocodeSources, String alertFeed, String label,
                  double focusLat, double focusLon) {
        this.endpoint = endpoint;
        this.geocodeSources = geocodeSources;
        this.alertFeed = alertFeed;
        this.label = label;
        this.focusLat = focusLat;
        this.focusLon = focusLon;
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
}
