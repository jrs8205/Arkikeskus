package org.jrs82.fsclock.mobile;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.widget.HorizontalScrollView;

/**
 * HorizontalScrollView joka pyytää parent-puuta olemaan kaappaamatta touch-eventeja
 * heti kun sormi koskettaa. Tällä estetään parent ScrollView:n kilpailu horisontaalisen
 * swipen kanssa, joka muuten aiheuttaa tökkimistä etusivun tuntiennusteessa.
 */
public class SwipeFriendlyHorizontalScrollView extends HorizontalScrollView {

    public SwipeFriendlyHorizontalScrollView(Context context) {
        super(context);
    }

    public SwipeFriendlyHorizontalScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SwipeFriendlyHorizontalScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            requestDisallowAllParents(true);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            requestDisallowAllParents(false);
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            requestDisallowAllParents(true);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            requestDisallowAllParents(false);
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
