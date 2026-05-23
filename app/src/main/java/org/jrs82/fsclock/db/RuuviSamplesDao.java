package org.jrs82.fsclock.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface RuuviSamplesDao {

    @Insert
    long insert(RuuviSampleEntity entity);

    @Query("SELECT timestamp FROM ruuvi_samples WHERE mac = :mac ORDER BY timestamp DESC LIMIT 1")
    Long lastTimestampForMac(String mac);

    @Query("SELECT measurement_sequence FROM ruuvi_samples WHERE mac = :mac ORDER BY timestamp DESC LIMIT 1")
    Integer lastSequenceForMac(String mac);

    @Query("SELECT COUNT(*) FROM ruuvi_samples")
    long count();

    @Query("SELECT COUNT(DISTINCT mac) FROM ruuvi_samples")
    int distinctMacCount();

    @Query("SELECT * FROM ruuvi_samples WHERE mac = :mac AND timestamp >= :fromMs AND timestamp <= :toMs ORDER BY timestamp ASC")
    List<RuuviSampleEntity> rangeForMac(String mac, long fromMs, long toMs);

    @Query("SELECT DISTINCT mac FROM ruuvi_samples ORDER BY mac")
    List<String> listMacs();

    /** Päiväkohtainen aggregaatti yhdelle MAC:lle. 'localtime' käyttää laitteen
     *  aikavyöhykettä (Europe/Helsinki tabletille), joten päiväraja on paikallinen. */
    @Query("SELECT date(timestamp/1000, 'unixepoch', 'localtime') AS day, "
            + "MIN(temperature_c) AS minT, "
            + "MAX(temperature_c) AS maxT, "
            + "AVG(temperature_c) AS avgT, "
            + "AVG(humidity_pct) AS avgH, "
            + "COUNT(*) AS cnt "
            + "FROM ruuvi_samples "
            + "WHERE mac = :mac AND timestamp >= :fromMs AND timestamp < :toMs "
            + "GROUP BY day ORDER BY day ASC")
    List<RuuviDailyAggregate> dailyAggregate(String mac, long fromMs, long toMs);

    @Query("DELETE FROM ruuvi_samples WHERE timestamp < :cutoffMs")
    int deleteOlderThan(long cutoffMs);

    @Query("DELETE FROM ruuvi_samples")
    int deleteAll();
}
