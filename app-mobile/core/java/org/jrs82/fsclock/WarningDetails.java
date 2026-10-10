package org.jrs82.fsclock;

/** FMI GeoServeristä yhdistetty lisätieto yhteen varoitukseen: toteutumis-todennäköisyys,
 *  konkreettinen fyysinen arvo ja FMI:n (joskus pidempi) kuvausteksti. Muuttumaton.
 *  Fyysinen arvo pidetään lukuna + yksikkönä, jotta UI muotoilee sen sovelluksen kielellä. */
public final class WarningDetails {

    public static final WarningDetails EMPTY = new WarningDetails(-1, Double.NaN, "", "");

    /** Toteutumis-todennäköisyys prosentteina, -1 jos ei tiedossa. */
    public final int probabilityPct;
    /** Konkreettinen arvo (esim. 27), NaN jos ei. */
    public final double physicalValue;
    /** FMI:n yksikkökoodi (celsius, mm/h, m/s, index), "" jos ei. */
    public final String physicalUnit;
    /** FMI:n kuvausteksti valitulla kielellä (entiteetit puretut). "" jos ei käytössä. */
    public final String detailText;

    public WarningDetails(int probabilityPct, double physicalValue, String physicalUnit, String detailText) {
        this.probabilityPct = probabilityPct;
        this.physicalValue = physicalValue;
        this.physicalUnit = physicalUnit == null ? "" : physicalUnit;
        this.detailText = detailText == null ? "" : detailText;
    }

    public boolean hasPhysical() {
        return !Double.isNaN(physicalValue) && !physicalUnit.isEmpty();
    }

    public boolean hasAny() {
        return probabilityPct >= 0 || hasPhysical() || !detailText.isEmpty();
    }
}
