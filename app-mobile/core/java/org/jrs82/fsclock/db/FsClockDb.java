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
    entities = {WeatherSample.class, DailyStat.class, RuuviSampleEntity.class, DailyStepsEntity.class,
                WorkoutEntity.class, WorkoutPointEntity.class, WorkoutSplitEntity.class},
    version = 6,
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
    public abstract WorkoutDao workoutDao();

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

    // Lenkkiseuranta: workouts + workout_points + workout_splits. Sarakkeet täsmälleen
    // entiteettien mukaan (Room validoi skeeman avattaessa).
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS workouts ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "type INTEGER NOT NULL, status INTEGER NOT NULL, "
                    + "startedAtMs INTEGER NOT NULL, endedAtMs INTEGER, "
                    + "movingTimeMs INTEGER NOT NULL, distanceM REAL NOT NULL, "
                    + "maxSpeedMps REAL NOT NULL, steps INTEGER NOT NULL, "
                    + "stepBaselineCum REAL NOT NULL, stepCommitted INTEGER NOT NULL, "
                    + "lastCumSeen REAL NOT NULL, stepsExcluded INTEGER NOT NULL, "
                    + "elevGainM REAL NOT NULL, elevLossM REAL NOT NULL, "
                    + "kcal INTEGER NOT NULL, autoStopped INTEGER NOT NULL, "
                    + "pauseStartedMs INTEGER, updatedAtMs INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS workout_points ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "workoutId INTEGER NOT NULL, tMs INTEGER NOT NULL, "
                    + "lat REAL NOT NULL, lon REAL NOT NULL, altM REAL, "
                    + "speedMps REAL NOT NULL, accuracyM REAL NOT NULL, "
                    + "segment INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_workout_points_workoutId "
                    + "ON workout_points(workoutId)");
            db.execSQL("CREATE TABLE IF NOT EXISTS workout_splits ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "workoutId INTEGER NOT NULL, splitIndex INTEGER NOT NULL, "
                    + "durationMs INTEGER NOT NULL, "
                    + "endLat REAL NOT NULL, endLon REAL NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_workout_splits_workoutId "
                    + "ON workout_splits(workoutId)");
        }
    };

    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE workouts ADD COLUMN name TEXT");
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
                    ).addMigrations(MIGRATION_1_2, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build();
                }
            }
        }
        return instance;
    }
}
