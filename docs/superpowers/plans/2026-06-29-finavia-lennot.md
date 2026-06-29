# Suomen lennot (Finavia) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lisää Arkikeskukseen "Lennot"-näkymä (Suomen saapuvat/lähtevät lennot Finaviasta), jossa kenttäkohtainen näyttötaulu + lentonumerohaku + etusivun kortti.

**Architecture:** Cloudflare Worker `lennot` pollaa Finaviaa kerran/min (avain piilossa), parsii XML→kevyt JSON, tarjoaa reunavälimuistitetun `/flights`-endpointin. Sovellus hakee koko Suomen JSON:n yhdellä kutsulla ja suodattaa/hakee paikallisesti. Sama Cloudflare-tili kuin `uutiskeskus`.

**Tech Stack:** Cloudflare Workers (JS, `fast-xml-parser`, `wrangler`), node:test; Android Kotlin (Compose Material3, `HttpURLConnection`, `org.json`), JUnit.

## Global Constraints

- **API-autentikointi:** Finavia vaatii otsikon **`app_key: <subscription-avain>`** (EI `Ocp-Apim-Subscription-Key`, EI query-param). Avain = Worker-secret `APP_KEY`, EI koskaan sovelluskoodissa/APK:ssa.
- **Endpoint:** `https://apigw.finavia.fi/flights/public/v0/flights/all/all` (koko Suomi, molemmat suunnat). Worker-julkis-URL: `https://lennot.jarsi.workers.dev` (subdomain `jarsi`, kuten `uutiskeskus`).
- **Live-aikaleimat ovat ISO 8601** (`2026-06-29T12:23:00Z`, UTC). Näyttö Europe/Helsinki-aikana.
- **Käyttöehdot:** lähde-maininta "Tiedot: Finavia" pieni/neutraali; **ei Finavia-logoa, ei affiliaatiovihjettä**.
- **Tuotanto-deployn (Worker) tekee käyttäjä**, ei agentti. Release = vain APK (ei mappingia GitHubiin, ei Claude-mainintoja). Commit-tyyli: conventional commits, **ei Co-Authored-By-trailereita**.
- **Android-paketti:** `org.jrs82.fsclock.mobile`. Uudet UI-/data-tiedostot `app-mobile/src/main/java/org/jrs82/fsclock/mobile/`, testit `app-mobile/src/test/java/org/jrs82/fsclock/mobile/`.
- **Versio-tavoite:** 2.23.0-mobile (versionCode 82) — asetetaan vasta lopuksi.

---

### Task 1: Worker `lennot` — kevennysfunktio (slim) + testi

**Files:**
- Create: `C:\Users\jrs82\Downloads\Samsung sm-t819\lennot\package.json`
- Create: `C:\Users\jrs82\Downloads\Samsung sm-t819\lennot\src\slim.js`
- Test: `C:\Users\jrs82\Downloads\Samsung sm-t819\lennot\test\slim.test.js`

**Interfaces:**
- Produces: `slimFlights(xmlText: string): { updated: string, dep: Flight[], arr: Flight[] }` ja `slimFlight(f: object, dir: 'dep'|'arr'): Flight`. Flight = `{ apt, fno, sch, est, act, scode, st, apt2, city, gate, stand, belt, chk, ac, cs }` (kentät `string|null`, `cs: string[]`).

- [ ] **Step 1: package.json**

```json
{
  "name": "lennot",
  "version": "1.0.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "wrangler dev",
    "deploy": "wrangler deploy",
    "test": "node --test"
  },
  "dependencies": {
    "fast-xml-parser": "^4.5.0"
  },
  "devDependencies": {
    "wrangler": "^4.0.0"
  }
}
```

- [ ] **Step 2: Asenna riippuvuudet**

Run (PowerShell, kansiossa `lennot`):
```
npm install
```
Expected: `node_modules/` luodaan, `fast-xml-parser` + `wrangler` asentuvat.

- [ ] **Step 3: Kirjoita kaatuva testi** (`test/slim.test.js`)

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { slimFlights } from "../src/slim.js";

const SAMPLE = `<?xml version="1.0" encoding="UTF-8"?>
<flights xmlns="http://www.finavia.fi/FlightsService.xsd">
  <dep>
    <header><timestamp>2026-06-29T12:23:00Z</timestamp><from>Finavia</from></header>
    <body>
      <flight>
        <h_apt>HEL</h_apt><fltnr>AY1731</fltnr>
        <sdt>2026-06-29T05:55:00Z</sdt>
        <mfltnr>AY123</mfltnr><cflight_1>AY123</cflight_1><cflight_2>QF8238</cflight_2>
        <route_1>FNC</route_1><route_n_1>Funchal</route_n_1><route_n_fi_1></route_n_fi_1>
        <chkarea>2</chkarea><chkdsk_1>207</chkdsk_1><chkdsk_2>215</chkdsk_2>
        <gate>28</gate><park>28</park>
        <prm>SCH</prm><prt_f>Aikataulussa</prt_f>
        <pest_d>2026-06-29T06:02:00Z</pest_d><act_d></act_d>
        <actype>321</actype><bltarea></bltarea>
      </flight>
    </body>
  </dep>
  <arr>
    <header><timestamp>2026-06-29T12:23:00Z</timestamp><from>Finavia</from></header>
    <body>
      <flight>
        <h_apt>HEL</h_apt><fltnr>AY432</fltnr>
        <sdt>2026-06-29T05:40:00Z</sdt>
        <route_1>OUL</route_1><route_n_1>Oulu</route_n_1><route_n_fi_1>Oulu</route_n_fi_1>
        <gate>22</gate><park>22</park>
        <prm>LAN</prm><prt_f>Laskeutunut</prt_f>
        <act_d>2026-06-29T05:40:00Z</act_d><bltarea>2A</bltarea><actype>E90</actype>
      </flight>
    </body>
  </arr>
</flights>`;

test("slimFlights mappaa dep + arr kentät", () => {
  const r = slimFlights(SAMPLE);
  assert.equal(r.updated, "2026-06-29T12:23:00Z");
  assert.equal(r.dep.length, 1);
  assert.equal(r.arr.length, 1);
  const d = r.dep[0];
  assert.equal(d.fno, "AY1731");
  assert.equal(d.apt2, "FNC");
  assert.equal(d.city, "Funchal");           // route_n_fi_1 tyhjä -> route_n_1
  assert.equal(d.est, "2026-06-29T06:02:00Z");
  assert.equal(d.chk, "2 / 207 / 215");
  assert.equal(d.belt, null);                // dep: ei hihnaa
  assert.deepEqual(d.cs, ["AY123", "QF8238"]); // mfltnr+cflight, dedup, oma fltnr pois
  const a = r.arr[0];
  assert.equal(a.city, "Oulu");
  assert.equal(a.belt, "2A");                // arr: hihna
  assert.equal(a.chk, null);                 // arr: ei lähtöselvitystä
  assert.equal(a.act, "2026-06-29T05:40:00Z");
});

