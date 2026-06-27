# Säävaroitukset-sivu Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lisää "Sää"-valikon alle skrollattava "Säävaroitukset"-sivu, joka näyttää kaikki FMI:n (MeteoAlarm-CAP) varoituskentät oman alueen / koko Suomen valitsimella, ja ohjaa säävaroitusilmoituksen napautuksen tälle sivulle.

**Architecture:** Java-ydin (`WeatherWarning`, `WarningsClient`, `WarningsRepository`) + Compose-UI, **ei** ViewModel/Hilt/MVI/StateFlow (projektin konventio). `WarningsRepository` hakee jo kaikki Suomen varoitukset välimuistiin → "Oma alue / Koko Suomi" on UI-suodatin samaan dataan. Sivun datavirta jäljittelee etusivun `HomeWarningsCard`-kuviota (repo-Listener + `tick++`). Deep-link käyttää valmista `Notifications.post(openSection=...)` → `externalSection` → `HomeSection.valueOf` -reittiä.

**Tech Stack:** Android, Kotlin + Jetpack Compose (Material3), Java (core), JUnit 4, Gradle (`:app-mobile`).

## Global Constraints

- Paketti: `org.jrs82.arkikeskus` (applicationId); koodipaketti `org.jrs82.fsclock` (core) / `org.jrs82.fsclock.mobile` (UI).
- **Ei** ViewModel/Hilt/MVI/StateFlow — pure-objektit + SharedPreferences + Compose-state.
- UI-tekstit suomeksi. Järjestelmäfontti. Teema-adaptiivinen (vaalea + tumma) `ArkiTheme.colors`-tokeneilla.
- Datalähde ennallaan: MeteoAlarm `https://feeds.meteoalarm.org/api/v1/warnings/feeds-finland` (virallinen FMI-varoituslähde). **Ei uutta verkkolähdettä.**
- Etusivun nykyinen `HomeWarningsCard` ei saa regressoitua → `WeatherWarning`-laajennukset taaksepäin yhteensopivia (vanha 8-arg konstruktori säilyy).
- Release-teksteissä EI Claude-mainintoja.
- Verifiointi ennen valmista: `:app-mobile:testDebugUnitTest` + `:app-mobile:lintVitalRelease` + `:app-mobile:assembleRelease` (R8) vihreät + emulaattoriajo.
- Testit: `app-mobile/src/test/java/...`, jaettu `FakeSharedPreferences`, JUnit 4 (`org.junit.Test`, `org.junit.Assert.*`).
- Gradle-komennot Git Bashista: `./gradlew ...`.

---

### Task 1: Laajenna WeatherWarning-malli (AwarenessType + uudet kentät)

**Files:**
- Modify: `app-mobile/core/java/org/jrs82/fsclock/WeatherWarning.java`
- Test: `app-mobile/src/test/java/org/jrs82/fsclock/WeatherWarningModelTest.kt` (create)

**Interfaces:**
- Produces:
  - `WeatherWarning.AwarenessType` enum: `WIND, SNOW_ICE, THUNDERSTORM, FOG, HIGH_TEMPERATURE, LOW_TEMPERATURE, COASTAL, FOREST_FIRE, AVALANCHE, RAIN, FLOOD, UNKNOWN`; kentät `public final int code`, `public final String fiName`; `public static AwarenessType fromParam(String raw)`.
  - Uudet `public final` kentät: `AwarenessType awarenessType`, `String severity`, `String certainty`, `String urgency`, `long effectiveMs`, `String senderName`, `String web`.
  - Uusi 15-arg konstruktori (täysi) + vanha 8-arg konstruktori delegoi oletuksilla.

- [ ] **Step 1: Kirjoita kaatuva testi**

Create `app-mobile/src/test/java/org/jrs82/fsclock/WeatherWarningModelTest.kt`:

```kotlin
package org.jrs82.fsclock

import org.junit.Assert.assertEquals
import org.junit.Test

/** [WeatherWarning.AwarenessType]-parsinta ja taaksepäin yhteensopiva konstruktori. */
class WeatherWarningModelTest {

    @Test fun parsesForestFireByCode() {
        assertEquals(WeatherWarning.AwarenessType.FOREST_FIRE,
            WeatherWarning.AwarenessType.fromParam("8; forest-fire"))
    }

    @Test fun parsesHighTemperatureByCode() {
        assertEquals(WeatherWarning.AwarenessType.HIGH_TEMPERATURE,
            WeatherWarning.AwarenessType.fromParam("5; high-temperature"))
    }

    @Test fun parsesWindByCode() {
        assertEquals(WeatherWarning.AwarenessType.WIND,
            WeatherWarning.AwarenessType.fromParam("1; wind"))
    }

    @Test fun fallsBackToKeywordWhenNoCode() {
        assertEquals(WeatherWarning.AwarenessType.THUNDERSTORM,
            WeatherWarning.AwarenessType.fromParam("thunderstorm"))
    }

    @Test fun unknownForNullOrEmpty() {
        assertEquals(WeatherWarning.AwarenessType.UNKNOWN, WeatherWarning.AwarenessType.fromParam(null))
        assertEquals(WeatherWarning.AwarenessType.UNKNOWN, WeatherWarning.AwarenessType.fromParam(""))
    }

    @Test fun legacyConstructorDefaultsNewFields() {
        val w = WeatherWarning("Hellevaroitus", "kuvaus", "Uusimaa",
            0L, 1L, WeatherWarning.Level.YELLOW, "id-1", false)
        assertEquals(WeatherWarning.AwarenessType.UNKNOWN, w.awarenessType)
        assertEquals("", w.severity)
        assertEquals("", w.web)
        assertEquals(0L, w.effectiveMs)
    }
}
```

