package org.jrs82.fsclock.mobile;

/**
 * Yksi RSS/Atom-uutisotsikko.
 */
final class NewsItem {
    final NewsSource source;
    final String title;
    final String link;
    /** epoch-ms; 0 jos puuttuu. */
    final long pubTimeMs;
    /** Pikkukuvan URL; null jos syötteessä ei ollut kuvaa. */
    final String imageUrl;

    NewsItem(NewsSource source, String title, String link, long pubTimeMs, String imageUrl) {
        this.source = source;
        this.title = title == null ? "" : title.trim();
        this.link = link == null ? "" : link.trim();
        this.pubTimeMs = pubTimeMs;
        this.imageUrl = (imageUrl == null || imageUrl.trim().isEmpty()) ? null : imageUrl.trim();
    }
}
