package org.jrs82.fsclock;

import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Singleton: säilyttää FMI:n 5 vrk:n varoitukset muistissa ja ilmoittaa kuuntelijoille.
 *  Päivä-/maakuntavalinta on UI-suodatin tähän välimuistiin (ei lisähakuja). */
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

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final FmiWarningsClient client = new FmiWarningsClient();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile List<WeatherWarning> latest = Collections.emptyList();
    private volatile long lastFetchAt = 0L;
    private volatile boolean inFlight = false;

    private FmiWarningsRepository() {}

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) { listeners.add(l); l.onWarningsChanged(latest); }
    }
    public void removeListener(Listener l) { if (l != null) listeners.remove(l); }
    public List<WeatherWarning> getLatest() { return latest; }

    public void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (inFlight) return;
        if (now - lastFetchAt < REFRESH_MIN_INTERVAL_MS && lastFetchAt > 0L) return;
        refreshNow();
    }

    public void refreshNow() {
        if (inFlight) return;
        inFlight = true;
        io.execute(() -> {
            try {
                List<WeatherWarning> list = client.fetch();
                latest = list;
                lastFetchAt = System.currentTimeMillis();
                Log.d(TAG, "Refreshed: " + list.size() + " FMI 5d warnings");
                for (Listener l : listeners) {
                    try { l.onWarningsChanged(latest); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.w(TAG, "FMI 5d fetch failed: " + e);
            } finally {
                inFlight = false;
            }
        });
    }
}