- [ ] **Step 2: Aja testi — varmista että kaatuu**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "org.jrs82.fsclock.WeatherWarningModelTest"`
Expected: FAIL (käännösvirhe: `AwarenessType` / uudet kentät puuttuvat).

- [ ] **Step 3: Lisää AwarenessType-enum WeatherWarning.javaan**

Lisää `WeatherWarning.java`:hin `Level`-enumin perään (ennen `public final String event;` -riviä):

```java
    public enum AwarenessType {
        WIND(1, "Tuuli"),
        SNOW_ICE(2, "Lumi/jää"),
        THUNDERSTORM(3, "Ukkonen"),
        FOG(4, "Sumu"),
        HIGH_TEMPERATURE(5, "Helle"),
        LOW_TEMPERATURE(6, "Pakkanen"),
        COASTAL(7, "Rannikko"),
        FOREST_FIRE(8, "Maastopalo"),
        AVALANCHE(9, "Lumivyöry"),
        RAIN(10, "Sade"),
        FLOOD(11, "Tulva"),
        UNKNOWN(0, "");

        public final int code;
        public final String fiName;
        AwarenessType(int code, String fiName) { this.code = code; this.fiName = fiName; }

        /** Parsii MeteoAlarmin awareness_type-stringin, esim. "8; forest-fire". */
        public static AwarenessType fromParam(String raw) {
            if (raw == null) return UNKNOWN;
            String s = raw.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) return UNKNOWN;
            String head = s.split(";")[0].trim();
            try {
                int code = Integer.parseInt(head);
                for (AwarenessType t : values()) if (t.code == code && t != UNKNOWN) return t;
            } catch (NumberFormatException ignored) { }
            if (s.contains("wind")) return WIND;
            if (s.contains("snow") || s.contains("ice")) return SNOW_ICE;
            if (s.contains("thunder")) return THUNDERSTORM;
            if (s.contains("fog")) return FOG;
            if (s.contains("forest") || s.contains("fire")) return FOREST_FIRE;
            if (s.contains("rain")) return RAIN;
            if (s.contains("flood")) return FLOOD;
            if (s.contains("high-temp")) return HIGH_TEMPERATURE;
            if (s.contains("low-temp")) return LOW_TEMPERATURE;
            if (s.contains("coastal")) return COASTAL;
            if (s.contains("avalanche")) return AVALANCHE;
            return UNKNOWN;
        }
    }
```

- [ ] **Step 4: Lisää uudet kentät + konstruktorit**

Korvaa `WeatherWarning.java`:ssa nykyinen kenttälohko + konstruktori (alkaa `public final String event;`, päättyy konstruktorin `}`-sulkuun, ts. nykyiset rivit 39–60) tällä:

```java
    public final String event;
    public final String description;
    public final String areaDesc;
    public final long onsetMs;
    public final long expiresMs;
    public final Level level;
    public final String identifier;
    /** true jos varoitus koskee veneilijöitä tai merialueita (lajitellaan listan loppuun). */
    public final boolean marine;
    /** Ilmiötyyppi (MeteoAlarm awareness_type) → ikonivalinta UI:ssa. */
    public final AwarenessType awarenessType;
    /** CAP-vakavuus raakana (Minor/Moderate/Severe/Extreme). */
    public final String severity;
    /** CAP-varmuus raakana (Observed/Likely/Possible/Unlikely). */
    public final String certainty;
    /** CAP-kiireellisyys raakana (Immediate/Expected/Future/Past). */
    public final String urgency;
    /** Julkaisuhetki (effective) millisekunteina, 0 jos ei tiedossa. */
    public final long effectiveMs;
    /** Lähettäjän nimi, esim. "Ilmatieteen laitos". */
    public final String senderName;
    /** Linkki lisätietoihin (FMI:n varoitussivu). */
    public final String web;

    /** Täysi konstruktori (WarningsClient käyttää tätä). */
    public WeatherWarning(String event, String description, String areaDesc,
                           long onsetMs, long expiresMs, Level level, String identifier,
                           boolean marine, AwarenessType awarenessType, String severity,
                           String certainty, String urgency, long effectiveMs,
                           String senderName, String web) {
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
    }

    /** Taaksepäin yhteensopiva konstruktori (etusivun kortti + olemassa olevat testit). */
    public WeatherWarning(String event, String description, String areaDesc,
                           long onsetMs, long expiresMs, Level level, String identifier,
                           boolean marine) {
        this(event, description, areaDesc, onsetMs, expiresMs, level, identifier, marine,
             AwarenessType.UNKNOWN, "", "", "", 0L, "", "");
    }
```

- [ ] **Step 5: Aja testi — varmista että menee läpi**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "org.jrs82.fsclock.WeatherWarningModelTest"`
Expected: PASS (6 testiä).

