# Säävaroitukset-sivu — suunnitteludokumentti

**Päivä:** 2026-06-27
**Sovellus:** Arkikeskus (`org.jrs82.arkikeskus`), Java + Jetpack Compose -hybridi
**Koodi:** `C:\Android\projects\FsClock-main`

## Tavoite

Lisätään **"Sää­varoitukset"-sivu** "Sää"-valikon alle. Nyt säävaroituksille on vain etusivun kortti, ei omaa sivua. Lisäksi:

1. Säävaroitusilmoituksen napautus johtaa jatkossa **tälle uudelle sivulle** (ei etusivulle, kuten nyt).
2. Sivu näyttää **niin paljon varoitusdataa kuin FMI:ltä on saatavissa**.
3. Sivu on **skrollattava**, jotta kaikki tiedot mahtuvat vaikka varoituksia olisi paljon.

## Datalähde — taustatutkimus

FMI:n **oma** avoindata-WFS ei sisällä varoituskyselyä (vanha `fmi::warnings::finland::simplified` → "No handler found"). FMI julkaisee varoitukset **CAP-muodossa**, ja **MeteoAlarmin Suomi-syöte** (`https://feeds.meteoalarm.org/api/v1/warnings/feeds-finland`, lähettäjä `cap@fmi.fi`, "Ilmatieteen laitos") on virallinen FMI-varoituslähde. Sovellus käyttää tätä jo. **Ei uutta verkkolähdettä** — rikkaampi data saadaan poimimalla samasta syötteestä enemmän kenttiä.

### CAP-syötteen kentät per varoitus

✅ = sovellus poimii jo · ➕ = saatavilla, otetaan käyttöön

| Kenttä | Esimerkki | Tila |
|---|---|---|
| event (tyyppi) | "Maastopalovaroitus", "Hellevaroitus", "Raju ukonilma", "Huomautus veneilijöille" | ✅ |
| awareness_level (väri) | `2; yellow; Moderate` | ✅ |
| areaDesc + EMMA_ID | "Etelä-Pohjanmaa, Keski-Pohjanmaa" / FI030 | ✅ |
| onset / expires | alkaa / päättyy | ✅ |
| description | "Maastopalovaroitus on voimassa." | ✅ |
| awareness_type | `8; forest-fire`, `5; high-temperature`, `3; thunderstorm`, `1; wind` → ilmiöikoni | ➕ |
| severity | Minor / Moderate / Severe / Extreme | ➕ |
| certainty | Observed / Likely / Possible | ➕ |
| urgency | Immediate / Expected / Future | ➕ |
| effective | julkaisuhetki | ➕ |
| msgType | Alert / Update / Cancel | ➕ (Cancel ei näytetä) |
| senderName / contact | "Ilmatieteen laitos – Turvallisuussääpäivystys" | ➕ |
| web | linkki `ilmatieteenlaitos.fi/varoitukset` | ➕ |

Syötteessä **ei ole** geometriaa/polygoneja (vain aluekoodit/-nimet) → karttapiirto ei ole mahdollista tästä datasta.

## Käyttäjän valinnat (brainstorming)

- **Laajuus:** Oma alue + Koko Suomi -valitsin yläpalkkiin (oletus: oma alue).
- **Tarkkuus:** Kaikki tiedot heti kortissa (ei napautettavaa tarkennusta).
- **Skrollaus:** Sivu skrollattava (LazyColumn).

## Arkkitehtuuri

Kunnioitetaan projektin konventiota: Java-ydin + Compose-UI, **ei** ViewModel/Hilt/MVI/StateFlow. `WarningsRepository` hakee jo **kaikki Suomen** varoitukset välimuistiin → "Oma alue / Koko Suomi" on pelkkä UI-suodatin samaan dataan (ei lisähakuja).

### 1. Datakerros (laajennus, ei regressiota etusivun korttiin)

- **`WeatherWarning.java`** — lisää kentät: `awarenessType` (enum ilmiötyypille), `severity`, `certainty`, `urgency`, `effectiveMs`, `senderName`/`contact`, `web`. Nykyiset kentät (`event`, `description`, `areaDesc`, `onsetMs`, `expiresMs`, `level`, `identifier`, `marine`) säilyvät ennallaan.
- **`WarningsClient.java`** — parser poimii uudet kentät CAP-JSONista (`parameter[awareness_type]`, `severity`, `certainty`, `urgency`, `effective`, `senderName`, `web`). `msgType == Cancel` (peruutetut) suodatetaan pois.
- **`WarningAreaMatcher` (uusi, pure)** — `WeatherWarningNotifier`-luokan alue-täsmäyslogiikka poimitaan jaetuksi pure-helperiksi (käyttää `FinnishRegions`). Sekä uusi sivu ("Oma alue"-suodatin) että ilmoitin käyttävät tätä → ei koodikopiota. Sananrajasuojaus säilyy ("Pohjanmaa" ≠ "Etelä-Pohjanmaa").

