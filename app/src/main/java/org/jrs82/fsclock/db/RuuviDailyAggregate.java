package org.jrs82.fsclock.db;

import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;

public class RuuviDailyAggregate {

    @ColumnInfo(name = "day")
    public String day = "";

    @ColumnInfo(name = "minT")
    @Nullable
    public Double minTemp;

    @ColumnInfo(name = "maxT")
    @Nullable
    public Double maxTemp;

    @ColumnInfo(name = "avgT")
    @Nullable
    public Double avgTemp;

    @ColumnInfo(name = "avgH")
    @Nullable
    public Double avgHumidity;

    @ColumnInfo(name = "cnt")
    public int sampleCount;
}
