package org.jrs82.fsclock.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Yhden matkan luvut viimeisimmästä lopetuksesta. */
@Entity(tableName = "trips")
public final class TripEntity {
    @PrimaryKey @NonNull public final String id;
    public final long startedAtMs;
    public final long endedAtMs;
    public final boolean estimatedStart;
    public final double distanceM;
    public final long totalMs;
    public final long movingMs;
    public final float maxSpeedMps;

    public boolean sameFinish(TripEntity other) {
        return other != null && id.equals(other.id) && startedAtMs == other.startedAtMs
                && endedAtMs == other.endedAtMs && estimatedStart == other.estimatedStart
                && distanceM == other.distanceM && totalMs == other.totalMs
                && movingMs == other.movingMs && maxSpeedMps == other.maxSpeedMps;
    }

    public TripEntity(@NonNull String id, long startedAtMs, long endedAtMs, boolean estimatedStart,
                      double distanceM, long totalMs, long movingMs, float maxSpeedMps) {
        this.id = id;
        this.startedAtMs = startedAtMs;
        this.endedAtMs = endedAtMs;
        this.estimatedStart = estimatedStart;
        this.distanceM = distanceM;
        this.totalMs = totalMs;
        this.movingMs = movingMs;
        this.maxSpeedMps = maxSpeedMps;
    }
}
