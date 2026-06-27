package org.jrs82.fsclock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Yhdistää MeteoAlarm-varoitukset FMI GeoServer -detaljeihin (tyyppi + aikaikkuna). Puhdas, testattava. */
public final class WarningEnricher {

    private WarningEnricher() {}

    public static List<WeatherWarning> enrich(List<WeatherWarning> warnings,
                                              List<FmiWarningDetail> details) {
        List<WeatherWarning> out = new ArrayList<>(warnings.size());
        for (WeatherWarning w : warnings) {
            List<String> contexts = contextsFor(w);
            int bestProb = -1;
            double bestPhysVal = Double.NaN;
            String bestPhysText = "";
            String bestText = "";
            for (FmiWarningDetail d : details) {
                if (!contexts.contains(d.context)) continue;
                if (!overlaps(d.fromMs, d.untilMs, w.onsetMs, w.expiresMs)) continue;
                if (d.probabilityPct > bestProb) bestProb = d.probabilityPct;
                // suurin fyysinen arvo edustaa (esim. korkein lämpötila/puuska)
                if (!Double.isNaN(d.physicalValue)
                        && (Double.isNaN(bestPhysVal) || d.physicalValue > bestPhysVal)) {
                    bestPhysVal = d.physicalValue;
                    bestPhysText = d.physicalText;
                }
                // pisin info_fi = rikkain teksti
                if (d.detailText.length() > bestText.length()) bestText = d.detailText;
            }
            WarningDetails wd = new WarningDetails(bestProb, bestPhysText, bestText);
            out.add(wd.hasAny() ? w.withDetails(wd) : w);
        }
        return out;
    }

    /** MeteoAlarm-varoitus → FMI warning_context -ehdokkaat. */
    static List<String> contextsFor(WeatherWarning w) {
        if (w.marine) return Arrays.asList("sea-thunder-storm", "wind");
        switch (w.awarenessType) {
            case FOREST_FIRE: return Arrays.asList("forest-fire-weather");
            case HIGH_TEMPERATURE: return Arrays.asList("hot-weather");
            case RAIN: return Arrays.asList("rain");
            case THUNDERSTORM: return Arrays.asList("thunder-storm", "sea-thunder-storm");
            case LOW_TEMPERATURE: return Arrays.asList("cold-weather");
            case WIND: return Arrays.asList("wind");
            default: return java.util.Collections.emptyList();
        }
    }

    /** Aikaikkunoiden leikkaus; 0/tuntematon kohdellaan sallivasti (avoin reuna). */
    static boolean overlaps(long aFrom, long aUntil, long bOn, long bEx) {
        long aStart = aFrom > 0 ? aFrom : Long.MIN_VALUE;
        long aEnd   = aUntil > 0 ? aUntil : Long.MAX_VALUE;
        long bStart = bOn > 0 ? bOn : Long.MIN_VALUE;
        long bEnd   = bEx > 0 ? bEx : Long.MAX_VALUE;
        return aStart < bEnd && aEnd > bStart;
    }
}
