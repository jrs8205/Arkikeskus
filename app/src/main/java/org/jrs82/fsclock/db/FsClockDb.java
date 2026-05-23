package org.jrs82.fsclock.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.AutoMigration;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
    entities = {WeatherSample.class, DailyStat.class, RuuviSampleEntity.class},
    version = 3,
    autoMigrations = {
        @AutoMigration(from = 2, to = 3)
    },
    exportSchema = true
)
public abstract class FsClockDb extends RoomDatabase {

    public abstract WeatherDao weatherDao();
    public abstract DailyStatDao dailyStatDao();
    public abstract RuuviSamplesDao ruuviSamplesDao();

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE weather_samples ADD COLUMN observedWawa INTEGER");
        }
    };

    private static volatile FsClockDb instance;

    public static FsClockDb get(Context context) {
        if (instance == null) {
            synchronized (FsClockDb.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                        context.getApplicationContext(),
                        FsClockDb.class,
                        "fsclock.db"
                    ).addMigrations(MIGRATION_1_2).build();
                }
            }
        }
        return instance;
    }
}
