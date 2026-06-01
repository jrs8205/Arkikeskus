package org.jrs82.fsclock.mobile;

/** Linjahaun tulos (Digitransit routes(name:)). */
final class RouteHit {
    final String gtfsId;
    final String shortName;
    final String longName;
    final String mode;

    RouteHit(String gtfsId, String shortName, String longName, String mode) {
        this.gtfsId = gtfsId;
        this.shortName = shortName;
        this.longName = longName;
        this.mode = mode;
    }
}
