package org.jrs82.fsclock.mobile;

import java.util.List;

/** Lähellä oleva pysäkki ja sen seuraavat lähdöt (Digitransit nearest → Stop). */
final class NearbyStop {

    final String gtfsId;
    final String name;
    final String code;
    final String zoneId;
    final String vehicleMode;     // pysäkin päämoodi (BUS/TRAM/RAIL/SUBWAY…)
    final double distanceMeters;
    final List<Departure> departures;
    final List<TransitAlert> alerts;   // pysäkin häiriötiedotteet (tyhjä jos ei)

    NearbyStop(String gtfsId, String name, String code, String zoneId, String vehicleMode,
               double distanceMeters, List<Departure> departures, List<TransitAlert> alerts) {
        this.gtfsId = gtfsId;
        this.name = name;
        this.code = code;
        this.zoneId = zoneId;
        this.vehicleMode = vehicleMode;
        this.distanceMeters = distanceMeters;
        this.departures = departures;
        this.alerts = alerts == null ? java.util.Collections.emptyList() : alerts;
    }
}
