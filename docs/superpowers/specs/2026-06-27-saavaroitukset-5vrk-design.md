# Säävaroitukset 5 vrk -näkymä — suunnitteludokumentti

**Päivä:** 2026-06-27
**Sovellus:** Arkikeskus (`org.jrs82.arkikeskus`), Java-ydin + Jetpack Compose
**Koodi:** `C:\Android\projects\FsClock-main` (worktree, `main` @ 60d83e6; edeltävä ominaisuus [Säävaroitukset-sivu + FMI-rikastus] jo pushattu debug/main)

## Tavoite

Korvataan nykyinen MeteoAlarm-pohjainen Säävaroitukset-sivu **FMI:n oman GeoServerin 5 vrk:n näkymällä**, joka jäljittelee ilmatieteenlaitos.fi/varoitukset-sivun UX:ää: **valitse päivä (ylärivi) + maakunta (pudotusvalikko)**, näytä valitun päivän + maakunnan varoitukset. Tämä tuo samalla **UV-tiedotteet**, kaikki varoitustyypit ja tulevat 5 vrk:n varoitukset.

## Datalähde — taustatutkimus (varmistettu)

- **FMI GeoServer** `https://www.ilmatieteenlaitos.fi/geoserver/alert/ows?service=WFS&version=2.0.0&request=GetFeature&typeName=alert:weather_finland_active_all&outputFormat=application/json` (selain-UA). Sisältää **5 vrk:n ikkunan** (effective_from tänään → +4 pv; per päivä 12–50 featurea), KAIKKI tyypit (forest-fire-weather, hot-weather, rain, thunder-storm, sea-thunder-storm, **uv-note**), `actualization_probability`-%, `physical_value`/`physical_unit` (celsius/mm/h/m/s/index), `info_fi` (HTML-entiteetit), `severity`=level-N, alue `reference`=county.N.
- **county.N → maakunta = Suomen viralliset maakuntakoodit** (varmistettu ristiintarkistamalla MeteoAlarmin maakuntanimiin): 1=Uusimaa, 2=Varsinais-Suomi, 4=Satakunta, 5=Kanta-Häme, 6=Pirkanmaa, 7=Päijät-Häme, 8=Kymenlaakso, 9=Etelä-Karjala, 10=Etelä-Savo, 11=Pohjois-Savo, 12=Pohjois-Karjala, 13=Keski-Suomi, 14=Etelä-Pohjanmaa, 15=Pohjanmaa, 16=Keski-Pohjanmaa, 17=Pohjois-Pohjanmaa, 18=Kainuu, 19=Lappi, 21=Ahvenanmaa (3 ja 20 ei käytössä).
- MeteoAlarm **jää vain ilmoituksiin** (lähivuorokausi). Ilmoituslogiikka EI muutu.

## Käyttäjän valinnat (brainstorming)

- Korvataan sivu FMI:n 5 vrk -näkymällä (ei toggle, ei pitkä lista).
- UX = **päivärivi + maakunta-pudotusvalikko**, näyttää valitun päivän + maakunnan varoitukset (FMI-sivun tapaan).
- Maakuntataso riittää (ei kuntatasoa — data on maakuntakohtaista; kunta vaatisi 300+ kohdan listan).
- Oletukset: **Tänään + kotipaikan maakunta** (fallback Koko Suomi).

## Arkkitehtuuri

Java-ydin + Compose, **ei** ViewModel/Hilt/MVI/StateFlow. **Reuse:** FMI-feature → olemassa oleva `WeatherWarning` + `WarningDetails` -malli → nykyinen `WarningCard` renderöi sellaisenaan.

### 1. county → maakunta -kartta
- **`FmiCounties.java`** (uusi, core, pure): `static String regionFor(int countyCode)` ja `static List<String> ALL_REGIONS` (19 maakuntaa kiinteässä järjestyksessä). Yllä varmistettu koodikartta.

### 2. FMI-varoituslähde (sivua varten, korvaa MeteoAlarmin sivulla)
- **`FmiWarningsClient.java`** (uusi, core): `List<WeatherWarning> fetch()` — hakee GeoServerin, parsii **jokaisen featuren** `WeatherWarning`-olioksi:
  - `event` + `awarenessType` ← `warning_context`-kartta (forest-fire-weather→FOREST_FIRE "Maastopalovaroitus", hot-weather→HIGH_TEMPERATURE "Hellevaroitus", rain→RAIN "Sadevaroitus", thunder-storm→THUNDERSTORM "Ukkosvaroitus", sea-thunder-storm→THUNDERSTORM+marine "Huomautus veneilijöille", uv-note→UV "UV-tiedote", cold-weather→LOW_TEMPERATURE "Pakkasvaroitus", wind→WIND "Tuulivaroitus", flood→FLOOD "Tulvavaroitus").
  - `level` ← `severity`: level-1→GREEN (uusi taso), level-2→YELLOW, level-3→ORANGE, level-4→RED.
  - `areaDesc` ← `FmiCounties.regionFor(county)`.
  - `onsetMs`/`expiresMs` ← effective_from/until (UTC-parsinta, sietää millit; sama logiikka kuin `FmiWarningDetailsClient.parseIso`).
  - `marine` ← sea-thunder-storm = true.
  - `details` ← `WarningDetails(probabilityPct, physicalText, detailText)` (reuse `FmiWarningDetailsClient.formatPhysical` + `decodeEntities`).
  - Reuse: jaetaan `decodeEntities`/`formatPhysical`/`parseIso` `FmiWarningDetailsClient`in kanssa (tehdään niistä jaettuja / siirretään apuriksi).
