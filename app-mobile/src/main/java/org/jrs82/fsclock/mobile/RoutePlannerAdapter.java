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

/** Reittihaun lista: Mistä/Minne-ehdotukset (GeoPlace), reittiehdotukset (Itinerary) ja reitin
 *  osat (Leg) samassa adapterissa. Pääsivun lista käyttää SUGGEST/ITINERARY, detail-lista LEG. */
class RoutePlannerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface Listener {
        void onSuggestClick(GeoPlace p);
        void onItineraryClick(Itinerary it);
    }

    private static final int TYPE_SUGGEST = 0;
    private static final int TYPE_ITINERARY = 1;
    private static final int TYPE_LEG = 2;
    private static final Locale FI = new Locale("fi", "FI");
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm", FI);

    private final List<Object> items = new ArrayList<>();
    private final Listener listener;

    RoutePlannerAdapter(Listener listener) { this.listener = listener; }

    void submit(List<?> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        Object it = items.get(position);
        if (it instanceof GeoPlace) return TYPE_SUGGEST;
        if (it instanceof Itinerary) return TYPE_ITINERARY;
        return TYPE_LEG;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_SUGGEST) {
            return new SuggestVH(inf.inflate(R.layout.item_geo_suggest, parent, false));
        }
        if (viewType == TYPE_ITINERARY) {
            return new ItineraryVH(inf.inflate(R.layout.item_itinerary, parent, false));
        }
        return new LegVH(inf.inflate(R.layout.item_leg, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = items.get(position);
        Context ctx = holder.itemView.getContext();
        if (item instanceof GeoPlace) {
            GeoPlace p = (GeoPlace) item;
            SuggestVH vh = (SuggestVH) holder;
            vh.name.setText(p.name);
            vh.locality.setText(p.locality == null ? "" : p.locality);
            vh.locality.setVisibility(p.locality == null || p.locality.isEmpty() ? View.GONE : View.VISIBLE);
            vh.itemView.setOnClickListener(v -> { if (listener != null) listener.onSuggestClick(p); });
        } else if (item instanceof Itinerary) {
            Itinerary it = (Itinerary) item;
            ItineraryVH vh = (ItineraryVH) holder;
            vh.time.setText(clock(it.startEpochMs) + " – " + clock(it.endEpochMs));
            vh.meta.setText(minutes(it.durationSec) + " · " + transfersText(it.transfers));
            buildLegChips(vh.legs, it);
            vh.itemView.setOnClickListener(v -> { if (listener != null) listener.onItineraryClick(it); });
        } else {
            bindLeg((LegVH) holder, (Leg) item, ctx);
        }
    }

    @Override
    public int getItemCount() { return items.size(); }

    // --- Reittikortin osatiivistelmä (vain joukkoliikenne-legit badgeina) ---

    private static void buildLegChips(LinearLayout box, Itinerary it) {
        Context ctx = box.getContext();
        box.removeAllViews();
        boolean any = false;
        for (Leg leg : it.legs) {
            // Näytä kaikki osat moodi-ikonein (myös kävely), myös kun reitillä on vain yksi kulkuneuvo.
            if (any) box.addView(sep(ctx));
            box.addView(legChip(ctx, leg));
            any = true;
        }
        if (!any) {
            TextView t = new TextView(ctx);
            t.setText("Kävely koko matka");
            t.setTextSize(13);
            t.setTextColor(ContextCompat.getColor(ctx, R.color.mobile_text_muted));
            box.addView(t);
        }
    }

    /** Reittikortin osachip: moodi-ikoni + (joukkoliikenteellä) linjanumero, moodivärillä. */
    private static View legChip(Context ctx, Leg leg) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int color = leg.isWalk()
                ? ContextCompat.getColor(ctx, R.color.mobile_text_muted)
                : TransitAdapter.modeColor(ctx, leg.mode);

        ImageView icon = new ImageView(ctx);
        int sz = dp(ctx, 20);
        icon.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        icon.setImageResource(TransitAdapter.modeIcon(leg.mode));
        icon.setImageTintList(ColorStateList.valueOf(color));
        row.addView(icon);

        if (!leg.isWalk() && leg.routeShortName != null && !leg.routeShortName.isEmpty()) {
            TextView num = new TextView(ctx);
            num.setText(leg.routeShortName);
            num.setTextColor(color);
            num.setTextSize(13);
            num.setTypeface(num.getTypeface(), android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nlp.setMarginStart(dp(ctx, 3));
            num.setLayoutParams(nlp);
            row.addView(num);
        }

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.setMarginEnd(dp(ctx, 4));
        row.setLayoutParams(rlp);
        return row;
    }

    private static TextView sep(Context ctx) {
        TextView t = new TextView(ctx);
        t.setText("›");
        t.setTextSize(15);
        t.setTextColor(ContextCompat.getColor(ctx, R.color.mobile_text_muted));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(ctx, 4));
        t.setLayoutParams(lp);
        return t;
    }

    // --- Reitin osa (leg) detail-listassa ---

    private static void bindLeg(LegVH vh, Leg leg, Context ctx) {
        vh.time.setText(clock(leg.startEpochMs));
        if (leg.isWalk()) {
            vh.badge.setVisibility(View.GONE);
            vh.primary.setText("Kävely");
            String sec = distance(leg.distanceMeters) + " · " + minutes(leg.durationSec);
            if (leg.toName != null && !leg.toName.isEmpty()
                    && !"Destination".equals(leg.toName)) sec += " → " + leg.toName;
            vh.secondary.setText(sec);
        } else {
            vh.badge.setVisibility(View.VISIBLE);
            vh.badge.setText(leg.routeShortName == null || leg.routeShortName.isEmpty()
                    ? "?" : leg.routeShortName);
            vh.badge.setBackgroundTintList(ColorStateList.valueOf(TransitAdapter.modeColor(ctx, leg.mode)));
            String head = leg.headsign == null || leg.headsign.isEmpty() ? "" : " → " + leg.headsign;
            vh.primary.setText(modeWord(leg.mode) + head);
            String stop = stopInfo(leg);
            String sec = (leg.fromName == null ? "" : leg.fromName)
                    + (stop.isEmpty() ? "" : " (" + stop + ")")
                    + "  ·  " + minutes(leg.durationSec);
            vh.secondary.setText(sec);
        }
    }

    /** Lähtöpaikan tarkenne reitin osaan: laituri (juna/metro) tai pysäkkikoodi, jos tiedossa. */
    private static String stopInfo(Leg leg) {
        if (leg.fromPlatform != null && !leg.fromPlatform.isEmpty()) return "laituri " + leg.fromPlatform;
        if (leg.fromStopCode != null && !leg.fromStopCode.isEmpty()) return "pysäkki " + leg.fromStopCode;
        return "";
    }

    // --- Apurit ---

    private static String clock(long epochMs) {
        return epochMs > 0 ? CLOCK.format(new Date(epochMs)) : "";
    }

    private static String minutes(int sec) {
        int min = Math.max(1, Math.round(sec / 60f));
        return min + " min";
    }

    private static String distance(int meters) {
        if (meters >= 1000) return String.format(FI, "%.1f km", meters / 1000.0);
        return meters + " m";
    }

    private static String transfersText(int n) {
        if (n <= 0) return "suora yhteys";
        return n == 1 ? "1 vaihto" : n + " vaihtoa";
    }

    private static String modeWord(String mode) {
        if ("TRAM".equals(mode)) return "Ratikka";
        if ("RAIL".equals(mode)) return "Juna";
        if ("SUBWAY".equals(mode)) return "Metro";
        if ("FERRY".equals(mode)) return "Lautta";
        if ("BUS".equals(mode)) return "Bussi";
        return mode == null ? "" : mode;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    static final class SuggestVH extends RecyclerView.ViewHolder {
        final TextView name, locality;
        SuggestVH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.geo_name);
            locality = v.findViewById(R.id.geo_locality);
        }
    }

    static final class ItineraryVH extends RecyclerView.ViewHolder {
        final TextView time, meta;
        final LinearLayout legs;
        ItineraryVH(@NonNull View v) {
            super(v);
            time = v.findViewById(R.id.itin_time);
            meta = v.findViewById(R.id.itin_meta);
            legs = v.findViewById(R.id.route_itin_legs);
        }
    }

    static final class LegVH extends RecyclerView.ViewHolder {
        final TextView time, badge, primary, secondary;
        LegVH(@NonNull View v) {
            super(v);
            time = v.findViewById(R.id.leg_time);
            badge = v.findViewById(R.id.leg_badge);
            primary = v.findViewById(R.id.leg_primary);
            secondary = v.findViewById(R.id.leg_secondary);
        }
    }
}
