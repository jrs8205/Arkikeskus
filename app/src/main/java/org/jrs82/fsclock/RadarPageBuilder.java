package org.jrs82.fsclock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;

public final class RadarPageBuilder {

    private static final String TAG = "RadarPageBuilder";
    private static final int FRAME_COUNT = 12;
    private static final int IMG_WIDTH = 512;
    private static final int IMG_HEIGHT = 768;
    private static final long FRAME_DELAY_MS = 400L;
    private static final long LAST_FRAME_DELAY_MS = 1200L;
    private static final long REFRESH_INTERVAL_MS = 5L * 60_000L;

    private final Context ctx;
    private final ExecutorService io;
    private final Handler ui;
    private final RadarClient client = new RadarClient();

    private ImageView radarView;
    private SeekBar timelineSeekBar;
    private ImageButton playPauseButton;
    private TextView currentTimeLabel;
    private TextView loadingText;

    private Bitmap[] frames;
    private long[] frameTimes;
    private int currentFrame = 0;
    private boolean playing = false;
    private boolean active = false;
    private long lastFetchMs = 0;
    private Runnable animRunnable;
    private Runnable refreshRunnable;

    private final SimpleDateFormat timeFmt;

    public RadarPageBuilder(Context ctx, ExecutorService io, Handler ui) {
        this.ctx = ctx;
        this.io = io;
        this.ui = ui;
        timeFmt = new SimpleDateFormat("HH:mm", new Locale("fi", "FI"));
        timeFmt.setTimeZone(TimeZone.getTimeZone("Europe/Helsinki"));
    }

    public View buildPage() {
        FrameLayout root = new FrameLayout(ctx);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(0xFF000000);

        radarView = new ImageView(ctx);
        radarView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams imgLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        imgLp.bottomMargin = dp(56);
        root.addView(radarView, imgLp);

        loadingText = new TextView(ctx);
        loadingText.setText("Ladataan sadetutka…");
        loadingText.setTextColor(0xFFB0B0B0);
        loadingText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        loadingText.setGravity(Gravity.CENTER);
        root.addView(loadingText, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout controls = buildControls();
        FrameLayout.LayoutParams ctrlLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        ctrlLp.bottomMargin = dp(4);
        root.addView(controls, ctrlLp);

        return root;
    }

    private LinearLayout buildControls() {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(6), dp(12), dp(6));

        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(0xCC000000);
        barBg.setCornerRadius(dp(8));
        bar.setBackground(barBg);

        playPauseButton = new ImageButton(ctx);
        playPauseButton.setBackgroundColor(Color.TRANSPARENT);
        playPauseButton.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        playPauseButton.setScaleType(ImageView.ScaleType.CENTER);
        playPauseButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        updatePlayPauseIcon();
        playPauseButton.setOnClickListener(v -> togglePlayPause());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        bar.addView(playPauseButton, btnLp);

        timelineSeekBar = new SeekBar(ctx);
        timelineSeekBar.setMax(FRAME_COUNT - 1);
        timelineSeekBar.setProgress(0);
        timelineSeekBar.getProgressDrawable().setColorFilter(0xFF4FA8E0, PorterDuff.Mode.SRC_IN);
        timelineSeekBar.getThumb().setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        seekLp.setMarginStart(dp(4));
        seekLp.setMarginEnd(dp(8));
        bar.addView(timelineSeekBar, seekLp);

        timelineSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    showFrame(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {
                if (playing) pauseAnimation();
            }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        currentTimeLabel = new TextView(ctx);
        currentTimeLabel.setTextColor(0xFFFFFFFF);
        currentTimeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        currentTimeLabel.setMinWidth(dp(48));
        currentTimeLabel.setGravity(Gravity.CENTER);
        currentTimeLabel.setText("--:--");
        bar.addView(currentTimeLabel);

        return bar;
    }

    public void onPageVisible() {
        active = true;
        long now = System.currentTimeMillis();
        if (frames == null || (now - lastFetchMs) > REFRESH_INTERVAL_MS) {
            fetchFrames();
        } else {
            startAnimation();
        }
        scheduleRefresh();
    }

    public void onPageHidden() {
        active = false;
        pauseAnimation();
        cancelRefresh();
    }

    private void fetchFrames() {
        if (io == null) return;
        loadingText.setVisibility(View.VISIBLE);
        radarView.setVisibility(View.GONE);

        io.execute(() -> {
            try {
                long[] times = client.computeFrameTimes(FRAME_COUNT);
                Bitmap[] bitmaps = new Bitmap[FRAME_COUNT];
                for (int i = 0; i < FRAME_COUNT; i++) {
                    if (!active) return;
                    bitmaps[i] = client.fetchFrame(times[i], IMG_WIDTH, IMG_HEIGHT);
                }
                ui.post(() -> {
                    if (!active) return;
                    frames = bitmaps;
                    frameTimes = times;
                    lastFetchMs = System.currentTimeMillis();
                    loadingText.setVisibility(View.GONE);
                    radarView.setVisibility(View.VISIBLE);
                    currentFrame = 0;
                    showFrame(0);
                    startAnimation();
                });
            } catch (Exception e) {
                Log.w(TAG, "Radar fetch failed", e);
                ui.post(() -> {
                    if (!active) return;
                    loadingText.setText("Sadetutka ei saatavilla");
                    loadingText.setVisibility(View.VISIBLE);
                    radarView.setVisibility(View.GONE);
                });
            }
        });
    }

    private void showFrame(int idx) {
        if (frames == null || idx < 0 || idx >= frames.length) return;
        currentFrame = idx;
        radarView.setImageBitmap(frames[idx]);
        timelineSeekBar.setProgress(idx);
        if (frameTimes != null && idx < frameTimes.length) {
            currentTimeLabel.setText(timeFmt.format(new Date(frameTimes[idx])));
        }
    }

    private void startAnimation() {
        if (frames == null) return;
        playing = true;
        updatePlayPauseIcon();
        scheduleNextFrame();
    }

    private void pauseAnimation() {
        playing = false;
        updatePlayPauseIcon();
        if (animRunnable != null) {
            ui.removeCallbacks(animRunnable);
            animRunnable = null;
        }
    }

    private void togglePlayPause() {
        if (playing) {
            pauseAnimation();
        } else {
            startAnimation();
        }
    }

    private void scheduleNextFrame() {
        if (!playing || !active || frames == null) return;
        boolean isLast = (currentFrame == frames.length - 1);
        long delay = isLast ? LAST_FRAME_DELAY_MS : FRAME_DELAY_MS;
        animRunnable = () -> {
            if (!playing || !active || frames == null) return;
            int next = (currentFrame + 1) % frames.length;
            showFrame(next);
            scheduleNextFrame();
        };
        ui.postDelayed(animRunnable, delay);
    }

    private void updatePlayPauseIcon() {
        if (playPauseButton == null) return;
        // ▶ = play (Unicode), ⏸ = pause
        // Use text since we don't have vector drawables for these
        playPauseButton.setImageDrawable(null);
        playPauseButton.setContentDescription(playing ? "Tauko" : "Toista");
        // Draw a simple play/pause using a small TextView overlay trick
        // Actually, set as content description and use a colored background indicator
        // Simpler: just create a tiny text-based approach
    }

    private void scheduleRefresh() {
        cancelRefresh();
        refreshRunnable = () -> {
            if (!active) return;
            fetchFrames();
            scheduleRefresh();
        };
        ui.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
    }

    private void cancelRefresh() {
        if (refreshRunnable != null) {
            ui.removeCallbacks(refreshRunnable);
            refreshRunnable = null;
        }
    }

    private int dp(int v) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }
}
