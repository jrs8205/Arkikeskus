package org.jrs82.fsclock.mobile;

import org.jrs82.fsclock.BuildConfig;

/** MapLibre-rasterityyli Digitransitin (OSM-pohjainen) hsl-map -taustakartalle; avain URL-parametrina.
 *  Sama kartta kuin HSL reittiopas → välttää julkisen OSM-palvelimen käyttöehdot + MML:n SSL-kikan. */
final class DigitransitMapStyle {

    private DigitransitMapStyle() {}

    static String rasterStyleJson() {
        String tiles = "https://cdn.digitransit.fi/map/v3/hsl-map/{z}/{x}/{y}@2x.png"
                + "?digitransit-subscription-key=" + BuildConfig.DIGITRANSIT_KEY;
        // hsl-map-tiilen perus-koko on 512 px (@2x-kuva on 1024×1024 px, mitattu 18.11.2026) → tileSize
        // PITÄÄ olla 512. Arvolla 256 MapLibre hakisi
        // saman näkymän ~4× tiilimäärällä (~230 kB/tiili) → mobiiliverkossa panorointi näytti
        // mustaa kymmeniä sekunteja. 512 = spec-oikea retina-määritys, sama terävyys.
        return "{"
                + "\"version\":8,"
                + "\"sources\":{\"osm\":{\"type\":\"raster\",\"tiles\":[\"" + tiles
                + "\"],\"tileSize\":512,\"attribution\":\"\\u00a9 OpenStreetMap, HSL\"}},"
                + "\"layers\":[{\"id\":\"osm\",\"type\":\"raster\",\"source\":\"osm\"}]"
                + "}";
    }
}
