package org.jrs82.fsclock.mobile;

import java.util.List;

/** Aikajana joko yksittäiselle vuorolle (tap lähtö) tai koko linjalle (tap suosikki/hakutulos):
 *  pysäkit järjestyksessä + ajoneuvojen sijainnit (indeksit stops-listassa). Vuoronäkymässä
 *  vehicleStopIndices sisältää 0–1 alkiota; linjanäkymässä kaikki liikkeellä olevat vuorot. */
final class TripTimeline {
    final String routeShortName;
    final String headsign;
    final String mode;
    final List<TimelineStop> stops;
    final List<Integer> vehicleStopIndices; // ajoneuvojen nykyiset pysäkki-indeksit
    final int boardStopIndex;               // käyttäjän nousupysäkki (vain vuoronäkymä), -1 muuten
    final boolean vehicleIncoming;          // vuoronäkymä: tulossa pysäkille (vs. pysäkillä)

    TripTimeline(String routeShortName, String headsign, String mode, List<TimelineStop> stops,
                 List<Integer> vehicleStopIndices, int boardStopIndex, boolean vehicleIncoming) {
        this.routeShortName = routeShortName;
        this.headsign = headsign;
        this.mode = mode;
        this.stops = stops;
        this.vehicleStopIndices = vehicleStopIndices;
        this.boardStopIndex = boardStopIndex;
        this.vehicleIncoming = vehicleIncoming;
    }
}
