package org.jrs82.fsclock.db;

import android.content.Context;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HistoryRepository {

    private static final ZoneId ZONE = ZoneId.of("Europe/Helsinki");
    private static final int DEFAULT_RETENTION_DAYS = 1095;
    private static final long PARTIAL_THRESHOLD_MS = 30L * 60_000L;

    private final FsClockDb db;
    private final ExecutorService io;

    private static volatile HistoryRepository instance;

    public static HistoryRepository get(Context context) {
        if (instance == null) {
            synchronized (HistoryRepository.class) {
                if (instance == null) {
                    instance = new HistoryRepository(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private HistoryRepository(Context appContext) {
        this.db = FsClockDb.get(appContext);
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fsclock-db");
            t.setDaemon(true);
            return t;
        });
    }

    public ExecutorService io() {
        return io;
    }

    public void saveSample(WeatherSample sample) {
        db.weatherDao().insert(sample);
    }

    public List<DailyStat> getMonth(String channel, int year, int month) {
        LocalDate first = LocalDate.of(year, month, 1);
        LocalDate last = first.with(TemporalAdjusters.lastDayOfMonth());
        return db.dailyStatDao().getRange(channel, first.toString(), last.toString());
    }

    public List<WeatherSample> getSamplesBetween(String channel, long startMs, long endMs) {
        return db.weatherDao().getSamplesBetween(channel, startMs, endMs);
    }

    public void recomputeDailyStats(String channel, LocalDate date) {
        long start = date.atStartOfDay(ZONE).toInstant().toEpochMilli();
        long end = date.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        List<WeatherSample> samples = db.weatherDao().getSamplesBetween(channel, start, end);
        if (samples.isEmpty()) return;

        double minTemp = Double.POSITIVE_INFINITY;
        double maxTemp = Double.NEGATIVE_INFINITY;
        long minTempAt = 0;
        long maxTempAt = 0;
        double sumTemp = 0.0;
        Double totalPrecip = null;
        Double maxWindGust = null;

        for (WeatherSample s : samples) {
            if (s.temperature < minTemp) {
                minTemp = s.temperature;
                minTempAt = s.timestamp;
            }
            if (s.temperature > maxTemp) {
                maxTemp = s.temperature;
                maxTempAt = s.timestamp;
            }
            sumTemp += s.temperature;

            if (s.precipitation1h != null) {
                totalPrecip = (totalPrecip == null) ? s.precipitation1h : totalPrecip + s.precipitation1h;
            }
            if (s.windGust != null) {
                maxWindGust = (maxWindGust == null) ? s.windGust : Math.max(maxWindGust, s.windGust);
            }
        }

        DailyStat stat = new DailyStat();
        stat.channel = channel;
        stat.date = date.toString();
        stat.minTemp = minTemp;
        stat.minTempAt = minTempAt;
        stat.maxTemp = maxTemp;
        stat.maxTempAt = maxTempAt;
        stat.avgTemp = sumTemp / samples.size();
        stat.totalPrecip = totalPrecip;
        stat.maxWindGust = maxWindGust;
        stat.sampleCount = samples.size();

        // isPartial=true jos päivän alku- TAI loppupäästä puuttuu yli 30 min dataa.
        // Kuluvalle päivälle "end" on huomisen 00:00 → loppupää aina iso → isPartial=true.
        long firstSampleAt = samples.get(0).timestamp;
        long lastSampleAt = samples.get(samples.size() - 1).timestamp;
        boolean firstLate = (firstSampleAt - start) > PARTIAL_THRESHOLD_MS;
        boolean lastEarly = (end - lastSampleAt) > PARTIAL_THRESHOLD_MS;
        stat.isPartial = firstLate || lastEarly;

        db.dailyStatDao().upsert(stat);
    }

    public int trimOldSamples() {
        return trimOldSamples(DEFAULT_RETENTION_DAYS);
    }

    public int trimOldSamples(int retentionDays) {
        long cutoffMs = Instant.now().minus(retentionDays, ChronoUnit.DAYS).toEpochMilli();
        int samples = db.weatherDao().deleteOlderThan(cutoffMs);
        String cutoffDate = LocalDate.now(ZONE).minusDays(retentionDays).toString();
        int stats = db.dailyStatDao().deleteOlderThan(cutoffDate);
        return samples + stats;
    }

    public DailyStat allTimeMax(String channel) {
        return db.dailyStatDao().getAllTimeMax(channel);
    }

    public DailyStat allTimeMin(String channel) {
        return db.dailyStatDao().getAllTimeMin(channel);
    }

    public long sampleCount() {
        return db.weatherDao().count();
    }

    public void clearAll() {
        db.weatherDao().clear();
        db.dailyStatDao().clear();
    }
}
