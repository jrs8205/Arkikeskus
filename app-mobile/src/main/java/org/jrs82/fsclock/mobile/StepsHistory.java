package org.jrs82.fsclock.mobile;

import android.content.Context;

import org.jrs82.fsclock.db.DailyStepsEntity;
import org.jrs82.fsclock.db.FsClockDb;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Rakentaa askelhistorian (päivät/viikot/kuukaudet) Room-päiväsummista raw-lähteelle.
 *  dateKey on yyyymmdd ja monotonisesti kasvava, joten BETWEEN-haku toimii. */
final class StepsHistory {

    private static final Locale FI = new Locale("fi");
    private static final String[] MONTHS_FI = {
            "Tammikuu", "Helmikuu", "Maaliskuu", "Huhtikuu", "Toukokuu", "Kesäkuu",
            "Heinäkuu", "Elokuu", "Syyskuu", "Lokakuu", "Marraskuu", "Joulukuu"};

    private StepsHistory() {}

    /** Suomenkielinen kuukauden nimi (1 = tammikuu). Jaettu HC-historian otsikoinnin kanssa. */
    static String monthNameFi(int monthValue) {
        if (monthValue < 1 || monthValue > 12) return "?";
        return MONTHS_FI[monthValue - 1];
    }

    /** [heightCm]/[weightKg]/[stepCm] = profiili kaloriarviolle; jos pituus/paino puuttuu (≤0),
     *  kalorit jätetään pois (vain askeleet). Kalorit lasketaan tallennetuista askelista lukuhetkellä
     *  ([StepCalorieEstimator.activeKcal]) — sama malli kuin HC-historiassa ja HTML-viennissä. */
    static CharSequence build(Context ctx, int tab, double heightCm, double weightKg, double stepCm) {
        FsClockDb db = FsClockDb.get(ctx);
        Calendar now = Calendar.getInstance();
        StringBuilder sb = new StringBuilder();
        if (tab == 1) {
            buildDays(db, now, sb, heightCm, weightKg, stepCm);
        } else if (tab == 2) {
            buildWeeks(db, now, sb, heightCm, weightKg, stepCm);
        } else {
            buildMonths(db, now, sb, heightCm, weightKg, stepCm);
        }
        String s = sb.toString().trim();
        if (s.isEmpty()) return "Ei vielä kertynyttä askeldataa.";
        String note = "\n\nHuom: laskenta kerää askelia kun sovellus on ollut käytössä; "
                + "katkoja voi tulla laitteen uudelleenkäynnistyksessä.";
        if (heightCm > 0 && weightKg > 0) {
            note += " 🔥 = askelpohjainen aktiivinen kaloriarvio.";
        }
        return s + note;
    }

    /** Liittää aktiivisen kaloriarvion riviin (" · ~A kcal") kun profiili sallii ja askelia on. */
    private static void appendKcal(StringBuilder sb, int steps, double h, double w, double stepCm) {
        if (steps <= 0 || h <= 0 || w <= 0) return;
        int kcal = StepCalorieEstimator.activeKcal(steps, h, w, stepCm);
        if (kcal > 0) sb.append("   🔥 ").append(kcal).append(" kcal");
    }

    private static Map<Integer, Integer> rangeMap(FsClockDb db, int fromKey, int toKey) {
        Map<Integer, Integer> map = new HashMap<>();
        List<DailyStepsEntity> rows = db.dailyStepsDao().range(fromKey, toKey);
        if (rows != null) {
            for (DailyStepsEntity e : rows) map.put(e.dateKey, e.steps);
        }
        return map;
    }

    private static void buildDays(FsClockDb db, Calendar now, StringBuilder sb, double h, double wKg, double stepCm) {
        Calendar from = (Calendar) now.clone();
        from.add(Calendar.DAY_OF_MONTH, -13);
        Map<Integer, Integer> map = rangeMap(db, StepCounter.dateKey(from), StepCounter.dateKey(now));
        SimpleDateFormat fmt = new SimpleDateFormat("EEE d.M.", FI);
        Calendar c = (Calendar) now.clone();
        for (int i = 0; i < 14; i++) {
            Integer steps = map.get(StepCounter.dateKey(c));
            int st = steps != null ? steps : 0;
            sb.append(fmt.format(c.getTime())).append("   ").append(formatSteps(st));
            appendKcal(sb, st, h, wKg, stepCm);
            sb.append('\n');
            c.add(Calendar.DAY_OF_MONTH, -1);
        }
    }

