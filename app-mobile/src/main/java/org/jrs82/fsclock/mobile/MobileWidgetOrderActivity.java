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
        setTitle("Etusivun kortit");
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

    /** Tallennettu järjestys + kaikki tunnetut etusivun kortit jotka puuttuvat. */
    private List<String> loadOrder() {
        List<String> order = new ArrayList<>();
        String raw = prefs.getString(ComposeHomeWidgetsKt.KEY_HOME_ORDER, null);
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

    /** Kaikki tunnetut etusivun kortit: kiinteät + per-lähde-uutiskortit (Kotlin-malli). */
    private List<String> allKnownWidgets() {
        return ComposeHomeWidgetsKt.allHomeWidgetIds(prefs);
    }

    private boolean isKnown(String id) {
        return id != null && allKnownWidgets().contains(id);
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
        editor.putString(ComposeHomeWidgetsKt.KEY_HOME_ORDER, order.toString());
        editor.apply();
    }

    // ---- Näkyvyys ----

    /** Kaikki etusivun kortit voi piilottaa. */
    private boolean hasToggle(String id) {
        return true;
    }

    private String visibilityKey(String id) {
        return ComposeHomeWidgetsKt.KEY_HOME_SHOW_PREFIX + id;
    }

    private boolean defaultVisible(String id) {
        return ComposeHomeWidgetsKt.defaultVisibleForId(id);
    }

    private boolean isVisible(String id) {
        return prefs.getBoolean(visibilityKey(id), defaultVisible(id));
    }

    private String widgetTitle(String id) {
        return ComposeHomeWidgetsKt.homeWidgetTitleForId(prefs, id);
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
