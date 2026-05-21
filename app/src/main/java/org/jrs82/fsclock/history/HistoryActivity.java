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
import org.jrs82.fsclock.db.DailyStat;
import org.jrs82.fsclock.db.FsClockDb;
import org.jrs82.fsclock.db.HistoryRepository;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
            final List<String> result = FsClockDb.get(this).weatherDao().listChannels();
            main.post(() -> bindChannels(result));
        });
    }

    private void bindChannels(List<String> result) {
        if (isFinishing() || isDestroyed()) return;
        channels.clear();
        channels.addAll(result);
        if (channels.isEmpty()) {
            emptyLabel.setVisibility(View.VISIBLE);
            emptyLabel.setText(R.string.history_no_channels);
            return;
        }

        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, channels);
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

    private void refresh() {
        monthLabel.setText(currentMonth.format(MONTH_FMT));
        final String channel = currentChannel;
        final YearMonth month = currentMonth;
        if (channel == null) return;
        repo.io().execute(() -> {
            final List<DailyStat> stats = repo.getMonth(channel, month.getYear(), month.getMonthValue());
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
