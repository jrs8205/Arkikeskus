package org.jrs82.fsclock.system;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.jrs82.fsclock.R;
import org.jrs82.fsclock.db.CsvExporter;
import org.jrs82.fsclock.db.DailyStat;
import org.jrs82.fsclock.db.FsClockDb;
import org.jrs82.fsclock.db.HistoryRepository;
import org.jrs82.fsclock.db.WeatherSample;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public class SystemActivity extends AppCompatActivity {

    private static final ZoneId ZONE = ZoneId.of("Europe/Helsinki");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final Locale FI = new Locale("fi", "FI");

    private HistoryRepository repo;
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView batteryCurrent;
    private TextView todayMinMax;
    private TextView dbCount;
    private TextView chartEmpty;
    private BatteryChartView chart;
    private Button exportButton;

    private final BroadcastReceiver liveBattery = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            int tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            if (tempTenths < 0 || level < 0) {
                batteryCurrent.setText(R.string.system_battery_current_unknown);
            } else {
                batteryCurrent.setText(getString(R.string.system_battery_current,
                        level, tempTenths / 10.0));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.SettingsTheme);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_system);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.system_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        repo = HistoryRepository.get(this);

        batteryCurrent = findViewById(R.id.battery_current);
        todayMinMax = findViewById(R.id.today_minmax);
        dbCount = findViewById(R.id.db_count);
        chartEmpty = findViewById(R.id.chart_empty);
        chart = findViewById(R.id.battery_chart);
        exportButton = findViewById(R.id.export_csv);

        exportButton.setOnClickListener(v -> exportCsv());
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerReceiver(liveBattery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        loadData();
    }

    @Override
    protected void onStop() {
        try { unregisterReceiver(liveBattery); } catch (IllegalArgumentException ignored) {}
        super.onStop();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void loadData() {
        repo.io().execute(() -> {
            long now = System.currentTimeMillis();
            long start = now - 24L * 3_600_000L;
            List<WeatherSample> samples = repo.getSamplesBetween("battery", start, now);
            LocalDate today = LocalDate.now(ZONE);
            DailyStat todayStat = FsClockDb.get(this).dailyStatDao().get("battery", today.toString());
            long count = repo.sampleCount();

            main.post(() -> renderData(samples, todayStat, count, start, now));
        });
    }

    private void renderData(List<WeatherSample> samples, DailyStat todayStat,
                            long totalCount, long rangeStartMs, long rangeEndMs) {
        if (samples.isEmpty()) {
            chart.setVisibility(View.GONE);
            chartEmpty.setVisibility(View.VISIBLE);
        } else {
            chart.setVisibility(View.VISIBLE);
            chartEmpty.setVisibility(View.GONE);
            chart.setData(samples, rangeStartMs, rangeEndMs);
        }

        if (todayStat == null) {
            todayMinMax.setText(R.string.system_today_no_data);
        } else {
            String minAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(todayStat.minTempAt), ZONE).format(HM);
            String maxAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(todayStat.maxTempAt), ZONE).format(HM);
            todayMinMax.setText(getString(R.string.system_today_minmax,
                    todayStat.minTemp, minAt, todayStat.maxTemp, maxAt));
        }

        dbCount.setText(String.format(FI, getString(R.string.system_db_count), totalCount));
    }

    private void exportCsv() {
        exportButton.setEnabled(false);
        String fileName = "fsclock_battery_" + nowStamp() + ".csv";
        repo.io().execute(() -> {
            final CsvExporter.Result result =
                    CsvExporter.export(getApplicationContext(), "battery", fileName);
            main.post(() -> {
                try {
                    if (result.ok) {
                        Toast.makeText(this, getString(R.string.toast_csv_exported, result.fileName),
                                Toast.LENGTH_LONG).show();
                    } else if (result.rowCount == 0 && result.error == null) {
                        Toast.makeText(this, R.string.toast_csv_empty, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.toast_csv_failed, Toast.LENGTH_LONG).show();
                    }
                } finally {
                    exportButton.setEnabled(true);
                }
            });
        });
    }

    private static String nowStamp() {
        return LocalDateTime.now(ZONE).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }
}
