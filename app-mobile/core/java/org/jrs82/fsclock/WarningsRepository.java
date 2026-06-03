package org.jrs82.fsclock;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Singleton: säilyttää tuoreimmat sääoitukset muistissa, hakee niitä taustalla
 *  ja ilmoittaa kuuntelijoille kun lista muuttuu. Ei käytä Roomia: varoitukset
 *  ovat lyhytikäisiä eikä historiaa tarvita. */
public class WarningsRepository {

    private static final String TAG = "WarningsRepo";
    private static final long REFRESH_MIN_INTERVAL_MS = 12L * 60_000L;

    private static volatile WarningsRepository instance;

    public static WarningsRepository get() {
        if (instance == null) {
            synchronized (WarningsRepository.class) {
                if (instance == null) instance = new WarningsRepository();
            }
        }
        return instance;
    }

    public interface Listener {
        void onWarningsChanged(List<WeatherWarning> warnings);
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final WarningsClient client = new WarningsClient();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile List<WeatherWarning> latest = Collections.emptyList();
    private volatile long lastFetchAt = 0L;
    private volatile boolean inFlight = false;

    private WarningsRepository() {}

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
            l.onWarningsChanged(latest);
        }
    }

    public void removeListener(Listener l) {
        if (l != null) listeners.remove(l);
    }

    public List<WeatherWarning> getLatest() { return latest; }

    /** Hakee uudet varoitukset jos viimeisestä kerrasta on yli REFRESH_MIN_INTERVAL_MS. */
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
                sortBySeverityThenOnset(list);
                latest = list;
                lastFetchAt = System.currentTimeMillis();
                Log.d(TAG, "Refreshed: " + list.size() + " warnings");
                for (Listener l : listeners) {
                    try { l.onWarningsChanged(latest); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.w(TAG, "Warnings fetch failed: " + e.getMessage());
            } finally {
                inFlight = false;
            }
        });
    }

    private static void sortBySeverityThenOnset(List<WeatherWarning> list) {
        List<WeatherWarning> tmp = new ArrayList<>(list);
        tmp.sort((a, b) -> {
            // Ei-marine ensin, marine loppuun
            if (a.marine != b.marine) return a.marine ? 1 : -1;
            int sev = Integer.compare(b.level.rank(), a.level.rank());
            if (sev != 0) return sev;
            return Long.compare(a.onsetMs, b.onsetMs);
        });
        list.clear();
        list.addAll(tmp);
    }
}
