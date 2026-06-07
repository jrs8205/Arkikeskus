# Arkikeskus (mobiili) — katselmusbriiffi Antigravitylle

**Tarkoitus:** Tämä on täysi tarkistuslista ja katselmusohje Google Antigravitylle. Tee sovelluksesta
**perusteellinen koodikatselmus** (oikeellisuus, regressiot, sudenkuopat, Compose-hygienia, tietoturva).
Toinen agentti (Codex) on juuri korjannut neljä puutetta (**#2/#5/#6/#10**, ks. kohta 8) — muutokset ovat
**työpuussa committoimattomina** (HEAD yhä 3cd0867, 5 muutettua Compose-tiedostoa). Tarkista **erityisesti että
ne on korjattu oikein** ja että korjaukset eivät rikkoneet muuta. Korjaukset on jo todettu kääntyviksi
(lintDebug + assembleDebug + assembleRelease onnistuivat); etsi LOGIIKKA-/regressiovirheitä, ei käännösvirheitä.

**Miten raportoit (tärkeää):**
- Älä muuta koodia ellei erikseen pyydetä — tämä on **read-only-katselmus**. Tuota löydöslista.
- Luokittele jokainen löydös: **🔴 KRIITTINEN** (kaatuu/rikkoo julkaisun) · **🟠 KORKEA** (väärä data/näkyvä bugi) ·
  **🟡 KESKITASO** (regressioriski/reunatapaus) · **⚪ MATALA** (siisteys/parannus).
- Jokaiseen löydökseen: `tiedosto:rivi`, mikä on vialla, miksi, ja konkreettinen korjausehdotus.
- Erottele **uudet löydökset** ja **#5/#6/#2-vahvistukset** omiin osioihinsa.
- Jos jokin näyttää bugilta mutta on tahallinen (kommentti selittää), mainitse se mutta merkitse "tahallinen".

---

## 1. Mikä sovellus on

**Arkikeskus** on suomalainen Android-kotinäyttö/"arjen kojelauta" -sovellus: kello, sää (FMI + Open-Meteo),
pörssisähkö, Ruuvi-anturit, säävaroitukset, liikennetiedot, kelikamerat, HSL-joukkoliikenne, uutiset (RSS),
GPS-nopeus, askelmittari (Health Connect) ja puhelimen tiedot. Yksi käyttäjä (omistaja), suomenkielinen UI.

- **Paketti / sovellustunnus:** namespace `org.jrs82.fsclock`, `applicationId org.jrs82.fsclock.mobile`.
- **Käynnistys (launcher):** `MobileComposeMainActivity` → `ComposeMainScreen()` (uusi Jetpack Compose -UI).
- **Vanha View-UI (`MobileMainActivity`, ~5000 riviä):** EI enää launcher. Jää (a) Health Connect
  -perustelunäkymäksi (`SHOW_PERMISSIONS_RATIONALE` / `VIEW_PERMISSION_USAGE`) ja (b) varalle/referenssiksi.
  Compose-koodi **replikoi** sen logiikkaa — käytä sitä vertailupohjana, kun arvioit onko Compose-versio oikein.
- **Tabletti (`app/`-moduuli, EI tässä repossa aktiivinen):** jäädytetty, eri haara (`tablet-2.0`).
  **ÄLÄ katselmoi `app/`-moduulia** — vain `app-mobile/` on relevantti.
- **Haara:** `mobile`. **Versio:** `1.15.0-mobile` (versionCode 49). Julkaistaan `2.0.0-mobile` (versionCode 50)
  vasta kun katselmukset ovat puhtaat (iso View→Compose-työ → major bump).

---

## 2. Tekninen ympäristö ja LUKITUT rajat

Nämä ovat tarkoituksellisia rajoitteita — **älä ehdota niiden nostamista** ilman erillistä perustetta:

- **Kotlin 1.9.24** (lukittu `build.gradle` resolutionStrategy force). → **Compose-kääntäjä 1.5.14**,
  **Compose BOM 2024.06.00** (Compose 1.6.8 / Material3 1.2.1). Kotlin 2.0 / Material3 1.3 (adaptive-navigation)
  on tietoisesti lykätty (riski: coroutines 1.7.3, Health Connect -silta).
- **coroutines 1.7.3**, **compileSdk 36**, **targetSdk 35**, **minSdk 30**, **Java 17** (desugaring päällä).
- **ABI vain `arm64-v8a` + `armeabi-v7a`** (MapLibre-natiivit; x86-emulaattorit eivät toimi → testaa oikealla
  ARM-laitteella tai ARM-imagella).
- **Asetukset: SharedPreferences** (`PreferenceManager.getDefaultSharedPreferences`). **EI DataStorea** — ~45
  Java-tiedostoa lukee SharedPreferencesia, siirto desynkronoisi. Avaimet: `MobileThemeController.KEY_*`,
  `SettingsManager.KEY_*`, `NewsFeedStore`.
- **EI ViewModelia, EI LiveDataa.** Compose lukee repository-singletoneista + SharedPreferencesista **suoraan**;
  taustahaut `LaunchedEffect` + `Dispatchers.IO`; live `DisposableEffect`-listenerillä; uudelleenluenta
  `tick`/`refresh`-Int-statella. Tämä on tietoinen arkkitehtuurivalinta (ei "puuttuva ViewModel" -löydös).
- **Kotlin–Java-silta:** Compose-Kotlin on **samassa paketissa** `org.jrs82.fsclock.mobile`, jotta se pääsee
  jaettuihin package-private Java-apuluokkiin ilman visibility-muutoksia. (Tästä syystä monet luokat eivät ole
  `public` — se on tarkoituksellista, ei puute.)
- **Allekirjoitus:** release `release.keystore` (alias `fsclock`), salasanat `local.properties`/env. Itsepäivitys
  (GitHub releases → lataa APK → FileProvider → asennus) edellyttää **samaa allekirjoitusta**.
- **API-avaimet:** `MML_API_KEY` ja `digitransit_subscription_key` luetaan `local.properties` → `BuildConfig`.
  Ne **bundlataan APK:hon** (tietoinen valinta tälle sovellukselle). Älä raportoi tätä kriittisenä
  tietovuotona, mutta saat mainita sen matalan tason huomiona.

### Build-rutiini (Windows, PowerShell)
> ⚠️ **Repo on ollut pilvisynkassa (Proton Drive).** Ennen jokaista buildia poista synkkakonfliktit, tai build
> kaatuu `duplicate class`- / `dexBuilder Failed to process` -virheisiin:
> ```powershell
> Get-ChildItem "C:\Android\projects\FsClock\app-mobile\src" -Recurse -File |
>   Where-Object { $_.Name -match "# (Edit conflict|Name clash)" } | Remove-Item -Force
> ```
> Huom: konfliktikopio voi sisältää kopion `final`-luokasta eri tiedostonimellä → tiedostonimihaku ei löydä,
> mutta `Grep "class <Nimi>"` koko repossa löytää. dex-välitulosvirhe → `:app-mobile:clean` + rebuild.

```powershell
& "C:\Android\projects\FsClock\gradlew.bat" -p "C:\Android\projects\FsClock" :app-mobile:assembleDebug
& "C:\Android\projects\FsClock\gradlew.bat" -p "C:\Android\projects\FsClock" :app-mobile:assembleRelease
& "C:\Android\projects\FsClock\gradlew.bat" -p "C:\Android\projects\FsClock" :app-mobile:lintDebug
```
APK ulos: `app-mobile/build/outputs/apk/.../Arkikeskus-<versionName>.apk`.

---

## 3. Arkkitehtuuri ja keskeiset tiedostot

### Compose-UI (`app-mobile/src/main/java/org/jrs82/fsclock/mobile/`)
- `ComposeMainScreen.kt` — runko: alapalkki (Koti/Päivitä/Valikko), overlay-valikko, `HomeSection`-enum +
  `when`-reititys, `LocalRefreshTick` (verkkopäivityssignaali), ON_RESUME-virkistys (throttle 30 s),
  automaattinen päivitysväli (`repeatOnLifecycle(RESUMED)`), asetusmuutoskuuntelija,
  `maybeRefreshDeviceLocation` (auto-sijainti).
- `ComposeHomeContent.kt` — etusivun kortit + koko näkymät: kello, pyhä/liputus, sää, pörssisähkö, anturit,
  sää-ennuste, uutiset. Sisältää datan formatointiapurit (replikoi `MobileMainActivity`).
- `ComposeHomeWidgets.kt` — säädettävän etusivun widget-malli (`HomeWidget`-enum + per-lähde-uutiskortit),
  kevyet kortit (Uutiset/Säävaroitukset/Liikenne/Lähilähdöt), uutisten prosessivälimuisti.
- `ComposePlacesSteps.kt` — Paikkakunnat (MML-haku/suosikit/sijainti, `chooseHomePlace`, `deviceLocation`) +
  Askeleet (Health Connect + raw fallback, kalorit, HTML-vienti).
- `ComposeExtraSections.kt` — Liikennetiedot, GPS-nopeus, kelikamerat/lähilähdöt/reittihaku (Fragment-hostaus),
  Puhelimen tiedot, `referenceCoordinates`.
- `SettingsScreen.kt` — koko asetusnäkymä Composessa (sää/etusivu/uutislähteet/omat syötteet/Ruuvi/sähkö/sovellus),
  itsepäivitys (`AppUpdater.kt`).
- `ArkikeskusTheme.kt` — `ArkiColors` semanttiset tokenit + brändi/dynamic-paletit (vaalea + tumma).
- `MobileComposeMainActivity.kt` / `MobileSettingsActivity.kt` — Activity-kuoret.

### Data ja taustatyö (`app-mobile/core/java/org/jrs82/fsclock/`)
- **Sää:** `WeatherRepository`/`FmiRepository`/`FmiClient` (FMI havainnot + ennuste), `OpenMeteoRepository`/
  `OpenMeteoClient`, `WeatherData`/`WeatherCondition`/`WeatherTextFormatter`/`WeatherIconView`.
- **Säävaroitukset:** `WarningsRepository`/`WarningsClient`/`WeatherWarning` (FMI).
- **Sähkö:** `ElectricityRepository`/`ElectricityClient`/`ElectricityData` (Elering/Nord Pool), `ElectricityAverages`
  (kk/vuosi-keskiarvot Vertailu-tabiin).
- **Anturit:** `ruuvi/RuuviRepository`/`RuuviScanner`/`RuuviPacket`/`RuuviSample` (BLE RAWv2-skanneri).
- **Liikenne/kelikamerat:** `mobile/TrafficNoticesRepository`/`TrafficNoticesClient` (Digitraffic),
  `mobile/Weathercam*` (Digitraffic-asemat + MapLibre/MML-taustakartta, `RoadCamerasFragment`).
- **HSL:** `mobile/DigitransitApi` (GraphQL), `TransitRepository`, `RoutePlannerFragment`/`TransitFragment`,
  `mobile/MmlGeocodingClient` (paikkahaku, **Telia CA**, ks. kohta 7).
- **Uutiset:** `mobile/RssRepository`/`RssClient`/`NewsFeed`/`NewsFeedStore`/`NewsItem`/`ImageLoader`.
- **Askeleet:** `mobile/StepCounter` (TYPE_STEP_COUNTER), `HealthConnectStepsBridge.kt` (ainoa muu Kotlin-tiedosto),
  `StepCalorieEstimator`, `StepsHistory`, `StepsHtmlExporter`, `db/DailyStepsDao`/`DailyStepsEntity`.
- **Room-tietokanta:** `db/FsClockDb` + DAOt (`WeatherDao`, `RuuviSamplesDao`, `DailyStatDao`, `DailyStepsDao`),
  `db/DailyStatsScheduler`, `db/BatteryMonitor`, `db/HistoryRepository`, `db/CsvExporter`.
- **Sovellus/tausta:** `FsClockApp` (Application — käynnistää akku-/FMI-tilastot/schedulerit), `BootReceiver`.
- **Asetukset:** `SettingsManager` (koti-paikka/koordinaatit, Ruuvi-MAC-slotit), `MobileThemeController`
  (teema + kaikki mobiilin pref-avaimet).

---

## 4. Ominaisuuslista + tarkistuslista (käy jokainen läpi)

Käytä vanhaa `MobileMainActivity.java`-toteutusta vertailukohtana: **näyttääkö Compose saman datan samoin?**

### 4.1 Etusivu (HomeDashboard)
- [ ] Kortit luetaan `visibleHomeWidgetIds(prefs)` järjestyksessä; jokainen kortti piilotettavissa/siirrettävissä
      (`MobileWidgetOrderActivity`, raahaus). Järjestys/näkyvyys SharedPreferencesissa (`mobile_home_order`,
      `mobile_home_show_*`).
- [ ] Säävaroituskortti näkyy **vain** kun voimassa olevia varoituksia on (tyhjä ei jätä väliä).
- [ ] Kello päivittyy sekunnin välein; pyhä/liputuspäivä lasketaan oikein (ei leikkaudu).
- [ ] Sisääntuloanimaatio vain kerran/prosessi (`sHomeEntranceShown`) — ei "hyppää" sivua vaihtaessa.
- [ ] Per-lähde-uutiskortit ("news:<feedId>") ilmestyvät/katoavat kun uutislähteitä lisätään/poistetaan.

### 4.2 Sää (etusivun kortti + Sää-ennuste-sektio)
- [ ] Etusivun sääkortti: paikka = `displayPlace(prefs)`, lämpötila/tuntuu kuin/tuuli/sade, loppupäivän tunnit.
- [ ] Sää-ennuste: päivätabit (7 pv), per-tunti FMI **ja** Open-Meteo rinnakkain (vertailu ±31 min).
- [ ] **FMI havainnot vs. ennuste:** havaintokysely käyttää `&place=`, ennuste `&latlon=` (ks. sudenkuoppa 6.2).
- [ ] **Open-Meteo paikka:** koordinaattipohjainen (ei nimipohjainen) → ei putoa oletus-Vantaaseen (= korjaus #6).
- [ ] "Viimeisin sääpäivitys" (asetukset) tallentuu onnistuneesta FMI-hausta (`setLastSuccessfulFmiUpdate`).

### 4.3 Pörssisähkö (etusivun kortti + sektio: Tänään/Huomenna/Vertailu)
- [ ] Nykyinen vartti, halvin/kallein, 96 varttia. Hinnat ALV 0 %, 3 desimaalia. Lähde Elering/Nord Pool.
- [ ] Halpa/normaali/kallis-luokitus: halpa < käyttäjän kynnys (`cheap_electricity_threshold`),
      kallis > 15 c/kWh. **Kynnyksen muutos asetuksista päivittyy etusivulle JA sähkösektioon** (= korjaus #5).
- [ ] Huomenna: status jos hintoja ei vielä saatavilla (päivittyvät ~klo 14:30).
- [ ] Vertailu: edellisvuoden keskihinta + kuluvan vuoden kuukausikeskiarvot — **painottamaton tuntikeskiarvo**
      (ks. sudenkuoppa 6.1, sähkön vartti/tunti-sekamuoto).

### 4.4 Anturit (etusivun kortti + sektio)
- [ ] 3 nimettyä slottia (makuuhuone/olohuone/parveke) + numeroidut lisäanturit; lämpötila/kosteus/ikä.
- [ ] BLE-skannaus sidottu elinkaareen: `start()` ON_START, `stop()` ON_STOP + onDispose (ei jää akkua syömään).
- [ ] **Anturin nimen tai Ruuvi-MAC-liitoksen muutos asetuksista päivittyy näkymään** ilman turhaa sää-/uutishakua
      (= korjaus #5). Tarkista että `ruuvi_mac_*`-avaimet ovat `isHomeDataPrefKey`-listalla.
- [ ] Lämpötilaväritys (kylmä→lämmin) `ArkiColors.forTemperature`.

### 4.5 Säävaroitukset / Liikennetiedot / Kelikamerat
- [ ] Säävaroitukset: taso/väri/alue/voimassaolo (FMI).
- [ ] Liikennetiedot: 5 alityyppiä (onnettomuudet/tietyöt/painorajoitukset/tiedotteet/ruuhkat), Digitraffic,
      lähimmät käyttäen `referenceCoordinates` (tuore laitesijainti tai kotikoordinaatit).
- [ ] **Kelikamerat (raskain interop-riski):** MapLibre Native + MML WMTS-taustakartta + Digitraffic-asemat,
      hostattu Fragmenttina Composen sisällä. Tarkista fragment-elinkaari (commit/poisto), karttamuistivuodot,
      ja **MML SSL** (ks. kohta 7) — taustakartta jää mustaksi jos Telia CA puuttuu.

### 4.6 Joukkoliikenne (HSL)
- [ ] Lähilähdöt: GPS-pohjaiset lähimmät lähdöt; lähdön napautus laajentaa reitin inline (pysäkit + live-sijainti).
      **EI välimuistia** (reaaliaikaisuus kriittinen — tahallinen).
- [ ] Reittihaku: Mistä/Minne-geokoodaus + `planConnection`-reitit (osat/vaihdot/ajat).
- [ ] Digitransit GraphQL, avain `BuildConfig.DIGITRANSIT_KEY`. Tarkista null/virhevartijat verkkovirheille.

### 4.7 Uutiset (RSS)
- [ ] Yhdistetty virta (max 50) + per-lähde-kortit; pikkukuvat `ImageLoader` (WeakReference); napautus CustomTabs.
- [ ] **Uutislähteen päälle/pois tai oman syötteen muutos:** tyhjentää uutisvälimuistin + hakee uudelleen,
      mutta **ei pakota muiden korttien verkkohakua** (= korjaus #5). Prosessivälimuisti `sHomeNewsCache`/
      `sHomeFeedCache` säilyttää uutiset sivua vaihtaessa.

### 4.8 GPS-nopeus / Askeleet / Puhelimen tiedot
- [ ] GPS-nopeus: `GpsSpeedometerView` + LocationManager(GPS+NETWORK)+GnssStatus; kuuntelijat elinkaaressa;
      <2 km/h→0, ikä>8 s→0. **Tarkista:** sekuntiajastin pysähtyykö taustalla; `removeUpdates` ja GNSS-poisto
      omissa try-lohkoissa (= valinnainen kovennus #10).
- [ ] Askeleet: Health Connect ensisijaisesti (aggregate), raw `TYPE_STEP_COUNTER` fallback; välilehdet
      Tänään/Päivät/Viikot/Kuukaudet; kalorit (HC tai arvio); HTML-vienti; ISO-viikot (ma–su); lupavirta
      (HC rationale Android 14+). Room `daily_steps` (migraatio 3→4).
- [ ] Puhelimen tiedot: 8 lohkoa (akku/wifi/mobiiliverkko/SIM/laitteisto/muisti/näyttö/anturit/värinä),
      lupavirta (READ_PHONE_STATE, sijainti). Ei IMEI Android 10+.

### 4.9 Asetukset + itsepäivitys + teema
- [ ] Kaikki kohdat kirjoittavat **samoihin** SharedPreferences-avaimiin kuin View-app.
- [ ] Automaattinen sijainti (oletus päällä); pois → kiinteä paikka. Ruuvi-skannaus + slottien liitos.
- [ ] Itsepäivitys: GitHub `releases/latest` → versiovertailu → lataa&asenna (FileProvider). **Varoitus:**
      jos julkaistu uusin on pienempi/vanhempi UI, "lataa ja asenna" korvaisi uuden buildin — versiointi ratkaisee.
- [ ] **Teema:** brändipaletti oletus + dynamic-color-kytkin (API 31+); **sekä vaalea (`values/`) että tumma
      (`values-night/`)** synkassa. `surfaceContainer*`-ramppi asetettava eksplisiittisesti (M3 1.2 baseline-violetti
      vuotaa Cardeihin jos ei → ks. sudenkuoppa 6.7). Teemanvaihto recreate; "ei vuoda tummaa" -suoja onStart.

---

## 5. Compose-katselmuksen yleiset kohteet

- [ ] **`remember`-avaimet:** jokainen prefseistä/argumenteista johdettu `remember { }` jolla pitäisi olla avain —
      onko avain? (Avaimeton `remember` jää jumiin vanhaan arvoon — tämä oli korjaus #5:n ydin.)
- [ ] **`LaunchedEffect`-avaimet:** kattavatko ne kaikki muuttujat joiden muutoksen pitäisi käynnistää uudelleenhaku?
      Liian leveä avain → turha verkko/akku; liian kapea → vanhentunut data.
- [ ] **Coroutine-peruutus:** `withContext(Dispatchers.IO)` taustahauissa; `suspendCancellableCoroutine`
      (sijainti) peruuntuu oikein (`invokeOnCancellation`). Ei kuumia silmukoita ilman `delay`/elinkaarta.
- [ ] **DisposableEffect:** jokainen `addListener`/`registerOnSharedPreferenceChangeListener`/lifecycle-observer
      poistetaan `onDispose`ssa (ei vuotoa).
- [ ] **Säikeistys:** UI-tila päivitetään vain main-säikeessä (`Handler(Looper.getMainLooper()).post` tai Compose-
      state). Repository-listenerit voivat tulla taustasäikeestä.
- [ ] **Lifecycle-vartijat:** taustasta main-handlerille postattu render tarkistaa `isFinishing/isDestroyed`
      (View-puolella) — ks. sudenkuoppa 6.4.
- [ ] **AndroidView-interop:** `WeatherIconView`, `ImageView`, Fragment-hostaus — factory/update-erottelu oikein,
      ei raskaita allokaatioita `update`ssa joka recompositionissa.
- [ ] **rememberSaveable:** valittu sektio säilyy Activityn uudelleenluonnissa (teemanvaihto) → hostatut fragmentit
      eivät jää orvoiksi.

---

## 6. Tunnetut sudenkuopat (tarkista että EI ole rikki)

Nämä on löydetty aiemmissa katselmuksissa. Varmista ettei korjaus #5/#6/#2 tai muu työ ole tuonut niitä takaisin.

**6.1 Sähkö, vartti vs. tunti -sekamuoto.** Nord Pool siirtyi tunti→15 min varttihintoihin **1.10.2025**. Vuosi-/
kk-keskiarvo aritmeettisesti KAIKISTA pisteistä painottaa loppuvuoden vartit 4× → liian korkea. **Bucketoi
tunneittain**, ota tunnin pisteiden ka, sitten tuntien painottamaton ka (= virallinen pörssikeskihinta). Esim.
2025 veroton oikein 4,05 (ei 4,21) snt/kWh. Koskee `ElectricityAverages`-Vertailua.

**6.2 FMI havainnot ≠ ennuste -parametrit.** Havaintokysely `fmi::observations::weather::simple` tukee **vain
`&place=`**, EI `&latlon=`. Ennuste tukee molempia. Jos automaattisijainnin koordinaatit syötetään havaintoihin
`&latlon=`, palautuu `numberReturned=0` → nykyhavainnot (lämpö/tuuli "nyt") katoavat. Vahvista: havainnot aina
`&place=`, ennuste `&latlon=`. (`FmiClient`.)

**6.3 Room `BETWEEN` on inklusiivinen molemmista päistä** → klo 00:00 -rivi osuu kahteen päivään. Käytä half-open
`timestamp >= :start AND timestamp < :end` päivä-/tuntirajoilla.

**6.4 FMI `r_1h` (ja vastaavat "edellisen jakson summa" -sarjat) toistuvat samana 10 min välein.** Älä summaa
kaikkia näytteitä — bucketoi tunneittain (`timestamp / 3_600_000`), ota yksi/tunti, summaa. Muuten ~6× yliarvio.

**6.5 Schedulerit eivät saa odottaa tasatuntia.** `DailyStatsScheduler.start()`: aja today + yesterday recompute
**heti** io-poolissa, muuten history näyttää "ei dataa" asennuksen/uudelleenkäynnistyksen jälkeen jopa tunnin.

**6.6 Async-DB-render Activityssa vaatii lifecycle-guardin** (`if (isFinishing()||isDestroyed()) return;`).

**6.7 M3 1.2 baseline-violetti.** Kun brändi-`primary` vaihdetaan, `surfaceContainer*`-ramppi on asetettava
eksplisiittisesti `ColorScheme`en, muuten `Card` ym. vuotavat baseline-violettia. Tarkista molemmat teemat.

**6.8 MML SSL (Telia Root CA v2).** Ks. kohta 7.

**6.9 Pilvisynkka-konfliktit.** Ks. kohta 2 (build-rutiini). Ei koodibugi, mutta rikkoo buildin.

---

## 7. Tietoturva & luvat & verkko

- **Luvat (manifest):** INTERNET, REQUEST_INSTALL_PACKAGES (itsepäivitys), ACCESS_FINE/COARSE_LOCATION,
  READ_PHONE_STATE, ACTIVITY_RECOGNITION, health.READ_STEPS/READ_*_CALORIES, BLUETOOTH_SCAN(neverForLocation)/
  BLUETOOTH_CONNECT (+ legacy BLUETOOTH/ADMIN maxSdk 30). Tarkista: pyydetäänkö runtime-luvat oikein ja
  degradoituuko sovellus siististi jos lupa evätään (etenkin sijainti cold-startissa).
- **MML + SSL:** `MmlGeocodingClient` (paikkahaku/geokoodaus) ja kelikamerakartan WMTS käyttävät
  `*.maanmittauslaitos.fi → Telia Server CA v3 → Telia Root CA v2`. Telia Root CA v2 puuttuu osasta laitteita
  (OEM trust store) → `CertPathValidatorException` → paikkahaku kaatuu / taustakartta musta. **Korjaus on jo
  toteutettu:** bundlattu `res/raw/telia_root_ca_v2.pem` + `res/xml/network_security_config.xml` (luottaa
  `system` + Telia-juuri vain `maanmittauslaitos.fi`-domaineilla) + manifest `networkSecurityConfig`. **Tarkista
  että tämä config on yhä paikallaan ja oikein rajattu** (ei laajenna luottamusta muille domaineille).
- **API-avaimet** bundlataan APK:hon (MML, Digitransit) — tietoinen valinta. Muut palvelut (FMI/Digitraffic/
  Open-Meteo/Elering/HSL) ovat avoimia/Let's Encrypt-CA.
- **Itsepäivitys:** FileProvider (`${applicationId}.fileprovider`), `file_paths.xml` cache/updates. Sama
  allekirjoitus → asentuu paikalleen. Tarkista että ladattu APK validoidaan järkevästi.
- **`allowBackup=false`** (tietoinen).

---

## 8. ERITYISESTI VAHVISTETTAVAT — Codexin 4 korjausta (#2/#5/#6/#10), TEHTY

Codex on **jo tehnyt** nämä (työpuussa, committoimaton). Kun katselmoit, **varmista että toteutus vastaa alla
olevaa** (ja että ne eivät rikkoneet muuta). Jos jokin on puutteellinen, raportoi `tiedosto:rivi` + mikä jäi.
Claude on jo tarkistanut diffin ja todennut korjaukset oikeiksi — sinun tehtäväsi on **riippumaton toinen
katselmus** (etsi mitä molemmat saattoivat missata: reunatapaukset, recomposition-/säikeistys-ongelmat,
prosessitason staattisten cachejen kunto).

**Jäljellä olevat tiedostetut varaukset (ei tarvitse raportoida bugina, vaan vahvista että OK):**
- **Open-Meteo nimipohjainen fallback:** jos tallennettuja kotikoordinaatteja EI ole (`SettingsManager
  .hasHomeCoordinates()` false, esim. paikka valittu ilman koordinaatteja), Open-Meteo putoaa yhä nimipohjaiseen
  `fetch(place, force)`-ratkaisuun. Tämä on tarkoituksellinen fallback — varmista vain ettei se aja
  oletus-Vantaaseen tilanteessa jossa koordinaatit OLISIVAT saatavilla.
- **Laitetestaus puuttuu:** mitään näistä ei ole vielä ajettu oikealla laitteella. Arvioi staattisesti
  todennäköiset laitetestiongelmat (luvat, sijainti cold-startissa, BLE, MapLibre fragment-in-compose).

### #5 — Asetusrevisio erotettu verkko-refreshTickistä (🟡 regressioriski) — TEHTY
**Ongelma ennen:** `ComposeMainScreen` kasvatti yhtä globaalia `refreshTick`iä KAIKISTA data-asetuksista, ja
kuluttajat tulkitsivat `refresh > 0` verkkopakoksi → esim. anturin nimen muutos pakotti turhat sää- JA uutishaut.
**Toteutus (vahvista):**
- Kaksi uutta CompositionLocalia `ComposeMainScreen.kt`ssä: **`LocalHomeDataRevision`** (re-read prefs, EI
  verkkoa) ja **`LocalHomeNewsRevision`** (käynnistää RSS-haun ilman pakotettua verkkopyyntöä). Molemmat
  tarjotaan `CompositionLocalProvider`issa `LocalRefreshTick`in rinnalla.
- Prefs-kuuntelija: `isHomeDataPrefKey(key)` → `homeDataRevision++`; jos `isHomeNewsListKey(key)` →
  lisäksi `invalidateHomeNewsCache()` + `homeNewsRevision++`. **EI enää `refreshTick++`** data-asetuksista.
- Kuluttajat: `ElectricityCard` (etusivu) + `ElectricitySection` lukevat `LocalHomeDataRevision` ja keyaavat
  `cheapThreshold`/`cheapNotice` siihen; `SensorsCard` + `SensorsSection` keyaavat `buildSensors` siihen;
  uutiskortit (`HomeNewsCard`/`HomeNewsSourceCard`/`NewsSection`) keyaavat `LaunchedEffect`in
  `(refresh, newsRevision)`iin ja laskevat `forceNetwork = refresh != handledRefresh` (= pakottaa verkon vain
  oikeasta Päivitä-/intervalli-/resume-tickistä, EI lähteen vaihdosta).
- `isHomeDataPrefKey` sisältää nyt `SettingsManager.KEY_RUUVI_MAC_BEDROOM/LIVINGROOM/BALCONY` + anturinimet.
**Tarkista:** (1) ettei mikään data-asetus enää laukaise sään/sähkön/uutisten **verkkohakua**; (2) että jokainen
data-asetuksen muutos silti näkyy heti UI:ssa (etenkin etusivun `ElectricityCard` — aiempi regressioloukku);
(3) ettei `handledRefresh`-mekaniikka jätä uutiskorttia vanhaan dataan reunatapauksessa (esim. nopea
peräkkäinen lähde-vaihto + Päivitä).

### #6 — Open-Meteo koordinaateilla + FMI null-seed (🟡 väärä paikka) — TEHTY
**Ongelma ennen:** `ForecastSection` haki Open-Meteon **nimellä** (`fetch(place, force)`) → prosessin
uudelleenkäynnistyksen jälkeen nimi ei välttämättä täsmännyt kotipaikkaan → `OpenMeteoRepository` putosi
oletus-Vantaaseen. Lisäksi FMI-tila alustettiin paikattomasta globaalista `sLastWeather`ista.
**Toteutus (vahvista, `ComposeHomeContent.kt` ForecastSection ~rivi 1090):**
- `coordinates` = kotikoordinaatit `SettingsManager`ista, **validoitu** (`hasHomeCoordinates()` + `isFinite()` +
  lat ∈ −90..90, lon ∈ −180..180); null jos epäkelvot.
- Open-Meteo: jos `coordinates != null` → `repo.fetch(place, lat, lon, forceOpenMeteo)` (koordinaattipohjainen
  overload `OpenMeteoRepository.java:76`); muuten `repo.fetch(place, force)` (nimipohjainen fallback).
- FMI + Open-Meteo seedataan **paikka+koordinaatti-avaimitetusta prosessicachesta** (`sForecastWeatherKey`/
  `sForecastWeather`, `sForecastOpenMeteoKey`/`sForecastOpenMeteo`, avain `"$place|$lat|$lon"`): seed käytetään
  VAIN jos avain täsmää → ei enää paikatonta `sLastWeather`-seediä. `forecastKey`-muutos (paikka/koordinaatti)
  pakottaa Open-Meteon haun vaikkei Päivitä-tickiä olisi (`forceOpenMeteo = forceNetwork || keyMuuttui`).
**Tarkista:** (1) ettei väärän paikan dataa näy uuden otsikon alla missään navigointijärjestyksessä;
(2) että prosessitason `sForecast*`-staattiset pysyvät johdonmukaisina (avain kirjoitetaan vain onnistuneen haun
yhteydessä); (3) ettei nimipohjainen fallback aktivoidu kun koordinaatit olisivat saatavilla.

### #10 — GPS-mittarin ajastin elinkaareen + try-lohkojen erotus (⚪ kovennus) — TEHTY
**Toteutus (vahvista, `ComposeExtraSections.kt` GpsSpeedContent ~rivi 410):**
- Sekuntiajastin (`nowMs`-silmukka) ajetaan nyt `lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED)`
  sisällä → pysähtyy taustalla.
- `lm.removeUpdates(listener)` ja `lm.unregisterGnssStatusCallback(gnss)` ovat **omissa try-lohkoissaan** →
  ensimmäisen poikkeus ei estä toista purkua.
**Tarkista:** ettei mittarin kuuntelijoita jää rekisteröidyiksi taustalla, ja ettei ajastin vuoda coroutinea.

### #2 — Sijainnin tarkkuusraja + force-haun null (🟡 väärä kaupunki) — TEHTY
**Ongelma ennen:** `deviceLocation` (`ComposePlacesSteps.kt`) ei tarkistanut tarkkuutta (vanha View-UI hylkäsi
>1500 m → kuntarajalla väärä kaupunki), eikä validoinut aktiivisesti haettua arvoa; force-haun epäonnistuessa se
palautti ≤10 min vanhan sijainnin.
**Toteutus (vahvista, `ComposePlacesSteps.kt` ~rivi 401):**
- Uusi vakio `MAX_AUTO_LOCATION_ACCURACY_M = 1500f`. `isFreshEnough` → **`isUsableAutoLocation(loc, now)`**:
  ikä 0..10 min **JA** (`!loc.hasAccuracy()` tai `loc.accuracy <= 1500f`). Replikoi View-UI:n säännön.
- Aktiivisesti haettu `fresh` validoidaan samalla `isUsableAutoLocation`illa; ikä lasketaan haun JÄLKEEN luetulla
  `completedAt`-ajalla (haku voi kestää ~8 s).
- **Force (Päivitä) epäonnistuessa → `null`** (Codex valitsi tiukan vaihtoehdon). Automaattipolku saa pudota
  käyttökelpoiseen last-knowniin (`if (!force && isUsableAutoLocation(last, completedAt)) last else null`).
**Tarkista:** ettei nopea polku (auto) palauta enää epätarkkaa last-knownia, ettei mikään palauta mielivaltaisen
vanhaa sijaintia, ja että Päivitä-napin null-paluu johtaa siistiin "ei päivitystä" -tilaan (ei kaadu, data
haetaan silti nykyiselle paikalle).

### Valinnaiset
- **#4 (EI TEHTY, tietoinen lykkäys):** Ruuvi-skannerin (singleton) start/stop split-screenissä / usean
  Activity-instanssin kanssa — pelkkä laskuri ei riitä, vakain olisi omistajatunnisteinen acquire/release.
  Reunatapaus, ei estä julkaisua. Saat arvioida onko nykyinen elinkaarisidonta riittävä yhden Activityn tapauksessa.
- **#10 (TEHTY):** ks. yllä.

---

## 9. Mitä EI tarvitse raportoida (tietoiset valinnat)

- "Puuttuva ViewModel/Repository-kerros / MVVM" — tietoinen (kohta 2).
- SharedPreferences DataStoren sijaan — tietoinen (kohta 2).
- API-avaimet APK:ssa — tiedossa (saa mainita matalana).
- Vanha `MobileMainActivity.java` / `MobileSettingsFragment.java` osin kuollutta — tarkoituksellinen jäänne
  (HC-rationale + referenssi). Älä ehdota sen poistoa.
- `app/`-tablettimoduuli — ei kuulu tähän katselmukseen.
- Kotlinin/Composen/SDK:n versiot — lukittu (kohta 2).
- Lähilähtöjen välimuistittomuus — tahallinen (reaaliaikaisuus).

---

## 10. Yhteenveto pyynnöstä

1. Käy kohdat **4–7** läpi ja raportoi uudet löydökset (luokiteltuna).
2. **Vahvista erikseen kohta 8** (#2/#5/#6/#10) — vastaako Codexin toteutus kuvausta, ja rikkoiko se mitään.
3. Tarkista ettei kohdan **6** sudenkuoppia ole palannut.
4. Ehdota mitä pitäisi vielä korjata ennen **2.0.0-mobile**-julkaisua (versionCode 50).

Kun katselmus on puhdas, omistaja rakennuttaa lopullisen `assembleRelease`-buildin, nostaa version 2.0.0-mobile
ja pushaa GitHubiin (`v2.0.0-mobile`-release). **Älä tee versiointia/pushia itse.**
