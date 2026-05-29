package org.jrs82.fsclock.mobile;

import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Pitää kirjaa kaikista uutislähteistä: sisäänrakennetut (kovakoodattu) +
 * käyttäjän omat (tallennettu JSON-listana SharedPreferencesiin).
 *
 * <p>Sisäänrakennettujen id:t vastaavat vanhoja {@code mobile_news_src_*}
 * -prefKey-loppuosia, joten 1.2.x:n toggle-asetukset säilyvät.
 */
final class NewsFeedStore {

    private static final String TAG = "NewsFeedStore";
    static final String KEY_CUSTOM_FEEDS = "mobile_custom_news_feeds";

    private NewsFeedStore() {}

    // Sisäänrakennetut lähteet. id, näyttönimi, url. Vrt. vanha NewsSource-enum.
    private static final NewsFeed[] BUILTIN = {
            new NewsFeed("yle", "YLE",
                    "https://feeds.yle.fi/uutiset/v1/majorHeadlines/YLE_UUTISET.rss", true),
            new NewsFeed("hs", "HS",
                    "https://www.hs.fi/rss/tuoreimmat.xml", true),
            new NewsFeed("is", "Ilta-Sanomat",
                    "https://www.is.fi/rss/tuoreimmat.xml", true),
            new NewsFeed("il", "Iltalehti",
                    "https://www.iltalehti.fi/rss/uutiset.xml", true),
            new NewsFeed("mtv", "MTV Uutiset",
                    "https://www.mtvuutiset.fi/api/feed/rss/uutiset_uusimmat", true),
            new NewsFeed("tivi", "Tivi",
                    "https://www.tivi.fi/feeds/rss.xml", true),
            new NewsFeed("tt", "Tekniikka & Talous",
                    "https://www.tekniikkatalous.fi/feeds/rss.xml", true),
            new NewsFeed("mb", "Mikrobitti",
                    "https://www.mikrobitti.fi/feeds/rss.xml", true),
            new NewsFeed("mobiili", "Mobiili.fi",
                    "https://mobiili.fi/feed/", true),
            new NewsFeed("reddit_suomi", "r/Suomi",
                    "https://www.reddit.com/r/Suomi/.rss", true),
    };

    /** Kaikki lähteet: sisäänrakennetut ensin, sitten käyttäjän omat. */
    static List<NewsFeed> allFeeds(SharedPreferences prefs) {
        List<NewsFeed> out = new ArrayList<>();
        for (NewsFeed f : BUILTIN) out.add(f);
        out.addAll(customFeeds(prefs));
        return out;
    }

    /** Vain ne lähteet jotka ovat asetuksissa päällä (oletuksena kaikki päällä). */
    static List<NewsFeed> enabledFeeds(SharedPreferences prefs) {
        List<NewsFeed> out = new ArrayList<>();
        for (NewsFeed f : allFeeds(prefs)) {
            if (prefs.getBoolean(f.enabledKey(), true)) out.add(f);
        }
        return out;
    }

    static NewsFeed feedById(SharedPreferences prefs, String id) {
        if (id == null) return null;
        for (NewsFeed f : allFeeds(prefs)) {
            if (f.id.equals(id)) return f;
        }
        return null;
    }

    static List<NewsFeed> customFeeds(SharedPreferences prefs) {
        List<NewsFeed> out = new ArrayList<>();
        String raw = prefs.getString(KEY_CUSTOM_FEEDS, null);
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", null);
                String name = o.optString("name", null);
                String url = o.optString("url", null);
                if (id == null || url == null || url.trim().isEmpty()) continue;
                out.add(new NewsFeed(id, name, url, false));
            }
        } catch (Exception e) {
            Log.w(TAG, "customFeeds parse failed: " + e.getMessage());
        }
        return out;
    }

    /** Lisää oman syötteen ja palauttaa luodun lähteen (tai null jos virheellinen). */
    static NewsFeed addCustom(SharedPreferences prefs, String name, String url) {
        if (url == null || url.trim().isEmpty()) return null;
        String cleanUrl = url.trim();
        String cleanName = (name == null || name.trim().isEmpty())
                ? hostFromUrl(cleanUrl) : name.trim();
        String id = "custom_" + System.currentTimeMillis();
        List<NewsFeed> custom = customFeeds(prefs);
        custom.add(new NewsFeed(id, cleanName, cleanUrl, false));
        persist(prefs, custom);
        return custom.get(custom.size() - 1);
    }

    static void updateCustom(SharedPreferences prefs, String id, String name, String url) {
        if (id == null) return;
        List<NewsFeed> custom = customFeeds(prefs);
        List<NewsFeed> updated = new ArrayList<>();
        for (NewsFeed f : custom) {
            if (f.id.equals(id)) {
                String cleanUrl = (url == null || url.trim().isEmpty()) ? f.url : url.trim();
                String cleanName = (name == null || name.trim().isEmpty())
                        ? hostFromUrl(cleanUrl) : name.trim();
                updated.add(new NewsFeed(id, cleanName, cleanUrl, false));
            } else {
                updated.add(f);
            }
        }
        persist(prefs, updated);
    }

    static void removeCustom(SharedPreferences prefs, String id) {
        if (id == null) return;
        List<NewsFeed> custom = customFeeds(prefs);
        List<NewsFeed> kept = new ArrayList<>();
        for (NewsFeed f : custom) {
            if (!f.id.equals(id)) kept.add(f);
        }
        persist(prefs, kept);
        // Siivoa myös toggle- ja widget-näkyvyysavaimet ettei jää roskaa.
        prefs.edit()
                .remove("mobile_news_src_" + id)
                .remove("mobile_show_news_feed_" + id)
                .apply();
    }

    private static void persist(SharedPreferences prefs, List<NewsFeed> custom) {
        JSONArray arr = new JSONArray();
        for (NewsFeed f : custom) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", f.id);
                o.put("name", f.name);
                o.put("url", f.url);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        prefs.edit().putString(KEY_CUSTOM_FEEDS, arr.toString()).apply();
    }

    private static String hostFromUrl(String url) {
        try {
            String host = new java.net.URL(url).getHost();
            return host == null ? url : host.replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return url;
        }
    }
}
