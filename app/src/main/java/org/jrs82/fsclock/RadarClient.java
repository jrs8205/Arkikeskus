package org.jrs82.fsclock;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class RadarClient {

    private static final String TAG = "RadarClient";
    private static final String WMS_BASE = "https://openwms.fmi.fi/geoserver/wms";
    private static final int TIMEOUT_MS = 15_000;
    private static final long SLOT_MS = 5L * 60_000L;

    public long[] computeFrameTimes(int count) {
        long now = System.currentTimeMillis();
        long latest = (now / SLOT_MS) * SLOT_MS;
        long[] times = new long[count];
        for (int i = 0; i < count; i++) {
            times[i] = latest - (long)(count - 1 - i) * SLOT_MS;
        }
        return times;
    }

    public Bitmap fetchFrame(long timeMs, int width, int height) throws Exception {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        String timeStr = iso.format(new Date(timeMs));

        String url = WMS_BASE
                + "?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap"
                + "&LAYERS=Radar:suomi_rr_eureffin"
                + "&STYLES="
                + "&SRS=EPSG:4326"
                + "&BBOX=17.5,58.0,33.5,72.0"
                + "&WIDTH=" + width
                + "&HEIGHT=" + height
                + "&FORMAT=image/png"
                + "&TRANSPARENT=true"
                + "&BGCOLOR=0x0A0A1E"
                + "&TIME=" + timeStr;

        Log.d(TAG, "GET " + url);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new RuntimeException("HTTP " + code + " " + conn.getResponseMessage());
            }
            try (InputStream is = conn.getInputStream()) {
                Bitmap bmp = BitmapFactory.decodeStream(is);
                if (bmp == null) throw new RuntimeException("Null bitmap from WMS");
                return bmp;
            }
        } finally {
            conn.disconnect();
        }
    }
}