- [ ] **Step 6: Commit**

```bash
git add app-mobile/core/java/org/jrs82/fsclock/WeatherWarning.java app-mobile/src/test/java/org/jrs82/fsclock/WeatherWarningModelTest.kt
git commit -m "feat(warnings): laajenna WeatherWarning-malli (AwarenessType + CAP-kentät)"
```

---

### Task 2: Laajenna WarningsClient-parseri (uudet kentät + Cancel-suodatus)

**Files:**
- Modify: `app-mobile/build.gradle` (testOptions returnDefaultValues)
- Modify: `app-mobile/core/java/org/jrs82/fsclock/WarningsClient.java`
- Test: `app-mobile/src/test/java/org/jrs82/fsclock/WarningsClientParseTest.kt` (create)

**Interfaces:**
- Consumes: `WeatherWarning` täysi konstruktori + `AwarenessType.fromParam` (Task 1).
- Produces: `WarningsClient.parse(String json)` täyttää uudet kentät; ohittaa `msgType == "Cancel"`.

- [ ] **Step 1: Salli android.jar-oletukset yksikkötesteissä**

`WarningsClient.parse` kutsuu `android.util.Log.d` → ilman tätä JVM-testi heittää "not mocked". Lisää `app-mobile/build.gradle`:ssa `android { ... }`-lohkoon (esim. `buildTypes`-lohkon jälkeen):

```groovy
    testOptions {
        unitTests.returnDefaultValues = true
    }
```

- [ ] **Step 2: Kirjoita kaatuva testi**

Create `app-mobile/src/test/java/org/jrs82/fsclock/WarningsClientParseTest.kt`:

```kotlin
package org.jrs82.fsclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [WarningsClient.parse]: uusien CAP-kenttien poiminta + Cancel-suodatus. */
class WarningsClientParseTest {

    // Yksi voimassa oleva Update-varoitus + yksi Cancel-varoitus (pitää suodattua pois).
    private val json = """
    {"warnings":[
      {"alert":{"identifier":"id-1","status":"Actual","msgType":"Update","sender":"cap@fmi.fi","info":[
        {"language":"fi-FI","event":"Maastopalovaroitus","severity":"Moderate","certainty":"Likely","urgency":"Future",
         "effective":"2026-06-22T20:24:38+03:00","onset":"2020-01-01T00:00:00+03:00","expires":"2099-01-01T00:00:00+03:00",
         "senderName":"Ilmatieteen laitos","web":"https://www.ilmatieteenlaitos.fi/varoitukset",
         "description":"Maastopalovaroitus on voimassa.",
         "parameter":[{"valueName":"awareness_level","value":"2; yellow; Moderate"},
                      {"valueName":"awareness_type","value":"8; forest-fire"}],
         "area":[{"areaDesc":"Etelä-Pohjanmaa, Keski-Pohjanmaa","geocode":[{"valueName":"EMMA_ID","value":"FI030"}]}]}
      ]}},
      {"alert":{"identifier":"id-2","status":"Actual","msgType":"Cancel","info":[
        {"language":"fi-FI","event":"Hellevaroitus","onset":"2020-01-01T00:00:00+03:00","expires":"2099-01-01T00:00:00+03:00",
         "area":[{"areaDesc":"Uusimaa"}]}
      ]}}
    ]}
    """.trimIndent()

    @Test fun parsesNewFieldsAndDropsCancel() {
        val list = WarningsClient().parse(json)
        assertEquals(1, list.size)
        val w = list[0]
        assertEquals("Maastopalovaroitus", w.event)
        assertEquals(WeatherWarning.AwarenessType.FOREST_FIRE, w.awarenessType)
        assertEquals(WeatherWarning.Level.YELLOW, w.level)
        assertEquals("Moderate", w.severity)
        assertEquals("Likely", w.certainty)
        assertEquals("Future", w.urgency)
        assertEquals("Ilmatieteen laitos", w.senderName)
        assertTrue(w.web.contains("ilmatieteenlaitos"))
        assertTrue(w.effectiveMs > 0L)
    }
}
```

- [ ] **Step 3: Aja testi — varmista että kaatuu**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "org.jrs82.fsclock.WarningsClientParseTest"`
Expected: FAIL (`w.awarenessType` / `w.severity` ym. eivät käänny, tai Cancel ei suodatu → koko==2).

- [ ] **Step 4: Päivitä parse() poimimaan uudet kentät + suodattamaan Cancel**

`WarningsClient.java`:ssa, heti `identifier`-rivin (`String identifier = alert.optString("identifier", "");`) JÄLKEEN lisää Cancel-suodatus:

```java
            String msgType = alert.optString("msgType", "");
            if ("Cancel".equalsIgnoreCase(msgType)) continue;
```

Sitten korvaa `awareness_level`-parsinnan lohko (nykyiset rivit 88–99) tällä, joka poimii sekä tason ETTÄ ilmiötyypin:

```java
            WeatherWarning.Level level = WeatherWarning.Level.UNKNOWN;
            WeatherWarning.AwarenessType awarenessType = WeatherWarning.AwarenessType.UNKNOWN;
            JSONArray params = info.optJSONArray("parameter");
            if (params != null) {
                for (int k = 0; k < params.length(); k++) {
                    JSONObject p = params.optJSONObject(k);
                    if (p == null) continue;
                    String name = p.optString("valueName", "");
                    if ("awareness_level".equalsIgnoreCase(name)) {
                        level = WeatherWarning.Level.fromAwareness(p.optString("value", ""));
                    } else if ("awareness_type".equalsIgnoreCase(name)) {
                        awarenessType = WeatherWarning.AwarenessType.fromParam(p.optString("value", ""));
                    }
                }
            }
