package org.jrs82.fsclock;

import java.text.Normalizer;
import java.util.HashMap;
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
        add("Jyvaskyla", 62.2426, 25.7473);
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
        add("Seinajoki", 62.7903, 22.8403);
        add("Hameenlinna", 60.9959, 24.4643);
        add("Kotka", 60.4664, 26.9458);
        add("Kouvola", 60.8681, 26.7042);
    }

    public final String name;
    public final double latitude;
    public final double longitude;

    private GeoPlace(String name, double latitude, double longitude) {
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public static GeoPlace forPlace(String rawPlace) {
        String key = normalize(rawPlace);
        GeoPlace p = PLACES.get(key);
        if (p != null) return p;
        return PLACES.get(normalize(SettingsManager.DEFAULT_HOME_PLACE));
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
