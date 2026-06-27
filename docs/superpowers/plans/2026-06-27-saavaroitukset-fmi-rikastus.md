# Säävaroitukset — FMI GeoServer -rikastus (Tasks 8–13)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Jatkoa planille `2026-06-27-saavaroitukset-sivu.md` (Tasks 1–7 valmiit). Steps käyttävät `- [ ]`-syntaksia.

**Goal:** Rikasta MeteoAlarm-pohjaiset säävaroituskortit (ja ilmoitukset) FMI:n oman GeoServerin tarkoilla tiedoilla: toteutumis-todennäköisyys (%), konkreettiset fyysiset arvot (°C / mm/h / m/s / UV-indeksi) ja FMI:n pidempi `info_fi`-teksti kun se on rikkaampi.

**Architecture:** Pidetään MeteoAlarm rakenteen lähteenä (selkeät maakuntanimet/tyyppi/taso). Haetaan rinnalla FMI GeoServer -varoituslayer ja yhdistetään detaljit jokaiseen varoitukseen **tyypin + ajan perusteella** (puhdas `WarningEnricher`). FMI-haun epäonnistuminen on ei-fataali → kortit toimivat MeteoAlarmilla kuten ennenkin. Ei ViewModel/Hilt/MVI/StateFlow.

**Tech Stack:** Java (core), Kotlin + Compose (UI), org.json, JUnit 4, Gradle `:app-mobile`.

## Global Constraints

- Paketit: core `org.jrs82.fsclock`, UI `org.jrs82.fsclock.mobile`. UI-teksti suomeksi.
- FMI-lähde: `https://www.ilmatieteenlaitos.fi/geoserver/alert/ows?service=WFS&version=2.0.0&request=GetFeature&typeName=alert:weather_finland_active_all&outputFormat=application/json`. HTTP-pyynnössä selain-UA (`Mozilla/5.0`), timeout 15 s.
- FMI-haun virhe EI saa kaataa varoitusten näyttöä → enrichaus jätetään väliin, MeteoAlarm-kentät säilyvät.
- Taaksepäin yhteensopivuus: `WeatherWarning`-laajennukset oletusarvoilla (vanhat 8- ja 15-arg konstruktorit säilyvät); etusivun kortti + olemassa olevat testit eivät regressoi.
- FMI-kentät (vahvistettu live-datasta): `warning_context` ∈ {forest-fire-weather, hot-weather, rain, thunder-storm, sea-thunder-storm, uv-note}; `actualization_probability` int %; `physical_value` luku + `physical_unit` ∈ {celsius, mm/h, m/s, index} (`physical_direction` aina null tässä datassa); `info_fi` HTML-entiteeteillä (&auml;=ä, &ouml;=ö, &aring;=å, &nbsp;=väli); `effective_from`/`effective_until` UTC ISO (joskus millit `.SSS`, joskus ei, pääte `Z`); `severity` = "level-N".
- Testit: `app-mobile/src/test/java/...`, JUnit 4, `unitTests.returnDefaultValues = true` on jo päällä (Task 2).
- Gradle Git Bashista: `./gradlew ...`. Älä pushaa.

---

### Task 8: WarningDetails-malli + WeatherWarning.details + withDetails

**Files:**
- Create: `app-mobile/core/java/org/jrs82/fsclock/WarningDetails.java`
- Modify: `app-mobile/core/java/org/jrs82/fsclock/WeatherWarning.java`
- Test: `app-mobile/src/test/java/org/jrs82/fsclock/WarningDetailsTest.kt` (create)

**Interfaces:**
- Produces:
  - `WarningDetails` (immutable): `public final int probabilityPct;` (-1 = ei tiedossa), `public final String physicalText;`, `public final String detailText;` + `public static final WarningDetails EMPTY;` + `public boolean hasAny()`.
  - `WeatherWarning.details` (final `WarningDetails`, oletus `EMPTY`); uusi 16-arg konstruktori; vanhat 8- ja 15-arg delegoivat `EMPTY`-arvolla; `public WeatherWarning withDetails(WarningDetails d)` palauttaa kopion.

- [ ] **Step 1: Kirjoita kaatuva testi**

Create `app-mobile/src/test/java/org/jrs82/fsclock/WarningDetailsTest.kt`:

