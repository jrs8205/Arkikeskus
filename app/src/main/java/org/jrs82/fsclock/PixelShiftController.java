package org.jrs82.fsclock;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.Random;

/** Burn-in -suoja: liikuttaa annettua näkymää satunnaisesti ±40 px X / ±20 px Y minuutin välein. */
public class PixelShiftController {

    private static final long SHIFT_MS = 60_000L;

    private final View shiftContainer;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();

    public PixelShiftController(View shiftContainer) {
        this.shiftContainer = shiftContainer;
    }

    public void start() {
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    public void stop() {
        ui.removeCallbacks(tick);
        if (shiftContainer != null) {
            shiftContainer.setTranslationX(0f);
            shiftContainer.setTranslationY(0f);
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            int dx = rnd.nextInt(81) - 40;
            int dy = rnd.nextInt(41) - 20;
            shiftContainer.setTranslationX(dx);
            shiftContainer.setTranslationY(dy);
            ui.postDelayed(this, SHIFT_MS);
        }
    };
}
