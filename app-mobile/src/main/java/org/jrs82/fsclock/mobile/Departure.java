package org.jrs82.fsclock.mobile;

/** Yksittäinen joukkoliikennelähtö pysäkiltä (Digitransit stoptimesWithoutPatterns).
 *  departureEpochSec = serviceDay + (realtime ? realtimeDeparture : scheduledDeparture).
 *  delaySeconds = realtimeDeparture - scheduledDeparture (positiivinen = myöhässä).
 *  trip/pattern/route-gtfsId:t mahdollistavat aikajanan ja suosikkilinjat. */
public final class Departure {

    public final String routeShortName;
    public final String headsign;
    public final String mode;           // BUS, TRAM, RAIL, SUBWAY, FERRY…
    public final long departureEpochSec;
    public final int delaySeconds;
    public final boolean realtime;
    public final double distanceMeters;  // etäisyys pysäkille (peritään NearbyStopilta riviä varten)
    public final String stopName;
    public final String stopGtfsId;
    public final String tripGtfsId;
    public final String patternCode;
    public final String routeGtfsId;
    public final String patternFirstStop;
    public final String patternLastStop;

    public Departure(String routeShortName, String headsign, String mode, long departureEpochSec,
              int delaySeconds, boolean realtime, double distanceMeters, String stopName,
              String stopGtfsId, String tripGtfsId, String patternCode, String routeGtfsId,
              String patternFirstStop, String patternLastStop) {
        this.routeShortName = routeShortName;
        this.headsign = headsign;
        this.mode = mode;
        this.departureEpochSec = departureEpochSec;
        this.delaySeconds = delaySeconds;
        this.realtime = realtime;
        this.distanceMeters = distanceMeters;
        this.stopName = stopName;
        this.stopGtfsId = stopGtfsId;
        this.tripGtfsId = tripGtfsId;
        this.patternCode = patternCode;
        this.routeGtfsId = routeGtfsId;
        this.patternFirstStop = patternFirstStop;
        this.patternLastStop = patternLastStop;
    }

    String directionLabel() {
        if (patternFirstStop != null && !patternFirstStop.isEmpty()
                && patternLastStop != null && !patternLastStop.isEmpty()
                && !patternFirstStop.equalsIgnoreCase(patternLastStop)) {
            return patternFirstStop + " \u2192 " + patternLastStop;
        }
        return headsign == null ? "" : headsign;
    }
}
