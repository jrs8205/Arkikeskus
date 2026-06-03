package org.jrs82.fsclock.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Yhden päivän askelmäärä (raw TYPE_STEP_COUNTER -lähteelle). dateKey muotoa yyyymmdd. */
@Entity(tableName = "daily_steps")
public class DailyStepsEntity {

    @PrimaryKey
    public int dateKey;
    public int steps;

    public DailyStepsEntity(int dateKey, int steps) {
        this.dateKey = dateKey;
        this.steps = steps;
    }
}
