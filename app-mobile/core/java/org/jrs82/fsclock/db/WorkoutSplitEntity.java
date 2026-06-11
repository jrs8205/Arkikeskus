package org.jrs82.fsclock.db;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Kilometrivälit: durationMs on liikkeelläoloaikaa (pause ei vuoda splittiin) ja
 *  endLat/endLon on kilometrin ylityspiste (interpoloitu) karttamerkkiä varten. */
@Entity(tableName = "workout_splits", indices = {@Index("workoutId")})
public class WorkoutSplitEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;
    public long workoutId;
    public int splitIndex;
    public long durationMs;
    public double endLat;
    public double endLon;
}
