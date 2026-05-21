package org.jrs82.fsclock.db;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public final class CsvExporter {

    public enum Kind {
        /** Kaikki kanavat ja kaikki kentät raaka-muodossa, ISO-ms aikaleima.
         *  Ei kommenttirivejä, jotta CSV-parserit toimivat suoraan. */
        RAW_ALL,
        /** Ihmisluettava sää-CSV: vain fmi_*-kanavat, paikallinen aika, kommenttirivit. */
        WEATHER_HUMAN,
        /** Ihmisluettava akku-CSV: vain battery-kanava, paikallinen aika, kommenttirivit. */
        BATTERY_HUMAN
    }

    public static final class Result {
        public final boolean ok;
        public final int rowCount;
        public final String fileName;
        public final Uri uri;
        public final String error;

        private Result(boolean ok, int rowCount, String fileName, Uri uri, String error) {
            this.ok = ok;
            this.rowCount = rowCount;
            this.fileName = fileName;
            this.uri = uri;
            this.error = error;
        }

        static Result success(int rowCount, String fileName, Uri uri) {
            return new Result(true, rowCount, fileName, uri, null);
        }

        static Result empty(String fileName) {
            return new Result(false, 0, fileName, null, null);
        }

        static Result failure(String fileName, String error) {
            return new Result(false, 0, fileName, null, error);
        }
    }

    private static final ZoneId ZONE = ZoneId.of("Europe/Helsinki");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter HUMAN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final String RAW_HEADER =
            "timestamp_iso,timestamp_ms,channel,temperature,humidity,pressure," +
            "windSpeed,windGust,windDirection,precipitation1h,cloudCover,radiationGlobal," +
            "weatherSymbol,observedWawa,batteryLevel";

    private static final String WEATHER_HEADER =
            "Aika,Kanava,Lämpö °C,Kosteus %,Tuuli m/s,Puuska m/s," +
            "Sade 1h mm,Pilvisyys %,Wawa";

    private static final String BATTERY_HEADER =
            "Aika,Kanava,Akku %,Akun lämpö °C";

    private static final String RELATIVE_SUBDIR =
            Environment.DIRECTORY_DOWNLOADS + "/FsClock";

    private CsvExporter() {}

    /** Rakenna tiedostonimi muodossa fsclock_<kind>_<yyyyMMdd_HHmmss>_<MODEL>.csv. */
    public static String buildFileName(Kind kind) {
        String prefix;
        switch (kind) {
            case WEATHER_HUMAN: prefix = "fsclock_weather_"; break;
            case BATTERY_HUMAN: prefix = "fsclock_battery_"; break;
            case RAW_ALL: default: prefix = "fsclock_all_"; break;
        }
        return prefix + LocalDateTime.now(ZONE).format(STAMP)
                + "_" + safeModel() + ".csv";
    }

    /** API-sopimus: ei koskaan heitä Exception-aliluokkaa. Error-tason virheet
     *  (OutOfMemoryError, StackOverflowError) jätetään tarkoituksella läpinäkyviksi. */
    public static Result export(Context context, Kind kind, String fileName) {
        Uri uri = null;
        try {
            FsClockDb db = FsClockDb.get(context);
            List<WeatherSample> rows;
            switch (kind) {
                case RAW_ALL:
                    rows = db.weatherDao().getAll();
                    break;
                case WEATHER_HUMAN:
                    rows = db.weatherDao().getWeatherChannels();
                    break;
                case BATTERY_HUMAN:
                    rows = db.weatherDao().getByChannel("battery");
                    break;
                default:
                    return Result.failure(fileName, "tuntematon Kind: " + kind);
            }

            if (rows.isEmpty()) return Result.empty(fileName);

            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
            values.put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_SUBDIR);
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            uri = context.getContentResolver().insert(collection, values);
            if (uri == null) {
                return Result.failure(fileName, "MediaStore insert palautti null");
            }

            try (OutputStream os = context.getContentResolver().openOutputStream(uri);
                 Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
                switch (kind) {
                    case RAW_ALL:
                        w.write(RAW_HEADER);
                        w.write('\n');
                        for (WeatherSample s : rows) appendRawRow(w, s);
                        break;
                    case WEATHER_HUMAN:
                        writeHumanHeader(w, kind);
                        w.write(WEATHER_HEADER);
                        w.write('\n');
                        for (WeatherSample s : rows) appendWeatherRow(w, s);
                        break;
                    case BATTERY_HUMAN:
                        writeHumanHeader(w, kind);
                        w.write(BATTERY_HEADER);
                        w.write('\n');
                        for (WeatherSample s : rows) appendBatteryRow(w, s);
                        break;
                }
            }

            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);

            return Result.success(rows.size(), fileName, uri);
        } catch (Exception e) {
            if (uri != null) {
                try { context.getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
            }
            return Result.failure(fileName, String.valueOf(e.getMessage()));
        }
    }

    private static void writeHumanHeader(Writer w, Kind kind) throws java.io.IOException {
        w.write("# device: ");
        w.write(Build.MODEL == null ? "?" : Build.MODEL);
        w.write('\n');
        w.write("# exported: ");
        w.write(LocalDateTime.now(ZONE).format(HUMAN));
        w.write('\n');
        if (kind == Kind.WEATHER_HUMAN) {
            w.write("# Wawa = WMO 0-99 -koodi havainnoista (tyhjä jos ei saatavilla)\n");
        }
        w.write("# aika = paikallinen aika Europe/Helsinki\n");
    }

    private static void appendRawRow(Writer w, WeatherSample s) throws java.io.IOException {
        w.write(LocalDateTime.ofInstant(Instant.ofEpochMilli(s.timestamp), ZONE).format(ISO));
        w.write(',');
        w.write(Long.toString(s.timestamp));
        w.write(',');
        w.write(escape(s.channel));
        w.write(',');
        w.write(num(s.temperature));
        w.write(',');
        w.write(num(s.humidity));
        w.write(',');
        w.write(num(s.pressure));
        w.write(',');
        w.write(num(s.windSpeed));
        w.write(',');
        w.write(num(s.windGust));
        w.write(',');
        w.write(num(s.windDirection));
        w.write(',');
        w.write(num(s.precipitation1h));
        w.write(',');
        w.write(num(s.cloudCover));
        w.write(',');
        w.write(num(s.radiationGlobal));
        w.write(',');
        w.write(s.weatherSymbol == null ? "" : s.weatherSymbol.toString());
        w.write(',');
        w.write(s.observedWawa == null ? "" : s.observedWawa.toString());
        w.write(',');
        w.write(s.batteryLevel == null ? "" : s.batteryLevel.toString());
        w.write('\n');
    }

    private static void appendWeatherRow(Writer w, WeatherSample s) throws java.io.IOException {
        w.write(LocalDateTime.ofInstant(Instant.ofEpochMilli(s.timestamp), ZONE).format(HUMAN));
        w.write(',');
        w.write(escape(s.channel));
        w.write(',');
        w.write(num1(s.temperature));
        w.write(',');
        w.write(num0(s.humidity));
        w.write(',');
        w.write(num1(s.windSpeed));
        w.write(',');
        w.write(num1(s.windGust));
        w.write(',');
        w.write(num1(s.precipitation1h));
        w.write(',');
        w.write(num0(s.cloudCover));
        w.write(',');
        w.write(s.observedWawa == null ? "" : s.observedWawa.toString());
        w.write('\n');
    }

    private static void appendBatteryRow(Writer w, WeatherSample s) throws java.io.IOException {
        w.write(LocalDateTime.ofInstant(Instant.ofEpochMilli(s.timestamp), ZONE).format(HUMAN));
        w.write(',');
        w.write(escape(s.channel));
        w.write(',');
        w.write(s.batteryLevel == null ? "" : s.batteryLevel.toString());
        w.write(',');
        w.write(num1(s.temperature));
        w.write('\n');
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static String num(Double v) {
        return v == null ? "" : String.format(Locale.ROOT, "%.3f", v);
    }

    private static String num0(Double v) {
        return v == null ? "" : String.format(Locale.ROOT, "%.0f", v);
    }

    private static String num1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String num1(Double v) {
        return v == null ? "" : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String escape(String s) {
        if (s == null) return "";
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String safeModel() {
        String m = Build.MODEL;
        if (m == null || m.isEmpty()) return "unknown";
        StringBuilder sb = new StringBuilder(m.length());
        for (int i = 0; i < m.length(); i++) {
            char c = m.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '+') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('-');
            }
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }
}
