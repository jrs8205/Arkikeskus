# Spec: Suomen lennot (Finavia) — Arkikeskus

**Päivä:** 2026-06-29
**Tila:** hyväksytty (brainstorming → suoraan toteutukseen)
**Versio-tavoite:** 2.23.0-mobile (versionCode 82)

## Tavoite

Tuoda Arkikeskukseen **Suomen saapuvat ja lähtevät lennot** (kaikki Finavian kentät) Finavian Public Flights -API:sta. Käyttötarkoitus = **yhdistelmä: kenttäkohtainen näyttötaulu + lentonumerohaku** samassa näkymässä.

## Käyttäjän valinnat (brainstorming)

- **Käyttötarkoitus:** taulu + haku (yhdistelmä).
- **Kentät:** kaikki ~16 Finavia-kenttää valittavissa, **oletus Helsinki (HEL)**.
- **Pinnat v1:** oma sivu valikossa **+** etusivun kortti. (Ilmoitukset → jatkolista; widget → tulevaisuus.)
- **Sivun lista:** rikkaat kortit heti auki (ei napautusta tietoihin).
- **Arkkitehtuuri:** A — Worker tarjoaa koko Suomen kevyen JSON:n + reunavälimuisti; sovellus suodattaa/hakee paikallisesti.
- **Skaalaus:** julkinen sovellus → suunniteltu kestämään iso käyttäjämäärä (reunacache irrottaa käyttäjämäärän Worker/Finavia-kuormasta).

## API-fakta (live-varmistettu 29.6.2026)

- Provider: Finavia API Developer Portal (Azure APIM), tuote "Public Flights v0". Ilmainen, ~100k kutsua/vrk, päivittyy ~1/min, min. pollausväli 30 s.
- **Endpoint:** `GET https://apigw.finavia.fi/flights/public/v0/flights/all/all` (flightType=`all`, airport=`all` → koko Suomi, molemmat suunnat).
- **⭐ AUTENTIKOINTI:** otsikko **`app_key: <subscription-avain>`** (EI `Ocp-Apim-Subscription-Key`, EI query-param → 401). Avain = käyttäjän tilaus "Arkikeskus" portaalista.
- **Vastaus:** XML, namespace `http://www.finavia.fi/FlightsService.xsd`, `<flights><dep>/<arr>` → `<header>` + `<body><flight>…`. **Live-data käyttää ISO 8601 -aikoja** (`2026-06-29T12:23:00Z`).
- Mittaus: `all/all` ≈ 1,0 MB, 784 lentoa, 16 kenttää (HEL ~644, RVN/OUL/TKU/VAA/MHQ/KUO/KOK/TMP/KTT/IVL/SVL/KEM/KAO/KAJ/JOE).
- **Käyttöehdot:** ilmainen, kehittäjille → sopii. ⚠️ ei Finavia-logoa/nimeä korostetusti, ei affiliaatiovihjettä (lähde-maininta pieni/neutraali); avain henkilökohtainen → vain Worker-secret.

## Arkkitehtuuri

```
Finavia apigw  ──(app_key, cron 60s)──►  Worker "lennot"  ──(JSON ~25–60kt, edge-cache)──►  Arkikeskus
 /all/all (XML ~1MB)                      scheduled: fetch→parse→karsi→caches.default
                                          fetch: palauta cachetettu JSON + Cache-Control:60
```

Worker on ainoa joka puhuu Finavialle (avain piilossa, kuorma vakio kerran/min riippumatta käyttäjämäärästä). Reunavälimuisti (CDN) tarjoilee toistuvat client-pyynnöt ilman Worker-kutsua. Sama Cloudflare-tili kuin `uutiskeskus` (Workers Paid).

## Komponentti 1: Worker `lennot`

**Tiedostot:** oma kansio (kuten `uutiskeskus/`): `wrangler.jsonc` + `src/index.js`. Riippuvuus `fast-xml-parser`. Avain: `wrangler secret put APP_KEY`.

**`wrangler.jsonc`:** name `lennot`, main `src/index.js`, `compatibility_date`, cron `"* * * * *"`. Ei D1, ei AI.

