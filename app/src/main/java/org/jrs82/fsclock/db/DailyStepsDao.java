package org.jrs82.fsclock.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface DailyStepsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(DailyStepsEntity entity);

    @Query("SELECT steps FROM daily_steps WHERE dateKey = :dateKey")
    Integer stepsForDay(int dateKey);

    @Query("SELECT * FROM daily_steps WHERE dateKey BETWEEN :from AND :to ORDER BY dateKey")
    List<DailyStepsEntity> range(int from, int to);
}
