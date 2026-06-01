package org.jrs82.fsclock.mobile;

/** Yksittäinen joukkoliikennelähtö pysäkiltä (Digitransit stoptimesWithoutPatterns).
 *  departureEpochSec = serviceDay + (realtime ? realtimeDeparture : scheduledDeparture).
 *  delaySeconds = realtimeDeparture - scheduledDeparture (positiivinen = myöhässä).
 *  trip/pattern/route-gtfsId:t mahdollistavat aikajanan ja suosikkilinjat. */
final class Departure {

    final String routeShortName;
    final String headsign;
    final String mode;           // BUS, TRAM, RAIL, SUBWAY, FERRY…
    final long departureEpochSec;
    final int delaySeconds;
    final boolean realtime;
    final double distanceMeters;  // etäisyys pysäkille (peritään NearbyStopilta riviä varten)
    final String stopName;
    final String stopGtfsId;
    final String tripGtfsId;
    final String patternCode;
    final String routeGtfsId;

    Departure(String routeShortName, String headsign, String mode, long departureEpochSec,
              int delaySeconds, boolean realtime, double distanceMeters, String stopName,
              String stopGtfsId, String tripGtfsId, String patternCode, String routeGtfsId) {
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
    }
}
