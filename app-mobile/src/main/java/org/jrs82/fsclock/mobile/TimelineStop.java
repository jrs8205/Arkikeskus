package org.jrs82.fsclock.mobile;

/** Yksi pysäkki vuoron aikajanalla (trip.stoptimesForDate). depEpochSec = serviceDay + departure. */
final class TimelineStop {
    final String gtfsId;
    final String name;
    final String code;
    final long depEpochSec;
    final boolean realtime;

    TimelineStop(String gtfsId, String name, String code, long depEpochSec, boolean realtime) {
        this.gtfsId = gtfsId;
        this.name = name;
        this.code = code;
        this.depEpochSec = depEpochSec;
        this.realtime = realtime;
    }
}
