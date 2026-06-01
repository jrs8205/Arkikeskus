package org.jrs82.fsclock.mobile;

import java.util.List;

/** Lähellä oleva pysäkki ja sen seuraavat lähdöt (Digitransit nearest → Stop). */
final class NearbyStop {

    final String gtfsId;
    final String name;
    final String code;
    final String vehicleMode;     // pysäkin päämoodi (BUS/TRAM/RAIL/SUBWAY…)
    final double distanceMeters;
    final List<Departure> departures;

    NearbyStop(String gtfsId, String name, String code, String vehicleMode,
               double distanceMeters, List<Departure> departures) {
        this.gtfsId = gtfsId;
        this.name = name;
        this.code = code;
        this.vehicleMode = vehicleMode;
        this.distanceMeters = distanceMeters;
        this.departures = departures;
    }
}
