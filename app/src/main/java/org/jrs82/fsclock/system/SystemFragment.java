package org.jrs82.fsclock.system;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

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

public class SystemFragment extends Fragment {

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
    private Button exportAllButton, exportWeatherButton, exportBatteryButton, openHistoryButton;

    private final BroadcastReceiver liveBattery = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            int tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            if (batteryCurrent == null) return;
            if (tempTenths < 0 || level < 0) {
                batteryCurrent.setText(R.string.system_battery_current_unknown);
            } else {
                batteryCurrent.setText(getString(R.string.system_battery_current,
                        level, tempTenths / 10.0));
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_system, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        repo = HistoryRepository.get(requireContext());

        batteryCurrent = view.findViewById(R.id.battery_current);
        todayMinMax = view.findViewById(R.id.today_minmax);
        dbCount = view.findViewById(R.id.db_count);
        chartEmpty = view.findViewById(R.id.chart_empty);
        chart = view.findViewById(R.id.battery_chart);
        exportAllButton = view.findViewById(R.id.export_all);
        exportWeatherButton = view.findViewById(R.id.export_weather);
        exportBatteryButton = view.findViewById(R.id.export_battery);
        openHistoryButton = view.findViewById(R.id.open_history);

        exportAllButton.setOnClickListener(v -> exportCsv(CsvExporter.Kind.RAW_ALL));
        exportWeatherButton.setOnClickListener(v -> exportCsv(CsvExporter.Kind.WEATHER_HUMAN));
        exportBatteryButton.setOnClickListener(v -> exportCsv(CsvExporter.Kind.BATTERY_HUMAN));
        openHistoryButton.setOnClickListener(v -> startActivity(
                new Intent(requireContext(), org.jrs82.fsclock.history.HistoryActivity.class)));
    }

    @Override
    public void onStart() {
        super.onStart();
        requireContext().registerReceiver(liveBattery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        loadData();
    }

    @Override
    public void onStop() {
        try { requireContext().unregisterReceiver(liveBattery); } catch (Exception ignored) {}
        super.onStop();
    }

    private void loadData() {
        repo.io().execute(() -> {
            long now = System.currentTimeMillis();
            long start = now - 24L * 3_600_000L;
            List<WeatherSample> samples = repo.getSamplesBetween("battery", start, now);
            LocalDate today = LocalDate.now(ZONE);
            DailyStat todayStat = FsClockDb.get(requireContext()).dailyStatDao().get("battery", today.toString());
            long count = repo.sampleCount();
            main.post(() -> renderData(samples, todayStat, count, start, now));
        });
    }

    private void renderData(List<WeatherSample> samples, DailyStat todayStat,
                            long totalCount, long rangeStartMs, long rangeEndMs) {
        if (!isAdded() || isDetached()) return;
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

    private void exportCsv(CsvExporter.Kind kind) {
        setExportButtonsEnabled(false);
        final String fileName = CsvExporter.buildFileName(kind);
        repo.io().execute(() -> {
            final CsvExporter.Result result =
                    CsvExporter.export(requireContext(), kind, fileName);
            main.post(() -> {
                if (!isAdded() || isDetached()) return;
                try {
                    if (result.ok) {
                        Toast.makeText(requireContext(),
                                getString(R.string.toast_csv_exported, result.fileName),
                                Toast.LENGTH_LONG).show();
                    } else if (result.rowCount == 0 && result.error == null) {
                        Toast.makeText(requireContext(), R.string.toast_csv_empty, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(requireContext(), R.string.toast_csv_failed, Toast.LENGTH_LONG).show();
                    }
                } finally {
                    setExportButtonsEnabled(true);
                }
            });
        });
    }

    private void setExportButtonsEnabled(boolean enabled) {
        exportAllButton.setEnabled(enabled);
        exportWeatherButton.setEnabled(enabled);
        exportBatteryButton.setEnabled(enabled);
    }
}