```kotlin
package org.jrs82.fsclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningDetailsTest {

    @Test fun emptyHasNothing() {
        assertFalse(WarningDetails.EMPTY.hasAny())
        assertEquals(-1, WarningDetails.EMPTY.probabilityPct)
        assertEquals("", WarningDetails.EMPTY.physicalText)
        assertEquals("", WarningDetails.EMPTY.detailText)
    }

    @Test fun hasAnyTrueWhenProbability() {
        assertTrue(WarningDetails(40, "", "").hasAny())
    }

    @Test fun hasAnyTrueWhenPhysical() {
        assertTrue(WarningDetails(-1, "Lämpötila jopa 27 °C", "").hasAny())
    }

    @Test fun legacyWarningHasEmptyDetails() {
        val w = WeatherWarning("Hellevaroitus", "k", "Uusimaa",
            0L, 1L, WeatherWarning.Level.YELLOW, "id", false)
        assertFalse(w.details.hasAny())
    }

    @Test fun withDetailsCopiesAndKeepsOtherFields() {
        val w = WeatherWarning("Hellevaroitus", "k", "Uusimaa",
            0L, 1L, WeatherWarning.Level.YELLOW, "id", false)
        val e = w.withDetails(WarningDetails(40, "Lämpötila jopa 27 °C", "pitkä teksti"))
        assertEquals(40, e.details.probabilityPct)
        assertEquals("Lämpötila jopa 27 °C", e.details.physicalText)
        assertEquals("pitkä teksti", e.details.detailText)
        // muut kentät säilyvät
        assertEquals("Hellevaroitus", e.event)
        assertEquals("Uusimaa", e.areaDesc)
        assertEquals(WeatherWarning.Level.YELLOW, e.level)
    }
}
```

- [ ] **Step 2: Aja testi — kaatuu**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "org.jrs82.fsclock.WarningDetailsTest"`
Expected: FAIL (luokka/kentät puuttuvat).

- [ ] **Step 3: Luo WarningDetails.java**

Create `app-mobile/core/java/org/jrs82/fsclock/WarningDetails.java`:

```java
package org.jrs82.fsclock;

/** FMI GeoServeristä yhdistetty lisätieto yhteen varoitukseen: toteutumis-todennäköisyys,
 *  konkreettinen fyysinen arvo ja FMI:n (joskus pidempi) kuvausteksti. Muuttumaton. */
public class WarningDetails {

    public static final WarningDetails EMPTY = new WarningDetails(-1, "", "");

    /** Toteutumis-todennäköisyys prosentteina, -1 jos ei tiedossa. */
    public final int probabilityPct;
    /** Konkreettinen arvo valmiiksi muotoiltuna, esim. "Lämpötila jopa 27 °C". "" jos ei. */
    public final String physicalText;
    /** FMI:n info_fi (entiteetit puretut). "" jos ei käytössä. */
    public final String detailText;

    public WarningDetails(int probabilityPct, String physicalText, String detailText) {
        this.probabilityPct = probabilityPct;
        this.physicalText = physicalText == null ? "" : physicalText;
        this.detailText = detailText == null ? "" : detailText;
    }

    public boolean hasAny() {
        return probabilityPct >= 0 || !physicalText.isEmpty() || !detailText.isEmpty();
    }
}
```

- [ ] **Step 4: Lisää details-kenttä + konstruktori + withDetails WeatherWarningiin**

`WeatherWarning.java`: lisää `web`-kentän jälkeen kenttä:

```java
    /** FMI GeoServer -rikastus (todennäköisyys, fyysinen arvo, pidempi teksti). Oletus EMPTY. */
    public final WarningDetails details;
```

Muuta nykyinen täysi 15-arg konstruktori ottamaan `details` viimeisenä parametrina (→ 16-arg) ja aseta kenttä; lisää oletus `details`-asetus. Konkreettisesti, korvaa nykyinen 15-arg konstruktorin allekirjoitus ja runko tällä:

```java
    /** Täysi konstruktori. */
    public WeatherWarning(String event, String description, String areaDesc,
                           long onsetMs, long expiresMs, Level level, String identifier,
                           boolean marine, AwarenessType awarenessType, String severity,
                           String certainty, String urgency, long effectiveMs,
                           String senderName, String web, WarningDetails details) {
        this.event = event == null ? "" : event;
        this.description = description == null ? "" : description;
        this.areaDesc = areaDesc == null ? "" : areaDesc;
        this.onsetMs = onsetMs;
        this.expiresMs = expiresMs;
        this.level = level == null ? Level.UNKNOWN : level;
        this.identifier = identifier == null ? "" : identifier;
        this.marine = marine;
        this.awarenessType = awarenessType == null ? AwarenessType.UNKNOWN : awarenessType;
        this.severity = severity == null ? "" : severity;
        this.certainty = certainty == null ? "" : certainty;
        this.urgency = urgency == null ? "" : urgency;
        this.effectiveMs = effectiveMs;
        this.senderName = senderName == null ? "" : senderName;
        this.web = web == null ? "" : web;
        this.details = details == null ? WarningDetails.EMPTY : details;
    }