    private static void buildWeeks(FsClockDb db, Calendar now, StringBuilder sb, double h, double wKg, double stepCm) {
        // Kohdista ikkunan alku 7 viikkoa taakse JA viikon alkuun (maanantai), jotta kaikki 8 viikkoa
        // ovat täysiä eikä reunapäiviä putoa eri (9.) viikolle, jonka numeroa ei ole listassa.
        Calendar from = (Calendar) now.clone();
        from.setFirstDayOfWeek(Calendar.MONDAY);
        from.add(Calendar.WEEK_OF_YEAR, -7);
        while (from.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            from.add(Calendar.DAY_OF_MONTH, -1);
        }
        Map<Integer, Integer> map = rangeMap(db, StepCounter.dateKey(from), StepCounter.dateKey(now));
        int[] weekNum = new int[8];
        int[] weekSum = new int[8];
        for (int w = 0; w < 8; w++) {
            Calendar wk = (Calendar) now.clone();
            wk.setFirstDayOfWeek(Calendar.MONDAY);
            wk.add(Calendar.WEEK_OF_YEAR, -w);
            weekNum[w] = wk.get(Calendar.WEEK_OF_YEAR);
        }
        Calendar d = (Calendar) from.clone();
        d.setFirstDayOfWeek(Calendar.MONDAY);
        int nowKey = StepCounter.dateKey(now);
        while (StepCounter.dateKey(d) <= nowKey) {
            Integer steps = map.get(StepCounter.dateKey(d));
            if (steps != null) {
                int wn = d.get(Calendar.WEEK_OF_YEAR);
                for (int w = 0; w < 8; w++) {
                    if (weekNum[w] == wn) { weekSum[w] += steps; break; }
                }
            }
            d.add(Calendar.DAY_OF_MONTH, 1);
        }
        for (int w = 0; w < 8; w++) {
            sb.append("Viikko ").append(weekNum[w]).append("   ").append(formatSteps(weekSum[w]));
            appendKcal(sb, weekSum[w], h, wKg, stepCm);
            sb.append('\n');
        }
    }

    private static void buildMonths(FsClockDb db, Calendar now, StringBuilder sb, double h, double wKg, double stepCm) {
        Calendar from = (Calendar) now.clone();
        from.add(Calendar.MONTH, -5);
        from.set(Calendar.DAY_OF_MONTH, 1);
        Map<Integer, Integer> map = rangeMap(db, StepCounter.dateKey(from), StepCounter.dateKey(now));
        int[] year = new int[6];
        int[] month = new int[6];
        int[] sums = new int[6];
        for (int m = 0; m < 6; m++) {
            Calendar mc = (Calendar) now.clone();
            mc.add(Calendar.MONTH, -m);
            year[m] = mc.get(Calendar.YEAR);
            month[m] = mc.get(Calendar.MONTH);
        }
        Calendar d = (Calendar) from.clone();
        int nowKey = StepCounter.dateKey(now);
        while (StepCounter.dateKey(d) <= nowKey) {
            Integer steps = map.get(StepCounter.dateKey(d));
            if (steps != null) {
                int dy = d.get(Calendar.YEAR);
                int dm = d.get(Calendar.MONTH);
                for (int m = 0; m < 6; m++) {
                    if (year[m] == dy && month[m] == dm) { sums[m] += steps; break; }
                }
            }
            d.add(Calendar.DAY_OF_MONTH, 1);
        }
        for (int m = 0; m < 6; m++) {
            sb.append(MONTHS_FI[month[m]]).append(' ').append(year[m]).append("   ").append(formatSteps(sums[m]));
            appendKcal(sb, sums[m], h, wKg, stepCm);
            sb.append('\n');
        }
    }

    private static String formatSteps(int steps) {
        String s = String.valueOf(steps);
        StringBuilder out = new StringBuilder();
        int cnt = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            out.insert(0, s.charAt(i));
            if (++cnt % 3 == 0 && i > 0) out.insert(0, ' ');
        }
        return out + " askelta";
    }
}
