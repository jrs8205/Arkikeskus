package org.jrs82.fsclock;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import org.jrs82.fsclock.db.BatteryMonitor;
import org.jrs82.fsclock.db.DailyStatsScheduler;

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

        SettingsManager.get().init(this);

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
    }
}
