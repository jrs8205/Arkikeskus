package org.jrs82.fsclock.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ColumnInfo;
import androidx.room.Ignore;
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

    @ColumnInfo(defaultValue = "0") public final boolean startedAutomatically;
    @ColumnInfo(defaultValue = "0") public final boolean endedAutomatically;

    public boolean sameFinish(TripEntity other) {
        return other != null && id.equals(other.id) && startedAtMs == other.startedAtMs
                && endedAtMs == other.endedAtMs && estimatedStart == other.estimatedStart
                && distanceM == other.distanceM && totalMs == other.totalMs
                && movingMs == other.movingMs && maxSpeedMps == other.maxSpeedMps
                && startedAutomatically == other.startedAutomatically && endedAutomatically == other.endedAutomatically;
    }

    @Ignore
    public TripEntity(@NonNull String id, long startedAtMs, long endedAtMs, boolean estimatedStart,
                      double distanceM, long totalMs, long movingMs, float maxSpeedMps) {
        this(id, startedAtMs, endedAtMs, estimatedStart, distanceM, totalMs, movingMs, maxSpeedMps, false, false);
    }

    public TripEntity(@NonNull String id, long startedAtMs, long endedAtMs, boolean estimatedStart,
                      double distanceM, long totalMs, long movingMs, float maxSpeedMps,
                      boolean startedAutomatically, boolean endedAutomatically) {
        this.startedAutomatically = startedAutomatically;
        this.endedAutomatically = endedAutomatically;
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
