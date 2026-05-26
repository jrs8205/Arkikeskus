package org.jrs82.fsclock;

import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.FrameLayout;

public class SwipeInterceptLayout extends FrameLayout {

    private GestureDetector swipeDetector;
    private float downX, downY;
    private boolean decidedIntercept;
    private final float touchSlop;

    public SwipeInterceptLayout(Context context) {
        this(context, null);
    }

    public SwipeInterceptLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setSwipeDetector(GestureDetector detector) {
        this.swipeDetector = detector;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                decidedIntercept = false;
                if (swipeDetector != null) swipeDetector.onTouchEvent(ev);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!decidedIntercept) {
                    float dx = Math.abs(ev.getX() - downX);
                    float dy = Math.abs(ev.getY() - downY);
                    if (dx > touchSlop || dy > touchSlop) {
                        if (dx > dy) {
                            decidedIntercept = true;
                            return true;
                        }
                    }
                }
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (swipeDetector != null) {
            return swipeDetector.onTouchEvent(ev);
        }
        return super.onTouchEvent(ev);
    }
}
