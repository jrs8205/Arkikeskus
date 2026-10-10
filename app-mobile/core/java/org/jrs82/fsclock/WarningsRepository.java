package org.jrs82.fsclock;

import android.content.Context;
import android.util.Log;

import org.jrs82.fsclock.mobile.AppLocale;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Singleton: säilyttää tuoreimmat sääoitukset muistissa, hakee niitä taustalla
 *  ja ilmoittaa kuuntelijoille kun lista muuttuu. Ei käytä Roomia: varoitukset
 *  ovat lyhytikäisiä eikä historiaa tarvita. Välimuisti on avaimistettu kielellä:
 *  kielen vaihduttua vanhan kielen listaa ei palauteta, vaan haetaan uusi. */
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

    /** Lista + kieli + hakuhetki yhtenä muuttumattomana arvona, jottei lukija näe sekakieltä. */
    private static final class Snapshot {
        final String language;
        final List<WeatherWarning> list;
        final long fetchedAt;

        Snapshot(String language, List<WeatherWarning> list, long fetchedAt) {
            this.language = language;
            this.list = list;
            this.fetchedAt = fetchedAt;
        }
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final WarningsClient client = new WarningsClient();
    private final FmiWarningDetailsClient detailsClient = new FmiWarningDetailsClient();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile Snapshot snapshot = new Snapshot("", Collections.emptyList(), 0L);
    private String inFlightLanguage = null;

    private WarningsRepository() {}

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
            l.onWarningsChanged(getLatest());
        }
    }

    public void removeListener(Listener l) {
        if (l != null) listeners.remove(l);
    }

    /** Nykyisen sovelluskielen lista (etusivu kutsuu ilman Contextia). */
    public List<WeatherWarning> getLatest() { return getLatest(currentLanguage()); }

    /** Lista annetulla kielellä; tyhjä, jos muistissa on vain toisen kielen haku. */
    public List<WeatherWarning> getLatest(String language) {
        Snapshot s = snapshot;
        return s.language.equals(language) ? s.list : Collections.emptyList();
    }

    /** Hakee uudet varoitukset jos viimeisestä kerrasta on yli REFRESH_MIN_INTERVAL_MS tai kieli vaihtui. */
    public void refreshIfStale() { refreshIfStale(currentLanguage()); }

    public void refreshIfStale(String language) {
        Snapshot s = snapshot;
        if (!isStale(System.currentTimeMillis(), s.fetchedAt, s.language, language, REFRESH_MIN_INTERVAL_MS)) return;
        refreshNow(language);
    }

    public void refreshNow() { refreshNow(currentLanguage()); }

    public void refreshNow(String lang) {
        final String language = lang == null || lang.isEmpty() ? "fi" : lang;
        synchronized (this) {
            if (language.equals(inFlightLanguage)) return;
            inFlightLanguage = language;
        }
        io.execute(() -> {
            try {
                List<WeatherWarning> base = client.fetch(language);
                sortBySeverityThenOnset(base);
                snapshot = new Snapshot(language, base, System.currentTimeMillis());
                Log.d(TAG, "Refreshed: " + base.size() + " warnings (" + language + ")");
                notifyListeners();
                try {
                    String infoKey = AppLocale.INSTANCE.fmiInfoKey(new Locale(language));
                    List<FmiWarningDetail> details = detailsClient.fetch(infoKey);
                    List<WeatherWarning> enriched = new ArrayList<>(WarningEnricher.enrich(base, details));
                    sortBySeverityThenOnset(enriched);
                    snapshot = new Snapshot(language, enriched, snapshot.fetchedAt);
                    Log.d(TAG, "Enriched with FMI details");
                    notifyListeners();
                } catch (Exception e) {
                    Log.w(TAG, "FMI details fetch failed (näytetään ilman rikastusta): " + e.toString());
                }
            } catch (Exception e) {
                Log.w(TAG, "Warnings fetch failed: " + e.getMessage());
            } finally {
                synchronized (this) {
                    if (language.equals(inFlightLanguage)) inFlightLanguage = null;
                }
            }
        });
    }

    /** Puhdas vanhenemissääntö: eri kielen välimuisti on aina vanha, muuten aikaraja. */
    static boolean isStale(long now, long fetchedAt, String cachedKey, String wantedKey, long minIntervalMs) {
        if (fetchedAt <= 0L) return true;
        if (cachedKey == null || !cachedKey.equals(wantedKey)) return true;
        return now - fetchedAt >= minIntervalMs;
    }

    /** Sovelluksen kieli prosessin sovelluskontekstista; ilman sitä (ei alustettu) suomi kuten ennen. */
    static String currentLanguage() {
        Context c = SettingsManager.get().appContextOrNull();
        return c == null ? "fi" : AppLocale.INSTANCE.language(c);
    }

    private void notifyListeners() {
        List<WeatherWarning> list = snapshot.list;
        for (Listener l : listeners) {
            try { l.onWarningsChanged(list); } catch (Exception ignored) {}
        }
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
