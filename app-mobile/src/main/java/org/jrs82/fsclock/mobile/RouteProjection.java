package org.jrs82.fsclock.mobile;

import java.util.List;

/** Bussin GPS:n projisointi reitin muodolle (patternGeometry) → etäisyys reittiä pitkin ajoneuvosta
 *  käyttäjän nousupysäkille. Projisointi rajataan pysäkkijärjestyksellä (pysäkit monotonisesti) ja
 *  ajoneuvon tunnetulla pysäkkivälillä, jotta silmukat/päällekkäiset tieosuudet eivät hyppää. */
final class RouteProjection {

    private static final double EARTH_R = 6371000.0;
    private static final double MIN_STOP_PROGRESS_M = 0.5;

    private RouteProjection() {}

    /** Kumulatiivinen etäisyys (m) reitin muodon jokaiseen pisteeseen. */
    static double[] cumulative(List<double[]> shape) {
        int n = shape == null ? 0 : shape.size();
        double[] cum = new double[n];
        for (int i = 1; i < n; i++) {
            cum[i] = cum[i - 1] + meters(shape.get(i - 1), shape.get(i));
        }
        return cum;
    }

    /** Kunkin pysäkin etäisyys reittiä pitkin (m), monotonisesti (haku alkaa edellisen pysäkin pisteestä).
     *  Täyttää stopVertexOut[]:n pysäkin lähimmällä reitin pisteindeksillä (ajoneuvon ikkunan rajaukseen). */
    static double[] stopDistances(List<double[]> shape, double[] cum, List<TimelineStop> stops,
                                  int[] stopVertexOut) {
        if (stops == null) return new double[0];
        int sn = stops.size();
        double[] out = new double[sn];
        int vn = shape == null ? 0 : shape.size();
        int startVertex = 0;
        for (int s = 0; s < sn; s++) {
            TimelineStop st = stops.get(s);
            if (vn < 2 || cum == null || cum.length != vn || st == null
                    || !Double.isFinite(st.lat) || !Double.isFinite(st.lon)) {
                out[s] = s > 0 ? out[s - 1] : 0;
                if (stopVertexOut != null && s < stopVertexOut.length) stopVertexOut[s] = startVertex;
                continue;
            }
            double minAlong = s == 0 ? 0 : out[s - 1] + MIN_STOP_PROGRESS_M;
            double[] r = project(shape, cum, st.lat, st.lon, startVertex, vn - 1, minAlong);
            if (!Double.isFinite(r[0])) {
                out[s] = s > 0 ? out[s - 1] : 0;
            } else {
                out[s] = Math.max(s > 0 ? out[s - 1] : 0, r[0]);
                startVertex = Math.max(startVertex, (int) r[2]);
            }
            if (stopVertexOut != null && s < stopVertexOut.length) stopVertexOut[s] = startVertex;
        }
        return out;
    }

    /** Projisoi piste reitille pistevälillä [loVertex, hiVertex]. Palauttaa {distAlong(m), perp(m), vertexIndex}. */
    static double[] project(List<double[]> shape, double[] cum, double lat, double lon,
                            int loVertex, int hiVertex) {
        return project(shape, cum, lat, lon, loVertex, hiVertex, Double.NEGATIVE_INFINITY);
    }

    /** Kuten project, mutta hylkää reittipisteet ennen minAlong-etäisyyttä. */
    static double[] project(List<double[]> shape, double[] cum, double lat, double lon,
                            int loVertex, int hiVertex, double minAlong) {
        if (shape == null || cum == null || shape.size() < 2 || cum.length != shape.size()
                || !Double.isFinite(lat) || !Double.isFinite(lon)) {
            return new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1};
        }
        int n = shape.size();
        int lo = Math.max(0, loVertex);
        int hi = Math.min(n - 1, hiVertex);
        if (hi <= lo) return new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1};
        double bestPerp = Double.POSITIVE_INFINITY, bestAlong = Double.NaN;
        int bestVertex = -1;
        for (int i = lo; i < hi; i++) {
            double[] a = shape.get(i), b = shape.get(i + 1);
            if (a == null || b == null || a.length < 2 || b.length < 2) continue;
            double mLat = Math.toRadians((a[0] + b[0]) / 2.0);
            double bx = Math.toRadians(b[1] - a[1]) * Math.cos(mLat) * EARTH_R;
            double by = Math.toRadians(b[0] - a[0]) * EARTH_R;
            double px = Math.toRadians(lon - a[1]) * Math.cos(mLat) * EARTH_R;
            double py = Math.toRadians(lat - a[0]) * EARTH_R;
            double seg2 = bx * bx + by * by;
            double t = seg2 <= 0 ? 0 : (px * bx + py * by) / seg2;
            if (t < 0) t = 0;
            if (t > 1) t = 1;
            double segLen = Math.sqrt(seg2);
            if (Double.isFinite(minAlong)) {
                if (cum[i] + segLen < minAlong) continue;
                if (segLen > 0) t = Math.max(t, Math.max(0, (minAlong - cum[i]) / segLen));
                if (t > 1) continue;
            }
            double dx = px - t * bx, dy = py - t * by;
            double perp = Math.sqrt(dx * dx + dy * dy);
            if (perp < bestPerp) {
                bestPerp = perp;
                bestAlong = cum[i] + t * segLen;
                bestVertex = i;
            }
        }
        return new double[]{bestAlong, bestPerp, bestVertex};
    }

    /** Kahden [lat,lon]-pisteen etäisyys metreinä (equirectangular-approksimaatio, riittää lyhyille väleille). */
    static double meters(double[] a, double[] b) {
        double mLat = Math.toRadians((a[0] + b[0]) / 2.0);
        double x = Math.toRadians(b[1] - a[1]) * Math.cos(mLat);
        double y = Math.toRadians(b[0] - a[0]);
        return Math.sqrt(x * x + y * y) * EARTH_R;
    }
}
