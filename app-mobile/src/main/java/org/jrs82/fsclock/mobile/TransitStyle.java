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

    /** Luettava tekstin/ikonin väri moodivärin (badge/merkki) päälle. Valkoinen teksti on heikko
     *  vaaleilla HSL-väreillä (ferry/subway/ratikka) → valitaan musta jos tausta vaalea. */
    static int onModeColor(Context ctx, String mode) {
        return onColorFor(modeColor(ctx, mode));
    }

    private static final int ON_DARK = 0xFF1A1A1A;    // pehmeä musta
    private static final int ON_LIGHT = 0xFFFFFFFF;   // valkoinen

    /** Valitsee tausta­väriä vasten paremman kontrastin: valkoinen vai pehmeä musta. Vertaa
     *  todellisia WCAG-kontrasteja (ei pelkkä luminanssikynnys → oikein myös rajatapauksille kuten
     *  bus #007AC9). Puhdas (bittilaskenta, ei android.graphics.Coloria) → yksikkötestattavissa. */
    static int onColorFor(int color) {
        return contrast(ON_LIGHT, color) >= contrast(ON_DARK, color) ? ON_LIGHT : ON_DARK;
    }

    private static double contrast(int fg, int bg) {
        double a = relLuminance(fg);
        double b = relLuminance(bg);
        double hi = Math.max(a, b);
        double lo = Math.min(a, b);
        return (hi + 0.05) / (lo + 0.05);
    }

    private static double relLuminance(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
    }

    private static double lin(int c8) {
        double c = c8 / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
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
