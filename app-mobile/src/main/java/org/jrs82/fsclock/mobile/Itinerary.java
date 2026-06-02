package org.jrs82.fsclock.mobile;

import java.util.List;

/** Yksi reittiehdotus (planConnection edge → node): osat (legs), kesto ja vaihtojen määrä. */
final class Itinerary {
    final long startEpochMs;
    final long endEpochMs;
    final int durationSec;
    final int transfers;       // numberOfTransfers (kulkuvälineen vaihdot)
    final int walkMeters;
    final List<Leg> legs;

    Itinerary(long startEpochMs, long endEpochMs, int durationSec, int transfers, int walkMeters,
              List<Leg> legs) {
        this.startEpochMs = startEpochMs;
        this.endEpochMs = endEpochMs;
        this.durationSec = durationSec;
        this.transfers = transfers;
        this.walkMeters = walkMeters;
        this.legs = legs;
    }
}
