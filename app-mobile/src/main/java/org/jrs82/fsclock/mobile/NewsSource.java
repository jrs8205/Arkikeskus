package org.jrs82.fsclock.mobile;

/**
 * Käyttäjälle tarjottavat suomalaiset uutislähteet RSS-feedeinä.
 * displayName on UI:ssa näkyvä nimi, prefKey on SharedPreferences-avain
 * päälle/pois-asetusta varten. Oletuksena kaikki päällä.
 */
enum NewsSource {
    YLE("YLE", "https://feeds.yle.fi/uutiset/v1/majorHeadlines/YLE_UUTISET.rss",
            "mobile_news_src_yle"),
    HS("HS", "https://www.hs.fi/rss/tuoreimmat.xml",
            "mobile_news_src_hs"),
    IS("Ilta-Sanomat", "https://www.is.fi/rss/tuoreimmat.xml",
            "mobile_news_src_is"),
    ILTALEHTI("Iltalehti", "https://www.iltalehti.fi/rss/uutiset.xml",
            "mobile_news_src_il"),
    MTV("MTV Uutiset", "https://www.mtvuutiset.fi/api/feed/rss/uutiset_uusimmat",
            "mobile_news_src_mtv"),
    TIVI("Tivi", "https://www.tivi.fi/feeds/rss.xml",
            "mobile_news_src_tivi"),
    TT("Tekniikka & Talous", "https://www.tekniikkatalous.fi/feeds/rss.xml",
            "mobile_news_src_tt"),
    MIKROBITTI("Mikrobitti", "https://www.mikrobitti.fi/feeds/rss.xml",
            "mobile_news_src_mb"),
    MOBIILI("Mobiili.fi", "https://mobiili.fi/feed/",
            "mobile_news_src_mobiili"),
    REDDIT_SUOMI("r/Suomi", "https://www.reddit.com/r/Suomi/.rss",
            "mobile_news_src_reddit_suomi");

    final String displayName;
    final String url;
    final String prefKey;

    NewsSource(String displayName, String url, String prefKey) {
        this.displayName = displayName;
        this.url = url;
        this.prefKey = prefKey;
    }
}
