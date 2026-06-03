package org.jrs82.fsclock.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface DailyStatDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(DailyStat stat);

    @Query("SELECT * FROM daily_stats WHERE channel = :ch AND date = :date")
    DailyStat get(String ch, String date);

    @Query("SELECT * FROM daily_stats WHERE channel = :ch AND date BETWEEN :startDate AND :endDate ORDER BY date")
    List<DailyStat> getRange(String ch, String startDate, String endDate);

    @Query("SELECT * FROM daily_stats WHERE channel = :ch ORDER BY maxTemp DESC LIMIT 1")
    DailyStat getAllTimeMax(String ch);

    @Query("SELECT * FROM daily_stats WHERE channel = :ch ORDER BY minTemp ASC LIMIT 1")
    DailyStat getAllTimeMin(String ch);

    @Query("DELETE FROM daily_stats WHERE date < :cutoffDate")
    int deleteOlderThan(String cutoffDate);

    @Query("DELETE FROM daily_stats")
    int clear();
}
