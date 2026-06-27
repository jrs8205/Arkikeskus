package org.jrs82.fsclock;

/** FMI GeoServeristä yhdistetty lisätieto yhteen varoitukseen: toteutumis-todennäköisyys,
 *  konkreettinen fyysinen arvo ja FMI:n (joskus pidempi) kuvausteksti. Muuttumaton. */
public final class WarningDetails {

    public static final WarningDetails EMPTY = new WarningDetails(-1, "", "");

    /** Toteutumis-todennäköisyys prosentteina, -1 jos ei tiedossa. */
    public final int probabilityPct;
    /** Konkreettinen arvo valmiiksi muotoiltuna, esim. "Lämpötila jopa 27 °C". "" jos ei. */
    public final String physicalText;
    /** FMI:n info_fi (entiteetit puretut). "" jos ei käytössä. */
    public final String detailText;

    public WarningDetails(int probabilityPct, String physicalText, String detailText) {
        this.probabilityPct = probabilityPct;
        this.physicalText = physicalText == null ? "" : physicalText;
        this.detailText = detailText == null ? "" : detailText;
    }

    public boolean hasAny() {
        return probabilityPct >= 0 || !physicalText.isEmpty() || !detailText.isEmpty();
    }
}
