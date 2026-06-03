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
    public static final int PARTLY_RAIN = 11;
    public static final int PARTLY_SNOW = 12;
    public static final int PARTLY_SLEET = 13;
    public static final int NIGHT_PARTLY_RAIN = 14;
    public static final int NIGHT_PARTLY_SNOW = 15;
    public static final int NIGHT_PARTLY_SLEET = 16;
    public static final int MOSTLY_RAIN = 17;
    public static final int MOSTLY_SNOW = 18;
    public static final int MOSTLY_SLEET = 19;
    public static final int NIGHT_MOSTLY_RAIN = 20;
    public static final int NIGHT_MOSTLY_SNOW = 21;
    public static final int NIGHT_MOSTLY_SLEET = 22;

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
        // Ensisijaisesti rawSmartSymbol: erotellaan puolipilvinen sade/räntä/lumi
        // (21/31-33, 41-43, 51-53) vs. lähes-pilvinen / pilvinen sade jne. (34-39/44-49/54-59).
        if (c.rawSmartSymbol != null && c.rawSmartSymbol > 0) {
            int ss = c.rawSmartSymbol;
            int base = ss % 100;            // ilman yötä
            boolean night = ss >= 100 || c.isNight;
            // Kuurot (osittain pilvinen + sade) ja puolipilvinen sade
            if (base == 21 || base == 24 || base == 27
                    || (base >= 31 && base <= 33)) {
                return night ? NIGHT_PARTLY_RAIN : PARTLY_RAIN;
            }
            // Lähes pilvinen sade
            if (base >= 34 && base <= 36) {
                return night ? NIGHT_MOSTLY_RAIN : MOSTLY_RAIN;
            }
            // 37-39 -> tavallinen sade (fallthrough type-vaiheeseen).
            // Räntä: kuurot ja puolipilvinen räntä
            if (base >= 41 && base <= 43) {
                return night ? NIGHT_PARTLY_SLEET : PARTLY_SLEET;
            }
            if (base >= 44 && base <= 46) {
                return night ? NIGHT_MOSTLY_SLEET : MOSTLY_SLEET;
            }
            // 47-49 -> tavallinen räntä (fallthrough).
            // Lumi: kuurot ja puolipilvinen lumisade
            if (base >= 51 && base <= 53) {
                return night ? NIGHT_PARTLY_SNOW : PARTLY_SNOW;
            }
            if (base >= 54 && base <= 56) {
                return night ? NIGHT_MOSTLY_SNOW : MOSTLY_SNOW;
            }
            // 57-59 -> tavallinen lumisade (fallthrough).
            // 61/64/67 raekuurot ja 71/74/77 ukkoskuurot -> fallthrough type-vaiheeseen.
        }
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
            case PARTLY_RAIN: drawPartlyRain(canvas, cx, cy, size, false); break;
            case PARTLY_SNOW: drawPartlySnow(canvas, cx, cy, size, false); break;
            case PARTLY_SLEET: drawPartlySleet(canvas, cx, cy, size, false); break;
            case NIGHT_PARTLY_RAIN: drawPartlyRain(canvas, cx, cy, size, true); break;
            case NIGHT_PARTLY_SNOW: drawPartlySnow(canvas, cx, cy, size, true); break;
            case NIGHT_PARTLY_SLEET: drawPartlySleet(canvas, cx, cy, size, true); break;
            case MOSTLY_RAIN: drawMostlyRain(canvas, cx, cy, size, false); break;
            case MOSTLY_SNOW: drawMostlySnow(canvas, cx, cy, size, false); break;
            case MOSTLY_SLEET: drawMostlySleet(canvas, cx, cy, size, false); break;
            case NIGHT_MOSTLY_RAIN: drawMostlyRain(canvas, cx, cy, size, true); break;
            case NIGHT_MOSTLY_SNOW: drawMostlySnow(canvas, cx, cy, size, true); break;
            case NIGHT_MOSTLY_SLEET: drawMostlySleet(canvas, cx, cy, size, true); break;
        }
    }

    /** Pieni aurinko/kuu yläoikealle, suurelta osin pilven peitossa.
     *  Käytetään "lähes pilvinen + sade/räntä/lumi" -ikoneissa. */
    private void drawTinySunOrMoon(Canvas c, float cx, float cy, float s, boolean night) {
        float scx = cx + s * 0.28f;
        float scy = cy - s * 0.30f;
        if (night) {
            paint.setColor(0xFFE0E0E0);
            paint.setStyle(Paint.Style.FILL);
            path.reset();
            float r = s * 0.10f;
            path.addCircle(scx, scy, r, Path.Direction.CW);
            Path mask = new Path();
            mask.addCircle(scx + s * 0.05f, scy - s * 0.025f, r * 0.95f, Path.Direction.CW);
            path.op(mask, Path.Op.DIFFERENCE);
            c.drawPath(path, paint);
        } else {
            paint.setColor(0xFFFFD700);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(scx, scy, s * 0.09f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(s * 0.022f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            // Vain ylä- ja yläoikeat säteet, koska pilvi peittää muut
            double[] angles = {-Math.PI / 2, -Math.PI / 4, 0};
            for (double a : angles) {
                float x1 = (float) (scx + Math.cos(a) * s * 0.13f);
                float y1 = (float) (scy + Math.sin(a) * s * 0.13f);
                float x2 = (float) (scx + Math.cos(a) * s * 0.19f);
                float y2 = (float) (scy + Math.sin(a) * s * 0.19f);
                c.drawLine(x1, y1, x2, y2, paint);
            }
        }
    }

    private void drawMostlyRain(Canvas c, float cx, float cy, float s, boolean night) {
        drawTinySunOrMoon(c, cx, cy, s, night);
        // Suurempi pilvi kuin partly-versiossa, mutta pienempi kuin pelkkä rain
        drawCloud(c, cx - s * 0.02f, cy - s * 0.08f, s * 0.95f, 0xFFA8A8A8);
        paint.setColor(0xFF4FA8E0);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(s * 0.04f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float dy = cy + s * 0.20f;
        for (int i = 0; i < 4; i++) {
            float x = cx + (i - 1.5f) * s * 0.10f - s * 0.02f;
            c.drawLine(x + s * 0.04f, dy, x - s * 0.02f, dy + s * 0.17f, paint);
        }
    }

    private void drawMostlySnow(Canvas c, float cx, float cy, float s, boolean night) {
        drawTinySunOrMoon(c, cx, cy, s, night);
        drawCloud(c, cx - s * 0.02f, cy - s * 0.08f, s * 0.95f, 0xFFB8B8B8);
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(s * 0.03f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float dy = cy + s * 0.24f;
        for (int i = 0; i < 3; i++) {
            float x = cx + (i - 1) * s * 0.14f - s * 0.02f;
            float r = s * 0.05f;
            c.drawLine(x - r, dy, x + r, dy, paint);
            c.drawLine(x, dy - r, x, dy + r, paint);
            c.drawLine(x - r * 0.7f, dy - r * 0.7f, x + r * 0.7f, dy + r * 0.7f, paint);
            c.drawLine(x - r * 0.7f, dy + r * 0.7f, x + r * 0.7f, dy - r * 0.7f, paint);
        }
    }

    private void drawMostlySleet(Canvas c, float cx, float cy, float s, boolean night) {
        drawTinySunOrMoon(c, cx, cy, s, night);
        drawCloud(c, cx - s * 0.02f, cy - s * 0.08f, s * 0.95f, 0xFFA8A8B0);
        paint.setStrokeWidth(s * 0.04f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        float dy = cy + s * 0.20f;
        for (int i = 0; i < 3; i++) {
            float x = cx + (i - 1) * s * 0.14f - s * 0.02f;
            if (i % 2 == 0) {
                paint.setColor(0xFF4FA8E0);
                c.drawLine(x + s * 0.04f, dy, x - s * 0.02f, dy + s * 0.17f, paint);
            } else {
                paint.setColor(0xFFFFFFFF);
                float r = s * 0.05f;
                c.drawLine(x - r, dy + s * 0.09f, x + r, dy + s * 0.09f, paint);
                c.drawLine(x, dy + s * 0.04f, x, dy + s * 0.14f, paint);
            }
        }
    }

    /** Piirrä pieni aurinko (tai kuu) yläoikealle. Käytetään puolipilvisten
     *  sateiden ikoneissa. Pilvi piirretään tämän jälkeen päälle siten,
     *  että aurinko/kuu jää osittain näkyviin. */
    private void drawSmallSunOrMoon(Canvas c, float cx, float cy, float s, boolean night) {
        float scx = cx + s * 0.22f;
        float scy = cy - s * 0.25f;
        if (night) {
            // Pieni kuu
            paint.setColor(0xFFE0E0E0);
            paint.setStyle(Paint.Style.FILL);
            path.reset();
            float r = s * 0.16f;
            path.addCircle(scx, scy, r, Path.Direction.CW);
            Path mask = new Path();
            mask.addCircle(scx + s * 0.08f, scy - s * 0.04f, r * 0.95f, Path.Direction.CW);
            path.op(mask, Path.Op.DIFFERENCE);
            c.drawPath(path, paint);
        } else {
            // Pieni aurinko + säteet
            paint.setColor(0xFFFFD700);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(scx, scy, s * 0.13f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(s * 0.025f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * 2 * i / 8;
                float x1 = (float) (scx + Math.cos(a) * s * 0.18f);
                float y1 = (float) (scy + Math.sin(a) * s * 0.18f);
                float x2 = (float) (scx + Math.cos(a) * s * 0.25f);
                float y2 = (float) (scy + Math.sin(a) * s * 0.25f);
                c.drawLine(x1, y1, x2, y2, paint);
            }
        }
    }

    private void drawPartlyRain(Canvas c, float cx, float cy, float s, boolean night) {
        drawSmallSunOrMoon(c, cx, cy, s, night);
        drawCloud(c, cx - s * 0.04f, cy - s * 0.06f, s * 0.85f, 0xFFB8B8B8);
        paint.setColor(0xFF4FA8E0);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(s * 0.04f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float dy = cy + s * 0.20f;
        for (int i = 0; i < 3; i++) {
            float x = cx + (i - 1f) * s * 0.10f - s * 0.04f;
            c.drawLine(x + s * 0.04f, dy, x - s * 0.02f, dy + s * 0.16f, paint);
        }
    }

    private void drawPartlySnow(Canvas c, float cx, float cy, float s, boolean night) {
        drawSmallSunOrMoon(c, cx, cy, s, night);
        drawCloud(c, cx - s * 0.04f, cy - s * 0.06f, s * 0.85f, 0xFFC8C8C8);
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(s * 0.03f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float dy = cy + s * 0.24f;
        for (int i = 0; i < 3; i++) {
            float x = cx + (i - 1) * s * 0.13f - s * 0.04f;
            float r = s * 0.05f;
            c.drawLine(x - r, dy, x + r, dy, paint);
            c.drawLine(x, dy - r, x, dy + r, paint);
            c.drawLine(x - r * 0.7f, dy - r * 0.7f, x + r * 0.7f, dy + r * 0.7f, paint);
            c.drawLine(x - r * 0.7f, dy + r * 0.7f, x + r * 0.7f, dy - r * 0.7f, paint);
        }
    }

    private void drawPartlySleet(Canvas c, float cx, float cy, float s, boolean night) {
        drawSmallSunOrMoon(c, cx, cy, s, night);
        drawCloud(c, cx - s * 0.04f, cy - s * 0.06f, s * 0.85f, 0xFFB0B0B8);
        paint.setStrokeWidth(s * 0.04f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        float dy = cy + s * 0.20f;
        for (int i = 0; i < 3; i++) {
            float x = cx + (i - 1) * s * 0.13f - s * 0.04f;
            if (i % 2 == 0) {
                paint.setColor(0xFF4FA8E0);
                c.drawLine(x + s * 0.04f, dy, x - s * 0.02f, dy + s * 0.16f, paint);
            } else {
                paint.setColor(0xFFFFFFFF);
                float r = s * 0.05f;
                c.drawLine(x - r, dy + s * 0.08f, x + r, dy + s * 0.08f, paint);
                c.drawLine(x, dy + s * 0.03f, x, dy + s * 0.13f, paint);
            }
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
