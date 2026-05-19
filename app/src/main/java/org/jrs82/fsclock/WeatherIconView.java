package org.jrs82.fsclock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class WeatherIconView extends View {

    public static final int SUNNY = 1;
    public static final int PARTLY_CLOUDY = 2;
    public static final int CLOUDY = 3;
    public static final int RAIN = 4;
    public static final int SNOW = 5;
    public static final int SLEET = 6;
    public static final int FOG = 7;
    public static final int THUNDER = 8;
    public static final int NIGHT_CLEAR = 9;
    public static final int NIGHT_CLOUDY = 10;

    private int symbol = SUNNY;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public WeatherIconView(Context c) { super(c); }
    public WeatherIconView(Context c, AttributeSet a) { super(c, a); }
    public WeatherIconView(Context c, AttributeSet a, int d) { super(c, a, d); }

    public void setSymbol(int s) {
        if (s != symbol) {
            symbol = s;
            invalidate();
        }
    }

    public int getSymbol() { return symbol; }

    /** WeatherCondition -> sisäinen piirtosymboli. */
    public void setCondition(WeatherCondition c) {
        setSymbol(mapCondition(c));
    }

    public static int mapCondition(WeatherCondition c) {
        if (c == null) return CLOUDY;
        switch (c.type) {
            case CLEAR: return c.isNight ? NIGHT_CLEAR : SUNNY;
            case PARTLY_CLOUDY: return c.isNight ? NIGHT_CLOUDY : PARTLY_CLOUDY;
            case CLOUDY: return CLOUDY;
            case RAIN: return RAIN;
            case SNOW: return SNOW;
            case SLEET: return SLEET;
            case FOG: return FOG;
            case THUNDER: return THUNDER;
            case UNKNOWN:
            default:
                return CLOUDY;
        }
    }

    /** Karkea Helsinki-Vantaan auringonnousu/lasku kuukausittain (paikallista aikaa).
     *  Käytetään fallbackina jos SmartSymbol ei kerro yötä. */
    public static boolean isNightHour(int hour, int monthOneBased) {
        int m = monthOneBased - 1;
        if (m < 0) m = 0;
        if (m > 11) m = 11;
        int[] sr = {9, 8, 7, 6, 5, 4, 4, 5, 6, 7, 8, 9};
        int[] ss = {15, 17, 18, 20, 22, 23, 23, 22, 20, 18, 16, 15};
        return hour < sr[m] || hour >= ss[m];
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float size = Math.min(w, h);
        float cx = w / 2f;
        float cy = h / 2f;

        switch (symbol) {
            case SUNNY: drawSun(canvas, cx, cy, size); break;
            case NIGHT_CLEAR: drawMoon(canvas, cx, cy, size); break;
            case PARTLY_CLOUDY: drawSunCloud(canvas, cx, cy, size); break;
            case NIGHT_CLOUDY: drawMoonCloud(canvas, cx, cy, size); break;
            case CLOUDY: drawCloud(canvas, cx, cy, size, 0xFFB0B0B0); break;
            case RAIN: drawRain(canvas, cx, cy, size); break;
            case SNOW: drawSnow(canvas, cx, cy, size); break;
            case SLEET: drawSleet(canvas, cx, cy, size); break;
            case FOG: drawFog(canvas, cx, cy, size); break;
            case THUNDER: drawThunder(canvas, cx, cy, size); break;
        }
    }

    private void drawSun(Canvas c, float cx, float cy, float s) {
        paint.setColor(0xFFFFD700);
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, s * 0.22f, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(s * 0.04f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float r1 = s * 0.30f;
        float r2 = s * 0.42f;
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            float x1 = (float) (cx + Math.cos(a) * r1);
            float y1 = (float) (cy + Math.sin(a) * r1);
            float x2 = (float) (cx + Math.cos(a) * r2);
            float y2 = (float) (cy + Math.sin(a) * r2);
            c.drawLine(x1, y1, x2, y2, paint);
        }
    }

    private void drawMoon(Canvas c, float cx, float cy, float s) {
        paint.setColor(0xFFE0E0E0);
        paint.setStyle(Paint.Style.FILL);
        path.reset();
        float r = s * 0.30f;
        path.addCircle(cx + s * 0.05f, cy, r, Path.Direction.CW);
        Path mask = new Path();
        mask.addCircle(cx + s * 0.18f, cy - s * 0.06f, r * 0.95f, Path.Direction.CW);
        path.op(mask, Path.Op.DIFFERENCE);
        c.drawPath(path, paint);
    }

    private void drawCloud(Canvas c, float cx, float cy, float s, int color) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        // pilvi - 3 ympyraa + alapohja suorakaide
        float r1 = s * 0.18f;
        float r2 = s * 0.22f;
        float r3 = s * 0.16f;
        float by = cy + s * 0.05f;
        c.drawCircle(cx - s * 0.18f, by + s * 0.04f, r1, paint);
        c.drawCircle(cx + s * 0.02f, by - s * 0.05f, r2, paint);
        c.drawCircle(cx + s * 0.20f, by + s * 0.02f, r3, paint);
        RectF base = new RectF(cx - s * 0.30f, by, cx + s * 0.30f, by + s * 0.15f);
        c.drawRoundRect(base, s * 0.08f, s * 0.08f, paint);
    }

    private void drawSunCloud(Canvas c, float cx, float cy, float s) {
        // aurinko taakse vasemmalle ylos
        float scx = cx - s * 0.15f;
        float scy = cy - s * 0.18f;
        paint.setColor(0xFFFFD700);
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(scx, scy, s * 0.16f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(s * 0.03f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            float x1 = (float) (scx + Math.cos(a) * s * 0.22f);
            float y1 = (float) (scy + Math.sin(a) * s * 0.22f);
            float x2 = (float) (scx + Math.cos(a) * s * 0.30f);
            float y2 = (float) (scy + Math.sin(a) * s * 0.30f);
            c.drawLine(x1, y1, x2, y2, paint);
        }
        // pilvi etualalle
        drawCloud(c, cx + s * 0.05f, cy + s * 0.08f, s * 0.9f, 0xFFD0D0D0);
    }

    private void drawMoonCloud(Canvas c, float cx, float cy, float s) {
        drawMoon(c, cx - s * 0.15f, cy - s * 0.15f, s * 0.7f);
        drawCloud(c, cx + s * 0.05f, cy + s * 0.08f, s * 0.9f, 0xFFB0B0B0);
    }

    private void drawRain(Canvas c, float cx, float cy, float s) {
        drawCloud(c, cx, cy - s * 0.12f, s, 0xFFA0A0A0);
        paint.setColor(0xFF4FA8E0);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(s * 0.04f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float dy = cy + s * 0.18f;
        for (int i = 0; i < 4; i++) {
            float x = cx + (i - 1.5f) * s * 0.10f;
            c.drawLine(x + s * 0.04f, dy, x - s * 0.02f, dy + s * 0.18f, paint);
        }
    }

    private void drawSnow(Canvas c, float cx, float cy, float s) {
        drawCloud(c, cx, cy - s * 0.12f, s, 0xFFC0C0C0);
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(s * 0.03f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float dy = cy + s * 0.22f;
        for (int i = 0; i < 3; i++) {
            float x = cx + (i - 1) * s * 0.14f;
            float r = s * 0.05f;
            c.drawLine(x - r, dy, x + r, dy, paint);
            c.drawLine(x, dy - r, x, dy + r, paint);
            c.drawLine(x - r * 0.7f, dy - r * 0.7f, x + r * 0.7f, dy + r * 0.7f, paint);
            c.drawLine(x - r * 0.7f, dy + r * 0.7f, x + r * 0.7f, dy - r * 0.7f, paint);
        }
    }

    private void drawSleet(Canvas c, float cx, float cy, float s) {
        drawCloud(c, cx, cy - s * 0.12f, s, 0xFFA8A8B0);
        paint.setStrokeWidth(s * 0.04f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        float dy = cy + s * 0.18f;
        for (int i = 0; i < 3; i++) {
            float x = cx + (i - 1) * s * 0.14f;
            if (i % 2 == 0) {
                paint.setColor(0xFF4FA8E0);
                c.drawLine(x + s * 0.04f, dy, x - s * 0.02f, dy + s * 0.18f, paint);
            } else {
                paint.setColor(0xFFFFFFFF);
                float r = s * 0.05f;
                c.drawLine(x - r, dy + s * 0.09f, x + r, dy + s * 0.09f, paint);
                c.drawLine(x, dy + s * 0.04f, x, dy + s * 0.14f, paint);
            }
        }
    }

    private void drawFog(Canvas c, float cx, float cy, float s) {
        paint.setColor(0xFFB0B0B0);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(s * 0.06f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(cx - s * 0.30f, cy - s * 0.20f, cx + s * 0.30f, cy - s * 0.20f, paint);
        c.drawLine(cx - s * 0.35f, cy - s * 0.05f, cx + s * 0.25f, cy - s * 0.05f, paint);
        c.drawLine(cx - s * 0.25f, cy + s * 0.10f, cx + s * 0.35f, cy + s * 0.10f, paint);
        c.drawLine(cx - s * 0.30f, cy + s * 0.25f, cx + s * 0.20f, cy + s * 0.25f, paint);
    }

    private void drawThunder(Canvas c, float cx, float cy, float s) {
        drawCloud(c, cx, cy - s * 0.12f, s, 0xFF707080);
        paint.setColor(0xFFFFD700);
        paint.setStyle(Paint.Style.FILL);
        path.reset();
        float bx = cx;
        float by = cy + s * 0.10f;
        path.moveTo(bx + s * 0.02f, by);
        path.lineTo(bx - s * 0.10f, by + s * 0.16f);
        path.lineTo(bx - s * 0.02f, by + s * 0.16f);
        path.lineTo(bx - s * 0.08f, by + s * 0.30f);
        path.lineTo(bx + s * 0.10f, by + s * 0.10f);
        path.lineTo(bx + s * 0.02f, by + s * 0.10f);
        path.close();
        c.drawPath(path, paint);
    }
}
