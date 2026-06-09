package org.jrs82.fsclock.mobile;

/** Yksi pysäkki vuoron aikajanalla (trip.stoptimesForDate). depEpochSec = serviceDay + departure.
 *  lat/lon (NaN jos ei haettu) bussin GPS:n projisointiin reittiviivalle (V2). */
final class TimelineStop {
    final String gtfsId;
    final String name;
    final String code;
    final long depEpochSec;
    final boolean realtime;
    final double lat;
    final double lon;

    TimelineStop(String gtfsId, String name, String code, long depEpochSec, boolean realtime) {
        this(gtfsId, name, code, depEpochSec, realtime, Double.NaN, Double.NaN);
    }

    TimelineStop(String gtfsId, String name, String code, long depEpochSec, boolean realtime,
                 double lat, double lon) {
        this.gtfsId = gtfsId;
        this.name = name;
        this.code = code;
        this.depEpochSec = depEpochSec;
        this.realtime = realtime;
        this.lat = lat;
        this.lon = lon;
    }
}
