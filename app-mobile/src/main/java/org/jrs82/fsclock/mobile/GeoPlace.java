package org.jrs82.fsclock.mobile;

/** Geokoodaustulos reittihaun Mistä/Minne-valintaan. Oletuslähteet (paikat, osoitteet, POI:t,
 *  pysäkit) → koordinaatit mukana, jotta ne voi syöttää planConnection-reittihakuun. */
final class GeoPlace {
    final String name;       // "Helsingin päärautatieasema"
    final String locality;   // "Kluuvi, Helsinki" (alaotsikko)
    final double lat;
    final double lon;
    final String layer;      // stop/station/venue/address/street/locality…

    GeoPlace(String name, String locality, double lat, double lon, String layer) {
        this.name = name;
        this.locality = locality;
        this.lat = lat;
        this.lon = lon;
        this.layer = layer;
    }
}