- **`AwarenessType.UV`** lisätään enumiin (icon = aurinko/uv; `mobile_ic_wx_hot` tai uusi). `WeatherWarning.Level.GREEN` lisätään (väri esim. 0xFF3FA34D; rank 0.5 < YELLOW).

### 3. Välimuisti
- **`FmiWarningsRepository.java`** (uusi, core, singleton; peilaa `WarningsRepository`): hakee koko 5 vrk:n listan kerran, säilyttää muistissa, Listener-kuviolla UI:lle, `refreshIfStale`/`refreshNow`. Päivä-/maakuntavalinta on **pelkkä UI-suodatin samaan välimuistiin** (ei lisähakuja).

### 4. Suodatus (pure, testattava)
- **`FmiWarningFilter.kt`** (mobile, pure funktiot):
  - `daysFrom(nowMs): List<DayOption>` — 5 päivää tästä (Helsinki), label "Tänään"/"Huomenna"/viikonpäivä+pvm + päivän alku/loppu-ms.
  - `overlapsDay(w, dayStartMs, dayEndMs): Boolean` — effective leikkaa päivän.
  - `warningsFor(all, day, region): List<WeatherWarning>`:
    - region = tietty maakunta → suodata `areaDesc==region && overlapsDay`, ryhmittele `awarenessType`:n mukaan → 1 kortti/tyyppi (aluetekstinä maakunta).
    - region = Koko Suomi → suodata `overlapsDay`, ryhmittele tyypeittäin, **kokoa maakunnat aluelistaksi** (edustava prob/physical = suurin fyysinen arvo; pisin teksti — sama logiikka kuin `WarningEnricher`).

### 5. UI — `WarningsScreen.kt` (kirjoitetaan uusiksi)
- Yläpalkki: otsikko "Säävaroitukset" + **päivärivi** (5 `FilterChip`, vaakaskrolli, oletus Tänään) + **maakunta-pudotusvalikko** (`ExposedDropdownMenuBox`: "Koko Suomi" + 19 maakuntaa; oletus kotimaakunta). Valinnat talteen prefseihin (`warn5_region`).
- Sisältö: `LazyColumn` valitun päivän+maakunnan korteista, **reuse `WarningCard`**. Tyhjä tila "Ei varoituksia — [maakunta], [päivä]."
- Päivitä-nappi (LocalRefreshTick → repo.refreshNow). Teema-adaptiivinen.
- **Poistuu:** nykyinen "Oma alue / Koko Suomi" -valitsin (korvautuu maakunta-pudotusvalikolla) + MeteoAlarm-pohjainen datahaku sivulta.

### 6. Mitä EI muutu
- Ilmoitukset (`WeatherWarningNotifier`) + MeteoAlarm-infra (`WarningsClient`/`WarningsRepository`/`WarningEnricher`) jäävät ennalleen → ilmoitukset toimivat kuten ennen, deep-link avaa tämän (uudistetun) sivun.
- Etusivun varoituskortti (`HomeWarningsCard`) + sen "Kaikki"-linkki säilyvät (lukee `WarningsRepository`-MeteoAlarmia). **Huom:** etusivun kortti pysyy MeteoAlarm-pohjaisena (lähivuorokausi) — sivu on FMI-5vrk. (Ei regressiota; eri lähteet eri paikoissa, molemmat FMI-peräisiä.)

### 7. Testit
Yksikkötestit: `FmiCounties.regionFor`; `FmiWarningsClient.parse` (feature→WeatherWarning: tyyppi/taso/alue/aika/details, UV + sea-thunder); `daysFrom`/`overlapsDay`; `warningsFor` (maakuntasuodatus + Koko Suomi -koonti + tyhjä). Olemassa olevat testikuviot (`returnDefaultValues` jo päällä). + emulaattori (debug + R8) + Pixel.

## Hyväksymiskriteerit
1. Sää-valikon Säävaroitukset-sivu näyttää päivärivin + maakunta-pudotusvalikon; valinta näyttää sen päivän + maakunnan varoitukset.
2. 5 vrk:n varoitukset (tänään→+4pv) saatavilla; UV-tiedotteet näkyvät; kaikki tyypit.
3. Koko Suomi -valinta kokoaa maakunnat aluelistaksi; tietty maakunta suodattaa.
4. Kortit näyttävät tyypin/tason/ajan/kuvauksen/todennäköisyyden/fyysisen arvon (reuse WarningCard).
5. Ilmoitukset + etusivun kortti toimivat ennallaan; deep-link avaa sivun.
6. Testit + lint + R8 vihreät; emulaattori/Pixel-vahvistus.

## Ulkopuolelle rajattu (YAGNI)
- Kuntataso (vain maakunta).
- Vaikutustekstit (FMI:n vakiotekstit per tyyppi+taso — ei datassa; jätetty).
- Ilmoituslogiikan muutokset (vain sivu uudistuu).
- Karttapiirto.
- Etusivun kortin vaihto FMI-lähteeseen (jää MeteoAlarm-pohjaiseksi).
