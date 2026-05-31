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

    static CharSequence build(Context ctx, int tab) {
        FsClockDb db = FsClockDb.get(ctx);
        Calendar now = Calendar.getInstance();
        StringBuilder sb = new StringBuilder();
        if (tab == 1) {
            buildDays(db, now, sb);
        } else if (tab == 2) {
            buildWeeks(db, now, sb);
        } else {
            buildMonths(db, now, sb);
        }
        String s = sb.toString().trim();
        if (s.isEmpty()) return "Ei vielä kertynyttä askeldataa.";
        return s + "\n\nHuom: laskenta kerää askelia kun sovellus on ollut käytössä; "
                + "katkoja voi tulla laitteen uudelleenkäynnistyksessä.";
    }

    private static Map<Integer, Integer> rangeMap(FsClockDb db, int fromKey, int toKey) {
        Map<Integer, Integer> map = new HashMap<>();
        List<DailyStepsEntity> rows = db.dailyStepsDao().range(fromKey, toKey);
        if (rows != null) {
            for (DailyStepsEntity e : rows) map.put(e.dateKey, e.steps);
        }
        return map;
    }

    private static void buildDays(FsClockDb db, Calendar now, StringBuilder sb) {
        Calendar from = (Calendar) now.clone();
        from.add(Calendar.DAY_OF_MONTH, -13);
        Map<Integer, Integer> map = rangeMap(db, StepCounter.dateKey(from), StepCounter.dateKey(now));
        SimpleDateFormat fmt = new SimpleDateFormat("EEE d.M.", FI);
        Calendar c = (Calendar) now.clone();
        for (int i = 0; i < 14; i++) {
            Integer steps = map.get(StepCounter.dateKey(c));
            sb.append(fmt.format(c.getTime())).append("   ")
                    .append(formatSteps(steps != null ? steps : 0)).append('\n');
            c.add(Calendar.DAY_OF_MONTH, -1);
        }
    }

    private static void buildWeeks(FsClockDb db, Calendar now, StringBuilder sb) {
        Calendar from = (Calendar) now.clone();
        from.add(Calendar.DAY_OF_MONTH, -55);
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
        for (int i = 0; i <= 55; i++) {
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
            sb.append("Viikko ").append(weekNum[w]).append("   ")
                    .append(formatSteps(weekSum[w])).append('\n');
        }
    }

    private static void buildMonths(FsClockDb db, Calendar now, StringBuilder sb) {
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
            sb.append(MONTHS_FI[month[m]]).append(' ').append(year[m]).append("   ")
                    .append(formatSteps(sums[m])).append('\n');
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
