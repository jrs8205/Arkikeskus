package org.jrs82.fsclock.mobile;

import java.util.List;
import java.util.Locale;

/** Yksittäinen HSL-häiriö-/poikkeustiedote (Digitransit GraphQL {@code alerts}).
 *  Liitetään pysäkkiin ({@link NearbyStop}), linjaan ({@link RoutePatterns}) ja
 *  reittihaun osuuteen ({@link Leg}). */
public final class TransitAlert {

    final String header;        // alertHeaderText
    final String description;   // alertDescriptionText
    final String severity;      // INFO / WARNING / SEVERE / UNKNOWN_SEVERITY
    final String effect;        // DETOUR / NO_SERVICE / REDUCED_SERVICE / OTHER_EFFECT …
    final long startEpochSec;   // effectiveStartDate (0 jos ei tiedossa)
    final long endEpochSec;     // effectiveEndDate (0 jos ei tiedossa)
    // Vain feed-haussa (serviceAlerts) — tyhjä entiteettiin liitetyillä (stop/route/leg).
    final String routeShortName; // häiriön koskema linja (esim. "63"), "" jos yleinen
    final String mode;           // linjan moodi (BUS/TRAM/…) badge-väriä varten, "" jos ei
    final String stopName;       // häiriön koskema pysäkki, "" jos ei
    // Pysäkin nimi riippuu Accept-Language-kielestä → suosikkivertailu ensisijaisesti id:llä.
    final String stopGtfsId;     // häiriön koskeman pysäkin gtfsId, "" jos ei

    TransitAlert(String header, String description, String severity, String effect,
                 long startEpochSec, long endEpochSec) {
        this(header, description, severity, effect, startEpochSec, endEpochSec, "", "", "");
    }

    TransitAlert(String header, String description, String severity, String effect,
                 long startEpochSec, long endEpochSec,
                 String routeShortName, String mode, String stopName) {
        this(header, description, severity, effect, startEpochSec, endEpochSec,
                routeShortName, mode, stopName, "");
    }

    TransitAlert(String header, String description, String severity, String effect,
                 long startEpochSec, long endEpochSec,
                 String routeShortName, String mode, String stopName, String stopGtfsId) {
        this.header = header == null ? "" : header;
        this.description = description == null ? "" : description;
        this.severity = severity == null ? "" : severity;
        this.effect = effect == null ? "" : effect;
        this.startEpochSec = startEpochSec;
        this.endEpochSec = endEpochSec;
        this.routeShortName = routeShortName == null ? "" : routeShortName;
        this.mode = mode == null ? "" : mode;
        this.stopName = stopName == null ? "" : stopName;
        this.stopGtfsId = stopGtfsId == null ? "" : stopGtfsId;
    }

    /** Onko häiriö voimassa annettuna hetkenä (sekunteina). 0-alku/-loppu = ei rajaa kyseiseen suuntaan. */
    boolean isActiveAt(long nowSec) {
        if (startEpochSec > 0 && nowSec < startEpochSec) return false;
        if (endEpochSec > 0 && nowSec > endEpochSec) return false;
        return true;
    }

    /** Severity-järjestysarvo (suurempi = vakavampi) bannerin värin ja järjestyksen valintaan. */
    int severityRank() {
        switch (severity.toUpperCase(Locale.ROOT)) {
            case "SEVERE": return 3;
            case "WARNING": return 2;
            case "INFO": return 1;
            default: return 0;     // UNKNOWN_SEVERITY / tyhjä
        }
    }

    /** Osuuko häiriö suosikkipysäkkiin: id ensin (kieliriippumaton), nimi varalla. */
    boolean matchesStop(java.util.Set<String> favStopIds, java.util.Set<String> favStopNames) {
        if (!stopGtfsId.isEmpty() && favStopIds != null && favStopIds.contains(stopGtfsId)) return true;
        return !stopName.isEmpty() && favStopNames != null && favStopNames.contains(stopName);
    }

    /** Näytettävä teksti: otsikko jos on, muuten kuvaus. */
    String displayText() {
        return !header.isEmpty() ? header : description;
    }

    /** Listan vakavin severity-rank (0 jos tyhjä) — bannerin värin valintaan. */
    static int maxSeverityRank(List<TransitAlert> alerts) {
        int max = 0;
        if (alerts != null) {
            for (TransitAlert a : alerts) {
                if (a != null) max = Math.max(max, a.severityRank());
            }
        }
        return max;
    }
}