test("slimFlights sietää tyhjän/puuttuvan body:n", () => {
  const r = slimFlights(`<flights xmlns="x"><dep></dep></flights>`);
  assert.deepEqual(r.dep, []);
  assert.deepEqual(r.arr, []);
});
```

- [ ] **Step 4: Aja testi, varmista että kaatuu**

Run: `npm test`
Expected: FAIL — `Cannot find module '../src/slim.js'`.

- [ ] **Step 5: Toteuta `src/slim.js`**

```js
import { XMLParser } from "fast-xml-parser";

const parser = new XMLParser({ ignoreAttributes: true, trimValues: true });

function toArray(x) {
  if (x === undefined || x === null) return [];
  return Array.isArray(x) ? x : [x];
}

function pick(v) {
  if (v === undefined || v === null) return null;
  const s = String(v).trim();
  return s.length ? s : null;
}

export function slimFlight(f, dir) {
  const own = pick(f.fltnr);
  const cs = [];
  for (const k of ["mfltnr", "cflight_1", "cflight_2", "cflight_3", "cflight_4", "cflight_5", "cflight_6"]) {
    const v = pick(f[k]);
    if (v && v !== own && !cs.includes(v)) cs.push(v);
  }
  const chk = dir === "dep"
    ? ([pick(f.chkarea), pick(f.chkdsk_1), pick(f.chkdsk_2)].filter(Boolean).join(" / ") || null)
    : null;
  return {
    apt: pick(f.h_apt),
    fno: own,
    sch: pick(f.sdt),
    est: pick(f.pest_d) ?? pick(f.est_d),
    act: pick(f.act_d),
    scode: pick(f.prm),
    st: pick(f.prt_f),
    apt2: pick(f.route_1),
    city: pick(f.route_n_fi_1) ?? pick(f.route_n_1),
    gate: pick(f.gate),
    stand: pick(f.park),
    belt: dir === "arr" ? pick(f.bltarea) : null,
    chk,
    ac: pick(f.actype),
    cs,
  };
}

export function slimFlights(xmlText) {
  const doc = parser.parse(xmlText);
  const root = doc.flights || {};
  const dep = root.dep || {};
  const arr = root.arr || {};
  const updated =
    pick(dep.header && dep.header.timestamp) ??
    pick(arr.header && arr.header.timestamp) ??
    new Date().toISOString();
  return {
    updated: String(updated),
    dep: toArray(dep.body && dep.body.flight).map((f) => slimFlight(f, "dep")),
    arr: toArray(arr.body && arr.body.flight).map((f) => slimFlight(f, "arr")),
  };
}
```

- [ ] **Step 6: Aja testi, varmista että menee läpi**

Run: `npm test`
Expected: PASS — molemmat testit vihreät.

- [ ] **Step 7: Commit**

Run (kansiossa `lennot`, alusta git tarvittaessa `git init`):
```
git add package.json src/slim.js test/slim.test.js
git commit -m "feat(lennot): Finavia XML -> kevyt JSON slim-funktio + testi"
```

---

### Task 2: Worker `lennot` — index.js (cron + fetch + cache) + config

**Files:**
- Create: `C:\Users\jrs82\Downloads\Samsung sm-t819\lennot\wrangler.jsonc`
- Create: `C:\Users\jrs82\Downloads\Samsung sm-t819\lennot\src\index.js`
- Create: `C:\Users\jrs82\Downloads\Samsung sm-t819\lennot\README.md`

**Interfaces:**
- Consumes: `slimFlights` (Task 1).
- Produces: HTTP `GET /flights` → kevyt JSON (`{updated,dep,arr}`); `GET /` → health.

- [ ] **Step 1: wrangler.jsonc**

```jsonc
{
  "name": "lennot",
  "main": "src/index.js",
  "compatibility_date": "2026-06-01",
  // Cron: kerran minuutissa pollaa Finaviaa (data päivittyy ~1/min).
  "triggers": {
    "crons": ["* * * * *"]
  }
}
```

- [ ] **Step 2: src/index.js**

```js
import { slimFlights } from "./slim.js";

const FINAVIA_URL = "https://apigw.finavia.fi/flights/public/v0/flights/all/all";
const FLIGHTS_KEY = "https://lennot.jarsi.workers.dev/flights"; // kiinteä cache-avain (= julkis-URL)

function cors() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, OPTIONS",
  };
}
function jsonHeaders() {
  return { "Content-Type": "application/json; charset=utf-8" };
}

async function refresh(env) {
  try {
    const r = await fetch(FINAVIA_URL, {
      headers: { app_key: env.APP_KEY, "Cache-Control": "no-cache" },
    });
    if (!r.ok) {
      console.log("Finavia HTTP " + r.status);
      return; // säilytä edellinen hyvä välimuisti
    }
    const xml = await r.text();
    const slim = slimFlights(xml);
    if (slim.dep.length + slim.arr.length === 0) {
      console.log("tyhjä parse — säilytä vanha");
      return;
    }
    const resp = new Response(JSON.stringify(slim), {
      headers: { ...jsonHeaders(), ...cors(), "Cache-Control": "public, max-age=60" },
    });
    await caches.default.put(new Request(FLIGHTS_KEY), resp);
    console.log("cached " + slim.dep.length + " dep / " + slim.arr.length + " arr");
  } catch (e) {
    console.log("refresh failed: " + e);
  }
}

export default {
  async scheduled(event, env, ctx) {
    ctx.waitUntil(refresh(env));
  },
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { headers: cors() });
    if (url.pathname === "/flights") {
      const cache = caches.default;
      let res = await cache.match(new Request(FLIGHTS_KEY));
      if (!res) {
        await refresh(env);
        res = await cache.match(new Request(FLIGHTS_KEY));
      }
      if (!res) {
        res = new Response(JSON.stringify({ updated: null, dep: [], arr: [] }), {
          headers: { ...jsonHeaders(), ...cors() },
        });
      }
      return res;
    }
    if (url.pathname === "/") return new Response("lennot OK\n", { headers: cors() });
    return new Response("Not found", { status: 404, headers: cors() });
  },
};
```

- [ ] **Step 3: README.md (deploy-ohjeet käyttäjälle)**

```markdown
# lennot — Finavia-lentodata-proxy (Cloudflare Worker)

Pollaa Finaviaa kerran/min, parsii XML→kevyt JSON, tarjoaa reunacachetetun `/flights`.

## Deploy (käyttäjä tekee)
1. `npm install`
2. `npx wrangler secret put APP_KEY`  → liitä Finavia-tilauksen Primary key
3. `npx wrangler deploy`
4. Testaa: `curl https://lennot.jarsi.workers.dev/flights | head`

## Endpointit
- `GET /flights` → `{ updated, dep[], arr[] }`
- `GET /` → health

