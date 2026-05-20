package org.jrs82.fsclock.db;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
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
    private static final String HEADER = "timestamp_iso,timestamp_ms,channel,temperature,humidity,pressure," +
            "windSpeed,windGust,windDirection,precipitation1h,cloudCover,radiationGlobal," +
            "weatherSymbol,batteryLevel";

    private CsvExporter() {}

    /** channelFilter == null → kaikki kanavat. API-sopimus: ei koskaan heitä. */
    public static Result export(Context context, String channelFilter, String fileName) {
        Uri uri = null;
        try {
            FsClockDb db = FsClockDb.get(context);
            List<WeatherSample> rows = (channelFilter == null)
                    ? db.weatherDao().getAll()
                    : db.weatherDao().getByChannel(channelFilter);

            if (rows.isEmpty()) return Result.empty(fileName);

            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            uri = context.getContentResolver().insert(collection, values);
            if (uri == null) {
                return Result.failure(fileName, "MediaStore insert palautti null");
            }

            try (OutputStream os = context.getContentResolver().openOutputStream(uri);
                 Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
                w.write(HEADER);
                w.write('\n');
                for (WeatherSample s : rows) {
                    appendRow(w, s);
                }
            }

            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);

            return Result.success(rows.size(), fileName, uri);
        } catch (Throwable t) {
            if (uri != null) {
                try { context.getContentResolver().delete(uri, null, null); } catch (Throwable ignored) {}
            }
            return Result.failure(fileName, String.valueOf(t.getMessage()));
        }
    }

    private static void appendRow(Writer w, WeatherSample s) throws java.io.IOException {
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
        w.write(s.batteryLevel == null ? "" : s.batteryLevel.toString());
        w.write('\n');
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static String num(Double v) {
        return v == null ? "" : String.format(Locale.ROOT, "%.3f", v);
    }

    private static String escape(String s) {
        if (s == null) return "";
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
