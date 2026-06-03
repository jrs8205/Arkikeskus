package org.jrs82.fsclock.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;

@Entity(tableName = "daily_stats", primaryKeys = {"channel", "date"})
public class DailyStat {

    @NonNull
    @ColumnInfo(name = "channel")
    public String channel = "";

    @NonNull
    @ColumnInfo(name = "date")
    public String date = "";

    @ColumnInfo(name = "minTemp")
    public double minTemp;

    @ColumnInfo(name = "minTempAt")
    public long minTempAt;

    @ColumnInfo(name = "maxTemp")
    public double maxTemp;

    @ColumnInfo(name = "maxTempAt")
    public long maxTempAt;

    @ColumnInfo(name = "avgTemp")
    public double avgTemp;

    @ColumnInfo(name = "totalPrecip")
    @Nullable
    public Double totalPrecip;

    @ColumnInfo(name = "maxWindGust")
    @Nullable
    public Double maxWindGust;

    @ColumnInfo(name = "sampleCount")
    public int sampleCount;

    @ColumnInfo(name = "isPartial")
    public boolean isPartial;

    public DailyStat() {}
}
