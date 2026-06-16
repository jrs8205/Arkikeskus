package org.jrs82.fsclock.mobile;

import java.util.Collections;
import java.util.List;

/** Yksi matkan osa (planConnection leg): kävely tai yksi joukkoliikennevälineen osuus. */
final class Leg {
    final String mode;            // WALK/BUS/RAIL/TRAM/SUBWAY/FERRY
    final long startEpochMs;
    final long endEpochMs;
    final int durationSec;
    final int distanceMeters;
    final String fromName;
    final String toName;
    final String routeShortName;  // tyhjä kävelyllä
    final String headsign;
    final boolean realtime;       // aika perustuu reaaliaika-arvioon
    final String fromStopCode;    // lähtöpysäkin koodi (esim. H1234), tyhjä jos ei tiedossa
    final String fromPlatform;    // lähtölaituri (juna/metro), tyhjä jos ei laituria
    final List<double[]> geometry; // osuuden muoto [lat,lon]-pareina (legGeometry), tyhjä jos ei
    final String tripGtfsId;      // vuoron gtfsId (live-bussin haku), tyhjä kävelyllä
    final String patternCode;     // vuoron pattern-koodi (vehiclePositions-haku), tyhjä kävelyllä
    final List<double[]> stops;   // osuuden pysäkit [lat,lon] (kartan pienet pisteet), tyhjä kävelyllä
    final List<TransitAlert> alerts; // osuuden linjan häiriötiedotteet (tyhjä kävelyllä/jos ei)

    Leg(String mode, long startEpochMs, long endEpochMs, int durationSec, int distanceMeters,
        String fromName, String toName, String routeShortName, String headsign, boolean realtime,
        String fromStopCode, String fromPlatform, List<double[]> geometry,
        String tripGtfsId, String patternCode, List<double[]> stops, List<TransitAlert> alerts) {
        this.mode = mode;
        this.startEpochMs = startEpochMs;
        this.endEpochMs = endEpochMs;
        this.durationSec = durationSec;
        this.distanceMeters = distanceMeters;
        this.fromName = fromName;
        this.toName = toName;
        this.routeShortName = routeShortName;
        this.headsign = headsign;
        this.realtime = realtime;
        this.fromStopCode = fromStopCode == null ? "" : fromStopCode;
        this.fromPlatform = fromPlatform == null ? "" : fromPlatform;
        this.geometry = geometry == null ? Collections.emptyList() : geometry;
        this.tripGtfsId = tripGtfsId == null ? "" : tripGtfsId;
        this.patternCode = patternCode == null ? "" : patternCode;
        this.stops = stops == null ? Collections.emptyList() : stops;
        this.alerts = alerts == null ? Collections.emptyList() : alerts;
    }

    boolean isWalk() { return "WALK".equals(mode); }
}