```

Lisää 15-arg delegoiva konstruktori (WarningsClient käyttää tätä — pysyy ennallaan kutsupaikalla) heti täyden konstruktorin jälkeen:

```java
    /** 15-arg (ilman detailsia) — WarningsClientin rakentama, rikastus tehdään myöhemmin withDetailsilla. */
    public WeatherWarning(String event, String description, String areaDesc,
                           long onsetMs, long expiresMs, Level level, String identifier,
                           boolean marine, AwarenessType awarenessType, String severity,
                           String certainty, String urgency, long effectiveMs,
                           String senderName, String web) {
        this(event, description, areaDesc, onsetMs, expiresMs, level, identifier, marine,
             awarenessType, severity, certainty, urgency, effectiveMs, senderName, web,
             WarningDetails.EMPTY);
    }
```

Varmista että vanha **8-arg** konstruktori edelleen delegoi (sen rivi `this(event, ..., marine, AwarenessType.UNKNOWN, "", "", "", 0L, "", "");` kutsuu nyt 15-arg versiota → toimii muuttumatta).

Lisää lopuksi `withDetails`-metodi luokan loppuun (ennen `detectMarine`-staattista tai sen jälkeen):

```java
    /** Palauttaa kopion samoilla kentillä mutta annetuilla FMI-lisätiedoilla. */
    public WeatherWarning withDetails(WarningDetails d) {
        return new WeatherWarning(event, description, areaDesc, onsetMs, expiresMs, level,
                identifier, marine, awarenessType, severity, certainty, urgency, effectiveMs,
                senderName, web, d);
    }
```

- [ ] **Step 5: Aja testi — menee läpi + regressiot**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "org.jrs82.fsclock.WarningDetailsTest" --tests "org.jrs82.fsclock.WeatherWarningModelTest" --tests "org.jrs82.fsclock.WarningsClientParseTest" --tests "org.jrs82.fsclock.mobile.WeatherWarningNotifierTest"`
Expected: PASS (kaikki — uudet + vanhat).

- [ ] **Step 6: Commit**

```bash
git add app-mobile/core/java/org/jrs82/fsclock/WarningDetails.java app-mobile/core/java/org/jrs82/fsclock/WeatherWarning.java app-mobile/src/test/java/org/jrs82/fsclock/WarningDetailsTest.kt
git commit -m "feat(warnings): WarningDetails-malli + WeatherWarning.details/withDetails"
```

---

### Task 9: FmiWarningDetailsClient (GeoServer-haku + parsinta + apurit)

**Files:**
- Create: `app-mobile/core/java/org/jrs82/fsclock/FmiWarningDetail.java`
- Create: `app-mobile/core/java/org/jrs82/fsclock/FmiWarningDetailsClient.java`
- Test: `app-mobile/src/test/java/org/jrs82/fsclock/FmiWarningDetailsParseTest.kt` (create)

**Interfaces:**
- Produces:
  - `FmiWarningDetail` (immutable): `String context; long fromMs; long untilMs; int probabilityPct; double physicalValue` (NaN = ei); `String physicalText; String detailText;`.
  - `FmiWarningDetailsClient`: `public List<FmiWarningDetail> fetch() throws Exception` ja package-private `List<FmiWarningDetail> parse(String json)`; static `String decodeEntities(String)`; static `String formatPhysical(double value, String unit)`.

- [ ] **Step 1: Kirjoita kaatuva testi**

Create `app-mobile/src/test/java/org/jrs82/fsclock/FmiWarningDetailsParseTest.kt`:

