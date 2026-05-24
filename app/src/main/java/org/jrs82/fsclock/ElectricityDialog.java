package org.jrs82.fsclock;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;

/** Sähkönhintojen popup-näkymä. Kaksi välilehteä: Tänään / Huomenna.
 *  Huomenna-välilehti hakee toistuvasti taustalla, kunnes NordPool julkaisee
 *  huomispäivän vartit (yleensä klo 14 EET tienoilla). */
public final class ElectricityDialog {

    private static final long POLL_INTERVAL_MS = 30L * 60_000L;

    private final Context ctx;
    private final ElectricityRepository repo;
    private final ExecutorService io;

    private AlertDialog dialog;
    private TextView tabTodayBtn, tabTomorrowBtn;
    private LinearLayout contentContainer;
    private ScrollView scrollView;
    private View currentRow;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable pollRunnable;
    private boolean showingTomorrow = false;
    /** Estää useamman samanaikaisen verkkokutsun: triggerFetch tarkistaa tämän. */
    private boolean fetchInFlight = false;
    /** Per-välilehti lippu: kunkin tabin ensimmäinen tyhjyysrender saa pyrkiä
     *  fetchaan kerran. Sen jälkeen luotetaan pollRunnableen / ClockControllerin
     *  taustahakuun. Estää renderActiveTab→triggerFetch→render-silmukan. */
    private boolean firedFetchForToday = false;
    private boolean firedFetchForTomorrow = false;

    public ElectricityDialog(Context ctx, ElectricityRepository repo, ExecutorService io) {
        this.ctx = ctx;
        this.repo = repo;
        this.io = io;
    }

    public void show() {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        // Välilehtirivi
        LinearLayout tabs = new LinearLayout(ctx);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabTodayBtn = buildTab("Tänään");
        tabTomorrowBtn = buildTab("Huomenna");
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tabs.addView(tabTodayBtn, tp);
        tabs.addView(tabTomorrowBtn, new LinearLayout.LayoutParams(tp));
        tabTodayBtn.setOnClickListener(v -> selectTab(false));
        tabTomorrowBtn.setOnClickListener(v -> selectTab(true));
        root.addView(tabs);

        View divider = new View(ctx);
        divider.setBackgroundColor(0xFF404040);
        LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        root.addView(divider, dp1);

        scrollView = new ScrollView(ctx);
        contentContainer = new LinearLayout(ctx);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        contentContainer.setPadding(pad, pad, pad, pad);
        scrollView.addView(contentContainer);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scrollView, sp);

        dialog = new AlertDialog.Builder(ctx)
                .setTitle("Sähkönhinta")
                .setView(root)
                .setPositiveButton("Sulje", null)
                .setOnDismissListener(d -> stopTomorrowPolling())
                .create();
        dialog.show();

