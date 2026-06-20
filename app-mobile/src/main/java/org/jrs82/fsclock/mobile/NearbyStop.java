package org.jrs82.fsclock.mobile;

import java.util.List;

/** Lähellä oleva pysäkki ja sen seuraavat lähdöt (Digitransit nearest → Stop). */
public final class NearbyStop {

    public final String gtfsId;
    public final String name;
    public final String code;
    public final String zoneId;
    public final String vehicleMode;     // pysäkin päämoodi (BUS/TRAM/RAIL/SUBWAY…)
    public final double distanceMeters;
    public final List<Departure> departures;
    public final List<TransitAlert> alerts;   // pysäkin häiriötiedotteet (tyhjä jos ei)

    public NearbyStop(String gtfsId, String name, String code, String zoneId, String vehicleMode,
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
