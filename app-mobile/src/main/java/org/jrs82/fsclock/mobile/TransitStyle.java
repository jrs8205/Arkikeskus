package org.jrs82.fsclock.mobile;

import android.content.Context;

import androidx.core.content.ContextCompat;

import org.jrs82.fsclock.R;

/** HSL-moodien brändivärit ja vektori-ikonit — jaettu Compose-näyttöjen (lähilähdöt,
 *  kelikamerat-badget) ja reittihaun View-toteutuksen kesken. Eriytetty poistetusta
 *  TransitAdapterista Compose-migraatiossa; arvot ennallaan. */
final class TransitStyle {

    private TransitStyle() {}

    static int modeColor(Context ctx, String mode) {
        int res;
        if ("TRAM".equals(mode)) res = R.color.mobile_transit_tram;
        else if ("RAIL".equals(mode)) res = R.color.mobile_transit_rail;
        else if ("SUBWAY".equals(mode)) res = R.color.mobile_transit_subway;
        else if ("BUS".equals(mode)) res = R.color.mobile_transit_bus;
        else if ("FERRY".equals(mode)) res = R.color.mobile_transit_ferry;
        else res = R.color.mobile_accent;
        return ContextCompat.getColor(ctx, res);
    }

    static int modeIcon(String mode) {
        if ("FAV".equals(mode)) return R.drawable.mobile_ic_star;
        if ("WALK".equals(mode)) return R.drawable.mobile_ic_transit_walk;
        if ("TRAM".equals(mode)) return R.drawable.mobile_ic_transit_tram;
        if ("RAIL".equals(mode)) return R.drawable.mobile_ic_transit_rail;
        if ("SUBWAY".equals(mode)) return R.drawable.mobile_ic_transit_subway;
        if ("FERRY".equals(mode)) return R.drawable.mobile_ic_transit_ferry;
        return R.drawable.mobile_ic_transit_bus;
    }
}
