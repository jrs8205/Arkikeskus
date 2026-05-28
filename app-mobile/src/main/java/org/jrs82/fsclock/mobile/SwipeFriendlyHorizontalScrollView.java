package org.jrs82.fsclock.mobile;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.HorizontalScrollView;

/**
 * HorizontalScrollView joka kunnioittaa myös pystysuoraa swipeä.
 *
 * Logiikka onInterceptTouchEvent:ssä:
 * - ACTION_DOWN: tallenna koordinaatit, älä vielä päätä.
 * - ACTION_MOVE: kun touch-slop ylittyy ensimmäisen kerran, vertaa dx ja dy:
 *     dy > dx -> liike on pystysuora. Palauta false jolloin tämä view ei
 *                intercept-tä, parent ScrollView saa scrollata.
 *     dx > dy -> liike on vaakasuora. Lukitse parent (ei voi intercept-tä)
 *                ja anna super:n käsitellä horisontaalinen scroll.
 *
 * Vertikaalin tunnistus tapahtuu mahdollisimman aikaisin jotta parent
 * ScrollView ehtii ottaa eventin haltuunsa ja scrollata pehmeästi.
 */
public class SwipeFriendlyHorizontalScrollView extends HorizontalScrollView {

    private float downX;
    private float downY;
    private boolean directionDecided;
    private boolean horizontalLocked;
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
                directionDecided = false;
                horizontalLocked = false;
                requestDisallowAllParents(false);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!directionDecided) {
                    float dx = Math.abs(ev.getX() - downX);
                    float dy = Math.abs(ev.getY() - downY);
                    if (dy > touchSlop && dy > dx) {
                        // Pystysuora veto. Tämä view ei intercept-tä.
                        directionDecided = true;
                        horizontalLocked = false;
                        return false;
                    }
                    if (dx > touchSlop && dx > dy) {
                        directionDecided = true;
                        horizontalLocked = true;
                        requestDisallowAllParents(true);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                requestDisallowAllParents(false);
                directionDecided = false;
                horizontalLocked = false;
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // Jos suunta on jo päätetty pystysuoraksi, älä reagoi lainkaan.
        if (directionDecided && !horizontalLocked) {
            return false;
        }
        return super.onTouchEvent(ev);
    }

    private void requestDisallowAllParents(boolean disallow) {
        ViewParent p = getParent();
        while (p != null) {
            p.requestDisallowInterceptTouchEvent(disallow);
            p = p.getParent();
        }
    }
}