Avain (`APP_KEY`) on Finavia-portaalin subscription Primary key. Lähetetään Finavialle otsikkona `app_key`.
Suositus: uusi avain portaalin "Regenerate"-linkistä kun secret on asetettu.
```

- [ ] **Step 4: Lint-tarkistus (parsiutuuko worker)**

Run (kansiossa `lennot`): `npx wrangler deploy --dry-run --outdir=dist`
Expected: kääntyy ilman virheitä (bundle luodaan `dist/`). Ei vaadi kirjautumista dry-runissa.

- [ ] **Step 5: Commit**

```
git add wrangler.jsonc src/index.js README.md
git commit -m "feat(lennot): worker scheduled-poll + reunacachetetty /flights endpoint"
```

> **HUOM:** Varsinaisen `wrangler deploy`-tuotantokäyttöönoton tekee käyttäjä (Step 2–3 READMEsta). Agentti ei deployaa.

---

### Task 3: Sovellus — Flight-mallit + FlightsClient (parse + fetch)

**Files:**
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/Flights.kt`
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/FlightsClient.kt`
- Test: `app-mobile/src/test/java/org/jrs82/fsclock/mobile/FlightsClientTest.kt`

**Interfaces:**
- Produces: `enum FlightDir { ARR, DEP }`; `data class Flight(...)` (kentät alla) `.effectiveMs: Long`, `.delayMin: Long`; `data class FlightsData(updatedMs, arr, dep)`; `object FlightsClient { fun fetch(): FlightsData?; fun parse(json: String): FlightsData }`.

- [ ] **Step 1: Flights.kt (mallit)**

```kotlin
package org.jrs82.fsclock.mobile

enum class FlightDir { ARR, DEP }

/** Yksi lento (kevyt malli; lähde = lennot-Worker). Ajat epoch ms (UTC), null = puuttuu. */
data class Flight(
    val dir: FlightDir,
    val airport: String,        // h_apt (IATA)
    val flightNo: String,       // fno
    val scheduledMs: Long,      // sch
    val estimatedMs: Long?,     // est
    val actualMs: Long?,        // act
    val statusCode: String,     // scode (prm)
    val status: String,         // st (prt_f, suomi)
    val otherAirport: String,   // apt2 (kohde dep / lähtö arr)
    val city: String,           // city
    val gate: String?,
    val stand: String?,
    val belt: String?,          // vain arr
    val checkin: String?,       // vain dep
    val aircraft: String?,
    val codeshares: List<String>,
) {
    /** Paras tiedossa oleva aika: toteutunut → arvio → aikataulu. */
    val effectiveMs: Long get() = actualMs ?: estimatedMs ?: scheduledMs

    /** Myöhästyminen minuutteina (+ = myöhässä) suhteessa aikatauluun. */
    val delayMin: Long get() = (effectiveMs - scheduledMs) / 60000L
}

data class FlightsData(val updatedMs: Long, val arr: List<Flight>, val dep: List<Flight>)
```

- [ ] **Step 2: Kirjoita kaatuva testi (FlightsClientTest.kt)**

```kotlin
package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightsClientTest {
    private val json = """
        {"updated":"2026-06-29T12:23:00Z",
         "dep":[{"apt":"HEL","fno":"AY1731","sch":"2026-06-29T05:55:00Z",
                 "est":"2026-06-29T06:10:00Z","act":null,"scode":"SCH","st":"Aikataulussa",
                 "apt2":"FNC","city":"Funchal","gate":"28","stand":"28","belt":null,
                 "chk":"2 / 207","ac":"321","cs":["AY123","QF8238"]}],
         "arr":[{"apt":"OUL","fno":"AY432","sch":"2026-06-29T05:40:00Z",
                 "est":null,"act":"2026-06-29T05:40:00Z","scode":"LAN","st":"Laskeutunut",
                 "apt2":"HEL","city":"Helsinki","gate":null,"stand":"22","belt":"2A",
                 "chk":null,"ac":"E90","cs":[]}]}
    """.trimIndent()

    @Test fun parsoiKentatJaAjat() {
        val data = FlightsClient.parse(json)
        assertEquals(1, data.dep.size)
        assertEquals(1, data.arr.size)
        val d = data.dep[0]
        assertEquals("AY1731", d.flightNo)
        assertEquals(FlightDir.DEP, d.dir)
        assertEquals("Funchal", d.city)
        assertEquals(listOf("AY123", "QF8238"), d.codeshares)
        assertNull(d.actualMs)
        // est 06:10 vs sch 05:55 -> 15 min myöhässä, effective = est
        assertEquals(15L, d.delayMin)
        assertEquals(d.estimatedMs, d.effectiveMs)
        val a = data.arr[0]
        assertEquals(FlightDir.ARR, a.dir)
        assertEquals("2A", a.belt)
        assertNull(a.gate)
        // act asetettu -> effective = act
        assertEquals(a.actualMs, a.effectiveMs)
    }

    @Test fun sietaaTyhjanJaNullin() {
        val data = FlightsClient.parse("""{"updated":null,"dep":[],"arr":[]}""")
        assertEquals(0L, data.updatedMs)
        assertTrue(data.dep.isEmpty())
        assertTrue(data.arr.isEmpty())
    }
}
```

- [ ] **Step 3: Aja testi, varmista että kaatuu**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "*.FlightsClientTest"`
Expected: FAIL — `FlightsClient` ei käänny (puuttuu).

- [ ] **Step 4: Toteuta FlightsClient.kt**

```kotlin
package org.jrs82.fsclock.mobile

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.jrs82.fsclock.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Lennot-Workerin JSON-asiakas. Hakee koko Suomen kevyen lentodatan yhdellä kutsulla;
 *  suodatus/haku tehdään paikallisesti ([FlightsFilter]). Julkinen endpoint, ei avainta. */
object FlightsClient {
    private const val BASE_URL = "https://lennot.jarsi.workers.dev"
    private const val TIMEOUT_MS = 10_000
    private const val MAX_BODY = 4_000_000

    fun fetch(): FlightsData? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$BASE_URL/flights").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "Arkikeskus/" + BuildConfig.VERSION_NAME + " (Android)")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) {
                Log.w("FlightsClient", "HTTP " + conn.responseCode)
                return null
            }
            val baos = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    baos.write(buf, 0, read)
                    if (baos.size() > MAX_BODY) break
                }
            }
            parse(baos.toString("UTF-8"))
        } catch (e: Exception) {
            Log.w("FlightsClient", "fetch failed: " + e.message)
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun parse(json: String): FlightsData {
        val o = JSONObject(json)
        val updated = isoToMs(str(o, "updated")) ?: 0L
        return FlightsData(updated, parseList(o.optJSONArray("arr"), FlightDir.ARR), parseList(o.optJSONArray("dep"), FlightDir.DEP))
    }

    private fun parseList(arr: JSONArray?, dir: FlightDir): List<Flight> {
        if (arr == null) return emptyList()
        val out = ArrayList<Flight>(arr.length())
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val fno = str(f, "fno") ?: continue
            val sch = isoToMs(str(f, "sch")) ?: continue
            val csArr = f.optJSONArray("cs")
            val cs = if (csArr == null) emptyList() else (0 until csArr.length())
                .mapNotNull { j -> csArr.optString(j, "").takeIf { it.isNotBlank() } }
            out.add(
                Flight(
                    dir = dir,
                    airport = str(f, "apt") ?: "",
                    flightNo = fno,
                    scheduledMs = sch,
                    estimatedMs = isoToMs(str(f, "est")),
                    actualMs = isoToMs(str(f, "act")),
                    statusCode = str(f, "scode") ?: "",
                    status = str(f, "st") ?: "",
                    otherAirport = str(f, "apt2") ?: "",
                    city = str(f, "city") ?: "",
                    gate = str(f, "gate"),
                    stand = str(f, "stand"),
                    belt = str(f, "belt"),
                    checkin = str(f, "chk"),
                    aircraft = str(f, "ac"),
                    codeshares = cs,
                ),
            )
        }
        return out
    }

    /** JSON-null tai "null"-merkkijono → null; muuten trimmattu arvo (tyhjä → null). */
    private fun str(o: JSONObject, key: String): String? {
        if (o.isNull(key)) return null
        val s = o.optString(key, "")
        return s.ifBlank { null }
    }

    /** ISO-8601 (`…Z`) → epoch ms. Fallback: doc-näytteen US-muoto. Parsimaton → null. */
    private fun isoToMs(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            java.time.Instant.parse(s).toEpochMilli()
        } catch (e: Exception) {
            try {
                val fmt = java.text.SimpleDateFormat("M/d/yyyy h:mm:ss a", java.util.Locale.US)
                fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                fmt.parse(s)?.time
            } catch (e2: Exception) {
                null
            }
        }
    }
}
```

