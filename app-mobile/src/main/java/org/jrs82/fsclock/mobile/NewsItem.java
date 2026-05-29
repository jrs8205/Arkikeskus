package org.jrs82.fsclock.mobile;

/**
 * Yksi RSS/Atom-uutisotsikko. Lähde tunnistetaan {@code feedId}:llä ja
 * näytetään {@code feedName}:lla (kopioidaan tähän, jotta UI ei tarvitse
 * NewsFeed-hakua eikä recreate-säilytys riko mitään).
 */
final class NewsItem {
    final String feedId;
    final String feedName;
    final String title;
    final String link;
    /** epoch-ms; 0 jos puuttuu. */
    final long pubTimeMs;
    /** Pikkukuvan URL; null jos syötteessä ei ollut kuvaa. */
    final String imageUrl;

    NewsItem(NewsFeed feed, String title, String link, long pubTimeMs, String imageUrl) {
        this.feedId = feed == null ? "" : feed.id;
        this.feedName = feed == null ? "" : feed.name;
        this.title = title == null ? "" : title.trim();
        this.link = link == null ? "" : link.trim();
        this.pubTimeMs = pubTimeMs;
        this.imageUrl = (imageUrl == null || imageUrl.trim().isEmpty()) ? null : imageUrl.trim();
    }
}
