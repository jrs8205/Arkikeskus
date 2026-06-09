package org.jrs82.fsclock.mobile;

import org.jrs82.fsclock.BuildConfig;

/** MapLibre-rasterityyli Digitransitin (OSM-pohjainen) hsl-map -taustakartalle; avain URL-parametrina.
 *  Sama kartta kuin HSL reittiopas → välttää julkisen OSM-palvelimen käyttöehdot + MML:n SSL-kikan. */
final class DigitransitMapStyle {

    private DigitransitMapStyle() {}

    static String rasterStyleJson() {
        String tiles = "https://cdn.digitransit.fi/map/v3/hsl-map/{z}/{x}/{y}@2x.png"
                + "?digitransit-subscription-key=" + BuildConfig.DIGITRANSIT_KEY;
        return "{"
                + "\"version\":8,"
                + "\"sources\":{\"osm\":{\"type\":\"raster\",\"tiles\":[\"" + tiles
                + "\"],\"tileSize\":256,\"attribution\":\"\\u00a9 OpenStreetMap, HSL\"}},"
                + "\"layers\":[{\"id\":\"osm\",\"type\":\"raster\",\"source\":\"osm\"}]"
                + "}";
    }
}
