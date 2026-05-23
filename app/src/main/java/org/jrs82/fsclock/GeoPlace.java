package org.jrs82.fsclock;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GeoPlace {

    private static final Map<String, GeoPlace> PLACES = new HashMap<>();

    static {
        add("Vantaa", 60.2934, 25.0378);
        add("Helsinki", 60.1699, 24.9384);
        add("Espoo", 60.2055, 24.6559);
        add("Kauniainen", 60.2124, 24.7276);
        add("Kerava", 60.4034, 25.1050);
        add("Tuusula", 60.4037, 25.0266);
        add("Tampere", 61.4978, 23.7610);
        add("Turku", 60.4518, 22.2666);
        add("Oulu", 65.0121, 25.4651);
        add("Jyväskylä", 62.2426, 25.7473);
        add("Lahti", 60.9827, 25.6612);
        add("Kuopio", 62.8924, 27.6770);
        add("Joensuu", 62.6010, 29.7636);
        add("Vaasa", 63.0951, 21.6165);
        add("Pori", 61.4851, 21.7972);
        add("Lappeenranta", 61.0587, 28.1887);
        add("Rovaniemi", 66.5039, 25.7294);
        add("Kajaani", 64.2273, 27.7285);
        add("Kemi", 65.7364, 24.5637);
        add("Tornio", 65.8481, 24.1466);
        add("Mikkeli", 61.6886, 27.2723);
        add("Seinäjoki", 62.7903, 22.8403);
        add("Hämeenlinna", 60.9959, 24.4643);
        add("Kotka", 60.4664, 26.9458);
        add("Kouvola", 60.8681, 26.7042);
        add("Hyvinkää", 60.6336, 24.8690);
        add("Järvenpää", 60.4737, 25.0899);
        add("Kirkkonummi", 60.1238, 24.4385);
        add("Lohja", 60.2518, 24.0653);
        add("Porvoo", 60.3923, 25.6651);
        add("Rauma", 61.1280, 21.5113);
        add("Salo", 60.3831, 23.1331);
        add("Savonlinna", 61.8688, 28.8864);
        add("Imatra", 61.1719, 28.7524);
        add("Varkaus", 62.3153, 27.8730);
        add("Iisalmi", 63.5592, 27.1907);
        add("Kokkola", 63.8385, 23.1307);
        add("Pietarsaari", 63.6749, 22.7026);
        add("Raahe", 64.6833, 24.4833);
        add("Ylivieska", 64.0736, 24.5378);
        add("Kuusamo", 65.9646, 29.1887);
        add("Sodankylä", 67.4155, 26.5897);
        add("Inari", 68.9055, 27.0283);
        add("Utsjoki", 69.9086, 27.0284);
        add("Maarianhamina", 60.0973, 19.9348);
    }

    public final String name;
    public final double latitude;
    public final double longitude;

    private GeoPlace(String name, double latitude, double longitude) {
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public static synchronized GeoPlace forPlace(String rawPlace) {
        String key = normalize(rawPlace);
        GeoPlace p = PLACES.get(key);
        if (p != null) return p;
        return PLACES.get(normalize(SettingsManager.DEFAULT_HOME_PLACE));
    }

    public static synchronized String[] placeNames() {
        List<String> names = new ArrayList<>();
        for (GeoPlace p : PLACES.values()) {
            names.add(p.name);
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names.toArray(new String[0]);
    }

    /** Lisää uuden paikan vain jos sitä ei ole rekisterissä. Built-in-koordinaatteja
     *  ei korvata FMI:n haku- tai vastaussijainnilla (B2-korjaus). */
    public static synchronized void register(String name, double lat, double lon) {
        String key = normalize(name);
        if (!PLACES.containsKey(key)) {
            PLACES.put(key, new GeoPlace(name, lat, lon));
        }
    }

    private static void add(String name, double lat, double lon) {
        PLACES.put(normalize(name), new GeoPlace(name, lat, lon));
    }

    private static String normalize(String s) {
        if (s == null) s = SettingsManager.DEFAULT_HOME_PLACE;
        String n = Normalizer.normalize(s.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.US);
        if (n.isEmpty()) n = SettingsManager.DEFAULT_HOME_PLACE.toLowerCase(Locale.US);
        return n;
    }
}
