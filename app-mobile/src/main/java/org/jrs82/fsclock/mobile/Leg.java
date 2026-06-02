package org.jrs82.fsclock.mobile;

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

    Leg(String mode, long startEpochMs, long endEpochMs, int durationSec, int distanceMeters,
        String fromName, String toName, String routeShortName, String headsign, boolean realtime) {
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
    }

    boolean isWalk() { return "WALK".equals(mode); }
}
