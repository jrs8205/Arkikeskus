package org.jrs82.fsclock;

/** Yksi FMI GeoServer -varoitusfeature (per maakunta + aikaviipale), pelkistettynä. Muuttumaton. */
public class FmiWarningDetail {
    public final String context;
    public final long fromMs;
    public final long untilMs;
    public final int probabilityPct;   // -1 jos ei
    public final double physicalValue;  // NaN jos ei
    public final String physicalText;   // valmiiksi muotoiltu, "" jos ei
    public final String detailText;     // info_fi puretuilla entiteeteillä

    public FmiWarningDetail(String context, long fromMs, long untilMs, int probabilityPct,
                            double physicalValue, String physicalText, String detailText) {
        this.context = context == null ? "" : context;
        this.fromMs = fromMs;
        this.untilMs = untilMs;
        this.probabilityPct = probabilityPct;
        this.physicalValue = physicalValue;
        this.physicalText = physicalText == null ? "" : physicalText;
        this.detailText = detailText == null ? "" : detailText;
    }
}
