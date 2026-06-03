package org.jrs82.fsclock.ruuvi;

/** RuuviTag RAWv2 (data format 5) -mainospaketin purku.
 *  Spec: https://docs.ruuvi.com/communication/bluetooth-advertisements/data-format-5-rawv2
 *
 *  Manufacturer ID 0x0499, payload alkaa format-tavusta (= 5), kokonaispituus 24 tavua. */
public final class RuuviPacket {

    public static final int MANUFACTURER_ID = 0x0499;
    public static final int FORMAT_RAW_V2 = 5;
    public static final int RAW_V2_LENGTH = 24;

    /** Lämpötila °C. null = ei mitattu. */
    public final Double temperatureC;
    /** Suhteellinen kosteus %. null = ei mitattu. */
    public final Double humidityPct;
    /** Ilmanpaine Pa. null = ei mitattu. */
    public final Integer pressurePa;
    /** Kiihtyvyydet milli-G:nä. null = ei mitattu. */
    public final Integer accelXmG, accelYmG, accelZmG;
    /** Anturin paristojännite millivoltteina. null = ei mitattu. */
    public final Integer batteryMv;
    /** Lähetysteho dBm. null = ei mitattu. */
    public final Integer txPowerDbm;
    /** Liikemittarin laskuri 0..254. null = ei tiedossa. */
    public final Integer movementCounter;
    /** Mittaussekvenssi 0..65534 — käytetään duplikaattien suodattamiseen. null = ei tiedossa. */
    public final Integer measurementSequence;
    /** MAC paketin sisältä (6 tavua, MSB first → "AA:BB:CC:DD:EE:FF"). null = ei luettu. */
    public final String macFromPacket;

    private RuuviPacket(Double t, Double h, Integer p,
                        Integer ax, Integer ay, Integer az,
                        Integer batt, Integer txp,
                        Integer move, Integer seq, String mac) {
        this.temperatureC = t;
        this.humidityPct = h;
        this.pressurePa = p;
        this.accelXmG = ax;
        this.accelYmG = ay;
        this.accelZmG = az;
        this.batteryMv = batt;
        this.txPowerDbm = txp;
        this.movementCounter = move;
        this.measurementSequence = seq;
        this.macFromPacket = mac;
    }

    /** Parsii RAWv2-paketin. Palauttaa null jos data ei kelpaa. */
    public static RuuviPacket parseRawV2(byte[] data) {
        if (data == null || data.length < RAW_V2_LENGTH) return null;
        if ((data[0] & 0xFF) != FORMAT_RAW_V2) return null;

        int rawT = readInt16(data, 1);
        Double t = (rawT == (short) 0x8000) ? null : rawT * 0.005;

        int rawH = readUint16(data, 3);
        Double h = (rawH == 0xFFFF) ? null : rawH * 0.0025;

        int rawP = readUint16(data, 5);
        Integer p = (rawP == 0xFFFF) ? null : rawP + 50000;

        int rawAx = readInt16(data, 7);
        int rawAy = readInt16(data, 9);
        int rawAz = readInt16(data, 11);
        Integer ax = (rawAx == (short) 0x8000) ? null : rawAx;
        Integer ay = (rawAy == (short) 0x8000) ? null : rawAy;
        Integer az = (rawAz == (short) 0x8000) ? null : rawAz;

        int rawPower = readUint16(data, 13);
        int battRaw = (rawPower >>> 5) & 0x07FF;
        int txRaw = rawPower & 0x001F;
        Integer batt = (battRaw == 0x07FF) ? null : 1600 + battRaw;
        Integer txp = (txRaw == 0x001F) ? null : -40 + txRaw * 2;

        int rawMove = data[15] & 0xFF;
        Integer move = (rawMove == 0xFF) ? null : rawMove;

        int rawSeq = readUint16(data, 16);
        Integer seq = (rawSeq == 0xFFFF) ? null : rawSeq;

        StringBuilder mac = new StringBuilder(17);
        boolean macValid = false;
        for (int i = 0; i < 6; i++) {
            int b = data[18 + i] & 0xFF;
            if (b != 0xFF) macValid = true;
            if (i > 0) mac.append(':');
            if (b < 0x10) mac.append('0');
            mac.append(Integer.toHexString(b).toUpperCase());
        }

        return new RuuviPacket(t, h, p, ax, ay, az, batt, txp,
                move, seq, macValid ? mac.toString() : null);
    }

    private static int readUint16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int readInt16(byte[] data, int offset) {
        int u = readUint16(data, offset);
        return (u >= 0x8000) ? u - 0x10000 : u;
    }
}
