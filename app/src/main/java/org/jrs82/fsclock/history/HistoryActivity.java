package org.jrs82.fsclock.history;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

public class HistoryActivity extends AppCompatActivity {

    private static final Locale FI = new Locale("fi", "FI");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("LLLL yyyy", FI);

    private HistoryRepository repo;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Spinner channelSpinner;
    private TextView monthLabel;
    private TextView emptyLabel;
    private Button prevButton;
    private Button nextButton;
    private MonthChartView chart;
    private RecyclerView list;
    private DailyStatAdapter adapter;

    private final List<String> channels = new ArrayList<>();
    private final List<String> channelLabels = new ArrayList<>();
    private String currentChannel;
    private YearMonth currentMonth = YearMonth.now();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.SettingsTheme);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.history_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        repo = HistoryRepository.get(this);

        channelSpinner = findViewById(R.id.channel_spinner);
        monthLabel = findViewById(R.id.month_label);
        emptyLabel = findViewById(R.id.empty_label);
        prevButton = findViewById(R.id.prev_month);
        nextButton = findViewById(R.id.next_month);
        chart = findViewById(R.id.month_chart);
        list = findViewById(R.id.days_list);

        adapter = new DailyStatAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        prevButton.setOnClickListener(v -> { currentMonth = currentMonth.minusMonths(1); refresh(); });
        nextButton.setOnClickListener(v -> { currentMonth = currentMonth.plusMonths(1); refresh(); });

        loadChannels();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void loadChannels() {
        repo.io().execute(() -> {
            final List<String> weather = FsClockDb.get(this).weatherDao().listChannels();
            final List<String> ruuviInDb = repo.listRuuviMacs();
            main.post(() -> bindChannels(weather, ruuviInDb));
        });
    }

    private void bindChannels(List<String> weather, List<String> ruuviInDb) {
        if (isFinishing() || isDestroyed()) return;
        channels.clear();
        channelLabels.clear();

        if (weather != null) {
            for (String ch : weather) {
                channels.add(ch);
                channelLabels.add(ch);
            }
        }

        // Yhdistä DB:ssä olevat MACit ja asetuksissa määritetyt MACit, jotta uusi
        // anturi näkyy listalla heti vaikkei vielä olisi yhtään tallennettua mittausta.
        Set<String> macs = new LinkedHashSet<>();
        if (ruuviInDb != null) macs.addAll(ruuviInDb);
        SettingsManager sm = SettingsManager.get();
        addIfNotNull(macs, sm.getRuuviMac(SettingsManager.RUUVI_SLOT_BEDROOM));
        addIfNotNull(macs, sm.getRuuviMac(SettingsManager.RUUVI_SLOT_LIVINGROOM));
        addIfNotNull(macs, sm.getRuuviMac(SettingsManager.RUUVI_SLOT_BALCONY));
        for (String mac : macs) {
            channels.add("ruuvi:" + mac);
            channelLabels.add(getString(R.string.history_ruuvi_label, ruuviDisplayName(sm, mac), mac));
        }

        if (channels.isEmpty()) {
            emptyLabel.setVisibility(View.VISIBLE);
            emptyLabel.setText(R.string.history_no_channels);
            return;
        }

        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, channelLabels);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        channelSpinner.setAdapter(spinnerAdapter);
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

    private static void addIfNotNull(Set<String> set, String mac) {
        if (mac != null && !mac.isEmpty()) set.add(mac.toUpperCase(FI));
    }

    private String ruuviDisplayName(SettingsManager sm, String mac) {
        String slot = sm.slotForMac(mac);
        if (SettingsManager.RUUVI_SLOT_BEDROOM.equals(slot)) return getString(R.string.sensor_label_bedroom);
        if (SettingsManager.RUUVI_SLOT_LIVINGROOM.equals(slot)) return getString(R.string.sensor_label_livingroom);
        if (SettingsManager.RUUVI_SLOT_BALCONY.equals(slot)) return getString(R.string.sensor_label_balcony);
        return getString(R.string.history_ruuvi_unassigned);
    }

    private void refresh() {
        monthLabel.setText(currentMonth.format(MONTH_FMT));
        final String channel = currentChannel;
        final YearMonth month = currentMonth;
        if (channel == null) return;
        repo.io().execute(() -> {
            final List<DailyStat> stats;
            if (channel.startsWith("ruuvi:")) {
                String mac = channel.substring("ruuvi:".length());
                stats = repo.getRuuviMonth(mac, month.getYear(), month.getMonthValue());
            } else {
                stats = repo.getMonth(channel, month.getYear(), month.getMonthValue());
            }
            main.post(() -> render(stats, channel, month));
        });
    }

    private void render(List<DailyStat> stats, String channel, YearMonth month) {
        if (isFinishing() || isDestroyed()) return;
        if (!channel.equals(currentChannel) || !month.equals(currentMonth)) return;
        adapter.setData(stats, channel);
        chart.setData(stats, month);
        if (stats == null || stats.isEmpty()) {
            emptyLabel.setVisibility(View.VISIBLE);
            emptyLabel.setText(R.string.history_no_data);
        } else {
            emptyLabel.setVisibility(View.GONE);
        }
    }
}