```

Lisää sitten ennen `WeatherWarning w = new WeatherWarning(...)` -rakennusta (heti `boolean marine = ...;` -rivin jälkeen) uusien kenttien poiminta:

```java
            long effective = parseIso(iso, info.optString("effective", null));
            String severity = info.optString("severity", "");
            String certainty = info.optString("certainty", "");
            String urgency = info.optString("urgency", "");
            String senderName = info.optString("senderName", "");
            String web = info.optString("web", "");
```

Lopuksi korvaa `WeatherWarning`-konstruktorikutsu (nykyiset rivit 129–137) täydellä konstruktorilla:

```java
            WeatherWarning w = new WeatherWarning(
                    event,
                    info.optString("description", ""),
                    areaStr,
                    onset,
                    expires,
                    level,
                    identifier,
                    marine,
                    awarenessType,
                    severity,
                    certainty,
                    urgency,
                    effective,
                    senderName,
                    web);
```

- [ ] **Step 5: Aja testi — varmista että menee läpi**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "org.jrs82.fsclock.WarningsClientParseTest"`
Expected: PASS.

- [ ] **Step 6: Aja koko core/notifier-testijoukko regression varalta**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "org.jrs82.fsclock.*" --tests "org.jrs82.fsclock.mobile.WeatherWarningNotifierTest"`
Expected: PASS (mm. WeatherWarningNotifierTest yhä vihreä — vanha 8-arg konstruktori toimii).

- [ ] **Step 7: Commit**

```bash
git add app-mobile/build.gradle app-mobile/core/java/org/jrs82/fsclock/WarningsClient.java app-mobile/src/test/java/org/jrs82/fsclock/WarningsClientParseTest.kt
git commit -m "feat(warnings): parseri poimii CAP-kentät + suodattaa Cancel-viestit"
```

---

### Task 3: UI-apurit (ikonit + suomenkieliset labelit + ajanjakso) + 2 uutta vektoria

**Files:**
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/WarningDisplay.kt`
- Create: `app-mobile/src/main/res/drawable/mobile_ic_wx_hot.xml`
- Create: `app-mobile/src/main/res/drawable/mobile_ic_wx_fire.xml`
- Test: `app-mobile/src/test/java/org/jrs82/fsclock/mobile/WarningDisplayTest.kt` (create)

**Interfaces:**
- Consumes: `WeatherWarning.AwarenessType` (Task 1).
- Produces (package `org.jrs82.fsclock.mobile`, `internal`):
  - `awarenessIconRes(type: WeatherWarning.AwarenessType): Int`
  - `severityFi(raw: String?): String`, `certaintyFi(raw: String?): String`, `urgencyFi(raw: String?): String`
  - `warningPeriod(onsetMs: Long, expiresMs: Long): String`

- [ ] **Step 1: Kirjoita kaatuva testi (puhtaat string-apurit)**

Create `app-mobile/src/test/java/org/jrs82/fsclock/mobile/WarningDisplayTest.kt`:

```kotlin
package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Säävaroitusten näyttöapurit (puhtaat, ei Composea/Androidia). */
class WarningDisplayTest {

    @Test fun severityFinnish() {
        assertEquals("Kohtalainen", severityFi("Moderate"))
        assertEquals("Erittäin vakava", severityFi("Extreme"))
        assertEquals("", severityFi(null))
        assertEquals("", severityFi("nonsense"))
    }

    @Test fun certaintyFinnish() {
        assertEquals("Todennäköinen", certaintyFi("Likely"))
        assertEquals("Mahdollinen", certaintyFi("possible"))
    }

    @Test fun urgencyFinnish() {
        assertEquals("Tuleva", urgencyFi("Future"))
        assertEquals("Välitön", urgencyFi("Immediate"))
    }

    @Test fun periodBothEnds() {
        // onset 2026-06-22 20:24 EEST, expires 2026-06-23 00:00 EEST → "–"-väli, molemmat ajat.
        val onset = 1_750_613_040_000L
        val expires = 1_750_626_000_000L
        val s = warningPeriod(onset, expires)
        assertTrue(s.contains("–"))
    }

    @Test fun periodOnlyExpires() {
        assertTrue(warningPeriod(0L, 1_750_626_000_000L).startsWith("voimassa asti "))
    }

    @Test fun periodOnlyOnset() {
        assertTrue(warningPeriod(1_750_613_040_000L, 0L).startsWith("alkaen "))
    }

    @Test fun periodEmptyWhenNoTimes() {
        assertEquals("", warningPeriod(0L, 0L))
    }
}
```

