package org.jrs82.fsclock.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import java.util.List;

@Dao
public abstract class TripDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void save(TripEntity trip);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public abstract long insertMissing(TripEntity trip);

    @Query("SELECT * FROM trips")
    public abstract List<TripEntity> all();

    @Query("SELECT * FROM trips")
    public abstract LiveData<List<TripEntity>> observe();

    @Query("SELECT * FROM trips WHERE id = :id")
    protected abstract TripEntity find(String id);

    @Query("DELETE FROM trips WHERE id = :id")
    protected abstract void delete(String id);

    @Transaction
    public TripEntity remove(TripEntity displayed) {
        if (!displayed.sameFinish(find(displayed.id))) return null;
        delete(displayed.id);
        return displayed;
    }
}
