package org.jrs82.fsclock.mobile;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.jrs82.fsclock.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Etusivun widgettien järjestely raahaamalla (drag & drop) sekä näkyvyyden
 * valinta checkboxilla. Korvaa aiemman ylös/alas-nappidialogin. Lista sisältää
 * kiinteät widgetit, yhdistetyn uutiswidgetin ja jokaisen uutislähteen
 * per-lähde-widgettinä.
 */
public class MobileWidgetOrderActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private final List<Row> rows = new ArrayList<>();
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        MobileThemeController.apply(this);
        super.onCreate(savedInstanceState);
        setTitle(R.string.mobile_pref_widget_order);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        RecyclerView recycler = new RecyclerView(this);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setBackgroundColor(themeColor(android.R.attr.colorBackground, Color.BLACK));
        int pad = dp(8);
        recycler.setPadding(pad, pad, pad, pad);
        recycler.setClipToPadding(false);
        setContentView(recycler);

        buildRows();
        adapter = new Adapter();
        recycler.setAdapter(adapter);

        touchHelper = new ItemTouchHelper(new DragCallback());
        touchHelper.attachToRecyclerView(recycler);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void buildRows() {
        rows.clear();
        for (String id : loadOrder()) {
            rows.add(new Row(id, widgetTitle(id), isVisible(id), hasToggle(id)));
        }
    }

    /** Tallennettu järjestys + kaikki tunnetut widgetit jotka puuttuvat. */
    private List<String> loadOrder() {
        List<String> order = new ArrayList<>();
        String raw = prefs.getString(MobileThemeController.KEY_WIDGET_ORDER,
                MobileThemeController.DEFAULT_WIDGET_ORDER);
        if (raw != null) {
            for (String token : raw.split(",")) {
                String id = token.trim();
                if (isKnown(id) && !order.contains(id)) order.add(id);
            }
        }
        for (String id : allKnownWidgets()) {
            if (!order.contains(id)) order.add(id);
        }
        return order;
    }

    private List<String> allKnownWidgets() {
        List<String> all = new ArrayList<>();
        all.add(MobileThemeController.WIDGET_WEATHER);
        all.add(MobileThemeController.WIDGET_ELECTRICITY);
        all.add(MobileThemeController.WIDGET_WARNINGS);
        all.add(MobileThemeController.WIDGET_SENSORS);
        all.add(MobileThemeController.WIDGET_TRAFFIC);
        all.add(MobileThemeController.WIDGET_GPS_SPEED);
        all.add(MobileThemeController.WIDGET_ROAD_CAMERAS);
        all.add(MobileThemeController.WIDGET_NEWS);
        for (NewsFeed feed : NewsFeedStore.allFeeds(prefs)) {
            all.add(feed.widgetId());
        }
        return all;
    }

    private boolean isKnown(String id) {
        if (MobileThemeController.isNewsFeedWidget(id)) {
            String feedId = MobileThemeController.newsFeedIdFromWidget(id);
            return NewsFeedStore.feedById(prefs, feedId) != null;
        }
        return allKnownWidgets().contains(id);
    }

    private void persist() {
        StringBuilder order = new StringBuilder();
        SharedPreferences.Editor editor = prefs.edit();
        for (Row r : rows) {
            if (order.length() > 0) order.append(',');
            order.append(r.id);
            String key = visibilityKey(r.id);
            if (key != null) editor.putBoolean(key, r.visible);
        }
        editor.putString(MobileThemeController.KEY_WIDGET_ORDER, order.toString());
        editor.apply();
    }

    // ---- Näkyvyys ----

    /** Sää on aina näkyvissä → ei togglea. Muilla on. */
    private boolean hasToggle(String id) {
        return !MobileThemeController.WIDGET_WEATHER.equals(id);
    }

    private String visibilityKey(String id) {
        if (MobileThemeController.WIDGET_ELECTRICITY.equals(id)) {
            return MobileThemeController.KEY_SHOW_ELECTRICITY_WIDGET;
        }
        if (MobileThemeController.WIDGET_WARNINGS.equals(id)) {
            return MobileThemeController.KEY_SHOW_WARNINGS_WIDGET;
        }
        if (MobileThemeController.WIDGET_SENSORS.equals(id)) {
            return MobileThemeController.KEY_SHOW_SENSORS_WIDGET;
        }
        if (MobileThemeController.WIDGET_TRAFFIC.equals(id)) {
            return MobileThemeController.KEY_SHOW_TRAFFIC_WIDGET;
        }
        if (MobileThemeController.WIDGET_GPS_SPEED.equals(id)) {
            return MobileThemeController.KEY_SHOW_GPS_SPEED_WIDGET;
        }
        if (MobileThemeController.WIDGET_ROAD_CAMERAS.equals(id)) {
            return MobileThemeController.KEY_SHOW_ROAD_CAMERAS_WIDGET;
        }
        if (MobileThemeController.WIDGET_NEWS.equals(id)) {
            return MobileThemeController.KEY_SHOW_NEWS_WIDGET;
        }
        if (MobileThemeController.isNewsFeedWidget(id)) {
            return MobileThemeController.newsFeedVisibilityKey(
                    MobileThemeController.newsFeedIdFromWidget(id));
        }
        return null;
    }

    private boolean defaultVisible(String id) {
        if (MobileThemeController.WIDGET_GPS_SPEED.equals(id)
                || MobileThemeController.WIDGET_ROAD_CAMERAS.equals(id)
                || MobileThemeController.isNewsFeedWidget(id)) {
            return false;
        }
        return true; // weather, electricity, warnings, sensors, traffic, news
    }

    private boolean isVisible(String id) {
        String key = visibilityKey(id);
        if (key == null) return true; // weather
        return prefs.getBoolean(key, defaultVisible(id));
    }

    private String widgetTitle(String id) {
        if (MobileThemeController.WIDGET_WEATHER.equals(id)) return getString(R.string.mobile_widget_weather);
        if (MobileThemeController.WIDGET_ELECTRICITY.equals(id)) return getString(R.string.mobile_widget_electricity);
        if (MobileThemeController.WIDGET_WARNINGS.equals(id)) return getString(R.string.mobile_widget_warnings);
        if (MobileThemeController.WIDGET_SENSORS.equals(id)) return getString(R.string.mobile_widget_sensors);
        if (MobileThemeController.WIDGET_TRAFFIC.equals(id)) return getString(R.string.mobile_widget_traffic);
        if (MobileThemeController.WIDGET_GPS_SPEED.equals(id)) return getString(R.string.mobile_widget_gps_speed);
        if (MobileThemeController.WIDGET_ROAD_CAMERAS.equals(id)) return getString(R.string.mobile_widget_road_cameras);
        if (MobileThemeController.WIDGET_NEWS.equals(id)) return getString(R.string.mobile_widget_news);
        if (MobileThemeController.isNewsFeedWidget(id)) {
            NewsFeed feed = NewsFeedStore.feedById(prefs,
                    MobileThemeController.newsFeedIdFromWidget(id));
            String name = feed != null ? feed.name : id;
            return getString(R.string.mobile_widget_news) + ": " + name;
        }
        return id;
    }

    private int themeColor(int attr, int fallback) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(attr, tv, true)) return tv.data;
        return fallback;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---- Data ----

    private static final class Row {
        final String id;
        final String title;
        boolean visible;
        final boolean hasToggle;

        Row(String id, String title, boolean visible, boolean hasToggle) {
            this.id = id;
            this.title = title;
            this.visible = visible;
            this.hasToggle = hasToggle;
        }
    }

    // ---- Adapter ----

    private final class Adapter extends RecyclerView.Adapter<VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(MobileWidgetOrderActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.mobile_card_bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            lp.bottomMargin = dp(6);
            row.setLayoutParams(lp);

            ImageView handle = new ImageView(MobileWidgetOrderActivity.this);
            handle.setImageResource(R.drawable.mobile_ic_drag_handle_24);
            handle.setColorFilter(ContextCompat.getColor(
                    MobileWidgetOrderActivity.this, R.color.mobile_text_muted));
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(28), dp(28));
            hlp.setMarginEnd(dp(12));
            handle.setLayoutParams(hlp);
            row.addView(handle);

            TextView title = new TextView(MobileWidgetOrderActivity.this);
            title.setTextColor(ContextCompat.getColor(
                    MobileWidgetOrderActivity.this, R.color.mobile_text_primary));
            title.setTextSize(16f);
            title.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(title);

            CheckBox check = new CheckBox(MobileWidgetOrderActivity.this);
            row.addView(check);

            return new VH(row, handle, title, check);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Row row = rows.get(position);
            holder.title.setText(row.title);
            holder.check.setOnCheckedChangeListener(null);
            holder.check.setChecked(row.visible);
            holder.check.setEnabled(row.hasToggle);
            holder.check.setVisibility(row.hasToggle ? View.VISIBLE : View.INVISIBLE);
            holder.check.setOnCheckedChangeListener((buttonView, isChecked) -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                rows.get(pos).visible = isChecked;
                persist();
            });
            holder.handle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    dragStarter.startDrag(holder);
                }
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static final class VH extends RecyclerView.ViewHolder {
        final ImageView handle;
        final TextView title;
        final CheckBox check;

        VH(@NonNull View itemView, ImageView handle, TextView title, CheckBox check) {
            super(itemView);
            this.handle = handle;
            this.title = title;
            this.check = check;
        }
    }

    // ItemTouchHelper-callback drag-handlen käynnistämiseen.
    private interface DragStarter { void startDrag(RecyclerView.ViewHolder vh); }
    private DragStarter dragStarter = vh -> { };

    private final class DragCallback extends ItemTouchHelper.Callback {
        DragCallback() {
            dragStarter = MobileWidgetOrderActivity.this::beginDrag;
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false; // raahaus vain kahvasta
        }

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView,
                                    @NonNull RecyclerView.ViewHolder viewHolder) {
            return makeMovementFlags(
                    ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder,
                              @NonNull RecyclerView.ViewHolder target) {
            int from = viewHolder.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false;
            Collections.swap(rows, from, to);
            adapter.notifyItemMoved(from, to);
            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) { }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            persist();
        }
    }

    private ItemTouchHelper touchHelper;

    private void beginDrag(RecyclerView.ViewHolder vh) {
        if (touchHelper != null) touchHelper.startDrag(vh);
    }
}
