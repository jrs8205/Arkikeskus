package org.jrs82.fsclock.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

import androidx.preference.PreferenceManager;

import org.jrs82.fsclock.db.DailyStepsEntity;
import org.jrs82.fsclock.db.FsClockDb;

import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Raw TYPE_STEP_COUNTER -pohjainen askellaskuri (fallback, kun Health Connect ei käytössä).
 *  Anturi on kumulatiivinen laitteen boottauksesta ja laskee laitteistossa taustallakin, joten
 *  kun listener rekisteröidään uudelleen, myös sovelluksen ollessa kiinni kertyneet askeleet
 *  saadaan erotuksena edelliseen tallennettuun lukemaan. Päiväsummat tallennetaan Roomiin.
 *
 *  Listener pidetään aktiivisena vain kun mittari on päällä JA sovellus on etualalla — tämä ei
 *  ole reaaliaikainen taustaseuranta (siihen tarvittaisiin Health Connect tai foreground service),
 *  mutta päiväsumma pysyy ajan tasalla anturin kumulatiivisuuden ansiosta. */
final class StepCounter implements SensorEventListener {

    interface Listener {
        void onTodayStepsChanged(int steps);
    }

    private static final String KEY_LAST_CUM = "mobile_steps_last_cumulative";
    private static final String KEY_LAST_DAY = "mobile_steps_last_day";

    private final Context ctx;
    private final SensorManager sm;
    private final Sensor sensor;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private volatile int todaySteps;
    private boolean running;

    StepCounter(Context context) {
        ctx = context.getApplicationContext();
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        sensor = sm != null ? sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) : null;
    }

    boolean isAvailable() {
        return sensor != null;
    }

    void setListener(Listener l) {
        listener = l;
    }

    int currentTodaySteps() {
        return todaySteps;
    }

    void start() {
        if (sensor == null || running) return;
        running = true;
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
        io.execute(() -> {
            int t = readDay(FsClockDb.get(ctx), todayKey());
            todaySteps = t;
            postToday(t);
        });
    }

    void stop() {
        if (sensor == null || !running) return;
        running = false;
        sm.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        final float total = e.values[0];
        io.execute(() -> processCumulative(total));
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) { }

    private void processCumulative(float total) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        int today = todayKey();
        float lastCum = prefs.getFloat(KEY_LAST_CUM, -1f);
        FsClockDb db = FsClockDb.get(ctx);
        int current = readDay(db, today);
        // lastCum < 0: ensimmäinen lukema. total < lastCum: laite buutattiin (counter nollautui).
        // Kummassakin vain päivitetään baseline, ei lisätä askelia (vältetään hyppy).
        if (lastCum >= 0f && total >= lastCum) {
            int delta = (int) (total - lastCum);
            if (delta > 0) {
                current += delta;
                db.dailyStepsDao().upsert(new DailyStepsEntity(today, current));
            }
        }
        prefs.edit().putFloat(KEY_LAST_CUM, total).putInt(KEY_LAST_DAY, today).apply();
        todaySteps = current;
        postToday(current);
    }

    private int readDay(FsClockDb db, int dateKey) {
        Integer v = db.dailyStepsDao().stepsForDay(dateKey);
        return v != null ? v : 0;
    }

    private void postToday(int steps) {
        Listener l = listener;
        if (l != null) main.post(() -> l.onTodayStepsChanged(steps));
    }

    /** Päiväavain muotoa yyyymmdd paikallisella aikavyöhykkeellä. */
    static int todayKey() {
        Calendar c = Calendar.getInstance();
        return dateKey(c);
    }

    static int dateKey(Calendar c) {
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100
                + c.get(Calendar.DAY_OF_MONTH);
    }
}
