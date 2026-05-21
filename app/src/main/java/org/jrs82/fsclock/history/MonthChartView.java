package org.jrs82.fsclock.history;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import org.jrs82.fsclock.db.DailyStat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MonthChartView extends View {

    private static final int COLOR_BG = Color.BLACK;
    private static final int COLOR_GRID = 0xFF303840;
    private static final int COLOR_AXIS = 0xFF707880;
    private static final int COLOR_MIN = 0xFF6FB3FF;
    private static final int COLOR_AVG = 0xFFFFD89B;
    private static final int COLOR_MAX = 0xFFFF7B6F;
    private static final int COLOR_BAND = 0x303076B0;

    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint minPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint maxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Map<Integer, DailyStat> byDay = new HashMap<>();
    private YearMonth month = YearMonth.now();

    public MonthChartView(Context ctx) { super(ctx); init(); }
    public MonthChartView(Context ctx, @Nullable AttributeSet a) { super(ctx, a); init(); }
    public MonthChartView(Context ctx, @Nullable AttributeSet a, int s) { super(ctx, a, s); init(); }

    private void init() {
        setBackgroundColor(COLOR_BG);
        gridPaint.setColor(COLOR_GRID);
        gridPaint.setStrokeWidth(dp(1));
        axisPaint.setColor(COLOR_AXIS);
        axisPaint.setStrokeWidth(dp(1));
        minPaint.setColor(COLOR_MIN);
        minPaint.setStyle(Paint.Style.STROKE);
        minPaint.setStrokeWidth(dp(2));
        avgPaint.setColor(COLOR_AVG);
        avgPaint.setStyle(Paint.Style.STROKE);
        avgPaint.setStrokeWidth(dp(2.5f));
        maxPaint.setColor(COLOR_MAX);
        maxPaint.setStyle(Paint.Style.STROKE);
        maxPaint.setStrokeWidth(dp(2));
        bandPaint.setColor(COLOR_BAND);
        bandPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(COLOR_AXIS);
        textPaint.setTextSize(dp(11));
    }

    public void setData(List<DailyStat> stats, YearMonth month) {
        this.month = month;
        byDay.clear();
        if (stats != null) {
            for (DailyStat s : stats) {
                try {
                    int day = LocalDate.parse(s.date).getDayOfMonth();
                    byDay.put(day, s);
                } catch (Exception ignored) {}
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float padL = dp(28);
        float padR = dp(8);
        float padT = dp(8);
        float padB = dp(20);
        float plotW = w - padL - padR;
        float plotH = h - padT - padB;

        int days = month.lengthOfMonth();

        if (byDay.isEmpty()) {
            canvas.drawLine(padL, h - padB, w - padR, h - padB, axisPaint);
            return;
        }

        double minVal = Double.POSITIVE_INFINITY;
        double maxVal = Double.NEGATIVE_INFINITY;
        for (DailyStat s : byDay.values()) {
            if (s.minTemp < minVal) minVal = s.minTemp;
            if (s.maxTemp > maxVal) maxVal = s.maxTemp;
        }
        if (minVal == maxVal) { minVal -= 1; maxVal += 1; }
        double pad = (maxVal - minVal) * 0.1;
        minVal -= pad;
        maxVal += pad;

        // Y-akselin viivat ja arvot (4 viivaa)
        for (int i = 0; i <= 4; i++) {
            float y = padT + plotH * i / 4f;
            canvas.drawLine(padL, y, w - padR, y, gridPaint);
            double v = maxVal - (maxVal - minVal) * i / 4.0;
            canvas.drawText(String.format(Locale.US, "%.0f", v), dp(2), y + dp(4), textPaint);
        }

        // X-akselin viikkomerkit (pe 7, 14, 21, 28)
        for (int d = 7; d <= days; d += 7) {
            float x = padL + plotW * (d - 0.5f) / days;
            canvas.drawLine(x, padT, x, h - padB, gridPaint);
            canvas.drawText(String.valueOf(d), x - dp(6), h - dp(4), textPaint);
        }

        // Min-max -kaista
        Path band = new Path();
        List<Integer> sortedDays = new ArrayList<>(byDay.keySet());
        java.util.Collections.sort(sortedDays);
        boolean first = true;
        for (Integer d : sortedDays) {
            DailyStat s = byDay.get(d);
            float x = xForDay(d, days, padL, plotW);
            float yMax = yForVal(s.maxTemp, minVal, maxVal, padT, plotH);
            if (first) { band.moveTo(x, yMax); first = false; }
            else band.lineTo(x, yMax);
        }
        for (int i = sortedDays.size() - 1; i >= 0; i--) {
            DailyStat s = byDay.get(sortedDays.get(i));
            float x = xForDay(sortedDays.get(i), days, padL, plotW);
            float yMin = yForVal(s.minTemp, minVal, maxVal, padT, plotH);
            band.lineTo(x, yMin);
        }
        band.close();
        canvas.drawPath(band, bandPaint);

        drawLine(canvas, sortedDays, days, padL, plotW, padT, plotH, minVal, maxVal, minPaint, ValueKind.MIN);
        drawLine(canvas, sortedDays, days, padL, plotW, padT, plotH, minVal, maxVal, maxPaint, ValueKind.MAX);
        drawLine(canvas, sortedDays, days, padL, plotW, padT, plotH, minVal, maxVal, avgPaint, ValueKind.AVG);

        canvas.drawLine(padL, h - padB, w - padR, h - padB, axisPaint);
        canvas.drawLine(padL, padT, padL, h - padB, axisPaint);
    }

    private enum ValueKind { MIN, AVG, MAX }

    private void drawLine(Canvas c, List<Integer> sortedDays, int days,
                          float padL, float plotW, float padT, float plotH,
                          double minVal, double maxVal, Paint paint, ValueKind kind) {
        Path p = new Path();
        boolean first = true;
        for (Integer d : sortedDays) {
            DailyStat s = byDay.get(d);
            double v;
            switch (kind) {
                case MIN: v = s.minTemp; break;
                case MAX: v = s.maxTemp; break;
                default:  v = s.avgTemp; break;
            }
            float x = xForDay(d, days, padL, plotW);
            float y = yForVal(v, minVal, maxVal, padT, plotH);
            if (first) { p.moveTo(x, y); first = false; }
            else p.lineTo(x, y);
        }
        c.drawPath(p, paint);
    }

    private float xForDay(int day, int days, float padL, float plotW) {
        return padL + plotW * (day - 0.5f) / days;
    }

    private float yForVal(double v, double minVal, double maxVal, float padT, float plotH) {
        return (float) (padT + plotH * (maxVal - v) / (maxVal - minVal));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
