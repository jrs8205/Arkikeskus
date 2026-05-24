package org.jrs82.fsclock;

import java.util.ArrayList;
import java.util.List;

/** Eleringin spot-hintadatasta johdettu sisäinen malli. Hinnat ovat snt/kWh
 *  ilman ALV:tä (sama yksikkö kuin sahkonhintatanaan.fi:n julkaisema raaka spot).
 *  Aika on UTC epoch ms, vartti = 15 min slot. */
public class ElectricityData {

    public static class Quarter {
        public long timestamp;       // ms epoch UTC (varttialun)
        public int hour;             // paikallinen 0..23
        public int minute;           // 0, 15, 30, 45
        public int dayOfMonth;       // paikallinen
        public int month;            // 1..12
        public int year;
        public double sntPerKwh;     // ALV mukana
    }

    public long fetchedAt = 0L;
    public final List<Quarter> quarters = new ArrayList<>();
}