```kotlin
package org.jrs82.fsclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FmiWarningDetailsParseTest {

    private val json = """
    {"type":"FeatureCollection","features":[
      {"type":"Feature","properties":{
        "warning_context":"hot-weather","actualization_probability":40,
        "physical_value":27,"physical_unit":"celsius","physical_direction":null,
        "effective_from":"2026-06-27T12:01:21.479Z","effective_until":"2026-06-27T20:00:00Z",
        "info_fi":"Hellevaroitus: L&auml;hivuorokauden aikana on odotettavissa tukalaa hellett&auml;.",
        "severity":"level-2"}},
      {"type":"Feature","properties":{
        "warning_context":"rain","actualization_probability":30,
        "physical_value":20,"physical_unit":"mm/h","physical_direction":null,
        "effective_from":"2026-06-28T02:00:00Z","effective_until":"2026-06-28T10:00:00Z",
        "info_fi":"Sadevaroitus: Aamuy&ouml;st&auml; alkaen voi sataa rankasti, yli 20 mm tunnissa.",
        "severity":"level-2"}}
    ]}
    """.trimIndent()

    @Test fun decodesFinnishEntities() {
        assertEquals("Lähivuorokauden ää", FmiWarningDetailsClient.decodeEntities("L&auml;hivuorokauden &auml;&auml;"))
        assertEquals("ö å &", FmiWarningDetailsClient.decodeEntities("&ouml; &aring; &amp;"))
        assertEquals("a b", FmiWarningDetailsClient.decodeEntities("a&nbsp;b"))
    }

    @Test fun formatsPhysicalByUnit() {
        assertEquals("Lämpötila jopa 27 °C", FmiWarningDetailsClient.formatPhysical(27.0, "celsius"))
        assertEquals("Sademäärä jopa 20 mm/h", FmiWarningDetailsClient.formatPhysical(20.0, "mm/h"))
        assertEquals("Tuulen puuskat jopa 15 m/s", FmiWarningDetailsClient.formatPhysical(15.0, "m/s"))
        assertEquals("UV-indeksi 6", FmiWarningDetailsClient.formatPhysical(6.0, "index"))
        assertEquals("", FmiWarningDetailsClient.formatPhysical(Double.NaN, "celsius"))
    }

    @Test fun parsesFeatures() {
        val list = FmiWarningDetailsClient().parse(json)
        assertEquals(2, list.size)
        val hot = list[0]
        assertEquals("hot-weather", hot.context)
        assertEquals(40, hot.probabilityPct)
        assertEquals("Lämpötila jopa 27 °C", hot.physicalText)
        assertTrue(hot.detailText.contains("tukalaa hellettä"))
        assertTrue(hot.fromMs > 0L && hot.untilMs > hot.fromMs)
    }
}
```

- [ ] **Step 2: Aja testi — kaatuu**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "org.jrs82.fsclock.FmiWarningDetailsParseTest"`
Expected: FAIL (luokat puuttuvat).

- [ ] **Step 3: Luo FmiWarningDetail.java**

Create `app-mobile/core/java/org/jrs82/fsclock/FmiWarningDetail.java`:

```java
package org.jrs82.fsclock;

/** Yksi FMI GeoServer -varoitusfeature (per maakunta + aikaviipale), pelkistettynä. Muuttumaton. */
public class FmiWarningDetail {
    public final String context;
    public final long fromMs;
    public final long untilMs;
    public final int probabilityPct;   // -1 jos ei
    public final double physicalValue;  // NaN jos ei
    public final String physicalText;   // valmiiksi muotoiltu, "" jos ei
    public final String detailText;     // info_fi puretuilla entiteeteillä

    public FmiWarningDetail(String context, long fromMs, long untilMs, int probabilityPct,
                            double physicalValue, String physicalText, String detailText) {
        this.context = context == null ? "" : context;
        this.fromMs = fromMs;
        this.untilMs = untilMs;
        this.probabilityPct = probabilityPct;
        this.physicalValue = physicalValue;
        this.physicalText = physicalText == null ? "" : physicalText;
        this.detailText = detailText == null ? "" : detailText;
    }
}
```

- [ ] **Step 4: Luo FmiWarningDetailsClient.java**

Create `app-mobile/core/java/org/jrs82/fsclock/FmiWarningDetailsClient.java`:

```java
package org.jrs82.fsclock;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Hakee FMI:n oman GeoServerin varoituslayerin (alert:weather_finland_active_all) ja parsii
 *  per-feature-detaljit (todennäköisyys, fyysinen arvo, info_fi). Käytetään MeteoAlarm-varoitusten
 *  RIKASTAMISEEN (ei korvaa MeteoAlarmia: maakuntanimet/tyyppi/taso tulevat sieltä). */
public class FmiWarningDetailsClient {

    private static final String TAG = "FmiWarnDetails";
    private static final String URL =
        "https://www.ilmatieteenlaitos.fi/geoserver/alert/ows?service=WFS&version=2.0.0"
        + "&request=GetFeature&typeName=alert:weather_finland_active_all&outputFormat=application/json";
    private static final int TIMEOUT_MS = 15000;

    public List<FmiWarningDetail> fetch() throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(URL).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("FMI GeoServer HTTP " + code);
            in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return parse(sb.toString());
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    List<FmiWarningDetail> parse(String json) throws Exception {
        List<FmiWarningDetail> out = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray feats = root.optJSONArray("features");
        if (feats == null) return out;
        for (int i = 0; i < feats.length(); i++) {
            JSONObject f = feats.optJSONObject(i);
            if (f == null) continue;
            JSONObject p = f.optJSONObject("properties");
            if (p == null) continue;
            String context = p.optString("warning_context", "");
            if (context.isEmpty()) continue;
            int prob = p.has("actualization_probability") && !p.isNull("actualization_probability")
                    ? p.optInt("actualization_probability", -1) : -1;
            double pv = p.has("physical_value") && !p.isNull("physical_value")
                    ? p.optDouble("physical_value", Double.NaN) : Double.NaN;
            String unit = p.optString("physical_unit", "");
            String physicalText = formatPhysical(pv, unit);
            String detailText = decodeEntities(p.optString("info_fi", ""));
            long from = parseIso(p.optString("effective_from", null));
            long until = parseIso(p.optString("effective_until", null));
            out.add(new FmiWarningDetail(context, from, until, prob, pv, physicalText, detailText));
        }
        Log.d(TAG, "Parsed " + out.size() + " FMI warning details");
        return out;
    }

