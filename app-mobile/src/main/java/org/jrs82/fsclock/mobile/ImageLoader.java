package org.jrs82.fsclock.mobile;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kevyt asynkroninen kuvanlataaja uutisten pikkukuville. Ei ulkoisia
 * riippuvuuksia: HttpURLConnection + BitmapFactory + muisti-LruCache.
 *
 * Lista rakennetaan ohjelmallisesti (ei RecyclerView-kierrätystä), mutta
 * ImageView:n tagilla varmistetaan silti ettei myöhästynyt lataus piirrä
 * väärää kuvaa jos sama view:tä käytettäisiin uudelleen.
 */
final class ImageLoader {

    private static final int TIMEOUT_MS = 8000;
    /** Pikkukuvan tavoiteleveys px; isompi data alinäytteistetään tähän. */
    private static final int TARGET_PX = 160;
    private static final int MAX_BYTES = 3_000_000;

    private static volatile ImageLoader INSTANCE;

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache;

    private ImageLoader() {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        cache = new LruCache<String, Bitmap>(Math.max(2048, maxKb)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    static ImageLoader get() {
        if (INSTANCE == null) {
            synchronized (ImageLoader.class) {
                if (INSTANCE == null) INSTANCE = new ImageLoader();
            }
        }
        return INSTANCE;
    }

    void load(String url, ImageView target, int placeholderRes) {
        if (url == null || url.trim().isEmpty()) {
            target.setTag(null);
            target.setImageResource(placeholderRes);
            return;
        }
        final String key = url.trim();
        target.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        target.setImageResource(placeholderRes);
        executor.execute(() -> {
            final Bitmap bmp = download(key);
            if (bmp == null) return;
            cache.put(key, bmp);
            main.post(() -> {
                if (key.equals(target.getTag())) {
                    target.setImageBitmap(bmp);
                }
            });
        });
    }

    private Bitmap download(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Arkikeskus (Android)");
            if (conn.getResponseCode() != 200) return null;
            byte[] data = readAll(conn.getInputStream());
            if (data == null) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            opts.inSampleSize = sampleSize(opts.outWidth, opts.outHeight, TARGET_PX);
            opts.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream is) throws Exception {
        if (is == null) return null;
        try (InputStream in = is;
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                baos.write(buf, 0, n);
                if (baos.size() > MAX_BYTES) return null;
            }
            return baos.toByteArray();
        }
    }

    private static int sampleSize(int w, int h, int target) {
        if (w <= 0 || h <= 0) return 1;
        int sample = 1;
        while (w / sample > target * 2 || h / sample > target * 2) {
            sample *= 2;
        }
        return sample;
    }
}
