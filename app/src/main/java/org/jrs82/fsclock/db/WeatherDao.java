package org.jrs82.fsclock.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface WeatherDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(WeatherSample sample);

    @Query("SELECT * FROM weather_samples WHERE channel = :ch AND timestamp BETWEEN :start AND :end ORDER BY timestamp")
    List<WeatherSample> getSamplesBetween(String ch, long start, long end);

    @Query("DELETE FROM weather_samples WHERE timestamp < :cutoff")
    int deleteOlderThan(long cutoff);

    @Query("SELECT COUNT(*) FROM weather_samples")
    long count();

    @Query("SELECT * FROM weather_samples ORDER BY channel, timestamp")
    List<WeatherSample> getAll();

    @Query("SELECT * FROM weather_samples WHERE channel = :ch ORDER BY timestamp")
    List<WeatherSample> getByChannel(String ch);

    @Query("DELETE FROM weather_samples")
    int clear();
}
