package org.jrs82.fsclock.mobile;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** HSL HFP -live-ajoneuvosijainnit MQTT:n yli (mqtt.hsl.fi:8883, TLS). Tilaa yhden ajoneuvon
 *  vp-tapahtumat (A-strategia: vehicleId "HSL:oper/veh"). Callback tulee MQTT-säikeestä → kutsuja
 *  vastaa UI-säikeelle siirrosta. GraphQL ei anna GPS:ää; oikea lat/long tulee tästä syötteestä. */
final class HslMqttClient {

    /** lat/lon WGS84 (NaN jos puuttuu); speed m/s; delay s (negatiivinen=myöhässä); loc: GPS/ODO/MAN/DR/N-A. */
    interface Listener {
        void onVehicle(double lat, double lon, double speed, int delay, String loc, double heading, long tsi);
    }

    private final AtomicLong generation = new AtomicLong();
    private volatile Mqtt3AsyncClient client;

    /** Tilaa annetun ajoneuvon vp-virran. Sulkee mahdollisen edellisen yhteyden ensin. */
    void subscribeVehicle(String vehicleId, Listener listener) {
        disconnect();
        String[] ov = parseOperVeh(vehicleId);
        if (ov == null || listener == null) return;
        final long token = generation.incrementAndGet();
        final String topic = "/hfp/v2/journey/ongoing/vp/+/" + ov[0] + "/" + ov[1] + "/#";
        final Mqtt3AsyncClient c = MqttClient.builder()
                .useMqttVersion3()
                .identifier("arkikeskus-" + UUID.randomUUID())
                .serverHost("mqtt.hsl.fi")
                .serverPort(8883)
                .useSslWithDefaultConfig()  // järjestelmän luottamusvarasto; vanhoilla laitteilla mahd. bundleroot
                .buildAsync();
        client = c;
        c.connect().whenComplete((ack, err) -> {
            if (err != null) {
                if (client == c) client = null;
                return;
            }
            if (generation.get() != token || client != c) {
                disconnectClient(c);
                return;
            }
            c.subscribeWith()
                    .topicFilter(topic)
                    .qos(MqttQos.AT_MOST_ONCE)
                    .callback(pub -> {
                        if (generation.get() == token && client == c) {
                            handle(pub.getPayloadAsBytes(), listener);
                        }
                    })
                    .send()
                    .whenComplete((subAck, subErr) -> {
                        if (subErr != null && client == c) {
                            client = null;
                            disconnectClient(c);
                        }
                    });
        });
    }

    private void handle(byte[] payload, Listener l) {
        try {
            JSONObject root = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            JSONObject vp = root.optJSONObject("VP");
            if (vp == null) return;
            l.onVehicle(
                    vp.optDouble("lat", Double.NaN),
                    vp.optDouble("long", Double.NaN),
                    vp.optDouble("spd", Double.NaN),
                    vp.optInt("dl", 0),
                    vp.optString("loc", ""),
                    vp.optDouble("hdg", Double.NaN),
                    vp.optLong("tsi", 0L));
        } catch (Exception ignored) { }
    }

    void disconnect() {
        generation.incrementAndGet();
        Mqtt3AsyncClient c = client;
        client = null;
        disconnectClient(c);
    }

    private static void disconnectClient(Mqtt3AsyncClient c) {
        if (c == null) return;
        try { c.disconnect().exceptionally(err -> null); }
        catch (Exception ignored) { }
    }

    /** "HSL:22/1127" → {"0022","01127"} (oper 4 num, veh 5 num, nollatäyttö). null jos ei jäsenny. */
    private static String[] parseOperVeh(String vehicleId) {
        if (vehicleId == null) return null;
        int colon = vehicleId.indexOf(':');
        int slash = vehicleId.indexOf('/');
        if (slash <= 0) return null;
        try {
            int oper = Integer.parseInt(vehicleId.substring(colon + 1, slash).trim());
            int veh = Integer.parseInt(vehicleId.substring(slash + 1).trim());
            return new String[]{
                    String.format(Locale.ROOT, "%04d", oper),
                    String.format(Locale.ROOT, "%05d", veh)};
        } catch (Exception e) {
            return null;
        }
    }
}
