package org.jrs82.fsclock.mobile;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Tampereen/Nyssen GTFS-RT live-ajoneuvosijainnit Digitransitin MQTT-brokerista
 *  (mqtt.digitransit.fi:8883, TLS, ei avainta). Tilaa yhden reitin+suunnan vp-virran ja suodattaa
 *  callbackissa annetun vuoron (tripId) ajoneuvon. Payload = protobuf GTFS-RT (vrt. {@link HslMqttClient},
 *  joka käyttää HSL:n JSON-HFP:tä) → parsinta {@link TampereVehicleParser}:lla. Callback tulee
 *  MQTT-säikeestä → kutsuja vastaa UI-säikeelle siirrosta (kuten HSL-polulla).
 *
 *  <p>Topic-suodatin ja kenttämappays varmistettu live-datalla 26.6.2026; ks. {@code PLAN_tampere_mqtt_live.md}. */
final class TampereMqttClient {

    /** Suodatetun vuoron ajoneuvon päivitys. lat/lon/bearing NaN ja stopSequence -1 jos puuttuu;
     *  incoming = lähestyy pysäkkiä (ei STOPPED_AT); tsi = GTFS-RT timestamp (unix s, 0 jos puuttuu). */
    interface Listener {
        void onVehicle(double lat, double lon, double bearing, boolean incoming,
                       String stopId, int stopSequence, long tsi);
    }

    private final AtomicLong generation = new AtomicLong();
    private volatile Mqtt3AsyncClient client;

    /** Tilaa patternin (reitti+suunta) vp-virran ja välittää vain tripId:tä vastaavan ajoneuvon.
     *  Sulkee mahdollisen edellisen yhteyden ensin (yksi yhteys per avattu aikajana). */
    void subscribe(String patternCode, String tripId, Listener listener) {
        disconnect();
        final String topic = topicForPattern(patternCode);
        if (topic == null || tripId == null || tripId.isEmpty() || listener == null) return;
        final long token = generation.incrementAndGet();
        final Mqtt3AsyncClient c = MqttClient.builder()
                .useMqttVersion3()
                .identifier("arkikeskus-tre-" + UUID.randomUUID())
                .serverHost("mqtt.digitransit.fi")
                .serverPort(8883)
                .useSslWithDefaultConfig()  // järjestelmän luottamusvarasto (kuten HSL-polku)
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
                            handle(pub.getPayloadAsBytes(), tripId, listener);
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

    private static void handle(byte[] payload, String tripId, Listener l) {
        for (TampereVehicleParser.Vp vp : TampereVehicleParser.parse(payload)) {
            if (tripId.equals(vp.tripId)) {
                l.onVehicle(vp.lat, vp.lon, vp.bearing, vp.incoming, vp.stopId, vp.stopSequence,
                        vp.timestampSec);
                return;
            }
        }
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

    /** patternCode "tampere:&lt;route&gt;:&lt;dir&gt;:&lt;variant&gt;" → vp-topic-suodatin
     *  "/gtfsrt/vp/tampere/+/+/+/&lt;route&gt;/&lt;dir&gt;/#" (3×+ = tyhjä agency_id, tyhjä agency_name, mode).
     *  null jos koodi puuttuu tai siitä ei saa reittiä+suuntaa. */
    static String topicForPattern(String patternCode) {
        if (patternCode == null) return null;
        String[] p = patternCode.split(":");
        if (p.length < 3) return null;
        String route = p[1];
        String dir = p[2];
        if (route.isEmpty() || dir.isEmpty()) return null;
        return "/gtfsrt/vp/tampere/+/+/+/" + route + "/" + dir + "/#";
    }
}