- [ ] **Step 2: Aja testi — varmista että kaatuu**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "org.jrs82.fsclock.mobile.WarningDisplayTest"`
Expected: FAIL (funktioita ei ole).

- [ ] **Step 3: Luo WarningDisplay.kt**

Create `app-mobile/src/main/java/org/jrs82/fsclock/mobile/WarningDisplay.kt`:

```kotlin
package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.R
import org.jrs82.fsclock.WeatherWarning.AwarenessType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Säävaroitusten näyttöapurit. Puhtaat funktiot (ikoniresurssi-int + suomenkieliset labelit
 *  + ajanjakson muotoilu) → yksikkötestattavissa ilman Composea. */

private val FI_WARN = Locale("fi", "FI")
private val HELSINKI_WARN: TimeZone = TimeZone.getTimeZone("Europe/Helsinki")

/** Ilmiötyyppi → drawable-ikoni. Tuntemattomille varoituskolmio. */
internal fun awarenessIconRes(type: AwarenessType): Int = when (type) {
    AwarenessType.WIND -> R.drawable.mobile_ic_wind_24
    AwarenessType.THUNDERSTORM -> R.drawable.mobile_ic_wx_thunder
    AwarenessType.SNOW_ICE, AwarenessType.LOW_TEMPERATURE -> R.drawable.mobile_ic_wx_snow
    AwarenessType.RAIN, AwarenessType.FLOOD -> R.drawable.mobile_ic_rain_24
    AwarenessType.FOG -> R.drawable.mobile_ic_wx_fog
    AwarenessType.HIGH_TEMPERATURE -> R.drawable.mobile_ic_wx_hot
    AwarenessType.FOREST_FIRE -> R.drawable.mobile_ic_wx_fire
    else -> R.drawable.mobile_ic_warning_24
}

internal fun severityFi(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "minor" -> "Vähäinen"
    "moderate" -> "Kohtalainen"
    "severe" -> "Vakava"
    "extreme" -> "Erittäin vakava"
    else -> ""
}

internal fun certaintyFi(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "observed" -> "Havaittu"
    "likely" -> "Todennäköinen"
    "possible" -> "Mahdollinen"
    "unlikely" -> "Epätodennäköinen"
    else -> ""
}

internal fun urgencyFi(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "immediate" -> "Välitön"
    "expected" -> "Odotettavissa"
    "future" -> "Tuleva"
    "past" -> "Mennyt"
    else -> ""
}

/** "alkaen X – Y" / "voimassa asti Y" / "alkaen X" / "" — Suomen aikavyöhyke. */
internal fun warningPeriod(onsetMs: Long, expiresMs: Long): String {
    if (onsetMs <= 0L && expiresMs <= 0L) return ""
    val fmt = SimpleDateFormat("d.M. HH:mm", FI_WARN)
    fmt.timeZone = HELSINKI_WARN
    return when {
        onsetMs <= 0L -> "voimassa asti " + fmt.format(Date(expiresMs))
        expiresMs <= 0L -> "alkaen " + fmt.format(Date(onsetMs))
        else -> fmt.format(Date(onsetMs)) + " – " + fmt.format(Date(expiresMs))
    }
}
```

- [ ] **Step 4: Luo helle-ikoni (aurinko)**

Create `app-mobile/src/main/res/drawable/mobile_ic_wx_hot.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?android:attr/textColorPrimary">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M6.76,4.84l-1.8,-1.79 -1.41,1.41 1.79,1.79 1.42,-1.41zM4,10.5L1,10.5v2h3v-2zM13,0.55h-2L11,3.5h2L13,0.55zM20.45,4.46l-1.41,-1.41 -1.79,1.79 1.41,1.41 1.79,-1.79zM17.24,18.16l1.79,1.8 1.41,-1.41 -1.8,-1.79 -1.4,1.4zM20,10.5v2h3v-2h-3zM12,5.5c-3.31,0 -6,2.69 -6,6s2.69,6 6,6 6,-2.69 6,-6 -2.69,-6 -6,-6zM12,15.5c-2.21,0 -4,-1.79 -4,-4s1.79,-4 4,-4 4,1.79 4,4 -1.79,4 -4,4z" />
</vector>
```

- [ ] **Step 5: Luo maastopalo-ikoni (liekki)**

Create `app-mobile/src/main/res/drawable/mobile_ic_wx_fire.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?android:attr/textColorPrimary">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M19.48,12.35c-1.57,-4.08 -7.16,-4.3 -5.81,-10.23c0.1,-0.44 -0.37,-0.78 -0.75,-0.55C9.29,3.71 6.68,8 8.87,13.62c0.18,0.46 -0.36,0.89 -0.75,0.59c-1.81,-1.37 -2,-3.34 -1.84,-4.75c0.06,-0.52 -0.62,-0.77 -0.91,-0.34C4.69,10.16 4,11.84 4,14.37c0.38,5.6 5.11,7.32 6.81,7.54c2.43,0.31 5.06,-0.14 6.95,-1.87c2.08,-1.93 2.84,-5.01 1.72,-7.69zM10.2,17.38c1.44,-0.35 2.18,-1.39 2.38,-2.31c0.33,-1.43 -0.96,-2.83 -0.09,-5.09c0.33,1.87 3.27,3.04 3.27,5.08c0.08,2.53 -2.66,4.7 -5.56,2.32z" />
</vector>
```

- [ ] **Step 6: Aja testi — varmista että menee läpi**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "org.jrs82.fsclock.mobile.WarningDisplayTest"`
Expected: PASS (7 testiä).