**`scheduled()` (kerran/min):**
1. `fetch(FINAVIA_ALL_ALL, { headers: { app_key: env.APP_KEY } })`.
2. Parsii XML→JSON (`fast-xml-parser`).
3. Karsii kentät (~50 → 13/lento), rakentaa kevyen `{ updated, dep[], arr[] }`.
4. `caches.default.put(CACHE_KEY, Response.json(slim, { headers: { "Cache-Control": "public, max-age=60" } }))`.
5. **Resilienssi:** ei-200/timeout/parse-virhe → EI ylikirjoiteta edellistä hyvää välimuistia.

**`fetch()`:** `GET /flights` → cachetettu JSON (CORS-otsikot). Cache-miss (kylmästartti) → laiska haku+parsinta kerran. `GET /` → health/status.

**Kevennetty JSON:**
```json
{ "updated": "2026-06-29T12:23:00Z", "dep": [ {flight} ], "arr": [ {flight} ] }
```
**flight-olio** (lyhyet avaimet):
| avain | XML-lähde | merkitys |
|---|---|---|
| `apt` | h_apt | kotikenttä IATA |
| `fno` | fltnr | lentonumero |
| `sch` | sdt | aikataulutettu (ISO) |
| `est` | pest_d ‹- est_d | arvioitu (julkinen ensin) |
| `act` | act_d | toteutunut |
| `scode` | prm | tilakoodi |
| `st` | prt_f | tila suomeksi |
| `apt2` | route_1 | toinen kenttä IATA (dep=kohde, arr=lähtö) |
| `city` | route_n_fi_1 ‹- route_n_1 | kaupunki (suomi ensin) |
| `gate` | gate | portti |
| `stand` | park | asemapaikka |
| `belt` | bltarea | matkalaukkahihna (vain arr) |
| `chk` | chkarea + chkdsk_1/2 | lähtöselvitys (vain dep) |
| `ac` | actype | konetyyppikoodi |
| `cs` | mfltnr + cflight_1..6 | codeshare-numerot (tyhjät pois, dedup) |

Huom: lähteessä **ei terminaali-kenttää** → portti/hihna/lähtöselvitys/asemapaikka. Konetyyppikoodin luettava nimi = jatkolista.

## Komponentti 2: Sovelluksen datakerros (Kotlin, mobile-paketti)

**Mallit (`Flights.kt`):**
```kotlin
enum class FlightDir { ARR, DEP }
data class Flight(
    val dir: FlightDir, val airport: String, val flightNo: String,
    val scheduledMs: Long, val estimatedMs: Long?, val actualMs: Long?,
    val statusCode: String, val status: String,
    val otherAirport: String, val city: String,
    val gate: String?, val stand: String?, val belt: String?, val checkin: String?,
    val aircraft: String?, val codeshares: List<String>,
) {
    val effectiveMs get() = actualMs ?: estimatedMs ?: scheduledMs
    val delayMin get() = (effectiveMs - scheduledMs) / 60000L
}
data class FlightsData(val updatedMs: Long, val arr: List<Flight>, val dep: List<Flight>)
```

**`FlightsClient`** (object, kuten `ForeignNewsClient`): `BASE_URL = "https://lennot.jarsi.workers.dev"`, GET `/flights`, UA `Arkikeskus/<versio> (Android)`, timeoutit, kattorajattu virtaluku, `org.json`-parsinta → `FlightsData`. Ajat ISO-8601→epoch ms (`Instant.parse`), tolerantti fallback US-muodolle; parsimaton → null.

**`FlightsRepository`** (object): `StateFlow<FlightsData?>` + `refresh(forced)` IO-säikeellä + throttle (min ~30 s autopäivityksen väli). Säilyttää viimeisimmän datan virheessä. **Sivu + etusivun kortti jakavat tämän flow'n** (yksi verkkokutsu).

**`FlightsFilter`** (puhtaat funktiot):
- `board(data, airport, dir)` → suodata airport+dir, järjestä `effectiveMs` nousevasti; menneet (lähtenyt/laskeutunut) himmeinä.
- `search(data, query)` → kaikki kentät + molemmat suunnat, osuma `flightNo`/codeshare (case-insensitive, välilyönnit pois).
- `airportsWithCounts(data)` → kenttävalitsin/aktiiviset kentät.

## Komponentti 3: UI

**Navigointi:** `HomeSection.FLIGHTS("Lennot")` → `when`-haara `FlightsSection()` + `DrawerItem` (matka-ryhmä). Ikoni `Icons.Filled.FlightTakeoff`. Deep-link `open_section=FLIGHTS` valmiiksi (mekanismi olemassa).

