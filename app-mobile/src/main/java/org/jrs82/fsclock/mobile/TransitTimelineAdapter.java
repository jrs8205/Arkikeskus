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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Aikajana: pysäkit järjestyksessä. Korostaa ajoneuvojen pysäkit (moodiväri, "bussi tässä") ja
 *  käyttäjän nousupysäkän (accent). Vuoronäkymässä ohitetut pysäkit himmennetään (passedBefore). */
class TransitTimelineAdapter extends RecyclerView.Adapter<TransitTimelineAdapter.VH> {

    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm", new Locale("fi", "FI"));

    private final List<TimelineStop> stops = new ArrayList<>();
    private final Set<Integer> vehicleIdx = new HashSet<>();
    private int boardIndex = -1;
    private int passedBefore = -1;
    private String mode = "";

    void submit(List<TimelineStop> newStops, Set<Integer> vehicles, int boardIndex,
                int passedBefore, String mode) {
        stops.clear();
        if (newStops != null) stops.addAll(newStops);
        vehicleIdx.clear();
        if (vehicles != null) vehicleIdx.addAll(vehicles);
        this.boardIndex = boardIndex;
        this.passedBefore = passedBefore;
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
        boolean isVehicle = vehicleIdx.contains(position);
        boolean isBoard = position == boardIndex;
        boolean passed = passedBefore >= 0 && position < passedBefore;

        int dotColor;
        if (isVehicle) dotColor = TransitAdapter.modeColor(ctx, mode);
        else if (isBoard) dotColor = ContextCompat.getColor(ctx, R.color.mobile_accent);
        else if (passed) dotColor = ContextCompat.getColor(ctx, R.color.mobile_text_muted);
        else dotColor = ContextCompat.getColor(ctx, R.color.mobile_card_stroke);
        h.dot.setBackgroundTintList(ColorStateList.valueOf(dotColor));

        String label = s.name == null ? "" : s.name;
        if (isVehicle) label += "  • " + modeWord(mode) + " tässä";
        else if (isBoard) label += "  • oma pysäkki";
        h.name.setText(label);
        h.name.setTypeface(null, (isVehicle || isBoard) ? Typeface.BOLD : Typeface.NORMAL);
        h.name.setTextColor(ContextCompat.getColor(ctx,
                (isVehicle || isBoard) ? R.color.mobile_text_primary
                        : passed ? R.color.mobile_text_muted : R.color.mobile_text_body));

        h.time.setText(s.depEpochSec > 0 ? CLOCK.format(new Date(s.depEpochSec * 1000L)) : "");
    }

    @Override
    public int getItemCount() {
        return stops.size();
    }

    private static String modeWord(String mode) {
        if ("TRAM".equals(mode)) return "ratikka";
        if ("RAIL".equals(mode)) return "juna";
        if ("SUBWAY".equals(mode)) return "metro";
        return "bussi";
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
