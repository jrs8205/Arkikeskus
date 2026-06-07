package org.jrs82.fsclock.mobile;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Pitää välimuistia uutislähteittäin (avaimena {@link NewsFeed#id}).
 * fetchEnabled() hakee rinnakkain kaikki käyttäjän asetuksissa päällä olevat
 * lähteet ja yhdistää tuloksen aikajärjestykseen (uusin ensin). peekForFeed()
 * palauttaa yhden lähteen välimuistin per-lähde-widgettiä varten.
 */
final class RssRepository {

    private static final String TAG = "RssRepository";
    private static final long CACHE_TTL_MS = 10L * 60_000L;
    private static final long HARD_TIMEOUT_MS = 15L * 1000L;

    private static volatile RssRepository INSTANCE;

    private final Map<String, CacheEntry> cache = new HashMap<>();
    /** Invalidointigeneraatio estää ennen URL-muutosta alkaneen haun kirjoittamisen takaisin cacheen. */
    private final Map<String, Long> cacheGenerations = new HashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    private RssRepository() {}

    static RssRepository get() {
        if (INSTANCE == null) {
            synchronized (RssRepository.class) {
                if (INSTANCE == null) INSTANCE = new RssRepository();
            }
        }
        return INSTANCE;
    }

    /** Tyhjentää yhden lähteen välimuistin (esim. URL:n muutos → heti tuoreet uutiset). */
    void invalidate(String feedId) {
        if (feedId == null) return;
        synchronized (cache) {
            cache.remove(feedId);
            cacheGenerations.put(feedId, generationLocked(feedId) + 1L);
        }
    }

    /** Yhdistetty virta kaikista päällä olevista lähteistä, uusin ensin. */
    List<NewsItem> peekEnabled(SharedPreferences prefs) {
        List<NewsItem> out = new ArrayList<>();
        for (NewsFeed f : NewsFeedStore.enabledFeeds(prefs)) {
            appendCached(out, f.id);
        }
        sortByTime(out);
        return out;
    }

    /** Hakee (tarvittaessa) yhden lähteen ja palauttaa sen otsikot uusin ensin. Käytetään
     *  etusivun per-lähde-uutiskortissa riippumatta siitä onko lähde "päällä" yhdistetyssä virrassa. */
    List<NewsItem> fetchForFeed(NewsFeed feed, boolean forced) {
        if (feed == null) return new ArrayList<>();
        long now = System.currentTimeMillis();
        CacheEntry entry;
        long generation;
        synchronized (cache) {
            entry = cache.get(feed.id);
            generation = generationLocked(feed.id);
        }
        if (forced || entry == null || !entry.sourceUrl.equals(feed.url)
                || (now - entry.timestamp) >= CACHE_TTL_MS) {
            refresh(feed, generation);
        }
        return peekForFeed(feed.id);
    }

    /** Yhden lähteen otsikot välimuistista (per-lähde-widget), uusin ensin. */
    List<NewsItem> peekForFeed(String feedId) {
        List<NewsItem> out = new ArrayList<>();
        appendCached(out, feedId);
        sortByTime(out);
        return out;
    }

    private void appendCached(List<NewsItem> out, String feedId) {
        CacheEntry entry;
        synchronized (cache) {
            entry = cache.get(feedId);
        }
        if (entry != null && entry.items != null) out.addAll(entry.items);
    }

    List<NewsItem> fetchEnabled(SharedPreferences prefs, boolean forced) {
        long now = System.currentTimeMillis();
        List<FetchRequest> toFetch = new ArrayList<>();
        for (NewsFeed f : NewsFeedStore.enabledFeeds(prefs)) {
            CacheEntry entry;
            long generation;
            synchronized (cache) {
                entry = cache.get(f.id);
                generation = generationLocked(f.id);
            }
            if (forced || entry == null || !entry.sourceUrl.equals(f.url)
                    || (now - entry.timestamp) >= CACHE_TTL_MS) {
                toFetch.add(new FetchRequest(f, generation));
            }
        }
        if (!toFetch.isEmpty()) {
            List<Future<?>> futures = new ArrayList<>();
            for (FetchRequest request : toFetch) {
                futures.add(executor.submit(() -> refresh(request.feed, request.generation)));
            }
            long deadline = System.currentTimeMillis() + HARD_TIMEOUT_MS;
            for (Future<?> f : futures) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    f.cancel(true);
                    continue;
                }
                try {
                    f.get(remaining, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    f.cancel(true);
                }
            }
        }
        return peekEnabled(prefs);
    }

    private void refresh(NewsFeed feed, long generation) {
        try {
            List<NewsItem> items = RssClient.fetch(feed);
            synchronized (cache) {
                if (generationLocked(feed.id) == generation) {
                    cache.put(feed.id, new CacheEntry(items, System.currentTimeMillis(), feed.url));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, feed.name + " fetch failed: " + e.getMessage());
            // Säilytetään aiempi cache jos sellainen on; muuten tallennetaan
            // tyhjä lista ettei hakua yritetä joka swipellä uudestaan.
            synchronized (cache) {
                if (generationLocked(feed.id) == generation && !cache.containsKey(feed.id)) {
                    cache.put(feed.id, new CacheEntry(
                            Collections.<NewsItem>emptyList(), System.currentTimeMillis(), feed.url));
                }
            }
        }
    }

    /** Kutsuttava vain cache-lukon sisältä. */
    private long generationLocked(String feedId) {
        Long generation = cacheGenerations.get(feedId);
        return generation != null ? generation : 0L;
    }

    private static void sortByTime(List<NewsItem> items) {
        Collections.sort(items, new Comparator<NewsItem>() {
            @Override
            public int compare(NewsItem a, NewsItem b) {
                return Long.compare(b.pubTimeMs, a.pubTimeMs);
            }
        });
    }

    private static final class CacheEntry {
        final List<NewsItem> items;
        final long timestamp;
        final String sourceUrl;

        CacheEntry(List<NewsItem> items, long timestamp, String sourceUrl) {
            this.items = items;
            this.timestamp = timestamp;
            this.sourceUrl = sourceUrl == null ? "" : sourceUrl;
        }
    }

    private static final class FetchRequest {
        final NewsFeed feed;
        final long generation;

        FetchRequest(NewsFeed feed, long generation) {
            this.feed = feed;
            this.generation = generation;
        }
    }
}