    /** Muotoilee fyysisen arvon suomeksi yksikön perusteella. */
    public static String formatPhysical(double value, String unit) {
        if (Double.isNaN(value) || unit == null || unit.isEmpty()) return "";
        String v = (value == Math.rint(value)) ? String.valueOf((long) value) : String.valueOf(value);
        switch (unit) {
            case "celsius": return "Lämpötila jopa " + v + " °C";
            case "mm/h":    return "Sademäärä jopa " + v + " mm/h";
            case "m/s":     return "Tuulen puuskat jopa " + v + " m/s";
            case "index":   return "UV-indeksi " + v;
            default:        return v + " " + unit;
        }
    }

    /** Purkaa FMI:n käyttämät HTML-entiteetit (suomalaiset + perus + numeeriset). */
    public static String decodeEntities(String s) {
        if (s == null || s.isEmpty()) return "";
        String r = s
            .replace("&auml;", "ä").replace("&Auml;", "Ä")
            .replace("&ouml;", "ö").replace("&Ouml;", "Ö")
            .replace("&aring;", "å").replace("&Aring;", "Å")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        // numeeriset &#NNN;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("&#(\\d+);").matcher(r);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            try { m.appendReplacement(sb, String.valueOf((char) Integer.parseInt(m.group(1)))); }
            catch (Exception e) { m.appendReplacement(sb, m.group(0)); }
        }
        m.appendTail(sb);
        // &amp; viimeisenä jottei tuplapurkua
        return sb.toString().replace("&amp;", "&");
    }

    private static long parseIso(String s) {
        if (s == null || s.isEmpty()) return 0L;
        String[] patterns = { "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX" };
        for (String pat : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(pat, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = f.parse(s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) { }
        }
        return 0L;
    }
}
```

- [ ] **Step 5: Aja testi — menee läpi**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "org.jrs82.fsclock.FmiWarningDetailsParseTest"`
Expected: PASS (3 testiä).

- [ ] **Step 6: Commit**

```bash
git add app-mobile/core/java/org/jrs82/fsclock/FmiWarningDetail.java app-mobile/core/java/org/jrs82/fsclock/FmiWarningDetailsClient.java app-mobile/src/test/java/org/jrs82/fsclock/FmiWarningDetailsParseTest.kt
git commit -m "feat(warnings): FmiWarningDetailsClient (GeoServer-haku + parsinta + entiteetit/yksiköt)"
```

---

### Task 10: WarningEnricher (puhdas yhdistäjä tyyppi+aika)

**Files:**
- Create: `app-mobile/core/java/org/jrs82/fsclock/WarningEnricher.java`
- Test: `app-mobile/src/test/java/org/jrs82/fsclock/WarningEnricherTest.kt` (create)

**Interfaces:**
- Consumes: `WeatherWarning` (+ `AwarenessType`, `marine`), `FmiWarningDetail`, `WarningDetails`, `withDetails`.
- Produces: `public static List<WeatherWarning> enrich(List<WeatherWarning> warnings, List<FmiWarningDetail> details)` — palauttaa uuden listan, jossa kukin varoitus on saanut yhdistetyt detaljit (tai EMPTY jos ei osumaa). Lisäksi package-private `static List<String> contextsFor(WeatherWarning w)` ja `static boolean overlaps(long aFrom, long aUntil, long bOn, long bEx)`.

- [ ] **Step 1: Kirjoita kaatuva testi**

Create `app-mobile/src/test/java/org/jrs82/fsclock/WarningEnricherTest.kt`:

