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
    entities = {WeatherSample.class, DailyStat.class, RuuviSampleEntity.class, DailyStepsEntity.class},
    version = 4,
    autoMigrations = {
        @AutoMigration(from = 2, to = 3)
    },
    exportSchema = true
)
public abstract class FsClockDb extends RoomDatabase {

    public abstract WeatherDao weatherDao();
    public abstract DailyStatDao dailyStatDao();
    public abstract RuuviSamplesDao ruuviSamplesDao();
    public abstract DailyStepsDao dailyStepsDao();

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE weather_samples ADD COLUMN observedWawa INTEGER");
        }
    };

    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS daily_steps "
                    + "(dateKey INTEGER NOT NULL, steps INTEGER NOT NULL, PRIMARY KEY(dateKey))");
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
                    ).addMigrations(MIGRATION_1_2, MIGRATION_3_4).build();
                }
            }
        }
        return instance;
    }
}
