package org.jrs82.fsclock.mobile;

import java.util.ArrayList;
import java.util.List;

/** Yksi Digitrafficin kelikamera-asema sijainteineen + sen presetit (eri suuntiin
 *  osoittavat kamerakuvat). GeoJSON featuresta jäsennetty, ks. {@link WeathercamClient}. */
final class WeathercamStation {

    final String id;
    final String name;
    final double lat;
    final double lon;
    final List<WeathercamPreset> presets;
    /** Etäisyys referenssipisteestä metreinä; asetetaan {@link WeathercamRepository}ssa. */
    double distanceMeters = Double.NaN;

    WeathercamStation(String id, String name, double lat, double lon, List<WeathercamPreset> presets) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.lat = lat;
        this.lon = lon;
        this.presets = presets == null ? new ArrayList<>() : presets;
    }

    /** Yhden aseman yksittäinen kamerakuva (yksi suunta). */
    static final class WeathercamPreset {
        final String id;
        final String presentationName;
        final String imageUrl;

        WeathercamPreset(String id, String presentationName) {
            this.id = id == null ? "" : id;
            this.presentationName = presentationName == null ? "" : presentationName;
            // Kuvan vakio-osoite. EI Authorization-headeria haettaessa (palauttaa 400).
            this.imageUrl = "https://weathercam.digitraffic.fi/" + this.id + ".jpg";
        }

        String thumbnailUrl() {
            return imageUrl + "?thumbnail=true";
        }
    }
}