```kotlin
package org.jrs82.fsclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningEnricherTest {

    private fun warn(event: String, type: WeatherWarning.AwarenessType, on: Long, ex: Long,
                     marine: Boolean = false) =
        WeatherWarning(event, "lyhyt", "Uusimaa", on, ex, WeatherWarning.Level.YELLOW,
            "id-$event", marine, type, "Moderate", "Likely", "Future", 0L, "FMI", "")

    private fun det(ctx: String, from: Long, until: Long, prob: Int, pv: Double,
                    ptext: String, text: String) =
        FmiWarningDetail(ctx, from, until, prob, pv, ptext, text)

    @Test fun matchesByTypeAndTime_aggregatesMaxProbAndLongestText() {
        val w = warn("Hellevaroitus", WeatherWarning.AwarenessType.HIGH_TEMPERATURE, 1000L, 5000L)
        val details = listOf(
            det("hot-weather", 1000L, 5000L, 30, 26.0, "Lämpötila jopa 26 °C", "lyhyt fmi"),
            det("hot-weather", 1000L, 5000L, 40, 27.0, "Lämpötila jopa 27 °C", "pidempi fmi teksti tähän"),
            det("rain", 1000L, 5000L, 99, 50.0, "Sademäärä jopa 50 mm/h", "eri tyyppi ei saa osua"),
        )
        val out = WarningEnricher.enrich(listOf(w), details)
        assertEquals(1, out.size)
        assertEquals(40, out[0].details.probabilityPct)
        assertEquals("Lämpötila jopa 27 °C", out[0].details.physicalText)  // suurin arvo
        assertEquals("pidempi fmi teksti tähän", out[0].details.detailText) // pisin
    }

    @Test fun noMatchKeepsEmptyDetails() {
        val w = warn("Hellevaroitus", WeatherWarning.AwarenessType.HIGH_TEMPERATURE, 1000L, 5000L)
        val details = listOf(det("rain", 1000L, 5000L, 30, 20.0, "Sademäärä jopa 20 mm/h", "sade"))
        val out = WarningEnricher.enrich(listOf(w), details)
        assertEquals(false, out[0].details.hasAny())
    }

    @Test fun nonOverlappingTimeDoesNotMatch() {
        val w = warn("Hellevaroitus", WeatherWarning.AwarenessType.HIGH_TEMPERATURE, 1000L, 2000L)
        val details = listOf(det("hot-weather", 9000L, 9999L, 40, 27.0, "Lämpötila jopa 27 °C", "fmi"))
        val out = WarningEnricher.enrich(listOf(w), details)
        assertEquals(false, out[0].details.hasAny())
    }

    @Test fun marineMatchesSeaThunder() {
        val w = warn("Huomautus veneilijöille", WeatherWarning.AwarenessType.WIND, 1000L, 5000L, marine = true)
        val details = listOf(det("sea-thunder-storm", 1000L, 5000L, 30, Double.NaN, "", "Ukkospuuskia merellä."))
        val out = WarningEnricher.enrich(listOf(w), details)
        assertEquals(30, out[0].details.probabilityPct)
        assertTrue(out[0].details.detailText.contains("Ukkospuuskia"))
    }
}
```

- [ ] **Step 2: Aja testi — kaatuu**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "org.jrs82.fsclock.WarningEnricherTest"`
Expected: FAIL (luokka puuttuu).

- [ ] **Step 3: Luo WarningEnricher.java**

Create `app-mobile/core/java/org/jrs82/fsclock/WarningEnricher.java`:

```java
package org.jrs82.fsclock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Yhdistää MeteoAlarm-varoitukset FMI GeoServer -detaljeihin (tyyppi + aikaikkuna). Puhdas, testattava. */
public final class WarningEnricher {

    private WarningEnricher() {}

    public static List<WeatherWarning> enrich(List<WeatherWarning> warnings,
                                              List<FmiWarningDetail> details) {
        List<WeatherWarning> out = new ArrayList<>(warnings.size());
        for (WeatherWarning w : warnings) {
            List<String> contexts = contextsFor(w);
            int bestProb = -1;
            double bestPhysVal = Double.NaN;
            String bestPhysText = "";
            String bestText = "";
            for (FmiWarningDetail d : details) {
                if (!contexts.contains(d.context)) continue;
                if (!overlaps(d.fromMs, d.untilMs, w.onsetMs, w.expiresMs)) continue;
                if (d.probabilityPct > bestProb) bestProb = d.probabilityPct;
                // suurin fyysinen arvo edustaa (esim. korkein lämpötila/puuska)
                if (!Double.isNaN(d.physicalValue)
                        && (Double.isNaN(bestPhysVal) || d.physicalValue > bestPhysVal)) {
                    bestPhysVal = d.physicalValue;
                    bestPhysText = d.physicalText;
                }
                // pisin info_fi = rikkain teksti
                if (d.detailText.length() > bestText.length()) bestText = d.detailText;
            }
            WarningDetails wd = new WarningDetails(bestProb, bestPhysText, bestText);
            out.add(wd.hasAny() ? w.withDetails(wd) : w);
        }
        return out;
    }

    /** MeteoAlarm-varoitus → FMI warning_context -ehdokkaat. */
    static List<String> contextsFor(WeatherWarning w) {
        if (w.marine) return Arrays.asList("sea-thunder-storm", "wind");
        switch (w.awarenessType) {
            case FOREST_FIRE: return Arrays.asList("forest-fire-weather");
            case HIGH_TEMPERATURE: return Arrays.asList("hot-weather");
            case RAIN: return Arrays.asList("rain");
            case THUNDERSTORM: return Arrays.asList("thunder-storm", "sea-thunder-storm");
            case LOW_TEMPERATURE: return Arrays.asList("cold-weather");
            case WIND: return Arrays.asList("wind");
            default: return java.util.Collections.emptyList();
        }
    }

