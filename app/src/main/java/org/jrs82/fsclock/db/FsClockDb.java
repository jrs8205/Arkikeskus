package org.jrs82.fsclock.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
    entities = {WeatherSample.class, DailyStat.class},
    version = 1,
    exportSchema = true
)
public abstract class FsClockDb extends RoomDatabase {

    public abstract WeatherDao weatherDao();
    public abstract DailyStatDao dailyStatDao();

    private static volatile FsClockDb instance;

    public static FsClockDb get(Context context) {
        if (instance == null) {
            synchronized (FsClockDb.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                        context.getApplicationContext(),
                        FsClockDb.class,
                        "fsclock.db"
                    ).build();
                }
            }
        }
        return instance;
    }
}
