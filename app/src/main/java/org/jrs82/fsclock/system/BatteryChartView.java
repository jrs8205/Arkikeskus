package org.jrs82.fsclock.system;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import org.jrs82.fsclock.db.WeatherSample;

import java.util.ArrayList;
import java.util.List;

public class BatteryChartView extends View {

    private static final int COLOR_BG = 0xFF000000;
    private static final int COLOR_GRID = 0xFF202830;
    private static final int COLOR_AXIS_TEXT = 0xFF808890;
    private static final int COLOR_LINE = 0xFFFFD89B;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<WeatherSample> samples = new ArrayList<>();
    private long rangeStartMs = 0;
    private long rangeEndMs = 0;

    public BatteryChartView(Context context) { super(context); init(); }
    public BatteryChartView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public BatteryChartView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle); init();
    }

    private void init() {
        gridPaint.setColor(COLOR_GRID);
        gridPaint.setStrokeWidth(dp(1));
        gridPaint.setStyle(Paint.Style.STROKE);

        axisTextPaint.setColor(COLOR_AXIS_TEXT);
        axisTextPaint.setTextSize(sp(11));

        linePaint.setColor(COLOR_LINE);
        linePaint.setStrokeWidth(dp(2));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setData(List<WeatherSample> samples, long rangeStartMs, long rangeEndMs) {
        this.samples = (samples != null) ? samples : new ArrayList<>();
        this.rangeStartMs = rangeStartMs;
        this.rangeEndMs = rangeEndMs;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();

        if (samples.isEmpty() || rangeEndMs <= rangeStartMs) return;

        float paddingLeft = dp(40);
        float paddingRight = dp(8);
        float paddingTop = dp(8);
        float paddingBottom = dp(20);

        float plotW = w - paddingLeft - paddingRight;
        float plotH = h - paddingTop - paddingBottom;
        if (plotW <= 0 || plotH <= 0) return;

        double minT = Double.POSITIVE_INFINITY;
        double maxT = Double.NEGATIVE_INFINITY;
        for (WeatherSample s : samples) {
            if (s.temperature < minT) minT = s.temperature;
            if (s.temperature > maxT) maxT = s.temperature;
        }
        if (maxT - minT < 1.0) {
            double mid = (maxT + minT) / 2.0;
            minT = mid - 0.5;
            maxT = mid + 0.5;
        }
        double pad = (maxT - minT) * 0.1;
        minT -= pad;
        maxT += pad;
        double rangeT = maxT - minT;

        // Y-axis labels (3 viivaa)
        for (int i = 0; i <= 3; i++) {
            float y = paddingTop + plotH - (plotH * i / 3f);
            double t = minT + (rangeT * i / 3.0);
            canvas.drawLine(paddingLeft, y, paddingLeft + plotW, y, gridPaint);
            String label = String.format(java.util.Locale.ROOT, "%.1f°", t);
            canvas.drawText(label, dp(2), y + sp(4), axisTextPaint);
        }

        // X-axis labels (now-24h, now-12h, now)
        long rangeMs = rangeEndMs - rangeStartMs;
        for (int i = 0; i <= 4; i++) {
            float x = paddingLeft + (plotW * i / 4f);
            canvas.drawLine(x, paddingTop, x, paddingTop + plotH, gridPaint);
            int hoursAgo = 24 - (i * 6);
            String label = (hoursAgo == 0) ? "nyt" : "-" + hoursAgo + "h";
            canvas.drawText(label, x - dp(10), h - dp(4), axisTextPaint);
        }

        // Viiva
        Path path = new Path();
        boolean first = true;
        for (WeatherSample s : samples) {
            float x = paddingLeft + (float) ((s.timestamp - rangeStartMs) / (double) rangeMs * plotW);
            float y = paddingTop + (float) ((maxT - s.temperature) / rangeT * plotH);
            if (first) { path.moveTo(x, y); first = false; }
            else path.lineTo(x, y);
        }
        canvas.drawPath(path, linePaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
