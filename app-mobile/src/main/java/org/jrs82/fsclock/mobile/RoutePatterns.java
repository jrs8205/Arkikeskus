package org.jrs82.fsclock.mobile;

import java.util.List;

/** Linjan (route) suunnat (patterns) linjanäkymää varten. */
final class RoutePatterns {
    final String shortName;
    final String longName;
    final String mode;
    final List<Pat> patterns;
    final List<TransitAlert> alerts;   // linjan häiriötiedotteet (tyhjä jos ei)

    RoutePatterns(String shortName, String longName, String mode, List<Pat> patterns,
                  List<TransitAlert> alerts) {
        this.shortName = shortName;
        this.longName = longName;
        this.mode = mode;
        this.patterns = patterns;
        this.alerts = alerts == null ? java.util.Collections.emptyList() : alerts;
    }

    static final class Pat {
        final String code;
        final int directionId;
        final String headsign;
        final String firstStop;
        final String lastStop;
        Pat(String code, int directionId, String headsign, String firstStop, String lastStop) {
            this.code = code;
            this.directionId = directionId;
            this.headsign = headsign;
            this.firstStop = firstStop;
            this.lastStop = lastStop;
        }

        String directionLabel() {
            if (firstStop != null && !firstStop.isEmpty()
                    && lastStop != null && !lastStop.isEmpty()
                    && !firstStop.equalsIgnoreCase(lastStop)) {
                return firstStop + " → " + lastStop;
            }
            return headsign == null ? "" : headsign;
        }
    }
}
