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

/** Open-Meteon JSON-rajapinnan kevyt asiakas. Haetaan seuraavan 48 tunnin
 *  tuntipohjainen ennuste yhdellä HTTPS-kutsulla. Käytetään MET Norway -mallia
 *  (metno_seamless), joka antaa Pohjoismaiden alueelle 2.5 km hila-resoluution. */
public class OpenMeteoClient {

    private static final String TAG = "OpenMeteoClient";
    private static final String BASE = "https://api.open-meteo.com/v1/forecast";
    private static final int TIMEOUT_MS = 15000;
    private static final String MODEL = "metno_seamless";

    private final GeoPlace place;

    public OpenMeteoClient(GeoPlace place) {
        this.place = place;
    }

    public OpenMeteoData fetch() throws Exception {
        OpenMeteoData data = new OpenMeteoData(place.name);

        String url = BASE
                + "?latitude=" + place.latitude
                + "&longitude=" + place.longitude
                + "&hourly=temperature_2m,relative_humidity_2m,apparent_temperature,"
                + "precipitation,cloudcover,windspeed_10m,windgusts_10m,winddirection_10m,"
                + "shortwave_radiation,weathercode"
                + "&windspeed_unit=ms"
                + "&timezone=auto"
                + "&forecast_days=3"
                + "&models=" + MODEL;

        Log.d(TAG, "GET " + url);
        String body = httpGet(url);
        parse(body, data);
        data.fetchedAt = System.currentTimeMillis();
        Log.d(TAG, "OpenMeteo " + place.name + " — " + data.hours.size() + " tuntia");
        return data;
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");
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

    private void parse(String body, OpenMeteoData data) throws Exception {
        JSONObject root = new JSONObject(body);
        if (!root.has("hourly")) return;
        JSONObject h = root.getJSONObject("hourly");

        String tz = root.optString("timezone", "Europe/Helsinki");
        TimeZone zone = TimeZone.getTimeZone(tz);
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        iso.setTimeZone(zone);

        JSONArray time = h.getJSONArray("time");
        JSONArray temp = h.optJSONArray("temperature_2m");
        JSONArray feels = h.optJSONArray("apparent_temperature");
        JSONArray rh = h.optJSONArray("relative_humidity_2m");
        JSONArray pcp = h.optJSONArray("precipitation");
        JSONArray cc = h.optJSONArray("cloudcover");
        JSONArray ws = h.optJSONArray("windspeed_10m");
        JSONArray wg = h.optJSONArray("windgusts_10m");
        JSONArray wd = h.optJSONArray("winddirection_10m");
        JSONArray rad = h.optJSONArray("shortwave_radiation");
        JSONArray code = h.optJSONArray("weathercode");

        Calendar cal = Calendar.getInstance(zone);
        int n = time.length();
        for (int i = 0; i < n; i++) {
            OpenMeteoData.Hour row = new OpenMeteoData.Hour();
            String t = time.getString(i);
            Date d = iso.parse(t);
            if (d == null) continue;
            row.timestamp = d.getTime();
            cal.setTime(d);
            row.hour = cal.get(Calendar.HOUR_OF_DAY);
            row.dayOfMonth = cal.get(Calendar.DAY_OF_MONTH);
            row.month = cal.get(Calendar.MONTH) + 1;

            row.temperature = optD(temp, i);
            row.feelsLike = optD(feels, i);
            row.humidity = optD(rh, i);
            row.precipitation = optD(pcp, i);
            row.cloudCover = optD(cc, i);
            row.windSpeed = optD(ws, i);
            row.windGust = optD(wg, i);
            row.windDirection = optD(wd, i);
            row.radiationGlobal = optD(rad, i);

            double tempD = row.temperature != null ? row.temperature : Double.NaN;
            double pcpD = row.precipitation != null ? row.precipitation : Double.NaN;
            double ccD = row.cloudCover != null ? row.cloudCover : Double.NaN;
            row.condition = WeatherCondition.inferFromValues(tempD, pcpD, ccD, false);
            if (code != null && !code.isNull(i)) {
                row.condition.rawWeatherSymbol3 = code.getInt(i);
            }

            data.hours.add(row);
        }
    }

    private static Double optD(JSONArray arr, int i) {
        if (arr == null || arr.isNull(i)) return null;
        double v = arr.optDouble(i, Double.NaN);
        return Double.isNaN(v) ? null : v;
    }
}
