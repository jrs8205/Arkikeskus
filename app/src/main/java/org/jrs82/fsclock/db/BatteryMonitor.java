package org.jrs82.fsclock.db;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.time.LocalDate;
import java.time.ZoneId;

public class BatteryMonitor {

    private static final String TAG = "BatteryMonitor";
    private static final String CHANNEL = "battery";
    private static final long MIN_INTERVAL_MS = 60_000L;
    /** Aikapohjaisen pollauksen jakso: kerran 10 min, offset xx:01/11/21/31/41/51. */
    private static final long POLL_PERIOD_MS = 10L * 60_000L;
    private static final long POLL_OFFSET_MS = 60_000L;
    private static final ZoneId ZONE = ZoneId.of("Europe/Helsinki");

    private final Context context;
    private final HistoryRepository repo;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean registered = false;
    private long lastSavedAt = 0L;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            recordIfFresh(intent, "broadcast");
        }
    };

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            // Lue sticky-broadcast. Tämä toimii vaikka järjestelmä ei lähettäisi
            // ACTION_BATTERY_CHANGED-tapahtumaa pitkään aikaan (esim. A9+:n
            // Samsung maximum -tila pitää akun 80 %:ssa eikä lähetä päivityksiä).
            Intent sticky = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky != null) recordIfFresh(sticky, "poll");
            scheduleNextPoll();
        }
    };

    public BatteryMonitor(Context context) {
        this.context = context.getApplicationContext();
        this.repo = HistoryRepository.get(this.context);
    }

    public void start() {
        if (registered) return;
        context.registerReceiver(receiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        registered = true;
        scheduleNextPoll();
    }

    public void stop() {
        if (!registered) return;
        handler.removeCallbacks(pollTask);
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "unregister failed: " + e.getMessage());
        }
        registered = false;
    }

    private void scheduleNextPoll() {
        handler.removeCallbacks(pollTask);
        long now = System.currentTimeMillis();
        long base = (now / POLL_PERIOD_MS) * POLL_PERIOD_MS;
        long next = base + POLL_OFFSET_MS;
        if (next <= now) next += POLL_PERIOD_MS;
        handler.postDelayed(pollTask, next - now);
    }

    private void recordIfFresh(Intent intent, String source) {
        long now = System.currentTimeMillis();
        if (now - lastSavedAt < MIN_INTERVAL_MS) return;

        int tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        if (tempTenths < 0) return;

        lastSavedAt = now;

        WeatherSample sample = new WeatherSample();
        sample.channel = CHANNEL;
        sample.timestamp = now;
        sample.temperature = tempTenths / 10.0;
        sample.batteryLevel = (level >= 0) ? level : null;

        LocalDate today = LocalDate.now(ZONE);
        repo.io().execute(() -> {
            try {
                repo.saveSample(sample);
                repo.recomputeDailyStats(CHANNEL, today);
            } catch (Exception e) {
                Log.w(TAG, "saveSample failed (" + source + ")", e);
            }
        });
    }
}
