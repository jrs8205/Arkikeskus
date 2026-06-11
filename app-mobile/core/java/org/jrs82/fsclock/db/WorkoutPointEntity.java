package org.jrs82.fsclock.db;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Yksi GPS-piste lenkin reitillä. segment kasvaa pausen tai GPS-katkon (>30 s) jälkeen —
 *  reittiviiva piirretään segmenteittäin, jolloin katkon yli ei vedetä viivaa. */
@Entity(tableName = "workout_points", indices = {@Index("workoutId")})
public class WorkoutPointEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;
    public long workoutId;
    public long tMs;
    public double lat;
    public double lon;
    public Double altM;
    public float speedMps;
    public float accuracyM;
    public int segment;
}