- [ ] **Step 5: Aja testi, varmista että menee läpi**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "*.FlightsClientTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```
git add app-mobile/src/main/java/org/jrs82/fsclock/mobile/Flights.kt app-mobile/src/main/java/org/jrs82/fsclock/mobile/FlightsClient.kt app-mobile/src/test/java/org/jrs82/fsclock/mobile/FlightsClientTest.kt
git commit -m "feat(lennot): Flight-mallit + FlightsClient (JSON-parse + haku)"
```

---

### Task 4: Sovellus — FlightsFilter (suodatus + haku)

**Files:**
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/FlightsFilter.kt`
- Test: `app-mobile/src/test/java/org/jrs82/fsclock/mobile/FlightsFilterTest.kt`

**Interfaces:**
- Consumes: `Flight`, `FlightsData`, `FlightDir` (Task 3).
- Produces: `object FlightsFilter { fun board(data, airport, dir): List<Flight>; fun search(data, query): List<Flight>; fun airportsWithCounts(data): Map<String,Int> }`.

- [ ] **Step 1: Kirjoita kaatuva testi (FlightsFilterTest.kt)**

```kotlin
package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class FlightsFilterTest {
    private fun f(dir: FlightDir, apt: String, fno: String, sch: Long, cs: List<String> = emptyList()) =
        Flight(dir, apt, fno, sch, null, null, "", "", "X", "X", null, null, null, null, null, cs)

    private val data = FlightsData(
        updatedMs = 0L,
        dep = listOf(f(FlightDir.DEP, "HEL", "AY1731", 300), f(FlightDir.DEP, "HEL", "AY100", 100), f(FlightDir.DEP, "OUL", "AY500", 200)),
        arr = listOf(f(FlightDir.ARR, "HEL", "AY432", 150, cs = listOf("JL6877"))),
    )

    @Test fun boardSuodattaaKentanJaSuunnanJaJarjestaa() {
        val r = FlightsFilter.board(data, "HEL", FlightDir.DEP)
        assertEquals(listOf("AY100", "AY1731"), r.map { it.flightNo }) // nouseva sch
    }

    @Test fun searchKattaaKaikkiKentatJaSuunnatJaCodeshare() {
        assertEquals(1, FlightsFilter.search(data, "AY500").size)       // toinen kenttä
        assertEquals("AY432", FlightsFilter.search(data, "ay 432")[0].flightNo) // ci + välilyönti
        assertEquals("AY432", FlightsFilter.search(data, "JL6877")[0].flightNo) // codeshare
        assertEquals(0, FlightsFilter.search(data, "").size)
    }

    @Test fun airportsWithCountsLaskee() {
        val c = FlightsFilter.airportsWithCounts(data)
        assertEquals(3, c["HEL"])  // 2 dep + 1 arr
        assertEquals(1, c["OUL"])
    }
}
```

- [ ] **Step 2: Aja testi, varmista että kaatuu**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "*.FlightsFilterTest"`
Expected: FAIL — `FlightsFilter` puuttuu.

- [ ] **Step 3: Toteuta FlightsFilter.kt**

```kotlin
package org.jrs82.fsclock.mobile

/** Paikalliset suodatus-/hakufunktiot Worker-dataan (ei lisähakuja). Puhdas → yksikkötestattava. */
object FlightsFilter {

    /** Valitun kentän + suunnan lennot, järjestettynä paras-tiedossa-oleva-aika nousevasti. */
    fun board(data: FlightsData?, airport: String, dir: FlightDir): List<Flight> {
        if (data == null) return emptyList()
        val src = if (dir == FlightDir.ARR) data.arr else data.dep
        return src.filter { it.airport.equals(airport, ignoreCase = true) }
            .sortedBy { it.effectiveMs }
    }

    /** Lentonumerohaku koko Suomesta (kaikki kentät + molemmat suunnat); osuma fno- tai codeshare-numeroon. */
    fun search(data: FlightsData?, query: String): List<Flight> {
        if (data == null) return emptyList()
        val q = query.replace(" ", "").uppercase()
        if (q.isEmpty()) return emptyList()
        fun norm(s: String) = s.replace(" ", "").uppercase()
        fun match(fl: Flight) = norm(fl.flightNo).contains(q) || fl.codeshares.any { norm(it).contains(q) }
        return (data.dep + data.arr).filter(::match).sortedBy { it.effectiveMs }
    }

    /** Kenttä → lentojen määrä juuri nyt (valitsimen apuna). */
    fun airportsWithCounts(data: FlightsData?): Map<String, Int> {
        if (data == null) return emptyMap()
        val m = HashMap<String, Int>()
        for (fl in data.dep + data.arr) m[fl.airport] = (m[fl.airport] ?: 0) + 1
        return m
    }
}
```

- [ ] **Step 4: Aja testi, varmista että menee läpi**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "*.FlightsFilterTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```
git add app-mobile/src/main/java/org/jrs82/fsclock/mobile/FlightsFilter.kt app-mobile/src/test/java/org/jrs82/fsclock/mobile/FlightsFilterTest.kt
git commit -m "feat(lennot): FlightsFilter (kenttä/suunta-board + koko Suomen lentonumerohaku)"
```

---

### Task 5: Sovellus — FlightDisplay (tilakategoria väritystä varten)

**Files:**
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/FlightDisplay.kt`
- Test: `app-mobile/src/test/java/org/jrs82/fsclock/mobile/FlightDisplayTest.kt`

