package org.jrs82.fsclock.mobile;

import org.jrs82.fsclock.FinnishHolidays;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

final class MobileHolidayProvider {

    private static final String URL_TEMPLATE =
            "https://vapaapaivat.com/api/v1/fi/holidays/%d.json";
    private static final int TIMEOUT_MS = 10000;
    private static final long CACHE_TTL_MS = 12L * 60L * 60_000L;
    private static final TimeZone HELSINKI = TimeZone.getTimeZone("Europe/Helsinki");
    private static final Locale FI = new Locale("fi", "FI");

    private static List<HolidayEvent> cached;
    private static int cachedYear;
    private static long cachedAt;

    private MobileHolidayProvider() {}

    static synchronized HolidayEvent next(Calendar from) {
        int year = from.get(Calendar.YEAR);
        long now = System.currentTimeMillis();
        if (cached == null || cachedYear != year || now - cachedAt > CACHE_TTL_MS) {
            try {
                cached = fetchYear(year);
                cachedYear = year;
                cachedAt = now;
            } catch (Exception ignored) {
                cached = fallbackYear(year);
                cachedYear = year;
                cachedAt = now;
            }
        }
        HolidayEvent found = firstOnOrAfter(cached, from);
        if (found != null) return found;
        try {
            List<HolidayEvent> nextYear = fetchYear(year + 1);
            return firstOnOrAfter(nextYear, from);
        } catch (Exception ignored) {
            return firstOnOrAfter(fallbackYear(year + 1), from);
        }
    }

    private static List<HolidayEvent> fetchYear(int year) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(String.format(Locale.US, URL_TEMPLATE, year))
                    .openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Arkikeskus Android");
            int code = conn.getResponseCode();
            if (code != 200) {
                InputStream err = conn.getErrorStream();
                if (err != null) {
                    try (InputStream toClose = err) {
                        byte[] buf = new byte[1024];
                        while (toClose.read(buf) > 0) { /* discard */ }
                    } catch (Exception ignored) { }
                }
                throw new Exception("Vapaapaivat HTTP " + code);
            }
            in = conn.getInputStream();
            JSONObject root = new JSONObject(readFully(in));
            JSONArray holidays = root.getJSONArray("holidays");
            List<HolidayEvent> out = new ArrayList<>();
            for (int i = 0; i < holidays.length(); i++) {
                JSONObject item = holidays.getJSONObject(i);
                String date = item.optString("date", "");
                JSONObject names = item.optJSONObject("name");
                String name = names == null ? "" : names.optString("fi", "");
                if (date.length() == 10 && !name.isEmpty()) {
                    out.add(new HolidayEvent(name, date, item.optBoolean("official", false),
                            item.optBoolean("weekend", false)));
                }
            }
            return out;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) { }
            }
            if (conn != null) conn.disconnect();
        }
    }

    private static String readFully(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }

    private static List<HolidayEvent> fallbackYear(int year) {
        Calendar from = Calendar.getInstance(HELSINKI, FI);
        from.clear();
        from.set(year, Calendar.JANUARY, 1);
        List<FinnishHolidays.Holiday> holidays = FinnishHolidays.upcoming(from, 40);
        List<HolidayEvent> out = new ArrayList<>();
        for (FinnishHolidays.Holiday h : holidays) {
            if (h.year != year || h.type != FinnishHolidays.EventType.HOLIDAY) continue;
            out.add(new HolidayEvent(h.name,
                    String.format(Locale.US, "%04d-%02d-%02d", h.year, h.month, h.day),
                    true, false));
        }
        return out;
    }

    private static HolidayEvent firstOnOrAfter(List<HolidayEvent> events, Calendar from) {
        if (events == null || events.isEmpty()) return null;
        int fromKey = from.get(Calendar.YEAR) * 10000
                + (from.get(Calendar.MONTH) + 1) * 100
                + from.get(Calendar.DAY_OF_MONTH);
        HolidayEvent best = null;
        for (HolidayEvent event : events) {
            if (event.sortKey() < fromKey) continue;
            if (best == null || event.sortKey() < best.sortKey()) best = event;
        }
        return best;
    }

    static final class HolidayEvent {
        final String name;
        final String date;
        final boolean official;
        final boolean weekend;

        HolidayEvent(String name, String date, boolean official, boolean weekend) {
            this.name = name;
            this.date = date;
            this.official = official;
            this.weekend = weekend;
        }

        int sortKey() {
            try {
                int y = Integer.parseInt(date.substring(0, 4));
                int m = Integer.parseInt(date.substring(5, 7));
                int d = Integer.parseInt(date.substring(8, 10));
                return y * 10000 + m * 100 + d;
            } catch (Exception e) {
                return Integer.MAX_VALUE;
            }
        }
    }
}
