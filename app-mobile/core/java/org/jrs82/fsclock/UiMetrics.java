package org.jrs82.fsclock;

import android.content.res.Configuration;
import android.content.res.Resources;

/** Pieni progressiivinen UI-mittakaava. Päätökset perustuvat Android
 *  Configuration-arvoihin, eivät yksittäisiin laitteisiin (esim. SM-T819).
 *  Nimi ei DisplayMetrics, jotta ei sekoitu android.util.DisplayMetricsiin. */
public final class UiMetrics {

    private UiMetrics() {}

    /** True, kun pystysuunnan dp-korkeus on rajallinen (esim. vaakapuhelin).
     *  Käytetään tilaviestintään, esim. fonttikoon pienennys, jotta yhdistetty
     *  rivi ei ylivuoda. SM-T819 landscape jää selvästi yli rajan. */
    public static boolean isCompactHeight(Resources r) {
        Configuration c = r.getConfiguration();
        return c.screenHeightDp < 480;
    }

    /** True, kun pienin näytön sivu on tabletin kokoa (>= 600dp).
     *  Käytetään myöhemmissä vaiheissa esim. tuntisivun shortLabel-näkyvyyteen. */
    public static boolean isTabletLike(Resources r) {
        Configuration c = r.getConfiguration();
        return c.smallestScreenWidthDp >= 600;
    }
}
