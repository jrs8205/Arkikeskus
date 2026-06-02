package org.jrs82.fsclock.mobile;

import java.util.List;

/** Geokoodaushaun osuma: HSL-pysäkki tai -asema moodeineen (Digitransit autocomplete,
 *  sources=gtfshsl). Napautus → pysäkin/aseman lähdöt. */
final class PlaceHit {
    final String gtfsId;       // reititin-id, esim. "HSL:2131551"; tyhjä = ei lähtöjä haettavissa
    final String name;         // "Tapiola (M)"
    final String locality;     // "Tapiolan Keskus, Espoo" (alaotsikko)
    final boolean station;     // true = asema (station-kysely), false = yksittäinen pysäkki
    final List<String> modes;  // kanoniset moodit järjestyksessä: BUS/TRAM/RAIL/SUBWAY/FERRY

    PlaceHit(String gtfsId, String name, String locality, boolean station, List<String> modes) {
        this.gtfsId = gtfsId;
        this.name = name;
        this.locality = locality;
        this.station = station;
        this.modes = modes;
    }
}
