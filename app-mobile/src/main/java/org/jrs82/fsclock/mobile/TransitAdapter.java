package org.jrs82.fsclock.mobile;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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

/** RecyclerView-adapteri joukkoliikennelistalle: osio-otsikot (moodi-ikoni + nimi) ja lähtörivit
 *  (linjanumero-badge moodivärillä, määränpää, lähtöaika, viive, etäisyys). Litteä item-lista. */
class TransitAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    static final class Header {
        final String title;
        final String mode;
        Header(String title, String mode) { this.title = title; this.mode = mode; }
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_DEPARTURE = 1;
    private static final Locale FI = new Locale("fi", "FI");
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm", FI);

    private final List<Object> items = new ArrayList<>();

    void submit(List<Object> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof Header ? TYPE_HEADER : TYPE_DEPARTURE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderVH(inf.inflate(R.layout.item_transit_header, parent, false));
        }
        return new DepartureVH(inf.inflate(R.layout.item_transit_departure, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = items.get(position);
        Context ctx = holder.itemView.getContext();
        if (item instanceof Header) {
            Header h = (Header) item;
            HeaderVH vh = (HeaderVH) holder;
            vh.title.setText(h.title);
            vh.icon.setImageResource(modeIcon(h.mode));
            vh.badge.setBackgroundTintList(ColorStateList.valueOf(modeColor(ctx, h.mode)));
        } else {
            Departure d = (Departure) item;
            DepartureVH vh = (DepartureVH) holder;
            vh.line.setText(d.routeShortName == null || d.routeShortName.isEmpty()
                    ? "?" : d.routeShortName);
            vh.line.setBackgroundTintList(ColorStateList.valueOf(modeColor(ctx, d.mode)));
            vh.headsign.setText(d.headsign == null || d.headsign.isEmpty()
                    ? "—" : d.headsign);
            vh.sub.setText(subLine(d));
            vh.time.setText(timeText(d));
            vh.time.setTextColor(ContextCompat.getColor(ctx, d.realtime
                    ? R.color.mobile_transit_tram : R.color.mobile_text_secondary));
            bindDelay(ctx, vh.delay, d);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static String subLine(Departure d) {
        String dist = distanceText(d.distanceMeters);
        if (d.stopName == null || d.stopName.isEmpty()) return dist;
        return dist.isEmpty() ? d.stopName : d.stopName + " · " + dist;
    }

    private static String distanceText(double meters) {
        if (Double.isNaN(meters) || meters < 0) return "";
        if (meters < 1000) return Math.round(meters) + " m";
        return String.format(FI, "%.1f km", meters / 1000.0);
    }

    /** "nyt" / "X min" alle tunnin, muuten kellonaika HH:mm. */
    private static String timeText(Departure d) {
        long nowSec = System.currentTimeMillis() / 1000L;
        long diffSec = d.departureEpochSec - nowSec;
        if (diffSec <= 30) return "nyt";
        long minutes = diffSec / 60L;
        if (minutes < 60) return minutes + " min";
        return CLOCK.format(new Date(d.departureEpochSec * 1000L));
    }

    private static void bindDelay(Context ctx, TextView view, Departure d) {
        if (!d.realtime || Math.abs(d.delaySeconds) < 60) {
            view.setVisibility(View.GONE);
            return;
        }
        int min = Math.round(d.delaySeconds / 60.0f);
        view.setVisibility(View.VISIBLE);
        if (d.delaySeconds > 0) {
            view.setText("+" + min + " min");
            view.setTextColor(ContextCompat.getColor(ctx, R.color.mobile_warning_red));
        } else {
            view.setText(min + " min");
            view.setTextColor(ContextCompat.getColor(ctx, R.color.mobile_transit_tram));
        }
    }

    static int modeColor(Context ctx, String mode) {
        int res;
        if ("TRAM".equals(mode)) res = R.color.mobile_transit_tram;
        else if ("RAIL".equals(mode)) res = R.color.mobile_transit_rail;
        else if ("SUBWAY".equals(mode)) res = R.color.mobile_transit_subway;
        else if ("BUS".equals(mode)) res = R.color.mobile_transit_bus;
        else res = R.color.mobile_accent;
        return ContextCompat.getColor(ctx, res);
    }

    private static int modeIcon(String mode) {
        if ("TRAM".equals(mode)) return R.drawable.mobile_ic_transit_tram;
        if ("RAIL".equals(mode)) return R.drawable.mobile_ic_transit_rail;
        if ("SUBWAY".equals(mode)) return R.drawable.mobile_ic_transit_subway;
        return R.drawable.mobile_ic_transit_bus;
    }

    static final class HeaderVH extends RecyclerView.ViewHolder {
        final View badge;
        final ImageView icon;
        final TextView title;
        HeaderVH(@NonNull View v) {
            super(v);
            badge = v.findViewById(R.id.transit_header_badge);
            icon = v.findViewById(R.id.transit_header_icon);
            title = v.findViewById(R.id.transit_header_title);
        }
    }

    static final class DepartureVH extends RecyclerView.ViewHolder {
        final TextView line;
        final TextView headsign;
        final TextView sub;
        final TextView time;
        final TextView delay;
        DepartureVH(@NonNull View v) {
            super(v);
            line = v.findViewById(R.id.transit_dep_line);
            headsign = v.findViewById(R.id.transit_dep_headsign);
            sub = v.findViewById(R.id.transit_dep_sub);
            time = v.findViewById(R.id.transit_dep_time);
            delay = v.findViewById(R.id.transit_dep_delay);
        }
    }
}
