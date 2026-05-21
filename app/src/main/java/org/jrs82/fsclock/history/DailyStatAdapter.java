package org.jrs82.fsclock.history;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.jrs82.fsclock.R;
import org.jrs82.fsclock.db.DailyStat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DailyStatAdapter extends RecyclerView.Adapter<DailyStatAdapter.VH> {

    private static final Locale FI = new Locale("fi", "FI");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEE d.M.", FI);

    private final List<DailyStat> data = new ArrayList<>();
    private boolean batteryChannel;

    public void setData(List<DailyStat> stats, String channel) {
        this.batteryChannel = "battery".equals(channel);
        data.clear();
        if (stats != null) data.addAll(stats);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_daily_stat, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        DailyStat s = data.get(position);
        String dateLabel = formatDate(s.date);
        String partial = s.isPartial ? h.itemView.getContext().getString(R.string.history_partial_marker) : "";

        if (batteryChannel) {
            h.primary.setText(h.itemView.getContext().getString(
                    R.string.history_battery_row, dateLabel, s.minTemp, s.avgTemp, s.maxTemp));
            h.extras.setText(String.format(FI,
                    h.itemView.getContext().getString(R.string.history_battery_extras),
                    s.sampleCount, partial));
        } else {
            h.primary.setText(h.itemView.getContext().getString(
                    R.string.history_weather_row, dateLabel, s.minTemp, s.avgTemp, s.maxTemp));
            h.extras.setText(buildWeatherExtras(h.itemView, s, partial));
        }
    }

    private String buildWeatherExtras(View item, DailyStat s, String partial) {
        boolean hasPrecip = s.totalPrecip != null;
        boolean hasGust = s.maxWindGust != null;
        if (hasPrecip && hasGust) {
            return String.format(FI,
                    item.getContext().getString(R.string.history_weather_extras),
                    s.totalPrecip, s.maxWindGust, s.sampleCount, partial);
        } else if (hasPrecip) {
            return String.format(FI,
                    item.getContext().getString(R.string.history_weather_extras_no_wind),
                    s.totalPrecip, s.sampleCount, partial);
        } else if (hasGust) {
            return String.format(FI,
                    item.getContext().getString(R.string.history_weather_extras_no_precip),
                    s.maxWindGust, s.sampleCount, partial);
        } else {
            return String.format(FI,
                    item.getContext().getString(R.string.history_weather_extras_basic),
                    s.sampleCount, partial);
        }
    }

    private String formatDate(String isoDate) {
        try {
            return LocalDate.parse(isoDate).format(DATE_FMT);
        } catch (Exception e) {
            return isoDate;
        }
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView primary;
        final TextView extras;
        VH(@NonNull View v) {
            super(v);
            primary = v.findViewById(R.id.row_primary);
            extras = v.findViewById(R.id.row_extras);
        }
    }
}
