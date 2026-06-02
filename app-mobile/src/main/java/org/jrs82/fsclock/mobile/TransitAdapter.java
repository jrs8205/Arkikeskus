package org.jrs82.fsclock.mobile;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
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

/** RecyclerView-adapteri joukkoliikennelistalle: osio-otsikot, lähtörivit (linjabadge moodivärillä,
 *  määränpää, aika, viive, etäisyys, suosikkitähti) ja linjahaun tulokset. Litteä item-lista. */
class TransitAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface Listener {
        void onDepartureClick(Departure d);
        void onDepartureLongClick(Departure d);
        void onLineStar(String routeGtfsId, String shortName, String longName, String mode);
        void onRouteClick(RouteHit r);
        void onPlaceClick(PlaceHit p);
        boolean isLineFav(String routeGtfsId);
    }

    static final class Header {
        final String title;
        final String mode;
        Header(String title, String mode) { this.title = title; this.mode = mode; }
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_DEPARTURE = 1;
    private static final int TYPE_ROUTE = 2;
    private static final int TYPE_PLACE = 3;
    private static final Locale FI = new Locale("fi", "FI");
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm", FI);

    private final List<Object> items = new ArrayList<>();
    private final Listener listener;

    TransitAdapter(Listener listener) { this.listener = listener; }

    void submit(List<Object> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        Object it = items.get(position);
        if (it instanceof Header) return TYPE_HEADER;
        if (it instanceof RouteHit) return TYPE_ROUTE;
        if (it instanceof PlaceHit) return TYPE_PLACE;
        return TYPE_DEPARTURE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderVH(inf.inflate(R.layout.item_transit_header, parent, false));
        }
        if (viewType == TYPE_ROUTE) {
            return new RouteVH(inf.inflate(R.layout.item_transit_route, parent, false));
        }
        if (viewType == TYPE_PLACE) {
            return new PlaceVH(inf.inflate(R.layout.item_transit_place, parent, false));
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
            vh.icon.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            vh.badge.setBackgroundTintList(ColorStateList.valueOf(modeColor(ctx, h.mode)));
        } else if (item instanceof RouteHit) {
            RouteHit r = (RouteHit) item;
            RouteVH vh = (RouteVH) holder;
            vh.line.setText(r.shortName == null || r.shortName.isEmpty() ? "?" : r.shortName);
            vh.line.setBackgroundTintList(ColorStateList.valueOf(modeColor(ctx, r.mode)));
            vh.name.setText(r.longName == null ? "" : r.longName);
            boolean fav = listener != null && listener.isLineFav(r.gtfsId);
            vh.star.setImageResource(fav ? R.drawable.mobile_ic_star : R.drawable.mobile_ic_star_outline);
            vh.star.setOnClickListener(v -> {
                if (listener != null) listener.onLineStar(r.gtfsId, r.shortName, r.longName, r.mode);
            });
            vh.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onRouteClick(r);
            });
            vh.itemView.setOnLongClickListener(null);
        } else if (item instanceof PlaceHit) {
            PlaceHit p = (PlaceHit) item;
            PlaceVH vh = (PlaceVH) holder;
            vh.name.setText(p.name == null || p.name.isEmpty() ? "?" : p.name);
            vh.locality.setText(p.locality == null ? "" : p.locality);
            vh.locality.setVisibility(p.locality == null || p.locality.isEmpty() ? View.GONE : View.VISIBLE);
            bindModes(vh.modes, p.modes);
            vh.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onPlaceClick(p);
            });
            vh.itemView.setOnLongClickListener(null);
        } else {
            Departure d = (Departure) item;
            DepartureVH vh = (DepartureVH) holder;
            vh.line.setText(d.routeShortName == null || d.routeShortName.isEmpty() ? "?" : d.routeShortName);
            vh.line.setBackgroundTintList(ColorStateList.valueOf(modeColor(ctx, d.mode)));
            vh.headsign.setText(d.headsign == null || d.headsign.isEmpty() ? "—" : d.headsign);
            vh.sub.setText(subLine(d));
            vh.time.setText(timeText(d));
            vh.time.setTextColor(ContextCompat.getColor(ctx, d.realtime
                    ? R.color.mobile_transit_tram : R.color.mobile_text_secondary));
            bindDelay(ctx, vh.delay, d);
            boolean fav = listener != null && listener.isLineFav(d.routeGtfsId);
            vh.star.setImageResource(fav ? R.drawable.mobile_ic_star : R.drawable.mobile_ic_star_outline);
            vh.star.setOnClickListener(v -> {
                if (listener != null) listener.onLineStar(d.routeGtfsId, d.routeShortName, "", d.mode);
            });
            vh.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onDepartureClick(d);
            });
            vh.itemView.setOnLongClickListener(v -> {
                if (listener != null) listener.onDepartureLongClick(d);
                return true;
            });
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
        if ("FAV".equals(mode)) return R.drawable.mobile_ic_star;
        if ("TRAM".equals(mode)) return R.drawable.mobile_ic_transit_tram;
        if ("RAIL".equals(mode)) return R.drawable.mobile_ic_transit_rail;
        if ("SUBWAY".equals(mode)) return R.drawable.mobile_ic_transit_subway;
        return R.drawable.mobile_ic_transit_bus;
    }

    /** Lisää moodi-ikonit (tintattuna) paikkahaun riville. Tyhjä lista → laatikko piiloon. */
    private static void bindModes(LinearLayout box, List<String> modes) {
        box.removeAllViews();
        if (modes == null || modes.isEmpty()) {
            box.setVisibility(View.GONE);
            return;
        }
        box.setVisibility(View.VISIBLE);
        Context ctx = box.getContext();
        int sz = dp(ctx, 19);
        int gap = dp(ctx, 5);
        for (String m : modes) {
            ImageView iv = new ImageView(ctx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.setMarginStart(box.getChildCount() == 0 ? 0 : gap);
            iv.setLayoutParams(lp);
            iv.setImageResource(modeIcon(m));
            iv.setImageTintList(ColorStateList.valueOf(modeColor(ctx, m)));
            box.addView(iv);
        }
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
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
        final TextView line, headsign, sub, time, delay;
        final ImageView star;
        DepartureVH(@NonNull View v) {
            super(v);
            line = v.findViewById(R.id.transit_dep_line);
            headsign = v.findViewById(R.id.transit_dep_headsign);
            sub = v.findViewById(R.id.transit_dep_sub);
            time = v.findViewById(R.id.transit_dep_time);
            delay = v.findViewById(R.id.transit_dep_delay);
            star = v.findViewById(R.id.transit_dep_star);
        }
    }

    static final class RouteVH extends RecyclerView.ViewHolder {
        final TextView line, name;
        final ImageView star;
        RouteVH(@NonNull View v) {
            super(v);
            line = v.findViewById(R.id.transit_route_line);
            name = v.findViewById(R.id.transit_route_name);
            star = v.findViewById(R.id.transit_route_star);
        }
    }

    static final class PlaceVH extends RecyclerView.ViewHolder {
        final TextView name, locality;
        final LinearLayout modes;
        PlaceVH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.transit_place_name);
            locality = v.findViewById(R.id.transit_place_locality);
            modes = v.findViewById(R.id.transit_place_modes);
        }
    }
}
