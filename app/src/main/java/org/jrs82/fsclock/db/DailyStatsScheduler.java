package org.jrs82.fsclock.db;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.jrs82.fsclock.SettingsManager;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

public class DailyStatsScheduler {

    private static final String TAG = "DailyStatsScheduler";
    private static final ZoneId ZONE = ZoneId.of("Europe/Helsinki");
    private static final LocalTime FINALIZE_TIME = LocalTime.of(3, 0);

    private final HistoryRepository repo;
    private final String channel;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;

    private final Runnable hourlyTick = new Runnable() {
        @Override public void run() {
            LocalDate today = LocalDate.now(ZONE);
            repo.io().execute(() -> {
                try {
                    repo.recomputeDailyStats(channel, today);
                } catch (Exception e) {
                    Log.w(TAG, "hourly recompute failed", e);
                }
            });
            handler.postDelayed(this, msUntilNextHour());
        }
    };

    private final Runnable finalizeTick = new Runnable() {
        @Override public void run() {
            LocalDate yesterday = LocalDate.now(ZONE).minusDays(1);
            repo.io().execute(() -> {
                try {
                    repo.recomputeDailyStats(channel, yesterday);
                } catch (Exception e) {
                    Log.w(TAG, "finalize recompute failed", e);
                }
                try {
                    int retention = SettingsManager.get().getRetentionDays();
                    int removed = repo.trimOldSamples(retention);
                    if (removed > 0) {
                        Log.i(TAG, "trim removed " + removed + " rows (retention=" + retention + "d)");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "trim failed", e);
                }
            });
            handler.postDelayed(this, msUntilNextFinalize());
        }
    };

    public DailyStatsScheduler(Context context, String channel) {
        this.repo = HistoryRepository.get(context);
        this.channel = channel;
    }

    public void start() {
        if (running) return;
        running = true;
        // Lasketaan today + yesterday heti, ettei history näytä tyhjältä
        // uudelleenkäynnistyksen jälkeen ennen seuraavaa tasatuntia.
        repo.io().execute(() -> {
            LocalDate today = LocalDate.now(ZONE);
            try { repo.recomputeDailyStats(channel, today); }
            catch (Exception e) { Log.w(TAG, "initial recompute (today) failed", e); }
            try { repo.recomputeDailyStats(channel, today.minusDays(1)); }
            catch (Exception e) { Log.w(TAG, "initial recompute (yesterday) failed", e); }
        });
        handler.postDelayed(hourlyTick, msUntilNextHour());
        handler.postDelayed(finalizeTick, msUntilNextFinalize());
    }

    public void stop() {
        if (!running) return;
        handler.removeCallbacks(hourlyTick);
        handler.removeCallbacks(finalizeTick);
        running = false;
    }

    private static long msUntilNextHour() {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        ZonedDateTime next = now.truncatedTo(ChronoUnit.HOURS).plusHours(1);
        return Math.max(1_000L, Duration.between(now, next).toMillis());
    }

    private static long msUntilNextFinalize() {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        ZonedDateTime todayFinalize = now.toLocalDate().atTime(FINALIZE_TIME).atZone(ZONE);
        ZonedDateTime next = now.isBefore(todayFinalize) ? todayFinalize : todayFinalize.plusDays(1);
        return Math.max(1_000L, Duration.between(now, next).toMillis());
    }
}
