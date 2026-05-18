package org.jrs82.fsclock;

import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class FmiClient {

    private static final String TAG = "FmiClient";
    private static final String BASE = "https://opendata.fmi.fi/wfs";
    // Helsinki-Vantaan lentoasema: FMISID 100968, koord. 60.32937,24.97274
    private static final String FMISID = "100968";
    private static final String LATLON = "60.32937,24.97274";
    private static final int TIMEOUT_MS = 15000;

    public WeatherData fetch() throws Exception {
        WeatherData data = new WeatherData();

        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));

        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        Date now = cal.getTime();
        cal.add(Calendar.HOUR, -25);
        Date obsStart = cal.getTime();
        cal.setTime(now);
        cal.add(Calendar.HOUR, 240); // 10 paivaa - FMI palauttaa sen mita on
        Date fcEnd = cal.getTime();

        // 1) Havainnot viimeiselta 25h:lta - lasketaan 24h sade ja otetaan tuoreimmat arvot
        String obsUrl = BASE + "?service=WFS&version=2.0.0&request=getFeature"
                + "&storedquery_id=fmi::observations::weather::simple"
                + "&fmisid=" + FMISID
                + "&parameters=t2m,rh,ws_10min,wd_10min,r_1h,wawa"
                + "&starttime=" + iso.format(obsStart)
                + "&endtime=" + iso.format(now);
        parseObservations(obsUrl, data);

        // 2) Ennuste seuraavalle 5 paivalle tunneittain
        String fcUrl = BASE + "?service=WFS&version=2.0.0&request=getFeature"
                + "&storedquery_id=fmi::forecast::edited::weather::scandinavia::point::simple"
                + "&latlon=" + LATLON
                + "&parameters=Temperature,Precipitation1h,WeatherSymbol3"
                + "&timestep=60"
                + "&starttime=" + iso.format(now)
                + "&endtime=" + iso.format(fcEnd);
        parseForecast(fcUrl, data);

        data.fetchedAt = System.currentTimeMillis();
        if (!Double.isNaN(data.current.temperature)) {
            data.current.feelsLike = WeatherData.computeFeelsLike(
                    data.current.temperature, data.current.windSpeed, data.current.humidity);
        }
        return data;
    }

    private void parseObservations(String url, WeatherData data) throws Exception {
        Map<Long, Map<String, Double>> byTime = new HashMap<>();
        readWfs(url, byTime);

        long latestTs = 0;
        Map<String, Double> latest = null;
        boolean hasWawa = false;
        for (Map.Entry<Long, Map<String, Double>> e : byTime.entrySet()) {
            if (e.getKey() > latestTs) {
                latestTs = e.getKey();
                latest = e.getValue();
            }
        }
        if (latest != null) {
            data.current.timestamp = latestTs;
            Double v;
            v = latest.get("t2m"); if (v != null) data.current.temperature = v;
            v = latest.get("rh"); if (v != null) data.current.humidity = v;
            v = latest.get("ws_10min"); if (v != null) data.current.windSpeed = v;
            v = latest.get("wd_10min"); if (v != null) data.current.windDirection = v;
            v = latest.get("wawa"); if (v != null && !v.isNaN()) {
                data.current.weatherSymbol = wawaToSym3((int) (double) v);
                hasWawa = true;
            }
        }
        data.current.hasSymbolFromObs = hasWawa;

        // Sade 24h: ota vain uusimmat 24 r_1h-arvoa (latestTs - 24h ... latestTs)
        long windowStart = latestTs - 24L * 3600L * 1000L;
        double rain24h = 0.0;
        boolean hasRainData = false;
        for (Map.Entry<Long, Map<String, Double>> e : byTime.entrySet()) {
            if (e.getKey() <= windowStart || e.getKey() > latestTs) continue;
            Double r = e.getValue().get("r_1h");
            if (r != null && !r.isNaN()) {
                hasRainData = true;
                rain24h += Math.max(0.0, r);
            }
        }
        data.current.rain24h = hasRainData ? rain24h : Double.NaN;
    }

    private void parseForecast(String url, WeatherData data) throws Exception {
        Map<Long, Map<String, Double>> byTime = new HashMap<>();
        readWfs(url, byTime);

        // jarjestetaan timestamp-jarjestyksessa
        Long[] keys = byTime.keySet().toArray(new Long[0]);
        java.util.Arrays.sort(keys);
        Calendar c = Calendar.getInstance();
        for (long ts : keys) {
            Map<String, Double> m = byTime.get(ts);
            WeatherData.Hour h = new WeatherData.Hour();
            h.timestamp = ts;
            c.setTimeInMillis(ts);
            h.hour = c.get(Calendar.HOUR_OF_DAY);
            h.dayOfMonth = c.get(Calendar.DAY_OF_MONTH);
            h.month = c.get(Calendar.MONTH) + 1;
            Double v;
            v = m.get("Temperature"); if (v != null) h.temperature = v;
            v = m.get("Precipitation1h"); if (v != null && !v.isNaN()) h.precipitation = v;
            v = m.get("WeatherSymbol3"); if (v != null && !v.isNaN()) h.weatherSymbol = (int) (double) v;
            data.hours.add(h);
            if (data.hours.size() >= 240) break;
        }

        // jos nyt-saa ei loytynyt havainnoista, kaytetaan ensimmaista ennustearvoa
        if (Double.isNaN(data.current.temperature) && !data.hours.isEmpty()) {
            WeatherData.Hour first = data.hours.get(0);
            data.current.temperature = first.temperature;
            data.current.weatherSymbol = first.weatherSymbol;
            data.current.hasSymbolFromObs = true; // ennusteen WeatherSymbol3 on aito symboli
            data.current.timestamp = first.timestamp;
        } else if (!data.current.hasSymbolFromObs && !data.hours.isEmpty()) {
            // havainnoissa oli muut arvot mutta wawa puuttui -> lainataan symboli ennusteen lahimmasta tunnista
            data.current.weatherSymbol = data.hours.get(0).weatherSymbol;
            data.current.hasSymbolFromObs = true;
        }
    }

    private void readWfs(String url, Map<Long, Map<String, Double>> out) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/xml");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("FMI HTTP " + code);
            }
            in = conn.getInputStream();
            XmlPullParser xpp = Xml.newPullParser();
            xpp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            xpp.setInput(in, "UTF-8");

            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));

            String curTime = null, curParam = null, curVal = null;
            int ev = xpp.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String name = xpp.getName();
                    if (name.endsWith("Time")) {
                        curTime = xpp.nextText().trim();
                    } else if (name.endsWith("ParameterName")) {
                        curParam = xpp.nextText().trim();
                    } else if (name.endsWith("ParameterValue")) {
                        curVal = xpp.nextText().trim();
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String name = xpp.getName();
                    if (name.endsWith("BsWfsElement")) {
                        if (curTime != null && curParam != null && curVal != null) {
                            try {
                                long ts = iso.parse(curTime).getTime();
                                double v = Double.parseDouble(curVal);
                                if (v == Double.NEGATIVE_INFINITY || Double.isNaN(v)) {
                                    // NaN sailytetaan
                                }
                                Map<String, Double> m = out.get(ts);
                                if (m == null) {
                                    m = new HashMap<>();
                                    out.put(ts, m);
                                }
                                m.put(curParam, v);
                            } catch (Exception ignored) { }
                        }
                        curTime = null; curParam = null; curVal = null;
                    }
                }
                ev = xpp.next();
            }
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) { }
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** WAWA-koodi (havainnot) -> WeatherSymbol3 ryhma */
    private int wawaToSym3(int wawa) {
        if (wawa == 0) return 1;
        if (wawa >= 4 && wawa <= 5) return 71; // utua/sumua
        if (wawa == 10) return 2;
        if (wawa >= 20 && wawa <= 25) return 21; // sade
        if (wawa >= 30 && wawa <= 35) return 31; // lumi
        if (wawa >= 40 && wawa <= 49) return 21;
        if (wawa >= 50 && wawa <= 59) return 21;
        if (wawa >= 60 && wawa <= 69) return 22;
        if (wawa >= 70 && wawa <= 79) return 32;
        if (wawa >= 80 && wawa <= 84) return 22;
        if (wawa >= 85 && wawa <= 89) return 32;
        return 3;
    }
}
