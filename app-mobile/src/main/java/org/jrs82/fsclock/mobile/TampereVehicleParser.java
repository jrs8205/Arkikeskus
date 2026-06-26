package org.jrs82.fsclock.mobile;

import com.google.transit.realtime.GtfsRealtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Puhdas (Android-vapaa) GTFS-RT-protobuf-parseri Tampereen/Nyssen Digitransit-MQTT-payloadille
 * (broker mqtt.digitransit.fi, topic {@code /gtfsrt/vp/tampere/…}). Yksi MQTT-viesti = yksi
 * {@link GtfsRealtime.FeedMessage}, joka sisältää yhden {@code VehiclePosition}-entityn.
 *
 * <p>Mappays varmistettu live-datalla 26.6.2026 (ks. {@code PLAN_tampere_mqtt_live.md}):
 * {@code vehicle.trip.trip_id} == {@code tripGtfsId.removePrefix("tampere:")} (matchausavain);
 * {@code vehicle.stop_id} == {@code timelineStop.gtfsId.removePrefix("tampere:")};
 * {@code current_status} = GTFS-RT VehicleStopStatus (sama kuin HSL-GraphQL-polku). ⚠️ Protobufin
 * {@code trip.route_id} on linja+operaattori (esim. "156990"), EI Digitransitin route_id ("15") —
 * sitä ei käytetä matchaykseen, vaan {@code trip_id}:tä. */
final class TampereVehicleParser {

    /** Yhden ajoneuvon poimitut kentät. lat/lon/bearing NaN ja stopSequence/directionId -1 jos puuttuu. */
    static final class Vp {
        final String tripId;
        final String routeId;       // pb trip.route_id (linja+operaattori, vain diagnostiikkaan)
        final String stopId;        // GTFS stop_id ("5200"); "" jos puuttuu
        final String vehicleRef;    // vehicle.id ("6990_415"); "" jos puuttuu
        final int directionId;      // 0/1; -1 jos puuttuu
        final int stopSequence;     // current_stop_sequence; -1 jos puuttuu
        final boolean incoming;     // true = lähestyy pysäkkiä (ei STOPPED_AT)
        final double lat;
        final double lon;
        final double bearing;
        final long timestampSec;

        Vp(String tripId, String routeId, String stopId, String vehicleRef, int directionId,
           int stopSequence, boolean incoming, double lat, double lon, double bearing,
           long timestampSec) {
            this.tripId = tripId;
            this.routeId = routeId;
            this.stopId = stopId;
            this.vehicleRef = vehicleRef;
            this.directionId = directionId;
            this.stopSequence = stopSequence;
            this.incoming = incoming;
            this.lat = lat;
            this.lon = lon;
            this.bearing = bearing;
            this.timestampSec = timestampSec;
        }
    }

    private TampereVehicleParser() {}

    /** Parsii FeedMessagen ajoneuvoiksi. Palauttaa tyhjän eikä heitä virheelliselle payloadille. */
    static List<Vp> parse(byte[] payload) {
        if (payload == null || payload.length == 0) return Collections.emptyList();
        try {
            GtfsRealtime.FeedMessage fm = GtfsRealtime.FeedMessage.parseFrom(payload);
            List<Vp> out = new ArrayList<>(fm.getEntityCount());
            for (GtfsRealtime.FeedEntity ent : fm.getEntityList()) {
                if (!ent.hasVehicle()) continue;
                GtfsRealtime.VehiclePosition v = ent.getVehicle();
                GtfsRealtime.TripDescriptor t = v.getTrip();
                GtfsRealtime.Position p = v.getPosition();
                String status = v.hasCurrentStatus() ? v.getCurrentStatus().name() : "";
                out.add(new Vp(
                        t.getTripId(),
                        t.getRouteId(),
                        v.hasStopId() ? v.getStopId() : "",
                        v.hasVehicle() ? v.getVehicle().getId() : "",
                        t.hasDirectionId() ? t.getDirectionId() : -1,
                        v.hasCurrentStopSequence() ? v.getCurrentStopSequence() : -1,
                        incomingFor(status),
                        v.hasPosition() ? p.getLatitude() : Double.NaN,
                        v.hasPosition() ? p.getLongitude() : Double.NaN,
                        (v.hasPosition() && p.hasBearing()) ? p.getBearing() : Double.NaN,
                        v.hasTimestamp() ? v.getTimestamp() : 0L));
            }
            return out;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** GTFS-RT VehicleStopStatus → lähestyykö pysäkkiä. Sama sääntö kuin HSL-GraphQL-polku
     *  ({@link DigitransitApi}): incoming = kaikki paitsi STOPPED_AT. */
    static boolean incomingFor(String status) {
        return !"STOPPED_AT".equalsIgnoreCase(status);
    }
}
