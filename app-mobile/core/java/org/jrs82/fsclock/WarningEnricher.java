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
            // HUOM: yhdistys tyypin + aikaikkunan perusteella koko maan featureista (ei maakuntakohtainen).
            // Valitaan YKSI edustava feature → todennäköisyys, fyysinen arvo ja teksti tulevat samasta
            // lähteestä (yhtenäiset luvut, ei eri maakuntien sekoitusta). Edustavin = suurin fyysinen arvo,
            // tasapelissä suurin todennäköisyys, sitten pisin teksti.
            FmiWarningDetail best = null;
            for (FmiWarningDetail d : details) {
                if (!contexts.contains(d.context)) continue;
                if (!overlaps(d.fromMs, d.untilMs, w.onsetMs, w.expiresMs)) continue;
                if (best == null || isMoreRepresentative(d, best)) best = d;
            }
            if (best != null) {
                WarningDetails wd = new WarningDetails(best.probabilityPct, best.physicalText, best.detailText);
                out.add(wd.hasAny() ? w.withDetails(wd) : w);
            } else {
                out.add(w);
            }
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

    /** a edustavampi kuin b: suurempi fyysinen arvo (NaN = pienin), sitten suurempi todennäköisyys, sitten pidempi teksti. */
    static boolean isMoreRepresentative(FmiWarningDetail a, FmiWarningDetail b) {
        double av = Double.isNaN(a.physicalValue) ? Double.NEGATIVE_INFINITY : a.physicalValue;
        double bv = Double.isNaN(b.physicalValue) ? Double.NEGATIVE_INFINITY : b.physicalValue;
        if (av != bv) return av > bv;
        if (a.probabilityPct != b.probabilityPct) return a.probabilityPct > b.probabilityPct;
        return a.detailText.length() > b.detailText.length();
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