**Interfaces:**
- Consumes: `Flight` (Task 3).
- Produces: `enum FlightStatusCat { ON_TIME, ATTENTION, DELAYED, CANCELLED, COMPLETED }`; `object FlightDisplay { fun category(f: Flight): FlightStatusCat }`.

Tilakategoria johdetaan suomenkielisestä statustekstistä (`prt_f`, aina luotettava) + myöhästymisestä — EI arvailla `prm`-koodeja.

- [ ] **Step 1: Kirjoita kaatuva testi (FlightDisplayTest.kt)**

```kotlin
package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class FlightDisplayTest {
    private fun f(status: String, sch: Long, est: Long?) =
        Flight(FlightDir.DEP, "HEL", "AY1", sch, est, null, "", status, "X", "X", null, null, null, null, null, emptyList())

    @Test fun kategoriaTekstistaJaMyohastymisesta() {
        assertEquals(FlightStatusCat.CANCELLED, FlightDisplay.category(f("Peruttu", 0, null)))
        assertEquals(FlightStatusCat.COMPLETED, FlightDisplay.category(f("Lähtenyt", 0, null)))
        assertEquals(FlightStatusCat.COMPLETED, FlightDisplay.category(f("Laskeutunut", 0, null)))
        assertEquals(FlightStatusCat.ATTENTION, FlightDisplay.category(f("Lähtöselvitys", 0, null)))
        // 10 min myöhässä, ei lopputilaa
        assertEquals(FlightStatusCat.DELAYED, FlightDisplay.category(f("Arvioitu", 0, 600_000)))
        assertEquals(FlightStatusCat.ON_TIME, FlightDisplay.category(f("Aikataulussa", 0, null)))
    }
}
```

- [ ] **Step 2: Aja testi, varmista että kaatuu**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "*.FlightDisplayTest"`
Expected: FAIL.

- [ ] **Step 3: Toteuta FlightDisplay.kt**

```kotlin
package org.jrs82.fsclock.mobile

enum class FlightStatusCat { ON_TIME, ATTENTION, DELAYED, CANCELLED, COMPLETED }

/** Lennon tilan visuaalinen kategoria. Johdetaan suomenkielisestä statustekstistä + myöhästymisestä
 *  (väri on toissijainen signaali; teksti näytetään aina). */
object FlightDisplay {
    fun category(f: Flight): FlightStatusCat {
        val s = f.status.lowercase()
        return when {
            s.contains("peru") -> FlightStatusCat.CANCELLED
            s.contains("lähtenyt") || s.contains("laskeutunut") || s.contains("saapunut") -> FlightStatusCat.COMPLETED
            s.contains("selvit") || s.contains("portti") || s.contains("portil") ||
                s.contains("koneeseen") || s.contains("kuulutus") || s.contains("viimeinen") -> FlightStatusCat.ATTENTION
            f.delayMin >= 5L -> FlightStatusCat.DELAYED
            else -> FlightStatusCat.ON_TIME
        }
    }
}
```

- [ ] **Step 4: Aja testi, varmista että menee läpi**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "*.FlightDisplayTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```
git add app-mobile/src/main/java/org/jrs82/fsclock/mobile/FlightDisplay.kt app-mobile/src/test/java/org/jrs82/fsclock/mobile/FlightDisplayTest.kt
git commit -m "feat(lennot): FlightDisplay tilakategoria (teksti+myöhästymä)"
```

---

### Task 6: Sovellus — FinaviaAirports (kenttälista)

**Files:**
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/FinaviaAirports.kt`
- Test: `app-mobile/src/test/java/org/jrs82/fsclock/mobile/FinaviaAirportsTest.kt`

**Interfaces:**
- Produces: `object FinaviaAirports { data class Airport(val iata: String, val name: String); val ALL: List<Airport>; fun name(iata: String): String }`. `ALL[0]` = HEL.

- [ ] **Step 1: Kirjoita kaatuva testi (FinaviaAirportsTest.kt)**

```kotlin
package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinaviaAirportsTest {
    @Test fun helOnEnsimmainen() {
        assertEquals("HEL", FinaviaAirports.ALL.first().iata)
    }
    @Test fun nimiLoytyyJaFallback() {
        assertEquals("Helsinki-Vantaa", FinaviaAirports.name("HEL"))
        assertEquals("XXX", FinaviaAirports.name("XXX")) // tuntematon → koodi
        assertTrue(FinaviaAirports.ALL.size >= 16)
    }
}
```

- [ ] **Step 2: Aja testi, varmista että kaatuu**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "*.FinaviaAirportsTest"`
Expected: FAIL.

- [ ] **Step 3: Toteuta FinaviaAirports.kt**

```kotlin
package org.jrs82.fsclock.mobile

/** Finavian lentokentät (IATA → suomenkielinen nimi). HEL ensin (oletus), loput kokoluokan mukaan. */
object FinaviaAirports {
    data class Airport(val iata: String, val name: String)

    val ALL: List<Airport> = listOf(
        Airport("HEL", "Helsinki-Vantaa"),
        Airport("RVN", "Rovaniemi"),
        Airport("OUL", "Oulu"),
        Airport("TKU", "Turku"),
        Airport("TMP", "Tampere-Pirkkala"),
        Airport("VAA", "Vaasa"),
        Airport("KUO", "Kuopio"),
        Airport("KTT", "Kittilä"),
        Airport("IVL", "Ivalo"),
        Airport("KOK", "Kokkola-Pietarsaari"),
        Airport("JOE", "Joensuu"),
        Airport("JYV", "Jyväskylä"),
        Airport("KAJ", "Kajaani"),
        Airport("KEM", "Kemi-Tornio"),
        Airport("KAO", "Kuusamo"),
        Airport("MHQ", "Maarianhamina"),
        Airport("POR", "Pori"),
        Airport("SVL", "Savonlinna"),
        Airport("ENF", "Enontekiö"),
    )

    fun name(iata: String): String = ALL.firstOrNull { it.iata == iata }?.name ?: iata
}
```

- [ ] **Step 4: Aja testi, varmista että menee läpi**

Run: `./gradlew :app-mobile:testDebugUnitTest --tests "*.FinaviaAirportsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```
git add app-mobile/src/main/java/org/jrs82/fsclock/mobile/FinaviaAirports.kt app-mobile/src/test/java/org/jrs82/fsclock/mobile/FinaviaAirportsTest.kt
git commit -m "feat(lennot): FinaviaAirports kenttälista (IATA→suomi, HEL oletus)"
```

---

### Task 7: Sovellus — FlightsRepository (jaettu välimuisti + listener)

**Files:**
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/FlightsRepository.kt`

**Interfaces:**
- Consumes: `FlightsClient.fetch()` (Task 3), `FlightsData`.
- Produces: `object FlightsRepository { fun interface Listener { fun onFlightsChanged(data: FlightsData?) }; fun addListener(l); fun removeListener(l); fun getLatest(): FlightsData?; fun refreshIfStale(); fun refreshNow() }`. Säilyttää viimeisen datan virheessä (ei tyhjennä).

