package org.jrs82.fsclock;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** Eleringin spot-hintadatasta johdettu sisäinen malli. Hinnat ovat snt/kWh
 *  ilman ALV:tä (sama yksikkö kuin sahkonhintatanaan.fi:n julkaisema raaka spot).
 *  Aika on UTC epoch ms, vartti = 15 min slot. */
public class ElectricityData {

    public static final String SOURCE_ELERING = "elering";
    /** Varalähde spot-hinta.fi ({@link ElectricityFallbackClient}). */
    public static final String SOURCE_SPOT_HINTA = "secondary";

    public static class Quarter {
        public long timestamp;       // ms epoch UTC (varttialun)
        public int hour;             // paikallinen 0..23
        public int minute;           // 0, 15, 30, 45
        public int dayOfMonth;       // paikallinen
        public int month;            // 1..12
        public int year;
        public double sntPerKwh;     // ALV 0 % (Elering-spot); ALV lisätään vasta näyttökerroksessa
    }

    /** Vartti annetulle alkuhetkelle; paikalliset kentät Suomen ajassa (kesä-/talviaika mukana). */
    public static Quarter quarter(long timestampMs, double sntPerKwh) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Helsinki"));
        cal.setTimeInMillis(timestampMs);
        Quarter q = new Quarter();
        q.timestamp = timestampMs;
        q.hour = cal.get(Calendar.HOUR_OF_DAY);
        q.minute = cal.get(Calendar.MINUTE);
        q.dayOfMonth = cal.get(Calendar.DAY_OF_MONTH);
        q.month = cal.get(Calendar.MONTH) + 1;
        q.year = cal.get(Calendar.YEAR);
        q.sntPerKwh = sntPerKwh;
        return q;
    }

    /** True jos varteissa on annetun hetken huomispäivä ILTAAN ASTI (vartti jonka tunti >= 20 Suomen
     *  aikaa). Pelkkä CET-aikavyöhykkeen ~1 h aamuyön ylivuoto (huomisen 00:00–00:45, joka kuuluu
     *  TÄMÄN päivän hintoihin) EI riitä. Kesä-/talviaika ei vaikuta: CET–Suomi-ero on aina 1 h, joten
     *  ylivuoto on aina vain aamuyötä; tunti on laskettu Europe/Helsinki-vyöhykkeellä (DST mukana). */
    public static boolean hasFullTomorrow(List<Quarter> quarters, long nowMs) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Europe/Helsinki"));
        c.setTimeInMillis(nowMs);
        c.add(Calendar.DAY_OF_YEAR, 1);
        int tomY = c.get(Calendar.YEAR);
        int tomM = c.get(Calendar.MONTH) + 1;
        int tomD = c.get(Calendar.DAY_OF_MONTH);
        for (Quarter q : quarters) {
            if (q.year == tomY && q.month == tomM && q.dayOfMonth == tomD && q.hour >= 20) return true;
        }
        return false;
    }

    public long fetchedAt = 0L;
    /** Mistä lähteestä vartit ovat ({@link #SOURCE_ELERING} tai {@link #SOURCE_SPOT_HINTA}); näkymän lähdemaininta. */
    public String source = SOURCE_ELERING;
    public final List<Quarter> quarters = new ArrayList<>();
}