        selectTab(false);
    }

    private TextView buildTab(String label) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        tv.setBackgroundResource(android.R.drawable.list_selector_background);
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private void selectTab(boolean tomorrow) {
        showingTomorrow = tomorrow;
        if (tomorrow) {
            tabTodayBtn.setTextColor(0xFF808080);
            tabTomorrowBtn.setTextColor(0xFFFFFFFF);
        } else {
            tabTodayBtn.setTextColor(0xFFFFFFFF);
            tabTomorrowBtn.setTextColor(0xFF808080);
        }
        renderActiveTab();
    }

    private void renderActiveTab() {
        if (contentContainer == null) return;
        contentContainer.removeAllViews();

        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Europe/Helsinki"));
        if (showingTomorrow) c.add(Calendar.DAY_OF_YEAR, 1);
        int y = c.get(Calendar.YEAR);
        int m = c.get(Calendar.MONTH) + 1;
        int d = c.get(Calendar.DAY_OF_MONTH);
        List<ElectricityData.Quarter> qs = repo.dayQuarters(y, m, d);

        if (qs.isEmpty()) {
            if (showingTomorrow) {
                contentContainer.addView(buildEmptyText(
                        "Huomisen hinnat eivät vielä saatavilla.\nNordPool julkaisee yleensä klo 14.\nPäivitetään automaattisesti…"));
                if (!firedFetchForTomorrow) {
                    firedFetchForTomorrow = true;
                    triggerFetch();
                }
                ensureTomorrowPollScheduled();
            } else {
                contentContainer.addView(buildEmptyText("Ladataan…"));
                if (!firedFetchForToday) {
                    firedFetchForToday = true;
                    triggerFetch();
                }
            }
            return;
        }

        if (showingTomorrow) stopTomorrowPolling();
        addQuartersToContainer(contentContainer, qs, !showingTomorrow);
    }

    /** Ajastaa pollin vain jos sellainen ei jo ole pyörimässä. Ei tee
     *  välitöntä triggerFetch-kutsua — se tehdään vain renderActiveTab:n
     *  firedFetchForTomorrow-portin kautta. */
    private void ensureTomorrowPollScheduled() {
        if (pollRunnable != null) return;
        pollRunnable = new Runnable() {
            @Override public void run() {
                if (dialog == null || !dialog.isShowing()) return;
                triggerFetch();
                ui.postDelayed(this, POLL_INTERVAL_MS);
            }
        };
        ui.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private void stopTomorrowPolling() {
        if (pollRunnable != null) {
            ui.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
    }

    private void triggerFetch() {
        if (fetchInFlight) return;
        fetchInFlight = true;
        io.execute(() -> {
            try {
                repo.fetchNow();
            } catch (Exception ignored) { }
            ui.post(() -> {
                fetchInFlight = false;
                if (dialog == null || !dialog.isShowing()) return;
                renderActiveTab();
            });
        });
    }

    private void addQuartersToContainer(LinearLayout dest, List<ElectricityData.Quarter> qs,
                                        boolean highlightCurrent) {
        long now = System.currentTimeMillis();
        currentRow = null;
        for (ElectricityData.Quarter q : qs) {
            boolean isCurrent = highlightCurrent
                    && q.timestamp <= now && q.timestamp + 15L * 60_000L > now;

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int rowVPad = isCurrent ? dp(8) : dp(3);
            int rowHPad = isCurrent ? dp(8) : 0;
            row.setPadding(rowHPad, rowVPad, rowHPad, rowVPad);

            TextView time = new TextView(ctx);
            time.setText(String.format(Locale.US, "%02d:%02d", q.hour, q.minute));
            time.setTextColor(isCurrent ? 0xFFFFFFFF : 0xFFD0D0D0);
            time.setTextSize(TypedValue.COMPLEX_UNIT_SP, isCurrent ? 26 : 20);
            time.setTypeface(Typeface.MONOSPACE, isCurrent ? Typeface.BOLD : Typeface.NORMAL);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    dp(isCurrent ? 100 : 80), ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(time, tlp);

            TextView price = new TextView(ctx);
            price.setText(String.format(new Locale("fi", "FI"), "%.3f c/KWh", q.sntPerKwh));
            price.setTextSize(TypedValue.COMPLEX_UNIT_SP, isCurrent ? 28 : 22);
            price.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            price.setTextColor(priceColor(q.sntPerKwh));
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(price, pp);

            if (isCurrent) {
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(0x33FFFFFF);
                bg.setStroke(dp(2), 0xFFFFFFFF);
                bg.setCornerRadius(dp(6));
                row.setBackground(bg);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp(4);
                rlp.bottomMargin = dp(4);
                row.setLayoutParams(rlp);
                currentRow = row;
            }
            dest.addView(row);
        }

        // Scrollataan nykyiseen varttiin avauksen jälkeen
        if (currentRow != null && scrollView != null) {
            final View target = currentRow;
            scrollView.post(() -> {
                int y = target.getTop() - dp(24);
                if (y < 0) y = 0;
                scrollView.scrollTo(0, y);
            });
        }
    }

    private TextView buildEmptyText(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFFB0B0B0);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tv.setPadding(0, dp(8), 0, dp(8));
        return tv;
    }

    private int dp(int v) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }

    public static int priceColor(double sntPerKwh) {
        if (sntPerKwh < 7.0) return 0xFF66DD66;
        if (sntPerKwh < 10.0) return interp(0xFF66DD66, 0xFFEED344, sntPerKwh, 7.0, 10.0);
        if (sntPerKwh < 15.0) return interp(0xFFEED344, 0xFFEE9933, sntPerKwh, 10.0, 15.0);
        if (sntPerKwh < 20.0) return interp(0xFFEE9933, 0xFFEE4444, sntPerKwh, 15.0, 20.0);
        return 0xFFEE4444;
    }

    private static int interp(int a, int b, double v, double lo, double hi) {
        double t = (v - lo) / (hi - lo);
        if (t < 0) t = 0; else if (t > 1) t = 1;
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return Color.argb(255, r, g, bl);
    }
}
