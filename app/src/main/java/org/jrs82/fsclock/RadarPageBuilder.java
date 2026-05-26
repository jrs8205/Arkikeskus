package org.jrs82.fsclock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
    private static final int IMG_WIDTH = 640;
    private static final int IMG_HEIGHT = 560;
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

    private Bitmap backgroundMap;
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
        loadingText.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                ctx.getResources().getDimension(R.dimen.page_body_large_text_size));
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
        currentTimeLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                ctx.getResources().getDimension(R.dimen.page_body_text_size));
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
                    Bitmap raw = client.fetchFrame(times[i], IMG_WIDTH, IMG_HEIGHT);
                    bitmaps[i] = compositeFrame(raw);
                    raw.recycle();
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
        playPauseButton.setImageResource(playing ? R.drawable.ic_pause_24 : R.drawable.ic_play_24);
        playPauseButton.setContentDescription(playing ? "Tauko" : "Toista");
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

    private Bitmap compositeFrame(Bitmap radar) {
        if (backgroundMap == null) backgroundMap = createBackgroundMap();
        Bitmap out = Bitmap.createBitmap(IMG_WIDTH, IMG_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(backgroundMap, 0, 0, null);
        c.drawBitmap(radar, 0, 0, null);
        return out;
    }

    private Bitmap createBackgroundMap() {
        Bitmap bmp = Bitmap.createBitmap(IMG_WIDTH, IMG_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF0A0A1E);

        // BBOX: lon 17.5–33.5, lat 58.0–72.0
        float bboxMinLon = 17.5f, bboxMaxLon = 33.5f;
        float bboxMinLat = 58.0f, bboxMaxLat = 72.0f;

        Paint landPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        landPaint.setColor(0xFF1A1A2E);
        landPaint.setStyle(Paint.Style.FILL);

        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(0xFF333355);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.5f);

        // Suomen ääriviiva (yksinkertaistettu)
        float[][] finland = {
            {22.8f,59.8f}, {24.0f,59.8f}, {25.5f,60.1f}, {27.0f,60.5f},
            {28.5f,61.0f}, {29.5f,61.5f}, {30.0f,62.0f}, {31.0f,62.5f},
            {30.5f,63.0f}, {30.0f,63.5f}, {30.5f,64.0f}, {30.0f,64.5f},
            {29.5f,65.0f}, {30.0f,65.5f}, {29.5f,66.0f}, {29.0f,66.5f},
            {29.5f,67.0f}, {29.0f,67.5f}, {28.5f,68.0f}, {28.0f,68.5f},
            {28.5f,69.0f}, {29.0f,69.5f}, {29.5f,70.0f},
            {28.0f,69.5f}, {27.0f,69.5f}, {26.0f,69.1f}, {25.5f,68.9f},
            {24.0f,68.6f}, {23.5f,68.0f}, {23.0f,67.5f},
            {23.5f,66.5f}, {24.0f,66.0f}, {23.5f,65.5f},
            {23.0f,65.0f}, {22.0f,64.5f}, {21.5f,64.0f},
            {21.0f,63.5f}, {21.5f,63.0f}, {21.5f,62.5f},
            {21.0f,62.0f}, {21.5f,61.5f}, {21.5f,61.0f},
            {22.0f,60.5f}, {22.5f,60.0f}, {22.8f,59.8f}
        };

        Path path = new Path();
        for (int i = 0; i < finland.length; i++) {
            float px = (finland[i][0] - bboxMinLon) / (bboxMaxLon - bboxMinLon) * IMG_WIDTH;
            float py = (bboxMaxLat - finland[i][1]) / (bboxMaxLat - bboxMinLat) * IMG_HEIGHT;
            if (i == 0) path.moveTo(px, py);
            else path.lineTo(px, py);
        }
        path.close();
        c.drawPath(path, landPaint);
        c.drawPath(path, borderPaint);

        return bmp;
    }

    private int dp(int v) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }
}
