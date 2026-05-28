package org.jrs82.fsclock.mobile;

import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Yksinkertainen RSS 2.0 + Atom 1.0 -parseri. Käyttää Androidin Xml.newPullParseria
 * eikä vaadi erillisriippuvuuksia.
 */
final class RssClient {

    private static final String TAG = "RssClient";
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_BODY_BYTES = 1_500_000;

    // Yleisimmät RSS-pubDate-formaatit. Atomilla on omat ISO-8601-formaatit.
    private static final String[] DATE_PATTERNS = {
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, d MMM yyyy HH:mm:ss zzz",
            "EEE, d MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssZ"
    };

    private RssClient() {}

    static List<NewsItem> fetch(NewsSource source) throws Exception {
        byte[] body = httpGet(source.url);
        return parse(source, body);
    }

    private static byte[] httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept",
                "application/rss+xml, application/atom+xml, application/xml, text/xml, */*");
        conn.setRequestProperty("User-Agent", "Arkikeskus Android");
        conn.setInstanceFollowRedirects(true);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                drainStream(conn.getErrorStream());
                throw new IOException("RSS HTTP " + code + " " + conn.getResponseMessage());
            }
            try (InputStream is = conn.getInputStream();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    baos.write(buf, 0, n);
                    if (baos.size() > MAX_BODY_BYTES) {
                        throw new IOException("RSS-vastaus liian iso: " + baos.size());
                    }
                }
                return baos.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static List<NewsItem> parse(NewsSource source, byte[] body) throws Exception {
        List<NewsItem> out = new ArrayList<>();
        XmlPullParser xpp = Xml.newPullParser();
        xpp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        xpp.setInput(new ByteArrayInputStream(body), null);

        boolean inItem = false;
        String title = null;
        String link = null;
        String pubText = null;

        int ev = xpp.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            String name = xpp.getName();
            if (ev == XmlPullParser.START_TAG && name != null) {
                String local = stripPrefix(name);
                if ("item".equalsIgnoreCase(local) || "entry".equalsIgnoreCase(local)) {
                    inItem = true;
                    title = null;
                    link = null;
                    pubText = null;
                } else if (inItem) {
                    if ("title".equalsIgnoreCase(local) && title == null) {
                        title = xpp.nextText();
                    } else if ("link".equalsIgnoreCase(local) && link == null) {
                        // Atom: <link href="..."/>; RSS: <link>...</link>
                        String href = attribute(xpp, "href");
                        if (href != null && !href.trim().isEmpty()) {
                            link = href;
                        } else {
                            link = xpp.nextText();
                        }
                    } else if (("pubDate".equalsIgnoreCase(local)
                            || "published".equalsIgnoreCase(local)
                            || "updated".equalsIgnoreCase(local)
                            || "date".equalsIgnoreCase(local))
                            && pubText == null) {
                        pubText = xpp.nextText();
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && name != null) {
                String local = stripPrefix(name);
                if ("item".equalsIgnoreCase(local) || "entry".equalsIgnoreCase(local)) {
                    if (title != null && !title.trim().isEmpty()
                            && link != null && !link.trim().isEmpty()) {
                        out.add(new NewsItem(source,
                                cleanTitle(title), link.trim(), parseDate(pubText)));
                    }
                    inItem = false;
                }
            }
            try {
                ev = xpp.next();
            } catch (Exception e) {
                Log.w(TAG, source.displayName + " parsing error: " + e.getMessage());
                break;
            }
        }
        return out;
    }

    private static String stripPrefix(String name) {
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static String attribute(XmlPullParser xpp, String localName) {
        for (int i = 0; i < xpp.getAttributeCount(); i++) {
            if (localName.equalsIgnoreCase(stripPrefix(xpp.getAttributeName(i)))) {
                return xpp.getAttributeValue(i);
            }
        }
        return null;
    }

    private static String cleanTitle(String raw) {
        String s = raw.trim();
        // Poista CDATA-jäänteet ja yleisimmät HTML-entiteetit
        s = s.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ");
        return s;
    }

    private static long parseDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 0L;
        String s = raw.trim();
        for (String pattern : DATE_PATTERNS) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = sdf.parse(s, new ParsePosition(0));
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    private static void drainStream(InputStream is) {
        if (is == null) return;
        try (InputStream toClose = is) {
            byte[] buf = new byte[1024];
            while (toClose.read(buf) > 0) { /* discard */ }
        } catch (IOException ignored) {
        }
    }
}
