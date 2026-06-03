package org.jrs82.fsclock.ruuvi;

import android.content.Context;
import android.util.Log;

import org.jrs82.fsclock.db.FsClockDb;
import org.jrs82.fsclock.db.RuuviSampleEntity;
import org.jrs82.fsclock.db.RuuviSamplesDao;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Singleton: keskittää RuuviTag-skannauksen, säilyttää tuoreimman mittauksen
 *  per MAC muistissa, kirjoittaa Roomiin korkeintaan 5 min välein per anturi
 *  (mittaussekvenssin avulla dedup). Ilmoittaa kuuntelijoille jokaisesta uudesta
 *  mittauksesta. */
public class RuuviRepository implements RuuviScanner.Listener {

    private static final String TAG = "RuuviRepo";
    /** Min. tallennusväli per MAC. */
    static final long PERSIST_INTERVAL_MS = 5L * 60_000L;

    private static volatile RuuviRepository instance;

    public static RuuviRepository get(Context ctx) {
        if (instance == null) {
            synchronized (RuuviRepository.class) {
                if (instance == null) instance = new RuuviRepository(ctx.getApplicationContext());
            }
        }
        return instance;
    }

    public interface Listener {
        /** Kutsutaan UI-säikeen ulkopuolella. */
        void onRuuviUpdate(String mac, RuuviSample sample);
    }

    private final Context appCtx;
    private final RuuviScanner scanner;
    private final ExecutorService dbIo = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private final Map<String, RuuviSample> latest = new ConcurrentHashMap<>();
    /** Viimeisin persist-aikaleima per MAC. */
    private final Map<String, Long> lastPersistAt = new ConcurrentHashMap<>();
    /** Viimeisin persistoitu mittaussekvenssi per MAC — auttaa dedup-suodatuksessa. */
    private final Map<String, Integer> lastPersistSeq = new ConcurrentHashMap<>();

    private RuuviRepository(Context ctx) {
        this.appCtx = ctx;
        this.scanner = new RuuviScanner(ctx, this);
    }

    public boolean start() {
        return scanner.start();
    }

    public void stop() {
        scanner.stop();
    }

    public boolean isScanning() { return scanner.isRunning(); }

    public RuuviSample getLatest(String mac) {
        if (mac == null) return null;
        return latest.get(mac.toUpperCase());
    }

    /** Palauttaa kopion kaikista tunnetuista antureista (snapshot). */
    public Map<String, RuuviSample> snapshot() {
        return new LinkedHashMap<>(latest);
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        if (l != null) listeners.remove(l);
    }

    @Override
    public void onRuuviSample(RuuviSample sample) {
        if (sample == null || sample.mac == null) return;
        String mac = sample.mac.toUpperCase();
        latest.put(mac, sample);

        for (Listener l : listeners) {
            try { l.onRuuviUpdate(mac, sample); } catch (Exception e) {
                Log.w(TAG, "listener heitti", e);
            }
        }

        // Persistoinnin throttle: kirjoita Roomiin korkeintaan PERSIST_INTERVAL_MS välein per MAC,
        // ja vain jos mittaussekvenssi on muuttunut viimeisestä tallennuksesta.
        long now = sample.timestamp;
        Long prev = lastPersistAt.get(mac);
        if (prev != null && (now - prev) < PERSIST_INTERVAL_MS) return;
        Integer seqNow = sample.sequence();
        Integer seqPrev = lastPersistSeq.get(mac);
        if (seqNow != null && seqPrev != null && seqNow.equals(seqPrev)) return;

        lastPersistAt.put(mac, now);
        if (seqNow != null) lastPersistSeq.put(mac, seqNow);

        dbIo.execute(() -> persist(sample));
    }

    private void persist(RuuviSample sample) {
        try {
            RuuviSamplesDao dao = FsClockDb.get(appCtx).ruuviSamplesDao();
            RuuviSampleEntity e = new RuuviSampleEntity();
            e.mac = sample.mac;
            e.timestamp = sample.timestamp;
            e.rssi = sample.rssi;
            if (sample.packet != null) {
                e.temperatureC = sample.packet.temperatureC;
                e.humidityPct = sample.packet.humidityPct;
                e.pressurePa = sample.packet.pressurePa;
                e.accelXmG = sample.packet.accelXmG;
                e.accelYmG = sample.packet.accelYmG;
                e.accelZmG = sample.packet.accelZmG;
                e.batteryMv = sample.packet.batteryMv;
                e.txPowerDbm = sample.packet.txPowerDbm;
                e.movementCounter = sample.packet.movementCounter;
                e.measurementSequence = sample.packet.measurementSequence;
            }
            dao.insert(e);
        } catch (Exception e) {
            Log.w(TAG, "Ruuvi-persist epäonnistui", e);
        }
    }
}
