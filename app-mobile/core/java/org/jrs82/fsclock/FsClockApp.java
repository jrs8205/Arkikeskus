package org.jrs82.fsclock;

import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.util.Log;

import org.jrs82.fsclock.db.BatteryMonitor;
import org.jrs82.fsclock.db.DailyStatsScheduler;
import org.jrs82.fsclock.mobile.ImageLoader;
import org.jrs82.fsclock.mobile.Notifications;

public class FsClockApp extends Application {

    private static final String TAG = "FsClockApp";

    private BatteryMonitor batteryMonitor;
    private DailyStatsScheduler batteryStats;
    private DailyStatsScheduler fmiStats;
    private String fmiStatsChannel;
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;

    @Override
    public void onCreate() {
        super.onCreate();

        // Sivuprosessit (esim. :restart-uudelleenkäynnistäjä) eivät saa avata tietokantaa
        // eivätkä käynnistää taustakeräystä — ne eläisivät rinnan pääprosessin kanssa.
        String proc = getProcessName();
        if (proc != null && !proc.equals(getPackageName())) {
            return;
        }

        SettingsManager.get().init(this);

        // Taustailmoitukset (4b): ajasta tunnittainen WorkManager-työ. Worker tarkistaa per-tyyppi-
        // kytkimet → kaikki opt-in (oletus pois), joten pois päältä olevat eivät tee mitään.
        Notifications.schedule(this);

        // Akun keräys ja päivätilastot pyörivät koko prosessin elinkaaren.
        // Näin Asetukset/Järjestelmä-sivun avaaminen ei katkaise datavirtaa.
        batteryMonitor = new BatteryMonitor(this);
        batteryMonitor.start();

        batteryStats = new DailyStatsScheduler(this, "battery");
        batteryStats.start();

        fmiStatsChannel = SettingsManager.get().homeChannel();
        fmiStats = new DailyStatsScheduler(this, fmiStatsChannel);
        fmiStats.start();
        Log.i(TAG, "Aloitettu FMI-tilastoinnit kanavalle " + fmiStatsChannel);

        // Kun kotipaikkakunta vaihtuu, käynnistä FMI-stats uudelle kanavalle.
        // Vanhalle kanavalle jää data talteen, mutta sen recompute pysähtyy.
        prefsListener = (sp, key) -> {
            if (!SettingsManager.KEY_HOME_PLACE.equals(key)) return;
            String newChannel = SettingsManager.get().homeChannel();
            if (newChannel.equals(fmiStatsChannel)) return;
            Log.i(TAG, "Kotipaikkakunta vaihtui: " + fmiStatsChannel + " -> " + newChannel);
            if (fmiStats != null) fmiStats.stop();
            fmiStatsChannel = newChannel;
            fmiStats = new DailyStatsScheduler(this, fmiStatsChannel);
            fmiStats.start();
        };
        SettingsManager.get().registerListener(prefsListener);

        // Vapauta uutisten pikkukuva-LruCache muistipaineessa — singleton ei muuten reagoi siihen.
        registerComponentCallbacks(new ComponentCallbacks2() {
            @Override
            public void onTrimMemory(int level) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
                    ImageLoader.get().trim();
                }
            }

            @Override
            public void onLowMemory() {
                ImageLoader.get().trim();
            }

            @Override
            public void onConfigurationChanged(Configuration newConfig) {
            }
        });
    }
}