    /** Aikaikkunoiden leikkaus; 0/tuntematon kohdellaan sallivasti (avoin reuna). */
    static boolean overlaps(long aFrom, long aUntil, long bOn, long bEx) {
        long aStart = aFrom > 0 ? aFrom : Long.MIN_VALUE;
        long aEnd   = aUntil > 0 ? aUntil : Long.MAX_VALUE;
        long bStart = bOn > 0 ? bOn : Long.MIN_VALUE;
        long bEnd   = bEx > 0 ? bEx : Long.MAX_VALUE;
        return aStart < bEnd && aEnd > bStart;
    }
}
```

- [ ] **Step 4: Aja testi — menee läpi**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "org.jrs82.fsclock.WarningEnricherTest"`
Expected: PASS (4 testiä).

- [ ] **Step 5: Commit**

```bash
git add app-mobile/core/java/org/jrs82/fsclock/WarningEnricher.java app-mobile/src/test/java/org/jrs82/fsclock/WarningEnricherTest.kt
git commit -m "feat(warnings): WarningEnricher (yhdistä MeteoAlarm + FMI detaljit tyyppi+aika)"
```

---

### Task 11: Kytke rikastus WarningsRepositoryyn ja ilmoittimeen

**Files:**
- Modify: `app-mobile/core/java/org/jrs82/fsclock/WarningsRepository.java`
- Modify: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/WeatherWarningNotifier.kt`

**Interfaces:**
- Consumes: `FmiWarningDetailsClient.fetch()`, `WarningEnricher.enrich(...)`.
- Produces: rikastetut `WeatherWarning`-oliot (`details` täytetty kun FMI-osuma). FMI-virhe ei-fataali.

- [ ] **Step 1: Rikasta repository-haussa**

`WarningsRepository.java`: lisää kenttä clientille konstruktorin lähelle:

```java
    private final FmiWarningDetailsClient detailsClient = new FmiWarningDetailsClient();
```

`refreshNow()`-metodissa, korvaa rivi `List<WeatherWarning> list = client.fetch();` tällä lohkolla (FMI-haku ei-fataali):

```java
                List<WeatherWarning> list = client.fetch();
                try {
                    List<FmiWarningDetail> details = detailsClient.fetch();
                    list = new ArrayList<>(WarningEnricher.enrich(list, details));
                } catch (Exception e) {
                    Log.w(TAG, "FMI details fetch failed (näytetään ilman rikastusta): " + e.getMessage());
                }
```

(`java.util.ArrayList` on jo importattu; `sortBySeverityThenOnset(list)` toimii rikastetulla listalla normaalisti.)

- [ ] **Step 2: Käännä**

Run: `./gradlew :app-mobile:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Rikasta ilmoittimessa**

`WeatherWarningNotifier.kt` `check()`-funktiossa, korvaa rivi
`val warnings = try { WarningsClient().fetch() } catch (e: Exception) { return }`
tällä (FMI ei-fataali):

```kotlin
        val raw = try { WarningsClient().fetch() } catch (e: Exception) { return }
        val warnings = try {
            WarningEnricher.enrich(raw, FmiWarningDetailsClient().fetch())
        } catch (e: Exception) {
            raw
        }
```

Ja `postFor`-funktiossa, yhden varoituksen haarassa, käytä rikkaampaa tekstiä ilmoituksen sisältönä — korvaa nykyinen
`if (w.description.isNotEmpty()) w.description else w.areaDesc,`
tällä:

```kotlin
                bestText(w),
```

Lisää `bestText`-apuri objektin sisään (esim. `warningKey`-funktion lähelle):

```kotlin
    /** Ilmoituksen sisältö: FMI:n pidempi teksti jos rikkaampi, muuten MeteoAlarm-kuvaus/alue. */
    private fun bestText(w: WeatherWarning): String {
        val fmi = w.details.detailText
        val base = if (w.description.isNotEmpty()) w.description else w.areaDesc
        val body = if (fmi.length > base.length) fmi else base
        val prob = if (w.details.probabilityPct >= 0) " (todennäköisyys ${w.details.probabilityPct} %)" else ""
        return body + prob
    }
```

(Tarvittavat importit `WeatherWarningNotifier.kt`:hin: `org.jrs82.fsclock.WarningEnricher`, `org.jrs82.fsclock.FmiWarningDetailsClient`. `WeatherWarning` on jo importattu.)

- [ ] **Step 4: Käännä koko moduuli**

