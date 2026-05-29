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

    /** Yhdistetty virta kaikista päällä olevista lähteistä, uusin ensin. */
    List<NewsItem> peekEnabled(SharedPreferences prefs) {
        List<NewsItem> out = new ArrayList<>();
        for (NewsFeed f : NewsFeedStore.enabledFeeds(prefs)) {
            appendCached(out, f.id);
        }
        sortByTime(out);
        return out;
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
        List<NewsFeed> toFetch = new ArrayList<>();
        for (NewsFeed f : NewsFeedStore.enabledFeeds(prefs)) {
            CacheEntry entry;
            synchronized (cache) {
                entry = cache.get(f.id);
            }
            if (forced || entry == null || (now - entry.timestamp) >= CACHE_TTL_MS) {
                toFetch.add(f);
            }
        }
        if (!toFetch.isEmpty()) {
            List<Future<?>> futures = new ArrayList<>();
            for (NewsFeed f : toFetch) {
                futures.add(executor.submit(() -> refresh(f)));
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

    private void refresh(NewsFeed feed) {
        try {
            List<NewsItem> items = RssClient.fetch(feed);
            synchronized (cache) {
                cache.put(feed.id, new CacheEntry(items, System.currentTimeMillis()));
            }
        } catch (Exception e) {
            Log.w(TAG, feed.name + " fetch failed: " + e.getMessage());
            // Säilytetään aiempi cache jos sellainen on; muuten tallennetaan
            // tyhjä lista ettei hakua yritetä joka swipellä uudestaan.
            synchronized (cache) {
                if (!cache.containsKey(feed.id)) {
                    cache.put(feed.id, new CacheEntry(
                            Collections.<NewsItem>emptyList(), System.currentTimeMillis()));
                }
            }
        }
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

        CacheEntry(List<NewsItem> items, long timestamp) {
            this.items = items;
            this.timestamp = timestamp;
        }
    }
}
