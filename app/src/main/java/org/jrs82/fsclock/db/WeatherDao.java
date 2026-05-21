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

    /** Half-open väli [start, end). FMI:n havaintorivi klo 00:00 kuuluu vain
     *  alkavalle päivälle, ei edelliselle, joten päivätilastot eivät tuplaa
     *  yötä päivärajan yli. */
    @Query("SELECT * FROM weather_samples WHERE channel = :ch AND timestamp >= :start AND timestamp < :end ORDER BY timestamp")
    List<WeatherSample> getSamplesBetween(String ch, long start, long end);

    @Query("DELETE FROM weather_samples WHERE timestamp < :cutoff")
    int deleteOlderThan(long cutoff);

    @Query("SELECT COUNT(*) FROM weather_samples")
    long count();

    @Query("SELECT * FROM weather_samples ORDER BY channel, timestamp")
    List<WeatherSample> getAll();

    @Query("SELECT * FROM weather_samples WHERE channel = :ch ORDER BY timestamp")
    List<WeatherSample> getByChannel(String ch);

    /** Kaikki FMI-säähavaintokanavat (fmi_<paikka>) — käytetään ihmisluettavassa
     *  sää-CSV-viennissä. Alaviiva escapataan, jotta LIKE-jokeri ei mätsäisi
     *  yksittäistä merkkiä. */
    @Query("SELECT * FROM weather_samples WHERE channel LIKE 'fmi\\_%' ESCAPE '\\' "
            + "ORDER BY channel, timestamp")
    List<WeatherSample> getWeatherChannels();

    /** Kanavien luettelo DailyStat-listausta varten (HistoryActivity). */
    @Query("SELECT DISTINCT channel FROM weather_samples ORDER BY channel")
    List<String> listChannels();

    @Query("DELETE FROM weather_samples")
    int clear();
}