### 2. Ilmiötyyppi → ikoni + nimi

`awareness_type`-koodi → `ArkiIconChip`-ikoni + varmistettu suomenkielinen nimi:
`1 wind` (tuuli), `2 snow-ice` (lumi/jää), `3 thunderstorm` (ukkonen), `4 fog` (sumu), `5 high-temperature` (helle), `6 low-temperature` (pakkanen), `7 coastal-event` (rannikko), `8 forest-fire` (maastopalo), `10 rain` (sade). Veneily/merialue (`marine`) → veneilytagi. Näyttönimi otetaan ensisijaisesti `event`-kentästä; awareness_type ohjaa ikonia. Fallback: varoituskolmio. Käytetään olemassa olevia sää-ikoneita missä mahdollista; puuttuvat lisätään vektoreina (`mobile_ic_*`).

### 3. Uusi sivu (UI) — `ComposeHomeContent.kt`

- `HomeSection.WEATHER_WARNINGS("Sää­varoitukset")` enumiin (`ComposeMainScreen.kt`) + drawer-rivi **"Sää"-otsikon alle** (`mobile_ic_warning_24`) + reititys `WarningsSection()`.
- **Skrollattava** sisältö: `LazyColumn` (kaikki varoitukset + ylä-/tyhjätilaelementit).
- **Yläpalkki:** otsikko + voimassa-laskuri-`ArkiPill` + **valitsin "Oma alue / Koko Suomi"** (`FilterChip`-rivi, sama kuvio kuin `RegionSelectorRow`), valinta talteen `SharedPreferences`-avaimeen (`warnings_scope`, prefix välttää auto-backupin tarvittaessa). + Päivitä-nappi muiden sivujen tapaan.
- **Kortit (`ArkiCard`, kaikki tiedot heti):** header (ilmiöikoni `ArkiIconChip` + tyyppi esim. "Hellevaroitus" + väri-`ArkiPill` keltainen/oranssi/punainen), alue (`areaDesc`), voimassaolo "alkaa – päättyy", koko `description`, kompakti metarivi (vakavuus · varmuus · kiireellisyys suomeksi), footer (lähde "Ilmatieteen laitos" + linkki FMI:n varoitussivulle CustomTabsilla). Veneilyvaroituksille "Veneily"-tagi.
- **Tyhjä tila:** "Ei voimassa olevia varoituksia" rauhallisella tyylillä (esim. vihreä aksentti).
- Teema-adaptiivinen (vaalea + tumma), järjestelmäfontti, `ArkiTheme.colors` (`warning`, `tileSurface`, `surfaceVariant`).
- Lajittelu kuten repositoryssa: ei-meri ensin, vakavuus laskevasti, onset nousevasti.

### 4. Ilmoituksen kohdistus

- **`WeatherWarningNotifier.kt`:** `openSection = null` → `"WEATHER_WARNINGS"` (molemmissa post-kutsuissa).
- **`MobileComposeMainActivity.kt`:** `EXTRA_OPEN_SECTION` → `externalSection` -reititys ohjaa jo sektioon; varmistetaan että `"WEATHER_WARNINGS"`-merkkijono mäppäytyy `HomeSection.WEATHER_WARNINGS`-enumiin (sama mekanismi kuin widgettien `resolveWidgetSection`). → napautus vie varoitussivulle.

### 5. Testit

Yksikkötestit (olemassa olevien kuvioiden mukaan, `FakeSharedPreferences`):
- `WarningsClient`-parser poimii uudet kentät oikein (severity/certainty/urgency/awareness_type/effective/web) + Cancel suodatetaan.
- `WarningAreaMatcher` — oma alue vs. koko Suomi, sananrajasuojaus (regressiotesti notifierin nykykäytökselle).
- `awareness_type` → ikoni/nimi -mäppäys (tunnetut koodit + fallback).

Lopuksi: `testDebugUnitTest` + `lintVitalRelease` + `assembleRelease` (R8) + emulaattoriajo (debug + signed release).

## Hyväksymiskriteerit

1. "Sää"-valikossa uusi "Sää­varoitukset"-rivi, joka avaa skrollattavan sivun.
2. Sivu näyttää kaikki yllä luetellut CAP-kentät kortissa, oman alueen / koko Suomen valitsimella.
3. Säävaroitusilmoituksen napautus avaa varoitussivun (ei etusivua).
4. Etusivun nykyinen varoituskortti toimii ennallaan (ei regressiota).
5. Molemmat teemat OK; testit + lint + R8-build vihreät; emulaattorivahvistus.

## Ulkopuolelle rajattu (YAGNI)

- Karttapiirto (data ei sisällä geometriaa).
- Push-ilmoituslogiikan muutokset (vain deep-link-kohde muuttuu).
- Varoitushistoria / peruutetut varoitukset (Cancel ei näytetä).
- HSL/Tampere-tyylinen alueenvaihto muille kuin oma alue / koko Suomi.