Run: `./gradlew :app-mobile:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app-mobile/core/java/org/jrs82/fsclock/WarningsRepository.java app-mobile/src/main/java/org/jrs82/fsclock/mobile/WeatherWarningNotifier.kt
git commit -m "feat(warnings): kytke FMI-rikastus repositoryyn + ilmoituksiin (ei-fataali)"
```

---

### Task 12: Korttien UI — todennäköisyys + fyysinen arvo + rikkaampi teksti

**Files:**
- Modify: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/WarningsScreen.kt` (`WarningCard`)

**Interfaces:**
- Consumes: `WeatherWarning.details` (`probabilityPct`, `physicalText`, `detailText`).

- [ ] **Step 1: Näytä rikkain kuvausteksti + lisätietorivi kortissa**

`WarningsScreen.kt` `WarningCard`-komponentissa:

(a) Korvaa kuvauksen näyttö niin että FMI:n pidempi teksti voittaa. Etsi nykyinen lohko:

```kotlin
            if (w.description.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(w.description, style = MaterialTheme.typography.bodyMedium)
            }
```

ja korvaa se tällä:

```kotlin
            val bodyText = if (w.details.detailText.length > w.description.length)
                w.details.detailText else w.description
            if (bodyText.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(bodyText, style = MaterialTheme.typography.bodyMedium)
            }
            if (w.details.probabilityPct >= 0 || w.details.physicalText.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val highlight = buildList {
                    if (w.details.physicalText.isNotEmpty()) add(w.details.physicalText)
                    if (w.details.probabilityPct >= 0) add("Todennäköisyys ${w.details.probabilityPct} %")
                }.joinToString("  ·  ")
                Text(
                    highlight,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ArkiTheme.colors.weatherAccent,
                )
            }
```

(`ArkiTheme`, `FontWeight`, `buildList` ovat jo käytettävissä/importattu tiedostossa; jos `buildList` ei käänny, käytä `listOfNotNull(...)`-muotoa.)

- [ ] **Step 2: Käännä**

Run: `./gradlew :app-mobile:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app-mobile/src/main/java/org/jrs82/fsclock/mobile/WarningsScreen.kt
git commit -m "feat(warnings): kortit näyttävät FMI-todennäköisyyden, fyysisen arvon ja rikkaamman tekstin"
```

---

### Task 13: Täysi verifiointi (testit + lint + R8 + emulaattori)

- [ ] **Step 1: Yksikkötestit**

Run: `./gradlew :app-mobile:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (kaikki, ml. WarningDetailsTest / FmiWarningDetailsParseTest / WarningEnricherTest + vanhat).

- [ ] **Step 2: Lint + R8-release**

Run: `./gradlew :app-mobile:lintVitalRelease :app-mobile:assembleRelease`
Expected: BUILD SUCCESSFUL (ei uusia error-tason lint-ongelmia).

- [ ] **Step 3: Emulaattorivahvistus**

Asenna release-APK emulaattoriin, avaa Sää → Säävaroitukset. Varmista:
1. Kortit näyttävät nyt **Todennäköisyys N %** + **konkreettisen arvon** (esim. "Lämpötila jopa 27 °C", "Tuulen puuskat jopa 15 m/s", "Sademäärä jopa 20 mm/h") niille tyypeille joilla FMI antaa arvon.
2. Kuvausteksti on FMI:n pidempi versio kun sellainen on (esim. ukkos-/maastopalovaroitus).
3. Jos FMI-haku ei vastaa (esim. verkko pois), kortit näkyvät silti MeteoAlarmin tiedoilla (ei kaatumista, ei tyhjää).
4. Molemmat teemat OK; ei kaatumista logcatissa.

## Self-Review

**Spec coverage:** todennäköisyys → Task 9 parse + Task 12 UI; fyysiset arvot → Task 9 formatPhysical + Task 12; rikkaampi teksti → Task 9 decode + Task 10 longest + Task 12; yhdistäminen tyyppi+aika → Task 10; ei-fataali FMI → Task 11; ilmoitusten rikastus → Task 11 bestText; taaksepäin yhteensopivuus → Task 8 (oletus EMPTY, vanhat konstruktorit).

**Placeholder scan:** ei TBD/TODO; täysi koodi joka askeleessa.

**Type consistency:** `WarningDetails(probabilityPct, physicalText, detailText)` sama Task 8/10/12. `FmiWarningDetail(context, fromMs, untilMs, probabilityPct, physicalValue, physicalText, detailText)` sama Task 9/10. `WarningEnricher.enrich(List<WeatherWarning>, List<FmiWarningDetail>)` sama Task 10/11. `withDetails(WarningDetails)` Task 8/10. `formatPhysical`/`decodeEntities` static Task 9 → testit Task 9.