**Lennot-sivu** (teema-adaptiivinen, reuse `ArkiCard`/pillit):
1. **Kenttävalitsin** `ExposedDropdownMenuBox` (kaikki Finavia-kentät suomeksi, oletus HEL).
2. **Suunta** segmentoitu: Lähtevät / Saapuvat (oletus Lähtevät).
3. **Hakukenttä** "Hae lentonumerolla (esim. AY1731)" → hakutila koko Suomesta; tyhjennys → taulu.
4. **`LazyColumn`** rikkaat kortit:
   - Ylärivi: aika (effective; myöhässä → aikataulu yliviivattuna + uusi aika oranssi/punainen) · lentonumero · tila-pilleri (prt_f, väri tilakoodista).
   - 2. rivi: "Kaupunki (IATA)".
   - Tietorivit (ei-tyhjät): Portti · Asemapaikka · Matkalaukkahihna (saap.) · Lähtöselvitys (lähd.) · Kone · Codeshare.
   - Tilavärit: ajallaan→neutraali, boarding/lähtöselvitys→korostus, myöhässä→oranssi, peruttu→punainen, lähtenyt/laskeutunut→himmeä.
5. **Päivitys:** refresh-nappi + autopäivitys ~60 s näkyvissä + "Päivitetty HH:mm".
6. **Tyhjätila** "Ei lentoja"; **lähde** "Tiedot: Finavia" (pieni/neutraali).

**Etusivun kortti** (`HomeFlightsCard`, kuten `HomeWarningsCard`): `HomeWidget.FLIGHTS` + wiring `ComposeHomeContent`. Otsikko "Lennot" + alaotsikko "Helsinki-Vantaa · lähtevät" + "Kaikki"-linkki → sivulle. Sisältö: seuraavat 2–3 lähtevää HEL:stä yksirivisinä. Jakaa `FlightsRepository`-flow'n.

## Virheenkäsittely & reunatapaukset

- Worker: Finavia 401/timeout/5xx/parse-virhe → säilytä viimeisin hyvä cache; kylmästartti epäonnistuu → tyhjät taulukot + vanha `updated`. Viallinen yksittäinen lento → ohita.
- Sovellus: verkkovirhe → näytä viimeksi ladattu data + "päivitys epäonnistui" + staleness; ei tyhjennetä ruutua. `updatedMs` > ~5 min → "tiedot voivat olla vanhentuneita".
- Aika UTC→Europe/Helsinki (DST-turvallinen). Parsimaton aika → "—".
- Codeshare dedup + tyhjät + oma numero pois. Menneet lennot himmeinä (API-ikkuna).
- Haku ilman osumia → "Ei osumia". Kenttä ilman lentoja → tyhjätila.

## Testaus

- **Sovellus-yksikkötestit** (org.json-testimpl kuten `NewsReadHistoryTest`): `FlightsFilter` (board-suodatus+järjestys, haku kaikista kentistä, codeshare-osuma, airportsWithCounts), `Flight.delayMin`/`effectiveMs`, `FlightsClient.parse` (JSON→malli, null, ISO-aika).
- **Worker:** parse-yksikkötesti (näyte-XML → kevyt JSON) tai curl-varmistus.
- **Laite:** emulaattori (debug + R8-release) + Pixel: sivu, kentänvaihto, suunta, haku, kortit, etusivukortti, deep-link, molemmat teemat, staleness/tyhjä/virhe. Release-tarkistus `testDebugUnitTest + lintVitalRelease + assembleRelease`.

## Jatkolista (EI v1)

1. Lento-ilmoitukset (seuraa lentoa → push tilamuutoksesta).
2. Kotinäytön Glance-widget.
3. Konetyyppikoodi → luettava nimi.
4. Custom-domain `lennot.arkikeskus.com`.
5. Monilegireitti ("via X"); "Koko Suomi" -koontitaulu.

## Julkaisu

- Uusi minor 2.23.0-mobile (code 82). Release = vain APK (ei mappingia GitHubiin, ei Claude-mainintoja).
- **Worker-deployn tekee käyttäjä** (`wrangler deploy` + `wrangler secret put APP_KEY`) — agentti ei tee tuotanto-deployta.
- Suositus: avaimen **Regenerate** kun secret Workerissa (chattiin vuotanut versio).
