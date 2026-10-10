package org.jrs82.fsclock;

import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Singleton: säilyttää FMI:n 5 vrk:n varoitukset muistissa ja ilmoittaa kuuntelijoille.
 *  Päivä-/maakuntavalinta on UI-suodatin tähän välimuistiin (ei lisähakuja). Välimuisti on
 *  avaimistettu FMI:n tekstikentällä (info_fi/sv/en), jottei kielen vaihto näytä vanhaa kieltä. */
public class FmiWarningsRepository {

    private static final String TAG = "FmiWarnRepo";
    private static final long REFRESH_MIN_INTERVAL_MS = 12L * 60_000L;

    private static volatile FmiWarningsRepository instance;

    public static FmiWarningsRepository get() {
        if (instance == null) {
            synchronized (FmiWarningsRepository.class) {
                if (instance == null) instance = new FmiWarningsRepository();
            }
        }
        return instance;
    }

    public interface Listener { void onWarningsChanged(List<WeatherWarning> warnings); }

    private static final class Snapshot {
        final String infoKey;
        final List<WeatherWarning> list;
        final long fetchedAt;

        Snapshot(String infoKey, List<WeatherWarning> list, long fetchedAt) {
            this.infoKey = infoKey;
            this.list = list;
            this.fetchedAt = fetchedAt;
        }
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final FmiWarningsClient client = new FmiWarningsClient();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile Snapshot snapshot = new Snapshot("", Collections.emptyList(), 0L);
    private String inFlightKey = null;

    private FmiWarningsRepository() {}

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) { listeners.add(l); l.onWarningsChanged(snapshot.list); }
    }
    public void removeListener(Listener l) { if (l != null) listeners.remove(l); }

    /** Lista annetulla tekstikentällä; tyhjä, jos muistissa on vain toisen kielen haku. */
    public List<WeatherWarning> getLatest(String infoKey) {
        Snapshot s = snapshot;
        return s.infoKey.equals(infoKey) ? s.list : Collections.emptyList();
    }

    public void refreshIfStale(String infoKey) {
        Snapshot s = snapshot;
        if (!WarningsRepository.isStale(System.currentTimeMillis(), s.fetchedAt, s.infoKey, infoKey,
                REFRESH_MIN_INTERVAL_MS)) return;
        refreshNow(infoKey);
    }

    public void refreshNow(String key) {
        final String infoKey = key == null || key.isEmpty() ? FmiWarningDetailsClient.INFO_FI : key;
        synchronized (this) {
            if (infoKey.equals(inFlightKey)) return;
            inFlightKey = infoKey;
        }
        io.execute(() -> {
            try {
                List<WeatherWarning> list = client.fetch(infoKey);
                snapshot = new Snapshot(infoKey, list, System.currentTimeMillis());
                Log.d(TAG, "Refreshed: " + list.size() + " FMI 5d warnings (" + infoKey + ")");
                for (Listener l : listeners) {
                    try { l.onWarningsChanged(list); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.w(TAG, "FMI 5d fetch failed: " + e);
            } finally {
                synchronized (this) {
                    if (infoKey.equals(inFlightKey)) inFlightKey = null;
                }
            }
        });
    }
}
