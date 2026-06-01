package org.jrs82.fsclock.mobile;

import java.util.List;

/** Yhden vuoron aikajana: pysäkit järjestyksessä + ajoneuvon nykyinen pysäkki (live) + käyttäjän
 *  nousupysäkki. currentStopIndex/boardStopIndex = indeksi stops-listassa (-1 jos tuntematon). */
final class TripTimeline {
    final String routeShortName;
    final String headsign;
    final String mode;
    final List<TimelineStop> stops;
    final int currentStopIndex;  // ajoneuvon nykyinen/tuleva pysäkki, -1 jos ei live-dataa
    final int boardStopIndex;    // käyttäjän nousupysäkki, -1 jos ei tiedossa
    final boolean vehicleIncoming; // true = INCOMING_AT/IN_TRANSIT_TO (tulossa), false = pysäkillä

    TripTimeline(String routeShortName, String headsign, String mode, List<TimelineStop> stops,
                 int currentStopIndex, int boardStopIndex, boolean vehicleIncoming) {
        this.routeShortName = routeShortName;
        this.headsign = headsign;
        this.mode = mode;
        this.stops = stops;
        this.currentStopIndex = currentStopIndex;
        this.boardStopIndex = boardStopIndex;
        this.vehicleIncoming = vehicleIncoming;
    }
}
