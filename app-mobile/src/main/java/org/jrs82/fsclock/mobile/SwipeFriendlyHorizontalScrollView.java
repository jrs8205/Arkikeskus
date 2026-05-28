package org.jrs82.fsclock.mobile;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.HorizontalScrollView;

/**
 * HorizontalScrollView joka kunnioittaa myös vertikaalista swipeä:
 * - ACTION_DOWN: ei vielä päätetä kummalle suunnalle touch kuuluu
 * - Ensimmäinen ACTION_MOVE joka ylittää touch-slopin: jos liike on
 *   vaakasuoraa, lukitaan parent (ei pääse kaappaamaan) ja itse scrollataan.
 *   Jos liike on pystysuoraa, parent ScrollView saa scrollata vapaasti.
 *
 * Tällä saadaan etusivun tuntiennusteen vaakaswipe toimimaan ilman että
 * pystysuora alas-veto sen päältä lukkiutuu.
 */
public class SwipeFriendlyHorizontalScrollView extends HorizontalScrollView {

    private float downX;
    private float downY;
    private boolean directionLocked;
    private int touchSlop;

    public SwipeFriendlyHorizontalScrollView(Context context) {
        super(context);
        init(context);
    }

    public SwipeFriendlyHorizontalScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SwipeFriendlyHorizontalScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                directionLocked = false;
                // Anna parentin myös prosessoida DOWN — emme vielä tiedä suuntaa.
                requestDisallowAllParents(false);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!directionLocked) {
                    float dx = Math.abs(ev.getX() - downX);
                    float dy = Math.abs(ev.getY() - downY);
                    if (dx > touchSlop || dy > touchSlop) {
                        boolean horizontal = dx > dy;
                        directionLocked = true;
                        // Jos vaakasuora veto -> lukitse parent ettei ScrollView
                        // varasta ja anna oman scrollin viedä event.
                        // Jos pystysuora -> päästä parent ScrollView scrollata.
                        requestDisallowAllParents(horizontal);
                        if (!horizontal) {
                            // Lähetä omalle viewille CANCEL, niin se ei jää
                            // odottamaan vaakascrollaa.
                            MotionEvent cancel = MotionEvent.obtain(ev);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            super.onTouchEvent(cancel);
                            cancel.recycle();
                            return false;
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                requestDisallowAllParents(false);
                directionLocked = false;
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    private void requestDisallowAllParents(boolean disallow) {
        ViewParent p = getParent();
        while (p != null) {
            p.requestDisallowInterceptTouchEvent(disallow);
            p = p.getParent();
        }
    }
}
