package org.jrs82.fsclock;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** FMI GeoServerin county-koodi (Suomen viralliset maakuntakoodit) → maakuntanimi.
 *  Lähde varmistettu ristiintarkistamalla MeteoAlarmin maakuntanimiin. */
public final class FmiCounties {

    private FmiCounties() {}

    // koodi-indeksi -> nimi (vain käytössä olevat koodit; 3 ja 20 puuttuvat)
    private static final int[] CODES = {1,2,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,21};
    private static final String[] NAMES = {
        "Uusimaa","Varsinais-Suomi","Satakunta","Kanta-Häme","Pirkanmaa","Päijät-Häme",
        "Kymenlaakso","Etelä-Karjala","Etelä-Savo","Pohjois-Savo","Pohjois-Karjala","Keski-Suomi",
        "Etelä-Pohjanmaa","Pohjanmaa","Keski-Pohjanmaa","Pohjois-Pohjanmaa","Kainuu","Lappi","Ahvenanmaa"
    };

    /** Maakunnat virallisessa koodijärjestyksessä (sama kuin NAMES). */
    public static final List<String> ALL_REGIONS =
            Collections.unmodifiableList(Arrays.asList(NAMES));

    private static final Pattern COUNTY = Pattern.compile("county\\.(\\d+)");

    public static String regionFor(int code) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i] == code) return NAMES[i];
        return "";
    }

    public static String regionForRef(String ref) {
        if (ref == null) return "";
        Matcher m = COUNTY.matcher(ref);
        if (m.find()) {
            try { return regionFor(Integer.parseInt(m.group(1))); }
            catch (NumberFormatException e) { return ""; }
        }
        return "";
    }

    /** Indeksi ALL_REGIONS-listassa, -1 jos ei. */
    public static int indexOf(String region) {
        return ALL_REGIONS.indexOf(region);
    }
}
