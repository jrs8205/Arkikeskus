package org.jrs82.fsclock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Pieni 5-palkkinen WiFi-signaalimittari headeriin.
 *
 * <p>{@link #setLevel(int)} (0..5) kertoo palaavien palkkien määrän. Palaavien
 * palkkien väri liukuu signaalitason mukaan punaisesta (heikko) keltaisen kautta
 * vihreään (vahva) — eli koko skaala on dynaaminen. Sammuneet palkit piirretään
 * himmeän harmaina.
 */
public class WifiSignalView extends View {

    private static final int BAR_COUNT = 5;
    private static final int COLOR_OFF = 0xFF3A3A3A;   // sammunut palkki (himmeä)
    // Väripysäkit heikoimmasta vahvimpaan.
    private static final int COLOR_LOW = 0xFFEE4444;   // punainen (taso 1)
    private static final int COLOR_MID = 0xFFE6C200;   // keltainen (taso 3)
    private static final int COLOR_HIGH = 0xFF66DD66;  // vihreä (taso 5)

    private int level = 0;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public WifiSignalView(Context c) { super(c); }
    public WifiSignalView(Context c, AttributeSet a) { super(c, a); }
    public WifiSignalView(Context c, AttributeSet a, int d) { super(c, a, d); }

    /** @param l palaavien palkkien määrä, rajataan välille 0..5. */
    public void setLevel(int l) {
        int clamped = Math.max(0, Math.min(BAR_COUNT, l));
        if (clamped != level) {
            level = clamped;
            invalidate();
        }
    }

    public int getLevel() { return level; }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int barW = dp(3.5f);
        int gap = dp(2.5f);
        int desiredW = getPaddingLeft() + getPaddingRight()
                + BAR_COUNT * barW + (BAR_COUNT - 1) * gap;
        int desiredH = getPaddingTop() + getPaddingBottom() + dp(18);
        setMeasuredDimension(
                resolveSize(desiredW, widthMeasureSpec),
                resolveSize(desiredH, heightMeasureSpec));
    }

    /** Liukuväri tasolle 1..5: punainen → keltainen → vihreä. */
    private int colorForLevel(int lvl) {
        float t = (lvl - 1) / (float) (BAR_COUNT - 1); // 0..1
        if (t <= 0.5f) {
            return lerp(COLOR_LOW, COLOR_MID, t / 0.5f);
        }
        return lerp(COLOR_MID, COLOR_HIGH, (t - 0.5f) / 0.5f);
    }

    private static int lerp(int from, int to, float f) {
        f = Math.max(0f, Math.min(1f, f));
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * f);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * f);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f);
        return Color.rgb(r, g, b);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int left = getPaddingLeft();
        int top = getPaddingTop();
        int usableW = getWidth() - left - getPaddingRight();
        int usableH = getHeight() - top - getPaddingBottom();
        if (usableW <= 0 || usableH <= 0) return;

        float gap = usableW * 0.14f;
        float barW = (usableW - gap * (BAR_COUNT - 1)) / BAR_COUNT;
        float radius = barW * 0.35f;
        float bottom = top + usableH;
        int litColor = level > 0 ? colorForLevel(level) : COLOR_OFF;

        for (int i = 0; i < BAR_COUNT; i++) {
            float frac = (i + 1) / (float) BAR_COUNT;   // 0.2 .. 1.0 korkeudesta
            float h = usableH * frac;
            float x = left + i * (barW + gap);
            rect.set(x, bottom - h, x + barW, bottom);
            paint.setColor(i < level ? litColor : COLOR_OFF);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }
}