- [ ] **Step 7: Commit**

```bash
git add app-mobile/src/main/java/org/jrs82/fsclock/mobile/WarningDisplay.kt app-mobile/src/main/res/drawable/mobile_ic_wx_hot.xml app-mobile/src/main/res/drawable/mobile_ic_wx_fire.xml app-mobile/src/test/java/org/jrs82/fsclock/mobile/WarningDisplayTest.kt
git commit -m "feat(warnings): ikoni-/labeliapurit + helle- ja maastopalo-vektorit"
```

---

### Task 4: WarningsSection-composable (skrollattava sivu, ei vielä reititetty)

**Files:**
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/WarningsScreen.kt`

**Interfaces:**
- Consumes: `WarningsRepository`, `WeatherWarning`, `WeatherWarningNotifier.areaMatchesHome`, `FinnishRegions.regionForPlace`, `SettingsManager`, `ArkiCard`/`ArkiIconChip`/`ArkiPill` (ComposeCommon), `ArkiTheme.colors`, `LocalRefreshTick` (ComposeMainScreen, sama paketti), `openUrl` (ComposeHomeContent, sama paketti), `awarenessIconRes`/`severityFi`/`certaintyFi`/`urgencyFi`/`warningPeriod` (Task 3).
- Produces: `internal fun WarningsSection()` (parametriton Composable). Kääntyy itsenäisesti; reititys lisätään Task 5:ssä.

- [ ] **Step 1: Luo WarningsScreen.kt**

Create `app-mobile/src/main/java/org/jrs82/fsclock/mobile/WarningsScreen.kt`:

```kotlin
package org.jrs82.fsclock.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.SettingsManager
import org.jrs82.fsclock.WarningsRepository
import org.jrs82.fsclock.WeatherWarning

private const val KEY_WARNINGS_SCOPE_OWN = "warnings_scope_own"

/** "Sää" → Säävaroitukset: skrollattava sivu, joka näyttää kaikki MeteoAlarm/FMI-varoituskentät.
 *  Oma alue / Koko Suomi -valitsin suodattaa samaa repo-välimuistia (ei lisähakuja). */
