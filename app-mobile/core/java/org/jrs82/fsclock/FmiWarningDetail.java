package org.jrs82.fsclock;

/** Yksi FMI GeoServer -varoitusfeature (per maakunta + aikaviipale), pelkistettynä. Muuttumaton. */
public class FmiWarningDetail {
    public final String context;
    public final long fromMs;
    public final long untilMs;
    public final int probabilityPct;   // -1 jos ei
    public final double physicalValue;  // NaN jos ei
    public final String physicalUnit;   // FMI:n yksikkökoodi, "" jos ei
    public final String detailText;     // valitun kielen info_xx puretuilla entiteeteillä

    public FmiWarningDetail(String context, long fromMs, long untilMs, int probabilityPct,
                            double physicalValue, String physicalUnit, String detailText) {
        this.context = context == null ? "" : context;
        this.fromMs = fromMs;
        this.untilMs = untilMs;
        this.probabilityPct = probabilityPct;
        this.physicalValue = physicalValue;
        this.physicalUnit = physicalUnit == null ? "" : physicalUnit;
        this.detailText = detailText == null ? "" : detailText;
    }
}
