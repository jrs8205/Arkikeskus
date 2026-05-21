package org.jrs82.fsclock;

import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.view.WindowManager;

import java.util.Calendar;
import java.util.Locale;

/** Päivä/yö-kirkkauden hallinta. Säätää Window.screenBrightness:in vuorokaudenajan
 *  ja testitilan mukaan. Pyörittää oman minuuttitickin, jotta kellonajan ylittävät
 *  rajat (morning/evening) tulevat huomatuiksi ilman ulkoista heräteellistä. */
public class BrightnessController {

    private static final Locale FI = new Locale("fi", "FI");
    private static final long TICK_MS = 60_000L;

    private final Window window;            // saa olla null (silloin no-op)
    private final SettingsManager settings;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private float lastBrightness = -1f;

    public BrightnessController(Window window, SettingsManager settings) {
        this.window = window;
        this.settings = settings;
    }

    public void start() {
        applyNow();
        ui.removeCallbacks(tick);
        // Synkronoi seuraava tikkaus seuraavan minuutin rajalle, jolloin 06:00/21:00
        // vaihdot tulevat alle sekunnin viiveellä.
        long delay = TICK_MS - (System.currentTimeMillis() % TICK_MS);
        ui.postDelayed(tick, delay);
    }

    public void stop() {
        ui.removeCallbacks(tick);
    }

    /** Säädä kirkkaus nykyhetken mukaan (testitila tai vuorokaudenaika).
     *  Cache estää tarpeettomat Window-attribuuttipäivitykset, mutta jos pct muuttuu,
     *  asetus astuu voimaan välittömästi. */
    public void applyNow() {
        if (window == null) return;
        int testMode = settings.getActiveTestMode();
        int pct;
        if (testMode == SettingsManager.TEST_DAY) {
            pct = settings.getDayBrightness();
        } else if (testMode == SettingsManager.TEST_NIGHT) {
            pct = settings.getNightBrightness();
        } else {
            int hour = Calendar.getInstance(FI).get(Calendar.HOUR_OF_DAY);
            pct = isNightBrightness(hour) ? settings.getNightBrightness() : settings.getDayBrightness();
        }
        float val = Math.max(0.01f, Math.min(1f, pct / 100f));
        if (Math.abs(val - lastBrightness) < 0.005f) return;
        lastBrightness = val;
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.screenBrightness = val;
        window.setAttributes(lp);
    }

    /** Pakota uudelleenasetus vaikka pct ei olisi muuttunut.
     *  Käytetään esim. SettingsActivitysta paluun jälkeen, kun järjestelmä on voinut
     *  välillä muuttaa Window:n attribuutteja. */
    public void reapply() {
        lastBrightness = -1f;
        applyNow();
    }

    private boolean isNightBrightness(int hourOfDay) {
        int morning = settings.getMorningHour();
        int evening = settings.getEveningHour();
        return hourOfDay >= evening || hourOfDay < morning;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            applyNow();
            long delay = TICK_MS - (System.currentTimeMillis() % TICK_MS);
            ui.postDelayed(this, delay);
        }
    };
}
