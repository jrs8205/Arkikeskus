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
    // Yksi häiriö voi koskea useaa linjaa ja pysäkkiä (Digitransitin entities): listat sisältävät kaikki,
    // yksittäiset kentät ovat listan ensimmäiset (näyttö, jossa tilaa on yhdelle).
    final List<String> routeShortNames; // häiriön koskemat linjat (esim. "54", "554"), tyhjä jos yleinen
    final List<String> routeModes;      // linjojen moodit samassa järjestyksessä (BUS/TRAM/…)
    final List<String> stopNames;       // häiriön koskemat pysäkit
    // Pysäkin nimi riippuu Accept-Language-kielestä → suosikkivertailu ensisijaisesti id:llä.
    final List<String> stopGtfsIds;     // pysäkkien gtfsId:t samassa järjestyksessä
    final String routeShortName; // ensimmäinen linja, "" jos ei
    final String mode;           // ensimmäisen linjan moodi badge-väriä varten, "" jos ei
    final String stopName;       // ensimmäinen pysäkki, "" jos ei
    final String stopGtfsId;     // ensimmäisen pysäkin gtfsId, "" jos ei

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
        this(header, description, severity, effect, startEpochSec, endEpochSec,
                single(routeShortName), single(mode, routeShortName, mode), single(stopName, stopName, stopGtfsId),
                single(stopGtfsId, stopName, stopGtfsId));
    }

    /** Monen linjan/pysäkin häiriö. Listat: linjat + moodit samassa järjestyksessä, pysäkit + id:t samoin. */
    TransitAlert(String header, String description, String severity, String effect,
                 long startEpochSec, long endEpochSec,
                 List<String> routeShortNames, List<String> routeModes,
                 List<String> stopNames, List<String> stopGtfsIds) {
        this.header = header == null ? "" : header;
        this.description = description == null ? "" : description;
        this.severity = severity == null ? "" : severity;
        this.effect = effect == null ? "" : effect;
        this.startEpochSec = startEpochSec;
        this.endEpochSec = endEpochSec;
        this.routeShortNames = clean(routeShortNames);
        this.routeModes = clean(routeModes);
        this.stopNames = clean(stopNames);
        this.stopGtfsIds = clean(stopGtfsIds);
        this.routeShortName = first(this.routeShortNames);
        this.mode = first(this.routeModes);
        this.stopName = first(this.stopNames);
        this.stopGtfsId = first(this.stopGtfsIds);
    }

    private static List<String> single(String value) {
        return value == null || value.isEmpty()
                ? java.util.Collections.emptyList() : java.util.Collections.singletonList(value);
    }

    /** Yhden kohteen lista, kun pari (esim. linja + moodi) on olemassa: pitää rinnakkaiset listat samanmittaisina. */
    private static List<String> single(String value, String... anyOfPair) {
        for (String s : anyOfPair) {
            if (s != null && !s.isEmpty()) return java.util.Collections.singletonList(value == null ? "" : value);
        }
        return java.util.Collections.emptyList();
    }

    private static List<String> clean(List<String> list) {
        if (list == null || list.isEmpty()) return java.util.Collections.emptyList();
        List<String> out = new java.util.ArrayList<>(list.size());
        for (String s : list) out.add(s == null ? "" : s);
        return java.util.Collections.unmodifiableList(out);
    }

    private static String first(List<String> list) {
        return list.isEmpty() ? "" : list.get(0);
    }

    /** Osuuko häiriö johonkin suosikkilinjaan (lyhyt linjatunnus, esim. "63"). */
    boolean matchesLine(java.util.Set<String> favLines) {
        if (favLines == null || favLines.isEmpty()) return false;
        for (String r : routeShortNames) {
            if (!r.isEmpty() && favLines.contains(r)) return true;
        }
        return false;
    }

    /** Koskeeko häiriö annettua kulkumuotoa (jokin sen linjoista). */
    boolean matchesMode(String wanted) {
        for (String m : routeModes) {
            if (m.equalsIgnoreCase(wanted)) return true;
        }
        return false;
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
        if (favStopIds != null) {
            for (String id : stopGtfsIds) {
                if (!id.isEmpty() && favStopIds.contains(id)) return true;
            }
        }
        if (favStopNames != null) {
            for (String n : stopNames) {
                if (!n.isEmpty() && favStopNames.contains(n)) return true;
            }
        }
        return false;
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
