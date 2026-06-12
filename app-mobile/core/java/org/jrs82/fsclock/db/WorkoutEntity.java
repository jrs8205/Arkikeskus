package org.jrs82.fsclock.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Yksi lenkki (kävely/pyöräily). Kesto kertyy elapsedRealtime-deltoista movingTimeMs-kenttään —
 *  sitä EI lasketa endedAt−startedAt:sta (pause + mahdolliset kellonsiirrot vääristäisivät).
 *  Askelkentät ovat lenkin OMA baseline-kirjanpito TYPE_STEP_COUNTER-anturille (kumulatiivinen
 *  laitteen bootista): steps = cum − baseline − excluded; reboot kesken lenkin siirtää kertymän
 *  committed-kenttään ja aloittaa uuden baselinen. */
@Entity(tableName = "workouts")
public class WorkoutEntity {

    public static final int TYPE_WALK = 0;
    public static final int TYPE_BIKE = 1;

    public static final int STATUS_ACTIVE = 0;
    public static final int STATUS_PAUSED = 1;
    public static final int STATUS_FINISHED = 2;
    public static final int STATUS_DISCARDED = 3;

    @PrimaryKey(autoGenerate = true)
    public long id;
    public int type;
    public int status;
    public long startedAtMs;
    public Long endedAtMs;
    public long movingTimeMs;
    public double distanceM;
    public float maxSpeedMps;
    public long steps;
    public float stepBaselineCum;
    public long stepCommitted;
    public float lastCumSeen;
    public long stepsExcluded;
    public double elevGainM;
    public double elevLossM;
    public int kcal;
    public boolean autoStopped;
    public Long pauseStartedMs;
    public long updatedAtMs;
    /** Käyttäjän antama nimi ("Koiralenkki"); null/tyhjä → UI näyttää oletusnimen. */
    public String name;
}
