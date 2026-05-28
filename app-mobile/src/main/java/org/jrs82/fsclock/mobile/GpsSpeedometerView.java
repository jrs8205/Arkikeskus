package org.jrs82.fsclock.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import org.jrs82.fsclock.R;

/**
 * Mercedes-tyylinen analoginen nopeusmittari 0..200 km/h.
 * 5 km/h välein pieni viiva, 10 km/h iso, 20 km/h numerolla.
 * Asteikko: kaari 240°, alkaa kello 7:n suunnasta (vas. alaviisto) ja
 * päättyy kello 5:n suuntaan (oik. alaviisto). Digitaalinen nopeus
 * tulee erillisessä TextViewissä mittarin alle.
 */
public class GpsSpeedometerView extends View {

    private static final float MAX_SPEED = 200f;
    private static final float START_ANGLE = 150f; // 0 km/h sijaitsee tässä kulmassa
    private static final float SWEEP_ANGLE = 240f;

    private final Paint dialPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float targetSpeed = 0f;
    private float displayedSpeed = 0f;
    private final Runnable animator = new Runnable() {
        @Override
        public void run() {
            float delta = targetSpeed - displayedSpeed;
            if (Math.abs(delta) < 0.2f) {
                displayedSpeed = targetSpeed;
                invalidate();
                return;
            }
            displayedSpeed += delta * 0.25f;
            invalidate();
            postDelayed(this, 16);
        }
    };

    public GpsSpeedometerView(Context context) {
        super(context);
        init();
    }

    public GpsSpeedometerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GpsSpeedometerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        dialPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        labelPaint.setStyle(Paint.Style.FILL);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setFakeBoldText(true);
        needlePaint.setStyle(Paint.Style.FILL);
        hubPaint.setStyle(Paint.Style.FILL);
    }

    public void setSpeedKmh(float kmh) {
        if (Float.isNaN(kmh) || kmh < 0f) kmh = 0f;
        if (kmh > MAX_SPEED) kmh = MAX_SPEED;
        targetSpeed = kmh;
        removeCallbacks(animator);
        post(animator);
    }

    public float getDisplayedSpeed() {
        return displayedSpeed;
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(animator);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int side = Math.min(getMeasuredWidth(), getMeasuredHeight());
        setMeasuredDimension(side, side);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) * 0.46f;

        int textColor = getContext().getColor(R.color.mobile_text_primary);
        int mutedColor = getContext().getColor(R.color.mobile_text_secondary);
        int accentColor = getContext().getColor(R.color.mobile_accent);
        int strokeColor = getContext().getColor(R.color.mobile_card_stroke);
        int dialColor = getContext().getColor(R.color.mobile_card_bg);

        dialPaint.setColor(dialColor);
        canvas.drawCircle(cx, cy, radius * 1.06f, dialPaint);

        ringPaint.setColor(strokeColor);
        ringPaint.setStrokeWidth(Math.max(2f, radius * 0.018f));
        canvas.drawCircle(cx, cy, radius * 1.06f, ringPaint);

        arcPaint.setColor(strokeColor);
        arcPaint.setStrokeWidth(Math.max(2f, radius * 0.020f));
        android.graphics.RectF arcRect = new android.graphics.RectF(
                cx - radius * 0.95f, cy - radius * 0.95f,
                cx + radius * 0.95f, cy + radius * 0.95f);
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, arcPaint);

        labelPaint.setTextSize(radius * 0.14f);

        for (int speed = 0; speed <= 200; speed += 5) {
            float angle = START_ANGLE + (speed / MAX_SPEED) * SWEEP_ANGLE;
            double rad = Math.toRadians(angle);

            float tickInner;
            float strokeW;
            if (speed % 20 == 0) {
                tickInner = radius * 0.74f;
                strokeW = Math.max(2f, radius * 0.035f);
                tickPaint.setColor(textColor);
            } else if (speed % 10 == 0) {
                tickInner = radius * 0.80f;
                strokeW = Math.max(2f, radius * 0.022f);
                tickPaint.setColor(textColor);
            } else {
                tickInner = radius * 0.86f;
                strokeW = Math.max(1.5f, radius * 0.012f);
                tickPaint.setColor(mutedColor);
            }
            tickPaint.setStrokeWidth(strokeW);

            float xOuter = (float) (cx + Math.cos(rad) * radius * 0.94f);
            float yOuter = (float) (cy + Math.sin(rad) * radius * 0.94f);
            float xInner = (float) (cx + Math.cos(rad) * tickInner);
            float yInner = (float) (cy + Math.sin(rad) * tickInner);
            canvas.drawLine(xInner, yInner, xOuter, yOuter, tickPaint);

            if (speed % 20 == 0) {
                float labelR = radius * 0.60f;
                float lx = (float) (cx + Math.cos(rad) * labelR);
                float ly = (float) (cy + Math.sin(rad) * labelR)
                        + labelPaint.getTextSize() / 3f;
                labelPaint.setColor(textColor);
                canvas.drawText(String.valueOf(speed), lx, ly, labelPaint);
            }
        }

        labelPaint.setColor(mutedColor);
        labelPaint.setTextSize(radius * 0.10f);
        canvas.drawText("km/h", cx, cy + radius * 0.42f, labelPaint);

        // Punainen kaari neulan polulla nopeuden mukaan
        float arcPercent = displayedSpeed / MAX_SPEED;
        arcPaint.setColor(accentColor);
        arcPaint.setStrokeWidth(Math.max(2f, radius * 0.04f));
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE * arcPercent, false, arcPaint);

        // Neulus
        float needleAngle = START_ANGLE + arcPercent * SWEEP_ANGLE;
        double nrad = Math.toRadians(needleAngle);
        double perp = nrad + Math.PI / 2.0;

        float tipX = (float) (cx + Math.cos(nrad) * radius * 0.88f);
        float tipY = (float) (cy + Math.sin(nrad) * radius * 0.88f);
        double backRad = Math.toRadians(needleAngle + 180.0);
        float backX = (float) (cx + Math.cos(backRad) * radius * 0.20f);
        float backY = (float) (cy + Math.sin(backRad) * radius * 0.20f);
        float halfWidth = radius * 0.030f;
        float side1X = (float) (cx + Math.cos(perp) * halfWidth);
        float side1Y = (float) (cy + Math.sin(perp) * halfWidth);
        float side2X = (float) (cx - Math.cos(perp) * halfWidth);
        float side2Y = (float) (cy - Math.sin(perp) * halfWidth);

        needlePaint.setColor(accentColor);
        Path needle = new Path();
        needle.moveTo(tipX, tipY);
        needle.lineTo(side1X, side1Y);
        needle.lineTo(backX, backY);
        needle.lineTo(side2X, side2Y);
        needle.close();
        canvas.drawPath(needle, needlePaint);

        hubPaint.setColor(textColor);
        canvas.drawCircle(cx, cy, radius * 0.07f, hubPaint);
        hubPaint.setColor(dialColor);
        canvas.drawCircle(cx, cy, radius * 0.045f, hubPaint);
    }
}
