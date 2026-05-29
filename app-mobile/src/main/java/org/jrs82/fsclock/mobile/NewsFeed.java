package org.jrs82.fsclock.mobile;

/**
 * Yksi uutislähde. Korvaa aiemman kiinteän {@code NewsSource}-enumin, jotta
 * käyttäjä voi lisätä omia RSS-/Atom-syötteitä sisäänrakennettujen rinnalle.
 *
 * <p>{@code id} on pysyvä avain (käytetään SharedPreferences-toggleissa
 * {@code mobile_news_src_<id>} ja widget-id:ssä {@code news:<id>}).
 * Sisäänrakennettujen id:t säilyttävät vanhat arvot (yle, hs, is, ...), joten
 * olemassa olevat asetukset toimivat ilman migraatiota.
 */
final class NewsFeed {
    final String id;
    final String name;
    final String url;
    final boolean builtin;

    NewsFeed(String id, String name, String url, boolean builtin) {
        this.id = id;
        this.name = name == null ? "" : name.trim();
        this.url = url == null ? "" : url.trim();
        this.builtin = builtin;
    }

    /** SharedPreferences-avain tämän lähteen päälle/pois-tilalle. */
    String enabledKey() {
        return "mobile_news_src_" + id;
    }

    /** Etusivun widget-id per-lähde-kortille (yhdistetty käyttää pelkkää "news"). */
    String widgetId() {
        return "news:" + id;
    }
}
