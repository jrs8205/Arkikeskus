package org.jrs82.fsclock.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Yksi RuuviTagin RAWv2-paketin (data format 5) tietorivi.
 *  Kanava on aina anturin MAC; käyttäjälle näkyvä nimi (Makuuhuone/Olohuone/Parveke)
 *  on SettingsManagerissa, ei tässä. Mittaussekvenssi sallii duplikaattien
 *  suodattamisen kun sama paketti tulee usean BLE-mainosrungon kautta. */
@Entity(
    tableName = "ruuvi_samples",
    indices = {
        @Index(value = {"mac", "timestamp"}),
        @Index(value = {"mac", "measurement_sequence"})
    }
)
public class RuuviSampleEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "mac")
    public String mac = "";

    @ColumnInfo(name = "timestamp")
    public long timestamp;

    @ColumnInfo(name = "temperature_c")
    @Nullable
    public Double temperatureC;

    @ColumnInfo(name = "humidity_pct")
    @Nullable
    public Double humidityPct;

    @ColumnInfo(name = "pressure_pa")
    @Nullable
    public Integer pressurePa;

    @ColumnInfo(name = "accel_x_mg")
    @Nullable
    public Integer accelXmG;

    @ColumnInfo(name = "accel_y_mg")
    @Nullable
    public Integer accelYmG;

    @ColumnInfo(name = "accel_z_mg")
    @Nullable
    public Integer accelZmG;

    @ColumnInfo(name = "battery_mv")
    @Nullable
    public Integer batteryMv;

    @ColumnInfo(name = "tx_power_dbm")
    @Nullable
    public Integer txPowerDbm;

    @ColumnInfo(name = "movement_counter")
    @Nullable
    public Integer movementCounter;

    @ColumnInfo(name = "measurement_sequence")
    @Nullable
    public Integer measurementSequence;

    @ColumnInfo(name = "rssi")
    public int rssi;

    public RuuviSampleEntity() {}
}
