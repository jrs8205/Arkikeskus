package org.jrs82.fsclock.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface WorkoutDao {

    @Insert
    long insertWorkout(WorkoutEntity w);

    @Update
    void updateWorkout(WorkoutEntity w);

    @Insert
    void insertPoints(List<WorkoutPointEntity> points);

    @Insert
    void insertSplit(WorkoutSplitEntity split);

    /** Keskeneräinen lenkki (ACTIVE/PAUSED) palautusta varten, uusin ensin. */
    @Query("SELECT * FROM workouts WHERE status IN (0, 1) ORDER BY startedAtMs DESC LIMIT 1")
    WorkoutEntity activeWorkout();

    @Query("SELECT * FROM workouts WHERE id = :id")
    WorkoutEntity workoutById(long id);

    @Query("SELECT * FROM workout_points WHERE workoutId = :workoutId ORDER BY tMs")
    List<WorkoutPointEntity> pointsFor(long workoutId);

    @Query("SELECT * FROM workout_splits WHERE workoutId = :workoutId ORDER BY splitIndex")
    List<WorkoutSplitEntity> splitsFor(long workoutId);

    @Query("SELECT * FROM workouts WHERE status = 2 ORDER BY startedAtMs DESC LIMIT :limit")
    List<WorkoutEntity> recentWorkouts(int limit);

    @Query("DELETE FROM workouts WHERE id = :id")
    void deleteWorkout(long id);

    @Query("DELETE FROM workout_points WHERE workoutId = :workoutId")
    void deletePointsFor(long workoutId);

    @Query("DELETE FROM workout_splits WHERE workoutId = :workoutId")
    void deleteSplitsFor(long workoutId);
}