Mirror `FmiWarningsRepository.java`-kuviota. Ei erillistä yksikkötestiä (verifioidaan laitteella Task 11) — säikeistys + System.currentTimeMillis tekee yksikkötestistä hauraan.

- [ ] **Step 1: Toteuta FlightsRepository.kt**

```kotlin
package org.jrs82.fsclock.mobile

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Singleton: säilyttää koko Suomen lentodatan muistissa, hakee taustasäikeellä ja ilmoittaa
 *  kuuntelijoille. Sivu + etusivun kortti jakavat tämän → yksi verkkokutsu. Vrt. FmiWarningsRepository. */
object FlightsRepository {
    private const val TAG = "FlightsRepo"
    private const val REFRESH_MIN_INTERVAL_MS = 45_000L

    fun interface Listener { fun onFlightsChanged(data: FlightsData?) }

    private val io = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile private var latest: FlightsData? = null
    @Volatile private var lastFetchAt = 0L
    @Volatile private var inFlight = false

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) { listeners.add(l); l.onFlightsChanged(latest) }
    }
    fun removeListener(l: Listener) { listeners.remove(l) }
    fun getLatest(): FlightsData? = latest

    fun refreshIfStale() {
        if (inFlight) return
        if (lastFetchAt > 0L && System.currentTimeMillis() - lastFetchAt < REFRESH_MIN_INTERVAL_MS) return
        refreshNow()
    }

    fun refreshNow() {
        if (inFlight) return
        inFlight = true
        io.execute {
            try {
                val data = FlightsClient.fetch()
                if (data != null) {
                    latest = data
                    lastFetchAt = System.currentTimeMillis()
                    Log.d(TAG, "Refreshed: ${data.dep.size} dep / ${data.arr.size} arr")
                    for (l in listeners) try { l.onFlightsChanged(latest) } catch (e: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetch failed: $e")
            } finally {
                inFlight = false
            }
        }
    }
}
```

- [ ] **Step 2: Käännä (varmista että moduuli kääntyy)**

Run: `./gradlew :app-mobile:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```
git add app-mobile/src/main/java/org/jrs82/fsclock/mobile/FlightsRepository.kt
git commit -m "feat(lennot): FlightsRepository (jaettu välimuisti + listener, vrt. FmiWarningsRepository)"
```

---

### Task 8: Sovellus — FlightsScreen (sivu + kortti + ikoni)

**Files:**
- Create: `app-mobile/src/main/res/drawable/mobile_ic_flight_24.xml`
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/FlightsScreen.kt`

**Interfaces:**
- Consumes: `FlightsRepository`, `FlightsFilter`, `FinaviaAirports`, `FlightDisplay`, `Flight`, `FlightDir`, `FlightStatusCat`; jaetut apurit `ArkiCard`, `ArkiPill`, `SearchTextField`, `LocalRefreshTick`, `R.drawable.mobile_ic_refresh_24`.
- Produces: `internal fun FlightsSection()` (käytetään Task 9 + 10).

Ei yksikkötestiä (Compose-UI → laiteverifiointi Task 11).

- [ ] **Step 1: Lisää lento-ikoni (mobile_ic_flight_24.xml)**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FF000000"
        android:pathData="M21,16v-2l-8,-5V3.5C13,2.67 12.33,2 11.5,2S10,2.67 10,3.5V9l-8,5v2l8,-2.5V19l-2,1.5V22l3.5,-1 3.5,1v-1.5L13,19v-5.5L21,16z" />
</vector>
```

- [ ] **Step 2: Toteuta FlightsScreen.kt**

```kotlin
package org.jrs82.fsclock.mobile

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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.delay
import org.jrs82.fsclock.R

