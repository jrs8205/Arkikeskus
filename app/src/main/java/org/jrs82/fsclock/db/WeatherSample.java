package org.jrs82.fsclock.db;

import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "weather_samples",
    indices = {@Index(value = {"channel", "timestamp"}, unique = true)}
)
public class WeatherSample {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "channel")
    public String channel;

    @ColumnInfo(name = "timestamp")
    public long timestamp;

    @ColumnInfo(name = "temperature")
    public double temperature;

    @ColumnInfo(name = "humidity")
    @Nullable
    public Double humidity;

    @ColumnInfo(name = "pressure")
    @Nullable
    public Double pressure;

    @ColumnInfo(name = "windSpeed")
    @Nullable
    public Double windSpeed;

    @ColumnInfo(name = "windGust")
    @Nullable
    public Double windGust;

    @ColumnInfo(name = "windDirection")
    @Nullable
    public Double windDirection;

    @ColumnInfo(name = "precipitation1h")
    @Nullable
    public Double precipitation1h;

    @ColumnInfo(name = "cloudCover")
    @Nullable
    public Double cloudCover;

    @ColumnInfo(name = "radiationGlobal")
    @Nullable
    public Double radiationGlobal;

    @ColumnInfo(name = "weatherSymbol")
    @Nullable
    public Integer weatherSymbol;

    @ColumnInfo(name = "batteryLevel")
    @Nullable
    public Integer batteryLevel;

    public WeatherSample() {}
}
