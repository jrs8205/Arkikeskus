package org.jrs82.fsclock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Helsingin yliopiston almanakkatoimiston julkinen nimipaivarajapinta.
 *  Endpoint: https://namedays.tm-tieto.fi/api/public/namedays-search (POST, JSON).
 *  Ilmainen taso: max 100 kyselya/h, edellyttaa attribuutiota.
 *  Tama luokka palauttaa pelkat "suomi"-tyyppiset nimet. */
public class AlmanakkaClient {

    private static final String URL = "https://namedays.tm-tieto.fi/api/public/namedays-search";
    private static final int TIMEOUT_MS = 15000;
    public static final String ATTRIBUTION =
            "Kalenteri- ja nimipäivätiedot tarjoaa Yliopiston almanakkatoimisto.";

    /** Palauttaa suomalaiset nimet annetulle paivalle pilkkueroteltuna, esim. "Maila, Maili, Mailis". */
    public static String fetchFinnishNames(int month, int day) throws Exception {
        JSONObject body = new JSONObject();
        body.put("q", "*");
        body.put("query_by", "name");
        body.put("filter_by", "day:=" + day + " && month:=" + month);
        body.put("per_page", 250);
        body.put("sort_by", "name:asc");

        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            conn = (HttpURLConnection) new URL(URL).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            out = conn.getOutputStream();
            out.write(payload);
            out.flush();
            out.close();
            out = null;

            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("Almanakka HTTP " + code);

            in = conn.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);

            JSONObject resp = new JSONObject(sb.toString());
            if (!resp.optBoolean("success", false)) return "";
            JSONObject dataObj = resp.optJSONObject("data");
            if (dataObj == null) return "";
            JSONArray hits = dataObj.optJSONArray("hits");
            if (hits == null) return "";

            StringBuilder names = new StringBuilder();
            for (int i = 0; i < hits.length(); i++) {
                JSONObject doc = hits.getJSONObject(i).optJSONObject("document");
                if (doc == null) continue;
                String type = doc.optString("type", "");
                if (!"suomi".equals(type)) continue;
                String name = doc.optString("name", "").trim();
                if (name.isEmpty()) continue;
                if (names.length() > 0) names.append(", ");
                names.append(name);
            }
            return names.toString();
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) { }
            if (in != null) try { in.close(); } catch (Exception ignored) { }
            if (conn != null) conn.disconnect();
        }
    }
}
