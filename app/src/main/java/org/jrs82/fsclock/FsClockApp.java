package org.jrs82.fsclock;

import android.app.Application;

import org.jrs82.fsclock.db.BatteryMonitor;
import org.jrs82.fsclock.db.DailyStatsScheduler;

public class FsClockApp extends Application {

    private BatteryMonitor batteryMonitor;
    private DailyStatsScheduler statsScheduler;

    @Override
    public void onCreate() {
        super.onCreate();

        SettingsManager.get().init(this);

        // Akun keräys ja päivätilastot pyörivät koko prosessin elinkaaren.
        // Näin Asetukset/Järjestelmä-sivun avaaminen ei katkaise datavirtaa.
        batteryMonitor = new BatteryMonitor(this);
        batteryMonitor.start();

        statsScheduler = new DailyStatsScheduler(this, "battery");
        statsScheduler.start();
    }
}
