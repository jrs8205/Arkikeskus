package org.jrs82.fsclock;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/** Pörssisähkön varalähde: spot-hinta.fi:n avoin rajapinta, joka hakee hinnat ensisijaisesti
 *  ENTSO-E:n Transparency Platformista eli Eleringistä riippumatta.
 *
 *  https://api.spot-hinta.fi/TodayAndDayForward?region=FI&priceResolution=15
 *
 *  Vastaus on lista {DateTime (ISO, Suomen aikaa), PriceNoTax (€/kWh, ALV 0 %)} kuluvalle ja
 *  seuraavalle päivälle; huominen puuttuu kunnes se on julkaistu. snt/kWh = PriceNoTax × 100.
 *
 *  Rajapinta sallii yhden pyynnön minuutissa per IP-osoite (ylitys → HTTP 429), joten kutsutiheyttä
 *  rajoittaa {@link ElectricityFetcher}. */
public final class ElectricityFallbackClient implements ElectricityFetcher.Source {

    private static final String TAG = "ElectricityFallback";
    private static final String URL_TODAY_AND_TOMORROW =
            "https://api.spot-hinta.fi/TodayAndDayForward?region=FI&priceResolution=15";
    private static final int TIMEOUT_MS = 15000;

    @Override
    public ElectricityData fetchRange(long startMs, long endMs) throws Exception {
        Log.d(TAG, "GET " + URL_TODAY_AND_TOMORROW);
        ElectricityData data = parse(httpGet(URL_TODAY_AND_TOMORROW), startMs, endMs);
        data.fetchedAt = System.currentTimeMillis();
        Log.d(TAG, "spot-hinta.fi " + data.quarters.size() + " hintaa");
        return data;
    }

    /** Jäsentää vastauksen ja pitää vartit, joiden alku on välillä [startMs, endMs). Rivi, jolta
     *  puuttuu kelvollinen aika tai hinta, ohitetaan; muu kuin JSON-lista on virhe. */
    public static ElectricityData parse(String body, long startMs, long endMs) throws Exception {
        JSONArray rows = new JSONArray(body);
        ElectricityData data = new ElectricityData();
        data.source = ElectricityData.SOURCE_SPOT_HINTA;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || row.isNull("PriceNoTax")) continue;
            double eurPerKwh = row.optDouble("PriceNoTax", Double.NaN);
            if (Double.isNaN(eurPerKwh)) continue;
            long ts;
            try {
                ts = OffsetDateTime.parse(row.optString("DateTime", "")).toInstant().toEpochMilli();
            } catch (DateTimeParseException e) {
                continue;
            }
            if (ts < startMs || ts >= endMs) continue;
            data.quarters.add(ElectricityData.quarter(ts, eurPerKwh * 100.0));
        }
        data.quarters.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
        return data;
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "Arkikeskus/" + BuildConfig.VERSION_NAME + " (Android)");
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new RuntimeException("HTTP " + code + " " + conn.getResponseMessage());
            }
            try (InputStream is = conn.getInputStream();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                return baos.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }
}
