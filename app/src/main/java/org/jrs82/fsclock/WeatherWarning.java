package org.jrs82.fsclock;

import java.util.Locale;

/** Yksittäinen FMI/MeteoAlarm-sääoitus. Yksi instanssi vastaa yhtä alert-objektia
 *  joka voi kattaa useita maakuntia (areaDesc on jo valmiiksi pilkulla erotettu lista). */
public class WeatherWarning {

    public enum Level {
        YELLOW(0xFFE6C32E, "Keltainen"),
        ORANGE(0xFFE89B2C, "Oranssi"),
        RED(0xFFD0413B, "Punainen"),
        UNKNOWN(0xFF888888, "");

        public final int color;
        public final String fiName;
        Level(int color, String fiName) { this.color = color; this.fiName = fiName; }

        /** Parsii MeteoAlarmin awareness_level-stringistä esim. "2; yellow; Moderate". */
        public static Level fromAwareness(String raw) {
            if (raw == null) return UNKNOWN;
            String low = raw.toLowerCase(Locale.ROOT);
            if (low.contains("red")) return RED;
            if (low.contains("orange")) return ORANGE;
            if (low.contains("yellow")) return YELLOW;
            return UNKNOWN;
        }

        public int rank() {
            switch (this) {
                case RED: return 3;
                case ORANGE: return 2;
                case YELLOW: return 1;
                default: return 0;
            }
        }
    }

    public final String event;
    public final String description;
    public final String areaDesc;
    public final long onsetMs;
    public final long expiresMs;
    public final Level level;
    public final String identifier;
    /** true jos varoitus koskee veneilijöitä tai merialueita (lajitellaan listan loppuun). */
    public final boolean marine;

    public WeatherWarning(String event, String description, String areaDesc,
                           long onsetMs, long expiresMs, Level level, String identifier,
                           boolean marine) {
        this.event = event == null ? "" : event;
        this.description = description == null ? "" : description;
        this.areaDesc = areaDesc == null ? "" : areaDesc;
        this.onsetMs = onsetMs;
        this.expiresMs = expiresMs;
        this.level = level == null ? Level.UNKNOWN : level;
        this.identifier = identifier == null ? "" : identifier;
        this.marine = marine;
    }

    public static boolean detectMarine(String event, String areaDesc, java.util.List<String> emmaIds) {
        String e = event == null ? "" : event.toLowerCase(Locale.ROOT);
        if (e.contains("veneilij") || e.contains("merialue")) return true;
        String a = areaDesc == null ? "" : areaDesc.toLowerCase(Locale.ROOT);
        // Suomen merialueiden nimet MeteoAlarmissa
        if (a.contains("perämer") || a.contains("selkämer") || a.contains("suomenlah")
                || a.contains("ahvenanm") || a.contains("saaristom") || a.contains("merenkurk")
                || a.contains("riianlah") || a.contains("itämer")) return true;
        if (emmaIds != null) {
            for (String id : emmaIds) {
                if (id != null && id.startsWith("FI8")) return true;
            }
        }
        return false;
    }
}
