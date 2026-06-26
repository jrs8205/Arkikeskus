package org.jrs82.fsclock.mobile;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Tampereen/Nyssen GTFS-RT live-ajoneuvosijainnit Digitransitin MQTT-brokerista
 *  (mqtt.digitransit.fi:8883, TLS, ei avainta). Tilaa reitin+suunnan vp-virran. Vuoronäkymä
 *  ({@link #subscribe}) suodattaa yhden vuoron (tripId); linjanäkymä ({@link #subscribeRoute})
 *  välittää kaikki reitin vuorot (monivuorokorostus, kuten HSL GraphQL:n vehiclePositions).
 *  Payload = protobuf GTFS-RT (vrt. {@link HslMqttClient} = HSL:n JSON-HFP) → parsinta
 *  {@link TampereVehicleParser}:lla. Callback tulee MQTT-säikeestä → kutsuja vastaa UI-säikeelle siirrosta.
 *
 *  <p>Topic-suodatin ja kenttämappays varmistettu live-datalla 26.6.2026; ks. {@code PLAN_tampere_mqtt_live.md}. */
final class TampereMqttClient {

    /** Suodatetun vuoron ajoneuvon päivitys (vuoronäkymä). lat/lon/bearing NaN ja stopSequence -1 jos
     *  puuttuu; incoming = lähestyy pysäkkiä (ei STOPPED_AT); tsi = GTFS-RT timestamp (unix s, 0 jos puuttuu). */
    interface Listener {
        void onVehicle(double lat, double lon, double bearing, boolean incoming,
                       String stopId, int stopSequence, long tsi);
    }

    /** Kuten {@link Listener}, mutta ilman trip-suodatusta: callback per ajoneuvo (kaikki reitin vuorot),
     *  tripId mukana jotta kutsuja osaa eritellä vuorot (linjanäkymän monivuorokorostus). */
    interface RouteListener {
        void onVehicle(String tripId, double lat, double lon, double bearing, boolean incoming,
                       String stopId, int stopSequence, long tsi);
    }

    private final AtomicLong generation = new AtomicLong();
    private volatile Mqtt3AsyncClient client;

    /** Tilaa patternin (reitti+suunta) vp-virran ja välittää vain tripId:tä vastaavan ajoneuvon. */
    void subscribe(String patternCode, String tripId, Listener listener) {
        if (tripId == null || tripId.isEmpty() || listener == null) {
            disconnect();
            return;
        }
        connect(topicForPattern(patternCode), payload -> handle(payload, tripId, listener));
    }

    /** Tilaa patternin (reitti+suunta) vp-virran ja välittää KAIKKI vuorot (ei trip-suodatusta). */
    void subscribeRoute(String patternCode, RouteListener listener) {
        if (listener == null) {
            disconnect();
            return;
        }
        connect(topicForPattern(patternCode), payload -> handleRoute(payload, listener));
    }

    /** Sulkee edellisen yhteyden, avaa uuden TLS-yhteyden brokeriin ja tilaa topicin. onPayload
     *  kutsutaan jokaisesta viestistä vain niin kauan kuin tämä tilaus on yhä voimassa (generation-token). */
    private void connect(String topic, Consumer<byte[]> onPayload) {
        disconnect();
        if (topic == null || onPayload == null) return;
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
                            onPayload.accept(pub.getPayloadAsBytes());
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

    private static void handleRoute(byte[] payload, RouteListener l) {
        for (TampereVehicleParser.Vp vp : TampereVehicleParser.parse(payload)) {
            if (vp.tripId != null && !vp.tripId.isEmpty()) {
                l.onVehicle(vp.tripId, vp.lat, vp.lon, vp.bearing, vp.incoming, vp.stopId,
                        vp.stopSequence, vp.timestampSec);
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
