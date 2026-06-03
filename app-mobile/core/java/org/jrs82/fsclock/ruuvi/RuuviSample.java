package org.jrs82.fsclock.ruuvi;

/** Yksi BLE-skannauksessa havaittu RuuviTag-mittaus.
 *  Yhdistää RuuviPacketin sisällön ja skannausmetadatan (laitteen MAC, RSSI, aikaleima). */
public final class RuuviSample {

    public final String mac;
    public final int rssi;
    public final long timestamp;
    public final RuuviPacket packet;

    public RuuviSample(String mac, int rssi, long timestamp, RuuviPacket packet) {
        this.mac = mac == null ? "" : mac.toUpperCase();
        this.rssi = rssi;
        this.timestamp = timestamp;
        this.packet = packet;
    }

    public Double temperatureC() { return packet == null ? null : packet.temperatureC; }
    public Double humidityPct()  { return packet == null ? null : packet.humidityPct; }
    public Integer pressurePa()  { return packet == null ? null : packet.pressurePa; }
    public Integer batteryMv()   { return packet == null ? null : packet.batteryMv; }
    public Integer sequence()    { return packet == null ? null : packet.measurementSequence; }
}
