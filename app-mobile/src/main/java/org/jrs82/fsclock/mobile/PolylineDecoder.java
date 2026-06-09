package org.jrs82.fsclock.mobile;

import java.util.ArrayList;
import java.util.List;

/** Google encoded polyline (precision 5) → [lat, lon]-pisteet. Käytetään Digitransitin
 *  patternGeometry/legGeometry -muotojen purkamiseen (reittiviiva + GPS:n projisointi). */
final class PolylineDecoder {

    private PolylineDecoder() {}

    static List<double[]> decode(String encoded) {
        List<double[]> path = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) return path;
        int index = 0, len = encoded.length();
        int lat = 0, lng = 0;
        while (index < len) {
            int b, shift = 0, result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20 && index < len);
            int dlat = (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
            lat += dlat;
            shift = 0;
            result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20 && index < len);
            int dlng = (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
            lng += dlng;
            path.add(new double[]{lat / 1e5, lng / 1e5});
        }
        return path;
    }
}
