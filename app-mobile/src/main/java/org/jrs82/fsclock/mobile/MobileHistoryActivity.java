package org.jrs82.fsclock.mobile;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import org.jrs82.fsclock.R;
import org.jrs82.fsclock.SettingsManager;
import org.jrs82.fsclock.db.DailyStat;
import org.jrs82.fsclock.db.FsClockDb;
import org.jrs82.fsclock.db.HistoryRepository;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MobileHistoryActivity extends AppCompatActivity {

    private static final Locale FI = new Locale("fi", "FI");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("LLLL yyyy", FI);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> channels = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    private HistoryRepository repo;
    private Spinner channelSpinner;
    private TextView monthText;
    private TextView summaryText;
    private LinearLayout list;
    private YearMonth currentMonth = YearMonth.now();
    private String currentChannel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        MobileThemeController.apply(this);
        setTheme(R.style.MobileSettingsTheme);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mobile_history);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.history_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        repo = HistoryRepository.get(this);
        channelSpinner = findViewById(R.id.mobile_history_channel);
        monthText = findViewById(R.id.mobile_history_month);
        summaryText = findViewById(R.id.mobile_history_summary);
        list = findViewById(R.id.mobile_history_list);

        findViewById(R.id.mobile_history_prev).setOnClickListener(v -> {
            currentMonth = currentMonth.minusMonths(1);
            refresh();
        });
        findViewById(R.id.mobile_history_next).setOnClickListener(v -> {
            currentMonth = currentMonth.plusMonths(1);
            refresh();
        });

        loadChannels();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void loadChannels() {
        repo.io().execute(() -> {
            List<String> weather = FsClockDb.get(this).weatherDao().listChannels();
            List<String> ruuvi = repo.listRuuviMacs();
            main.post(() -> bindChannels(weather, ruuvi));
        });
    }

    private void bindChannels(List<String> weather, List<String> ruuviInDb) {
        if (isFinishing() || isDestroyed()) return;
        channels.clear();
        labels.clear();

        if (weather != null) {
            for (String ch : weather) {
                channels.add(ch);
                labels.add(displayChannel(ch));
            }
        }

        Set<String> macs = new LinkedHashSet<>();
        if (ruuviInDb != null) macs.addAll(ruuviInDb);
        SettingsManager sm = SettingsManager.get();
        addIfNotNull(macs, sm.getRuuviMac(SettingsManager.RUUVI_SLOT_BEDROOM));
        addIfNotNull(macs, sm.getRuuviMac(SettingsManager.RUUVI_SLOT_LIVINGROOM));
        addIfNotNull(macs, sm.getRuuviMac(SettingsManager.RUUVI_SLOT_BALCONY));
        for (String mac : macs) {
            channels.add("ruuvi:" + mac);
            labels.add("Ruuvi: " + ruuviDisplayName(sm, mac));
        }

        if (channels.isEmpty()) {
            summaryText.setText("Historiaa ei ole vielä tallennettu.");
            return;
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        channelSpinner.setAdapter(adapter);
        channelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentChannel = channels.get(position);
                refresh();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        currentChannel = channels.get(0);
        refresh();
    }

    private void refresh() {
        monthText.setText(capitalize(currentMonth.format(MONTH_FMT)));
        list.removeAllViews();
        summaryText.setText("Ladataan...");
        String channel = currentChannel;
        YearMonth month = currentMonth;
        if (channel == null) return;
        repo.io().execute(() -> {
            List<DailyStat> stats;
            if (channel.startsWith("ruuvi:")) {
                stats = repo.getRuuviMonth(channel.substring("ruuvi:".length()),
                        month.getYear(), month.getMonthValue());
            } else {
                stats = repo.getMonth(channel, month.getYear(), month.getMonthValue());
            }
            main.post(() -> render(channel, month, stats));
        });
    }

    private void render(String channel, YearMonth month, List<DailyStat> stats) {
        if (isFinishing() || isDestroyed()) return;
        if (!channel.equals(currentChannel) || !month.equals(currentMonth)) return;
        list.removeAllViews();
        if (stats == null || stats.isEmpty()) {
            summaryText.setText("Tälle kuukaudelle ei ole dataa.");
            return;
        }
        boolean ruuvi = channel.startsWith("ruuvi:");
        summaryText.setText(summary(stats, ruuvi));
        for (DailyStat s : stats) {
            list.addView(dayCard(s, ruuvi));
        }
    }

    private String summary(List<DailyStat> stats, boolean ruuvi) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum = 0.0;
        int count = 0;
        for (DailyStat s : stats) {
            min = Math.min(min, s.minTemp);
            max = Math.max(max, s.maxTemp);
            sum += s.avgTemp;
            count++;
        }
        String value = String.format(FI, "Päiviä %d\nAlin %.1f C\nYlin %.1f C\nKeskiarvo %.1f C",
                count, min, max, sum / Math.max(1, count));
        if (ruuvi) value += "\nKosteus näkyy päiväkohtaisissa riveissä.";
        return value;
    }

    private TextView dayCard(DailyStat s, boolean ruuvi) {
        StringBuilder text = new StringBuilder();
        text.append(s.date);
        if (s.isPartial) text.append("  · vajaa");
        text.append('\n');
        text.append(String.format(FI, "Lämpö %.1f / %.1f / %.1f C",
                s.minTemp, s.avgTemp, s.maxTemp));
        if (ruuvi && s.totalPrecip != null) {
            text.append(String.format(FI, "\nKosteus avg %.0f %%", s.totalPrecip));
        } else {
            if (s.totalPrecip != null) text.append(String.format(FI, "\nSade %.1f mm", s.totalPrecip));
            if (s.maxWindGust != null) text.append(String.format(FI, "  Puuska %.1f m/s", s.maxWindGust));
        }
        text.append("\nNäytteitä ").append(s.sampleCount);

        TextView tv = new TextView(this);
        tv.setText(text.toString());
        tv.setTextSize(15);
        tv.setTextColor(getColor(R.color.mobile_text_body));
        tv.setLineSpacing(dp(3), 1f);
        tv.setBackgroundResource(R.drawable.mobile_card_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        tv.setLayoutParams(lp);
        return tv;
    }

    private static void addIfNotNull(Set<String> set, String mac) {
        if (mac != null && !mac.isEmpty()) set.add(mac.toUpperCase(FI));
    }

    private String displayChannel(String channel) {
        if (channel == null) return "";
        if (channel.startsWith("fmi_")) {
            String place = channel.substring(4).replace('_', ' ');
            return "FMI: " + capitalize(place);
        }
        if ("battery".equals(channel)) return "Akku";
        return channel;
    }

    private String ruuviDisplayName(SettingsManager sm, String mac) {
        String slot = sm.slotForMac(mac);
        String fallback = null;
        String key = null;
        if (SettingsManager.RUUVI_SLOT_BEDROOM.equals(slot)) {
            fallback = "Anturi 1";
            key = MobileThemeController.KEY_SENSOR_NAME_BEDROOM;
        } else if (SettingsManager.RUUVI_SLOT_LIVINGROOM.equals(slot)) {
            fallback = "Anturi 2";
            key = MobileThemeController.KEY_SENSOR_NAME_LIVINGROOM;
        } else if (SettingsManager.RUUVI_SLOT_BALCONY.equals(slot)) {
            fallback = "Anturi 3";
            key = MobileThemeController.KEY_SENSOR_NAME_BALCONY;
        }
        if (key != null) {
            String name = PreferenceManager.getDefaultSharedPreferences(this)
                    .getString(key, fallback);
            return name == null || name.trim().isEmpty() ? fallback : name.trim();
        }
        return mac;
    }

    private static String capitalize(String text) {
        if (text == null || text.isEmpty()) return "";
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
