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

    public enum AwarenessType {
        WIND(1, "Tuuli"),
        SNOW_ICE(2, "Lumi/jää"),
        THUNDERSTORM(3, "Ukkonen"),
        FOG(4, "Sumu"),
        HIGH_TEMPERATURE(5, "Helle"),
        LOW_TEMPERATURE(6, "Pakkanen"),
        COASTAL(7, "Rannikko"),
        FOREST_FIRE(8, "Maastopalo"),
        AVALANCHE(9, "Lumivyöry"),
        RAIN(10, "Sade"),
        FLOOD(11, "Tulva"),
        UNKNOWN(0, "");

        public final int code;
        public final String fiName;
        AwarenessType(int code, String fiName) { this.code = code; this.fiName = fiName; }

        /** Parsii MeteoAlarmin awareness_type-stringin, esim. "8; forest-fire". */
        public static AwarenessType fromParam(String raw) {
            if (raw == null) return UNKNOWN;
            String s = raw.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) return UNKNOWN;
            String head = s.split(";")[0].trim();
            try {
                int code = Integer.parseInt(head);
                for (AwarenessType t : values()) if (t.code == code && t != UNKNOWN) return t;
            } catch (NumberFormatException ignored) { }
            if (s.contains("wind")) return WIND;
            if (s.contains("snow") || s.contains("ice")) return SNOW_ICE;
            if (s.contains("thunder")) return THUNDERSTORM;
            if (s.contains("fog")) return FOG;
            if (s.contains("forest") || s.contains("fire")) return FOREST_FIRE;
            if (s.contains("rain")) return RAIN;
            if (s.contains("flood")) return FLOOD;
            if (s.contains("high-temp")) return HIGH_TEMPERATURE;
            if (s.contains("low-temp")) return LOW_TEMPERATURE;
            if (s.contains("coastal")) return COASTAL;
            if (s.contains("avalanche")) return AVALANCHE;
            return UNKNOWN;
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
    /** Ilmiötyyppi (MeteoAlarm awareness_type) → ikonivalinta UI:ssa. */
    public final AwarenessType awarenessType;
    /** CAP-vakavuus raakana (Minor/Moderate/Severe/Extreme). */
    public final String severity;
    /** CAP-varmuus raakana (Observed/Likely/Possible/Unlikely). */
    public final String certainty;
    /** CAP-kiireellisyys raakana (Immediate/Expected/Future/Past). */
    public final String urgency;
    /** Julkaisuhetki (effective) millisekunteina, 0 jos ei tiedossa. */
    public final long effectiveMs;
    /** Lähettäjän nimi, esim. "Ilmatieteen laitos". */
    public final String senderName;
    /** Linkki lisätietoihin (FMI:n varoitussivu). */
    public final String web;
    /** FMI GeoServer -rikastus (todennäköisyys, fyysinen arvo, pidempi teksti). Oletus EMPTY. */
    public final WarningDetails details;

    /** Täysi konstruktori. */
    public WeatherWarning(String event, String description, String areaDesc,
                           long onsetMs, long expiresMs, Level level, String identifier,
                           boolean marine, AwarenessType awarenessType, String severity,
                           String certainty, String urgency, long effectiveMs,
                           String senderName, String web, WarningDetails details) {
        this.event = event == null ? "" : event;
        this.description = description == null ? "" : description;
        this.areaDesc = areaDesc == null ? "" : areaDesc;
        this.onsetMs = onsetMs;
        this.expiresMs = expiresMs;
        this.level = level == null ? Level.UNKNOWN : level;
        this.identifier = identifier == null ? "" : identifier;
        this.marine = marine;
        this.awarenessType = awarenessType == null ? AwarenessType.UNKNOWN : awarenessType;
        this.severity = severity == null ? "" : severity;
        this.certainty = certainty == null ? "" : certainty;
        this.urgency = urgency == null ? "" : urgency;
        this.effectiveMs = effectiveMs;
        this.senderName = senderName == null ? "" : senderName;
        this.web = web == null ? "" : web;
        this.details = details == null ? WarningDetails.EMPTY : details;
    }

    /** 15-arg (ilman detailsia) — WarningsClientin rakentama, rikastus tehdään myöhemmin withDetailsilla. */
    public WeatherWarning(String event, String description, String areaDesc,
                           long onsetMs, long expiresMs, Level level, String identifier,
                           boolean marine, AwarenessType awarenessType, String severity,
                           String certainty, String urgency, long effectiveMs,
                           String senderName, String web) {
        this(event, description, areaDesc, onsetMs, expiresMs, level, identifier, marine,
             awarenessType, severity, certainty, urgency, effectiveMs, senderName, web,
             WarningDetails.EMPTY);
    }

    /** Taaksepäin yhteensopiva konstruktori (etusivun kortti + olemassa olevat testit). */
    public WeatherWarning(String event, String description, String areaDesc,
                           long onsetMs, long expiresMs, Level level, String identifier,
                           boolean marine) {
        this(event, description, areaDesc, onsetMs, expiresMs, level, identifier, marine,
             AwarenessType.UNKNOWN, "", "", "", 0L, "", "");
    }

    /** Palauttaa kopion samoilla kentillä mutta annetuilla FMI-lisätiedoilla. */
    public WeatherWarning withDetails(WarningDetails d) {
        return new WeatherWarning(event, description, areaDesc, onsetMs, expiresMs, level,
                identifier, marine, awarenessType, severity, certainty, urgency, effectiveMs,
                senderName, web, d);
    }

    public static boolean detectMarine(String event, String areaDesc, java.util.List<String> emmaIds) {
        String e = event == null ? "" : event.toLowerCase(Locale.ROOT);
        if (e.contains("veneilij") || e.contains("merialue")) return true;
        String a = areaDesc == null ? "" : areaDesc.toLowerCase(Locale.ROOT);
        // Suomen merialueiden nimet MeteoAlarmissa
        // HUOM: "ahvenanmer" (Ahvenanmeri = meri), EI "ahvenanm" — muuten maakunta "Ahvenanmaa"
        // (maa-alue) leimaisi koko maan maavaroitukset virheellisesti merivaroituksiksi.
        if (a.contains("perämer") || a.contains("selkämer") || a.contains("suomenlah")
                || a.contains("ahvenanmer") || a.contains("saaristom") || a.contains("merenkurk")
                || a.contains("riianlah") || a.contains("itämer")) return true;
        if (emmaIds != null) {
            for (String id : emmaIds) {
                if (id != null && id.startsWith("FI8")) return true;
            }
        }
        return false;
    }
}