private fun timeHm(ms: Long): String {
    val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    fmt.timeZone = java.util.TimeZone.getTimeZone("Europe/Helsinki")
    return fmt.format(java.util.Date(ms))
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun FlightsSection() {
    val refresh = LocalRefreshTick.current
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val l = FlightsRepository.Listener { main.post { tick++ } }
        FlightsRepository.addListener(l)
        FlightsRepository.refreshIfStale()
        onDispose { FlightsRepository.removeListener(l) }
    }
    LaunchedEffect(refresh) { if (refresh > 0) FlightsRepository.refreshNow() }
    LaunchedEffect(Unit) { while (true) { delay(60_000); FlightsRepository.refreshIfStale() } }

    val data = remember(tick) { FlightsRepository.getLatest() }
    var airport by rememberSaveable { mutableStateOf("HEL") }
    var dir by rememberSaveable { mutableStateOf(FlightDir.DEP) }
    var query by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    val list = remember(data, airport, dir, query, tick) {
        if (query.isNotBlank()) FlightsFilter.search(data, query)
        else FlightsFilter.board(data, airport, dir)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lennot", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val updated = data?.updatedMs ?: 0L
            if (updated > 0L) {
                Text("Päivitetty ${timeHm(updated)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { FlightsRepository.refreshNow() }) {
                Icon(painterResource(R.drawable.mobile_ic_refresh_24), contentDescription = "Päivitä")
            }
        }
        Spacer(Modifier.height(6.dp))
        if (query.isBlank()) {
            ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = it }) {
                OutlinedTextField(
                    value = "${FinaviaAirports.name(airport)} ($airport)",
                    onValueChange = {}, readOnly = true,
                    label = { Text("Lentokenttä") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    FinaviaAirports.ALL.forEach { ap ->
                        DropdownMenuItem(text = { Text("${ap.name} (${ap.iata})") }, onClick = { airport = ap.iata; menuOpen = false })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = dir == FlightDir.DEP, onClick = { dir = FlightDir.DEP }, label = { Text("Lähtevät") })
                FilterChip(selected = dir == FlightDir.ARR, onClick = { dir = FlightDir.ARR }, label = { Text("Saapuvat") })
            }
            Spacer(Modifier.height(8.dp))
        }
        SearchTextField(value = query, onValueChange = { query = it }, placeholder = "Hae lentonumerolla (esim. AY1731)", onClear = { query = "" })
        if (query.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text("Haku kattaa koko Suomen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        when {
            data == null -> Text("Ladataan lentoja…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.isEmpty() -> Text(
                if (query.isNotBlank()) "Ei osumia haulle \"$query\"."
                else "Ei lentoja — ${FinaviaAirports.name(airport)}, ${if (dir == FlightDir.DEP) "lähtevät" else "saapuvat"}.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list, key = { it.dir.name + it.airport + it.flightNo + it.scheduledMs }) { fl -> FlightCard(fl, showAirport = query.isNotBlank()) }
                item {
                    Spacer(Modifier.height(4.dp))
                    Text("Tiedot: Finavia", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun FlightCard(f: Flight, showAirport: Boolean) {
    val arki = ArkiTheme.colors
    val cat = FlightDisplay.category(f)
    val color = when (cat) {
        FlightStatusCat.CANCELLED -> Color(0xFFD32F2F)
        FlightStatusCat.DELAYED -> Color(0xFFE08A00)
        FlightStatusCat.ATTENTION -> arki.weatherAccent
        FlightStatusCat.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
        FlightStatusCat.ON_TIME -> MaterialTheme.colorScheme.primary
    }
    val dimmed = cat == FlightStatusCat.COMPLETED
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp).alpha(if (dimmed) 0.6f else 1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    val timeChanged = f.delayMin != 0L && (f.actualMs != null || f.estimatedMs != null)
                    if (timeChanged) {
                        Text(timeHm(f.scheduledMs), style = MaterialTheme.typography.bodySmall,
                            textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(timeHm(f.effectiveMs), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        color = if (cat == FlightStatusCat.DELAYED) color else MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.flightNo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val place = if (f.city.isNotBlank()) "${f.city} (${f.otherAirport})" else f.otherAirport
                    Text((if (f.dir == FlightDir.ARR) "Saapuu: " else "Määränpää: ") + place,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (showAirport) {
                        Text("Kenttä: ${FinaviaAirports.name(f.airport)} (${f.airport})",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (f.status.isNotBlank()) ArkiPill(f.status, color)
            }
            val details = buildList {
                f.gate?.let { add("Portti $it") }
                f.belt?.let { add("Hihna $it") }
                f.checkin?.let { add("Lähtöselvitys $it") }
                f.stand?.let { add("Asemapaikka $it") }
                f.aircraft?.let { add("Kone $it") }
            }
            if (details.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(details.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
            }
            if (f.codeshares.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Myös: ${f.codeshares.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
```

- [ ] **Step 3: Käännä**

Run: `./gradlew :app-mobile:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (Jos `menuAnchor()` antaa deprecation-varoituksen, se on OK — sama kuin WarningsScreen.kt.)

- [ ] **Step 4: Commit**

```
git add app-mobile/src/main/res/drawable/mobile_ic_flight_24.xml app-mobile/src/main/java/org/jrs82/fsclock/mobile/FlightsScreen.kt
git commit -m "feat(lennot): FlightsSection-sivu (kenttävalitsin + suunta + haku + rikkaat kortit)"
```

---

### Task 9: Sovellus — navigointi (HomeSection.FLIGHTS + dispatch + drawer + deep-link)

**Files:**
- Modify: `app-mobile/.../mobile/ComposeMainScreen.kt` (enum `HomeSection` ~268; section-`when` ~586; `DrawerContent` "Muut" ~707)

**Interfaces:**
- Consumes: `FlightsSection()` (Task 8).
- Produces: `HomeSection.FLIGHTS` (deep-link `open_section=FLIGHTS` toimii automaattisesti olemassa olevan `HomeSection.valueOf`-mekanismin kautta).

- [ ] **Step 1: Lisää enum-arvo**

Tiedostossa `ComposeMainScreen.kt`, `enum class HomeSection` — lisää rivi `DEVICE_INFO`-rivin jälkeen (ennen sulkevaa `}`):

```kotlin
    DEVICE_INFO("Puhelimen tiedot"),
    FLIGHTS("Lennot"),
}
```

- [ ] **Step 2: Lisää section-dispatch**

Samassa tiedostossa, `when (section)`-lohkossa (n. rivi 587), lisää `WEATHER_WARNINGS`-rivin jälkeen:

```kotlin
                    HomeSection.WEATHER_WARNINGS -> WarningsSection()
                    HomeSection.FLIGHTS -> FlightsSection()
```

- [ ] **Step 3: Lisää valikkokohta ("Muut"-ryhmään)**

Samassa tiedostossa, `DrawerContent`-funktion "Muut"-ryhmässä (n. rivi 707–709), lisää `DEVICE_INFO`-rivin jälkeen:

```kotlin
            DrawerItem(HomeSection.ELECTRICITY, current, onSelect)
            DrawerItem(HomeSection.DEVICE_INFO, current, onSelect)
            DrawerItem(HomeSection.FLIGHTS, current, onSelect)
```

- [ ] **Step 4: Käännä**

Run: `./gradlew :app-mobile:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```
git add app-mobile/src/main/java/org/jrs82/fsclock/mobile/ComposeMainScreen.kt
git commit -m "feat(lennot): HomeSection.FLIGHTS + navigointi (dispatch + valikko + deep-link)"
```

---

### Task 10: Sovellus — etusivun kortti (HomeFlightsCard + HomeWidget.FLIGHTS)

**Files:**
- Modify: `app-mobile/.../mobile/ComposeHomeWidgets.kt` (enum `HomeWidget` ~97; lisää `HomeFlightsCard` loppuun)
- Modify: `app-mobile/.../mobile/ComposeHomeContent.kt` (kortti-`when` ~216)

**Interfaces:**
- Consumes: `FlightsRepository`, `FlightsFilter`, `FinaviaAirports`, `FlightDisplay`, `ArkiCard`, `ArkiCardHeader`, `ArkiPill`, `R.drawable.mobile_ic_flight_24`, `HomeSection.FLIGHTS`.
- Produces: `HomeWidget.FLIGHTS`, `internal fun HomeFlightsCard(onOpenFlights: () -> Unit)`.

- [ ] **Step 1: Lisää HomeWidget-enum-arvo**

`ComposeHomeWidgets.kt`, `enum class HomeWidget` — lisää `TRANSIT`-rivin jälkeen (ennen `}`):

```kotlin
    TRANSIT("transit", "Lähilähdöt", true),
    FLIGHTS("flights", "Lennot", true),
}
```

- [ ] **Step 2: Lisää HomeFlightsCard (ComposeHomeWidgets.kt loppuun)**

```kotlin
// ===================== Lennot-kortti (etusivulle) =====================

/** Etusivun lentokortti: seuraavat lähtevät HEL:stä. Jakaa FlightsRepositoryn (ei omaa hakua). */
@Composable
internal fun HomeFlightsCard(onOpenFlights: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val l = FlightsRepository.Listener { main.post { tick++ } }
        FlightsRepository.addListener(l)
        FlightsRepository.refreshIfStale()
        onDispose { FlightsRepository.removeListener(l) }
    }
    val data = remember(tick) { FlightsRepository.getLatest() }
    val arki = ArkiTheme.colors
    val next = remember(data, tick) {
        FlightsFilter.board(data, "HEL", FlightDir.DEP)
            .filter { FlightDisplay.category(it) != FlightStatusCat.COMPLETED }
            .take(3)
    }
    if (data != null && next.isEmpty()) return // ei näytetä tyhjää korttia
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            ArkiCardHeader(
                icon = painterResource(R.drawable.mobile_ic_flight_24),
                accent = arki.weatherAccent,
                title = "Lennot",
                subtitle = "Helsinki-Vantaa · lähtevät",
                trailing = { TextButton(onClick = onOpenFlights) { Text("Kaikki") } },
            )
            if (data == null) {
                Text("Ladataan…", modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                next.forEach { f ->
                    RowDivider()
                    HomeFlightRow(f)
                }
            }
        }
    }
}

@Composable
private fun HomeFlightRow(f: Flight) {
    val timeFmt = remember {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).apply {
            timeZone = java.util.TimeZone.getTimeZone("Europe/Helsinki")
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(timeFmt.format(java.util.Date(f.effectiveMs)), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(f.flightNo, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            val place = if (f.city.isNotBlank()) f.city else f.otherAirport
            Text(place, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (f.status.isNotBlank()) {
            Text(f.status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
```

> Tarkista importit: `HomeFlightsCard`/`HomeFlightRow` käyttävät samoja symboleja kuin `HomeWarningsCard` samassa tiedostossa (Handler, Looper, DisposableEffect, remember, mutableStateOf, ArkiCard, ArkiCardHeader, RowDivider, TextButton, painterResource, Row, Column, Text, Spacer, Modifier, FontWeight, Alignment, dp, R). Ne ovat jo tuotuina tiedostoon (HomeWarningsCard käyttää niitä). Lisää vain mahdollisesti puuttuva `androidx.compose.foundation.layout.width` jos kääntäjä valittaa.

- [ ] **Step 3: Liitä kortti dispatch-lohkoon (ComposeHomeContent.kt ~221)**

`when (id)` -lohkossa, lisää `TRANSIT`-rivin jälkeen:

```kotlin
                                HomeWidget.TRANSIT.id -> HomeTransitCard(onOpenTransit = { onOpenSection(HomeSection.TRANSIT) })
                                HomeWidget.FLIGHTS.id -> HomeFlightsCard(onOpenFlights = { onOpenSection(HomeSection.FLIGHTS) })
```

- [ ] **Step 4: Käännä**

Run: `./gradlew :app-mobile:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```
git add app-mobile/src/main/java/org/jrs82/fsclock/mobile/ComposeHomeWidgets.kt app-mobile/src/main/java/org/jrs82/fsclock/mobile/ComposeHomeContent.kt
git commit -m "feat(lennot): etusivun lentokortti (HomeFlightsCard + HomeWidget.FLIGHTS)"
```

---

### Task 11: Versionnosto + testit + emulaattori-/laiteverifiointi + release-tarkistus

**Files:**
- Modify: `app-mobile/build.gradle` (rivit ~34–35: `versionCode`/`versionName`)

- [ ] **Step 1: Nosta versio**

`app-mobile/build.gradle`: `versionCode 81` → `82`, `versionName '2.22.0-mobile'` → `'2.23.0-mobile'`.

- [ ] **Step 2: Aja kaikki yksikkötestit**

Run: `./gradlew :app-mobile:testDebugUnitTest`
Expected: PASS (ml. uudet FlightsClientTest, FlightsFilterTest, FlightDisplayTest, FinaviaAirportsTest). Aja myös worker-testi: `lennot`-kansiossa `npm test` → PASS.

- [ ] **Step 3: Käynnistä emulaattori**

PowerShell (Windows-env, EI Git Bash → muuten PANIC):
```
$env:ANDROID_SDK_ROOT="C:\Users\jrs82\AppData\Local\Android\Sdk"
& "$env:ANDROID_SDK_ROOT\emulator\emulator.exe" -avd <avd_nimi> -netdelay none -netspeed full
```
(Listaa AVD:t: `& "$env:ANDROID_SDK_ROOT\emulator\emulator.exe" -list-avds`.)
Asenna debug: `./gradlew :app-mobile:installDebug` → käynnistä sovellus.

- [ ] **Step 4: Manuaalinen verifiointi (emulaattori, debug)**

Worker on oltava deployattuna (käyttäjä), jotta data tulee. Tarkista:
1. Valikko → "Lennot" avaa sivun; kenttävalitsin (oletus Helsinki-Vantaa), Lähtevät/Saapuvat toimii, lista latautuu.
2. Kentän vaihto (esim. Oulu) + suunnan vaihto = välitön (ei latausta).
3. Lentonumerohaku (esim. "AY") suodattaa koko Suomesta; tyhjennys palauttaa taulun.
4. Korttien kentät (aika, lentonumero, kaupunki, tila-pilleri, portti/hihna/kone/codeshare) näkyvät; myöhässä-aika oranssina + yliviivattu aikataulu.
5. "Päivitä"-nappi + "Päivitetty HH:mm". Tyhjä kenttä → tyhjätila. "Tiedot: Finavia" näkyy.
6. Etusivun "Lennot"-kortti: seuraavat lähtevät HEL:stä + "Kaikki" → sivulle.
7. Deep-link: `adb -s emulator-5554 shell am start -n org.jrs82.arkikeskus.debug/org.jrs82.fsclock.mobile.MobileComposeMainActivity -f 0x14000000 --es open_section FLIGHTS` → avaa Lennot-sivun.
8. Molemmat teemat (vaalea/tumma).

- [ ] **Step 5: Release-tarkistus (R8)**

Run: `./gradlew :app-mobile:testDebugUnitTest :app-mobile:lintVitalRelease :app-mobile:assembleRelease`
Expected: kaikki vihreät, signed APK syntyy `app-mobile/build/outputs/apk/release/`.
Asenna release emulaattoriin/Pixeliin ja toista Step 4:n ydinkohdat (R8 voi karsia/yhdistää → varmista ettei lentonäkymä regressoi).

- [ ] **Step 6: Commit**

```
git add app-mobile/build.gradle
git commit -m "release: 2.23.0-mobile (versionCode 82) — Lennot (Finavia)"
```

---

## Self-Review (tehty)

- **Spec-kattavuus:** Worker (slim+endpoint+cache+resilienssi) → Task 1–2. Mallit/parse → Task 3. Suodatus/haku → Task 4. Tilavärit → Task 5. Kenttälista → Task 6. Repository (jaettu, säilyttää virheessä) → Task 7. Sivu (valitsin/suunta/haku/kortit/refresh/staleness/tyhjä/lähde) → Task 8. Navigointi+deep-link → Task 9. Etusivukortti → Task 10. Versio+testit+emulaattori+laite+release → Task 11. ✔
- **Tyyppikonsistenssi:** `FlightsClient.fetch()/parse()`, `FlightsFilter.board/search/airportsWithCounts`, `FlightDisplay.category`, `FlightStatusCat`, `FinaviaAirports.ALL/name`, `FlightsRepository.Listener/getLatest/refreshIfStale/refreshNow`, `FlightsSection()`, `HomeFlightsCard(onOpenFlights)` — käytetään johdonmukaisesti tehtävien välillä. ✔
- **Ei placeholdereita:** kaikki vaiheet sisältävät täyden koodin/komennot. ✔

## Jatkolista (EI tässä) — kirjattu speksiin
Lento-ilmoitukset (seuranta→push) · Glance-widget · konetyyppikoodi→nimi · custom-domain `lennot.arkikeskus.com` · monilegireitti / "Koko Suomi" -koonti.
