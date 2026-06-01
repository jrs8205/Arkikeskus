package org.jrs82.fsclock.mobile;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.jrs82.fsclock.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Vuoron aikajana: pysäkit järjestyksessä. Korostaa ajoneuvon nykyisen pysäkän (moodiväri) ja
 *  käyttäjän nousupysäkän (accent); ohitetut pysäkit himmennetään. */
class TransitTimelineAdapter extends RecyclerView.Adapter<TransitTimelineAdapter.VH> {

    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm", new Locale("fi", "FI"));

    private final List<TimelineStop> stops = new ArrayList<>();
    private int currentIndex = -1;
    private int boardIndex = -1;
    private String mode = "";

    void submit(List<TimelineStop> newStops, int currentIndex, int boardIndex, String mode) {
        stops.clear();
        if (newStops != null) stops.addAll(newStops);
        this.currentIndex = currentIndex;
        this.boardIndex = boardIndex;
        this.mode = mode;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_transit_timeline_stop, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Context ctx = h.itemView.getContext();
        TimelineStop s = stops.get(position);
        boolean isCurrent = position == currentIndex;
        boolean isBoard = position == boardIndex;
        boolean passed = currentIndex >= 0 && position < currentIndex;

        int dotColor;
        if (isCurrent) dotColor = TransitAdapter.modeColor(ctx, mode);
        else if (isBoard) dotColor = ContextCompat.getColor(ctx, R.color.mobile_accent);
        else if (passed) dotColor = ContextCompat.getColor(ctx, R.color.mobile_text_muted);
        else dotColor = ContextCompat.getColor(ctx, R.color.mobile_card_stroke);
        h.dot.setBackgroundTintList(ColorStateList.valueOf(dotColor));

        String label = s.name == null ? "" : s.name;
        if (isCurrent) label += "  • bussi tässä";
        else if (isBoard) label += "  • oma pysäkki";
        h.name.setText(label);
        h.name.setTypeface(null, (isCurrent || isBoard) ? Typeface.BOLD : Typeface.NORMAL);
        h.name.setTextColor(ContextCompat.getColor(ctx,
                (isCurrent || isBoard) ? R.color.mobile_text_primary
                        : passed ? R.color.mobile_text_muted : R.color.mobile_text_body));

        h.time.setText(s.depEpochSec > 0 ? CLOCK.format(new Date(s.depEpochSec * 1000L)) : "");
    }

    @Override
    public int getItemCount() {
        return stops.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final View dot;
        final TextView name, time;
        VH(@NonNull View v) {
            super(v);
            dot = v.findViewById(R.id.transit_tl_dot);
            name = v.findViewById(R.id.transit_tl_name);
            time = v.findViewById(R.id.transit_tl_time);
        }
    }
}