@Composable
internal fun WarningsSection() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val refresh = LocalRefreshTick.current
    val arki = ArkiTheme.colors
    val repo = remember { WarningsRepository.get() }

    var tick by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val l = WarningsRepository.Listener { main.post { tick++ } }
        repo.addListener(l) // kutsuu heti nykyisellä listalla
        repo.refreshIfStale()
        onDispose { repo.removeListener(l) }
    }
    LaunchedEffect(refresh) { if (refresh > 0) repo.refreshNow() }

    var scopeOwn by remember { mutableStateOf(prefs.getBoolean(KEY_WARNINGS_SCOPE_OWN, true)) }
    val all = remember(tick) { repo.getLatest() }
    val homePlace = remember(tick) { SettingsManager.get().homePlace ?: "" }
    val homeRegion = remember(homePlace) { FinnishRegions.regionForPlace(homePlace) }
    val shown = remember(all, scopeOwn, homePlace, homeRegion) {
        if (scopeOwn && homePlace.isNotBlank()) {
            all.filter { WeatherWarningNotifier.areaMatchesHome(it.areaDesc, homePlace, homeRegion) }
        } else {
            all
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Säävaroitukset",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (shown.isNotEmpty()) ArkiPill("${shown.size} voimassa", arki.warning)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = scopeOwn,
                onClick = { scopeOwn = true; prefs.edit().putBoolean(KEY_WARNINGS_SCOPE_OWN, true).apply() },
                label = { Text("Oma alue") },
            )
            FilterChip(
                selected = !scopeOwn,
                onClick = { scopeOwn = false; prefs.edit().putBoolean(KEY_WARNINGS_SCOPE_OWN, false).apply() },
                label = { Text("Koko Suomi") },
            )
        }
        Spacer(Modifier.height(12.dp))
        if (shown.isEmpty()) {
            val msg = when {
                scopeOwn && homePlace.isBlank() ->
                    "Aseta kotipaikka asetuksissa nähdäksesi oman alueesi varoitukset."
                scopeOwn -> "Ei voimassa olevia varoituksia alueellasi ($homePlace)."
                else -> "Ei voimassa olevia säävaroituksia Suomessa."
            }
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown, key = { it.identifier.ifEmpty { it.event + it.areaDesc + it.onsetMs } }) { w ->
                    WarningCard(context, w)
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun WarningCard(context: Context, w: WeatherWarning) {
    val arki = ArkiTheme.colors
    val levelColor = Color(w.level.color)
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArkiIconChip(painterResource(awarenessIconRes(w.awarenessType)), levelColor)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (w.event.isNotEmpty()) w.event else w.awarenessType.fiName.ifEmpty { "Varoitus" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (w.areaDesc.isNotEmpty()) {
                        Text(
                            w.areaDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (w.level.fiName.isNotEmpty()) ArkiPill(w.level.fiName, levelColor)
            }
            if (w.marine) {
                Spacer(Modifier.height(8.dp))
                ArkiPill("Veneily", arki.weatherAccent)
            }
            val period = warningPeriod(w.onsetMs, w.expiresMs)
            if (period.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(period, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            if (w.description.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(w.description, style = MaterialTheme.typography.bodyMedium)
            }
            val meta = listOf(
                severityFi(w.severity).let { if (it.isNotEmpty()) "Vakavuus: $it" else "" },
                certaintyFi(w.certainty).let { if (it.isNotEmpty()) "Varmuus: $it" else "" },
                urgencyFi(w.urgency).let { if (it.isNotEmpty()) "Kiireellisyys: $it" else "" },
            ).filter { it.isNotEmpty() }.joinToString("  ·  ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (w.senderName.isNotEmpty()) w.senderName else "Ilmatieteen laitos",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (w.web.isNotEmpty()) {
                    Text(
                        "Lisätietoja",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { openUrl(context, w.web) },
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: Käännä — varmista että moduuli kääntyy**

Run: `./gradlew :app-mobile:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (uusi tiedosto kääntyy; `WarningsSection` on toistaiseksi käyttämätön internal — sallittu).

> Jos käännös valittaa `LocalRefreshTick`- tai `openUrl`-symboleista, varmista että ne ovat samassa paketissa `org.jrs82.fsclock.mobile` (ComposeMainScreen.kt / ComposeHomeContent.kt). Molemmat ovat `internal` samassa moduulissa → ei importtia tarvita.

- [ ] **Step 3: Commit**

```bash
git add app-mobile/src/main/java/org/jrs82/fsclock/mobile/WarningsScreen.kt
git commit -m "feat(warnings): WarningsSection-sivu (skrollattava, oma alue/koko Suomi, kaikki kentät)"
```

---

### Task 5: Rekisteröi sivu "Sää"-valikkoon ja reititykseen

**Files:**
- Modify: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/ComposeMainScreen.kt` (enum ~270, routing ~585, drawer ~673)

**Interfaces:**
- Consumes: `WarningsSection()` (Task 4).
- Produces: `HomeSection.WEATHER_WARNINGS` (name = `"WEATHER_WARNINGS"`, käytetään deep-linkissä Task 6).

- [ ] **Step 1: Lisää enum-arvo**

`ComposeMainScreen.kt`, korvaa:

```kotlin
    FORECAST("Sää-ennuste 7vrk"),
    PLACES("Paikkakunnat"),
```

→

```kotlin
    FORECAST("Sää-ennuste 7vrk"),
    WEATHER_WARNINGS("Säävaroitukset"),
    PLACES("Paikkakunnat"),
```

- [ ] **Step 2: Lisää reititys**

`ComposeMainScreen.kt`, korvaa:

```kotlin
                    HomeSection.FORECAST -> ForecastSection()
```

→

```kotlin
                    HomeSection.FORECAST -> ForecastSection()
                    HomeSection.WEATHER_WARNINGS -> WarningsSection()
```

- [ ] **Step 3: Lisää drawer-rivi "Sää"-otsikon alle**

`ComposeMainScreen.kt`, korvaa:

```kotlin
            DrawerHeader("Sää", R.drawable.mobile_ic_weather_24)
            DrawerItem(HomeSection.FORECAST, current, onSelect)
            DrawerItem(HomeSection.PLACES, current, onSelect)
```

→

```kotlin
            DrawerHeader("Sää", R.drawable.mobile_ic_weather_24)
            DrawerItem(HomeSection.FORECAST, current, onSelect)
            DrawerItem(HomeSection.WEATHER_WARNINGS, current, onSelect)
            DrawerItem(HomeSection.PLACES, current, onSelect)
```

- [ ] **Step 4: Käännä — varmista että kääntyy ja `when` on tyhjentävä**

Run: `./gradlew :app-mobile:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (uusi enum-haara katettu reitityksessä → ei `when`-varoitusta).

- [ ] **Step 5: Commit**

```bash
git add app-mobile/src/main/java/org/jrs82/fsclock/mobile/ComposeMainScreen.kt
git commit -m "feat(warnings): rekisteröi Säävaroitukset-sivu Sää-valikkoon"
```

---

### Task 6: Säävaroitusilmoitus deep-linkkaa varoitussivulle

**Files:**
- Modify: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/WeatherWarningNotifier.kt` (postFor, ~104 ja ~115)

**Interfaces:**
- Consumes: `HomeSection.WEATHER_WARNINGS` (Task 5); valmis `Notifications.post(openSection)` → `externalSection` → `HomeSection.valueOf` (ComposeMainScreen LaunchedEffect, ei muutosta).

- [ ] **Step 1: Vaihda openSection null → "WEATHER_WARNINGS" (yksi varoitus)**

`WeatherWarningNotifier.kt` `postFor`-funktiossa, ensimmäinen `Notifications.post(...)`-kutsu (yhden varoituksen haara): korvaa rivi joka on pelkkä `                null,` (otsikon "Säävaroitus: ..." jälkeinen post-kutsu) tällä:

```kotlin
                "WEATHER_WARNINGS",
```

- [ ] **Step 2: Vaihda openSection null → "WEATHER_WARNINGS" (monta varoitusta)**

Saman funktion `else`-haaran `Notifications.post(...)`-kutsussa ("Säävaroituksia paikkakunnalla ..."): korvaa sen `                null,` tällä:

```kotlin
                "WEATHER_WARNINGS",
```

> Tarkista että `postFor`-funktiossa ei jää enää yhtään `null,`-argumenttia `Notifications.post`-kutsuihin. Lopputulos: molemmat kutsut antavat `"WEATHER_WARNINGS"` `openSection`-parametriksi.

- [ ] **Step 3: Käännä — varmista että kääntyy**

Run: `./gradlew :app-mobile:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app-mobile/src/main/java/org/jrs82/fsclock/mobile/WeatherWarningNotifier.kt
git commit -m "feat(warnings): säävaroitusilmoitus avaa Säävaroitukset-sivun (ei etusivua)"
```

---

### Task 7: Täysi verifiointi (testit + lint + R8 + emulaattori)

**Files:** (ei muutoksia — verifiointitehtävä)

- [ ] **Step 1: Aja koko yksikkötestijoukko**

Run: `./gradlew :app-mobile:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (kaikki testit, ml. uudet `WeatherWarningModelTest`, `WarningsClientParseTest`, `WarningDisplayTest`, ja muuttumattomat `WeatherWarningNotifierTest`/`FinnishRegionsTest`).

- [ ] **Step 2: Lint (release-vital)**

Run: `./gradlew :app-mobile:lintVitalRelease`
Expected: BUILD SUCCESSFUL (0 error).

- [ ] **Step 3: Release-build (R8)**

Run: `./gradlew :app-mobile:assembleRelease`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Emulaattorivahvistus (debug)**

Asenna debug-APK x86_64-emulaattoriin. Tarkista käsin:
1. "Sää"-valikossa näkyy uusi "Säävaroitukset"-rivi → avaa skrollattavan sivun.
2. "Oma alue" / "Koko Suomi" -valitsin toimii; "Koko Suomi" näyttää kaikki Suomen voimassa olevat varoitukset kortteina (ikoni + tyyppi + väri-pill + alue + aika + kuvaus + vakavuus/varmuus/kiireellisyys + lähde + "Lisätietoja"-linkki avaa FMI:n sivun).
3. Tyhjä tila näkyy oikein kun varoituksia ei ole / kotipaikkaa ei ole asetettu.
4. Vaalea + tumma teema OK.
5. Etusivun vanha säävaroituskortti toimii ennallaan (ei regressiota).
6. (Jos mahdollista) säävaroitusilmoituksen napautus avaa Säävaroitukset-sivun, ei etusivua.

> Emulaattori-gotchat (muistista): AVD `arkikeskus` käynnistyy vain kun `ANDROID_SDK_ROOT=C:\Users\jrs82\AppData\Local\Android\Sdk`. Mock-sijainti esim. `adb -s emulator-5554 emu geo fix 24.94 60.17` (Helsinki) → "Oma alue" suodattaa Uudenmaan varoitukset. RELEASE-APK (`org.jrs82.arkikeskus`) ajettavissa rinnakkain debugin kanssa → R8-runtime-testi ilman ARM-laitetta.

- [ ] **Step 5: Loppucommit (jos emulaattorissa ilmeni hiottavaa, korjaa ja committaa)**

Jos kaikki vihreää eikä korjattavaa: ei lisäcommittia. Muutoin korjaa havaittu ja:

```bash
git add -A
git commit -m "fix(warnings): emulaattorivahvistuksen hiomakorjaukset"
```

---

## Self-Review

**Spec coverage:**
- "Sää"-valikon alle uusi sivu → Task 5 (enum + drawer + routing).
- Skrollattava (kaikki data mahtuu) → Task 4 (LazyColumn).
- Niin paljon FMI-dataa kuin saatavissa → Task 1 (mallikentät) + Task 2 (parseri: severity/certainty/urgency/awareness_type/effective/senderName/web) + Task 4 (kaikki näytetään kortissa).
- Oma alue / koko Suomi -valitsin (käyttäjän valinta) → Task 4 (FilterChip + prefs) reusing `WeatherWarningNotifier.areaMatchesHome` + `FinnishRegions`.
- Kaikki tiedot heti kortissa (käyttäjän valinta) → Task 4 `WarningCard`.
- Ilmoitus johtaa varoitussivulle → Task 6.
- Ei regressiota etusivun korttiin → Task 1 vanha 8-arg konstruktori + Task 2 ei muuta repo-/listener-rajapintaa.
- Cancel ei näytetä → Task 2.
- Verifiointi (testit/lint/R8/emulaattori) → Task 7.

**Placeholder scan:** Ei TBD/TODO; jokaisella koodiaskeleella täysi koodi.

**Type consistency:** `awarenessIconRes`/`severityFi`/`certaintyFi`/`urgencyFi`/`warningPeriod` (Task 3) — samat nimet/parametrit Task 4:n kutsuissa. `WeatherWarning.AwarenessType.fromParam` (Task 1) — sama Task 2:ssa. Täysi 15-arg konstruktori (Task 1) — sama argumenttijärjestys Task 2:n kutsussa. `HomeSection.WEATHER_WARNINGS` (Task 5) name `"WEATHER_WARNINGS"` — sama merkkijono Task 6:n openSectionissa. `KEY_WARNINGS_SCOPE_OWN` määritelty ja käytetty vain Task 4:ssä.
