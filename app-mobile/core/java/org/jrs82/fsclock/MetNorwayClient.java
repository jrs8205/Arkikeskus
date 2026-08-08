package org.jrs82.fsclock;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

/** MET Norwayn Locationforecast 2.0 -client (compact-variantti), 9 vrk globaali ennuste
 *  ilman avainta. Käyttöehdot vaativat tunnistavan User-Agentin ja gzip-tuen; koordinaatit
 *  katkaistaan 4 desimaaliin (5+ desimaalia palauttaa 403). Tunnit kattavat ~2,5 vrk,
 *  loput päivät ovat 6 h -lohkoja UTC-rajoilla (Hour.blockHours=6).
 *  https://api.met.no/weatherapi/locationforecast/2.0/documentation */
public class MetNorwayClient {

    private static final String TAG = "MetNorwayClient";
    private static final String BASE = "https://api.met.no/weatherapi/locationforecast/2.0/compact";
    private static final String USER_AGENT =
            "Arkikeskus/" + BuildConfig.VERSION_NAME + " github.com/jrs8205/Arkikeskus";
    private static final int TIMEOUT_MS = 15000;

    private final double lat;
    private final double lon;

    public MetNorwayClient(double lat, double lon) {
        this.lat = lat;
        this.lon = lon;
    }

    public WeatherData fetch() throws Exception {
        String url = BASE + "?lat=" + trunc4(lat) + "&lon=" + trunc4(lon);
        Log.d(TAG, "GET " + url);
        String body = httpGet(url);
        WeatherData data = parse(body);
        data.fetchedAt = System.currentTimeMillis();
        data.forecastFetchedAt = data.fetchedAt;
        Log.d(TAG, "MET Norway — " + data.hours.size() + " riviä");
        return data;
    }

    /** Käyttöehdot rajaavat koordinaattitarkkuuden 4 desimaaliin. */
    private static String trunc4(double v) {
        return String.format(Locale.US, "%.4f", v);
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept-Encoding", "gzip");
        try {
            int code = conn.getResponseCode();
            if (code != 200 && code != 203) {
                InputStream es = conn.getErrorStream();
                if (es != null) {
                    try (InputStream in = es) {
                        byte[] buf = new byte[4096];
                        while (in.read(buf) > 0) { /* tyhjennys yhteyden kierrätystä varten */ }
                    } catch (Exception ignored) { }
                }
                throw new RuntimeException("HTTP " + code + " " + conn.getResponseMessage());
            }
            InputStream raw = conn.getInputStream();
            InputStream is = "gzip".equalsIgnoreCase(conn.getContentEncoding())
                    ? new GZIPInputStream(raw) : raw;
            try (InputStream in = is; ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
                return baos.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }

    private WeatherData parse(String body) throws Exception {
        WeatherData data = new WeatherData();
        JSONObject root = new JSONObject(body);
        JSONArray series = root.getJSONObject("properties").getJSONArray("timeseries");

        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        Calendar cal = Calendar.getInstance(TimeZone.getDefault());

        for (int i = 0; i < series.length(); i++) {
            JSONObject entry = series.getJSONObject(i);
            Date time = iso.parse(entry.getString("time"));
            if (time == null) continue;
            long ts = time.getTime();

            JSONObject d = entry.getJSONObject("data");
            JSONObject instant = d.getJSONObject("instant").getJSONObject("details");
            JSONObject next1 = d.optJSONObject("next_1_hours");
            JSONObject next6 = d.optJSONObject("next_6_hours");

            double temp = instant.optDouble("air_temperature", Double.NaN);
            double rh = instant.optDouble("relative_humidity", Double.NaN);
            double wind = instant.optDouble("wind_speed", Double.NaN);
            double clouds = instant.optDouble("cloud_area_fraction", Double.NaN);

            String symbol = null;
            double precip = Double.NaN;
            int blockHours = 1;
            if (next1 != null) {
                JSONObject sum = next1.optJSONObject("summary");
                if (sum != null) symbol = sum.optString("symbol_code", null);
                JSONObject det = next1.optJSONObject("details");
                if (det != null) precip = det.optDouble("precipitation_amount", Double.NaN);
            } else if (next6 != null) {
                blockHours = 6;
                JSONObject sum = next6.optJSONObject("summary");
                if (sum != null) symbol = sum.optString("symbol_code", null);
                JSONObject det = next6.optJSONObject("details");
                if (det != null) precip = det.optDouble("precipitation_amount", Double.NaN);
            }

            WeatherData.Hour row = new WeatherData.Hour();
            row.timestamp = ts;
            cal.setTimeInMillis(ts);
            row.hour = cal.get(Calendar.HOUR_OF_DAY);
            row.dayOfMonth = cal.get(Calendar.DAY_OF_MONTH);
            row.month = cal.get(Calendar.MONTH) + 1;
            row.temperature = temp;
            row.precipitation = precip;
            row.windSpeed = wind;
            row.humidity = rh;
            row.blockHours = blockHours;
            row.condition = symbol != null
                    ? WeatherCondition.fromMetSymbol(symbol)
                    : WeatherCondition.inferFromValues(temp, precip, clouds,
                        WeatherIconView.isNightHour(row.hour, row.month));
            data.hours.add(row);
        }
        return data;
    }
}
