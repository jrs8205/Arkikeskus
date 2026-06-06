# Arkikeskus 2.0.0-mobile — auditti (ennen julkaisua)

Tämä dokumentti kokoaa **kaikki muutokset, värit, koodit ja polut** versiolle 2.0.0-mobile, jotta
ulkopuolinen tarkistaja (esim. ChatGPT) voi etsiä virheitä ennen julkaisua. Tavoite: mahdollisimman
vakaa 2.0-julkaisu.

> **Tila auditin kirjoitushetkellä:** koodi valmis, **versio yhä `1.15.0-mobile` (versionCode 49)**,
> EI vielä pushattu. Hyväksynnän jälkeen → bump **`2.0.0-mobile` (versionCode 50)** + push + GitHub-julkaisu.

---

## 0. Ohje tarkistajalle (ChatGPT) — lue tämä ensin

Olet kokenut Android / Kotlin / Jetpack Compose -koodikatselmoija. **Tehtäväsi:** auttaa tekemään tästä
Arkikeskus-sovelluksen 2.0-julkaisusta mahdollisimman **vakaa** etsimällä bugeja, regressioita, kaatumisriskejä
ja Compose-sudenkuoppia ENNEN julkaisua.

**Tärkeää konteksta:**
- **Lähdekoodin saatavuus:** Jos sinulla on pääsy tiedostojärjestelmään / repoon (esim. ajat CLI-agenttina, kuten Codex),
  **lue varsinaiset lähdetiedostot suoraan** äläkä luota pelkkään tähän auditiin — varmista väitteet koodista. Repo on
  `C:\Android\projects\FsClock`, haara `mobile`; Compose-koodi `app-mobile\src\main\java\org\jrs82\fsclock\mobile\`,
  resurssit `app-mobile\src\main\res\`. Tämä auditti on kartta, koodi on totuus.
  Jos taas et pääse tiedostoihin (vain tämä dokumentti liitettynä), **älä keksi tiedostojen sisältöä** — pyydä nimeltä
  ne tiedostot jotka haluat nähdä (lista kohdassa 4), niin käyttäjä liittää ne.
- Sovellus on uudistettu View/XML → Compose. Logiikka (verkko/data/säikeet, Java-repositoryt) säilyi; vain UI uusittiin.
- Kiinnitä erityishuomio **kohdan 6 riskialueisiin** (säikeistys, valikko-overlay + back-käsittely, edge-to-edge-insetit,
  Lähilähtöjen inline-reitti, prosessivälimuisti, Java↔Kotlin-interop, Compose-state taustasäikeestä, fragment-in-Compose).

**Mitä haluan sinun tekevän:**
1. Käy läpi auditti ja etsi todennäköiset ongelmat: kaatumiset, säikeistysvirheet (Compose-state väärästä säikeestä),
   muisti-/elinkaarivuodot, takaisin-painikkeen käsittely, null/edge-caset, insetit, suorituskyky, regressiot vanhaan nähden.
2. Listaa löydökset **prioriteetilla**: 🔴 Kriittinen (kaatuu / rikkoo) · 🟡 Keskitaso · ⚪ Pieni / kosmeettinen.
3. Anna jokaisesta: **(a) missä** (tiedosto/alue), **(b) mikä ongelma**, **(c) miksi se on ongelma**, **(d) ehdotettu korjaus**.
4. Lopuksi: listaa **mitkä lähdetiedostot haluat nähdä** varmistaaksesi epävarmat kohdat.
5. Vastaa **suomeksi**. Älä ehdota koko uudelleenkirjoitusta — pieniä, kohdistettuja korjauksia.

(Käyttäjä korjaa löydökset ennen 2.0-julkaisua.)

---

## 1. Yleiskatsaus

- **Mikä tämä on:** Arkikeskus-mobiilisovelluksen **koko käyttöliittymän uudistus View/XML → Jetpack Compose + Material 3**.
  Logiikka (verkko, data, säikeet) säilyi ennallaan; vain esityskerros uusittiin. Sama sovellus, uusi ulkoasu.
- **Paketti:** `org.jrs82.fsclock` (moduuli `app-mobile`). minSdk 30, targetSdk 35, compileSdk 36, Java 17, Kotlin **1.9.24**.
- **Compose:** BOM `2024.06.00` (Compose 1.6.8 / Material3 1.2.1), Compose Compiler Extension `1.5.14`. (Kotlin lukittu 1.9.24.)
- **Haara:** `mobile`. **48 committia** Compose-työtä (`a830e87` → `852d45f`).
- **Käynnistys (launcher):** `MobileComposeMainActivity` (uusi Compose-UI). Vanha `MobileMainActivity` (View, ~5000 riviä) jää
  varalle + Health Connect -perustelunäkymäksi (ei enää launcher).
- **Data-arkkitehtuuri:** EI ViewModelia/LiveDataa. Compose lukee olemassa olevista **repository-singletoneista** ja
  **SharedPreferencesistä SUORAAN**; taustahaut `LaunchedEffect`+`Dispatchers.IO`, live-päivitykset `DisposableEffect`-
  listenereillä (taustasäikeen tulos postataan main-Handlerilla), uudelleenluku `tick`-Int-statella.
- **Raskaat natiivinäkymät** (MapLibre-kelikamerat, transit/reittihaku-RecyclerViewit) **hostataan olemassa olevina
  Fragmentteina** Composen sisällä (`AndroidView`+`FragmentContainerView`+`commitNowAllowingStateLoss`).

---

## 2. Tehdyt työt (toiminnallisuus + tämän uudistuksen muutokset)

### Sovelluksen sektiot (kaikki Composessa)
Etusivu (dashboard), Sää-ennuste, Paikkakunnat, Anturit (Ruuvi), Liikennetiedot (5 alityyppiä: onnettomuudet/
tietyöt/painorajoitukset/liikennetiedotteet/ruuhkat), Kelikamerat (MapLibre), Lähilähdöt (HSL), Reittihaku (HSL),
GPS-nopeus, Askeleet (Health Connect), Uutiset (RSS), Pörssisähkö (Elering/Nord Pool), Puhelimen tiedot, Asetukset.

### Etusivun säädettävät kortit (widgetit)
Kortit: kello, pyhä-/liputuspäivät, sää, pörssisähkö, **säävaroitukset**, anturit, **liikennetiedot**, uutiset (yhdistetty),
**per-lähde-uutiskortit** (jokainen RSS-lähde + omat syötteet), lähilähdöt. Järjestys + näkyvyys raahaamalla
(`MobileWidgetOrderActivity`, View). Avaimet `mobile_home_order` / `mobile_home_show_<id>`. Per-lähde-id `news:<feedId>`.

### Tämän julkaisun keskeiset muutokset (uusin → vanhin, valikoituja)
- **852d45f** Valikko + Asetukset **yhtenäinen tyyli**: kukin kohta oma pyöristetty laatikko (`ItemBoxShape = RoundedCornerShape(20.dp)`),
  sama taustaväri (`colorScheme.background`) ja väritys molemmilla. Asetuksissa `SettingsCard`→väljä Column, `RowDivider`→tyhjä,
  rivit (ClickableRow/SwitchRow/InfoRow) käärittiin `Card`iin. Valikossa `NavigationDrawerItem`→`Card`-laatikko (valittu = primaryContainer).
- **bd97f55** Etusivun Lähilähdöt: **lähdön napautus laajentaa vuoron reitin inline** (pysäkit + ajat + nousupysäkki + ajoneuvon sijainti);
  uusi napautus piilottaa; yksi auki kerrallaan. Data `DigitransitApi.tripTimeline(tripGtfsId, patternCode, stopGtfsId)`.
- **a61290e** Valikon osio-otsikoihin ikonit (sää/liikenne/joukkoliikenne/muut).
- **813ceed** Valikko koko ruudun levyiseksi (`Surface(fillMaxSize)`); Valikko-nappi togglaa, Koti sulkee.
- **3db375b** Valikko **oma overlay** (ilmestyy heti, ei liukuanimaatiota), piirtyy vain sisältöalueen päälle → **alapalkki jää näkyviin**;
  poistettu valikon "Etusivu"-kohta (alapalkin Koti hoitaa). `ModalNavigationDrawer` korvattu.
- **904ac0e** Asetukset HSL-tyyli: per-rivin sininen leading-ikoni + osio-ikonit + leveämmät kortit (8 dp reuna).
- **accc8c2 / dbb24d8** Etusivun **uutisten prosessivälimuisti** (`sHomeNewsCache`/`sHomeFeedCache`): uutiset eivät katoa/lataudu
  uudelleen sivua vaihtaessa, vain avaus + Päivitä hakee. **Lähilähtöjä EI välimuisteta** (reaaliaikaisuus säilyy).
- **9650b50** Etusivun ylös/alas-hyppäys poistettu (`animateContentSize` + slide pois).
- **586cb01** Alapalkki vs. järjestelmänavipalkki: erotinviivat + nuolinappuloiden alueelle oma sävy (`surfaceContainerHighest`),
  toimii tummalla + vaalealla.
- **50cd744 / 12a4da0** Käynnistyskuva (splash): brändiväri `#1B53C0` + sovelluksen ikoni (androidx core-splashscreen), minimikesto ~0,9 s.
- **9f3920e** Yläpalkki POIS → kiinteä alapalkki (HSL-tyylinen `NavigationBar`: Koti / Päivitä / Valikko, ikoni+teksti, ≥48 dp).
- **03e8cfb** Valikon reunavedos pois (kelikamerakartta panoroituu yhdellä sormella).
- **531be55** Säävaroitukset + Liikennetiedot takaisin etusivulle + per-lähde-uutiskortit (string-id-widgetmalli).
- **f833f62** Sijaintilupa kysytään heti ensikäynnistyksessä.
- **0ddb68d / 5e10dbd** Reittihaku: alaotsikko "Minne olet matkalla?", "Lähde nyt"→"Lähtö nyt", kentän fokus → hakutila (ehdotukset koko ruutuun).
- **8bddad7 / a37deeb** Värimaailma (kirkas brändipaletti + dynamic-color-kytkin) + sovellusikoni (adaptive icon).

### Täysi commit-lista (`a830e87` → `852d45f`, uusin ensin)
```
852d45f Valikko + Asetukset yhtenaiseksi: pyoristetyt laatikot
bd97f55 Etusivu Lahilahdot: lahdon napautus laajentaa reitin inline
a61290e Valikko: ikonit osio-otsikoihin
813ceed Valikko: koko ruudun levyinen
3db375b Valikko: oma overlay (ilmestyy heti), alapalkki nakyy; Etusivu-kohta pois
904ac0e Asetukset: HSL-tyyli (per-rivin ikonit + osio-ikonit + leveammat)
dbb24d8 Lahilahdot: ei valimuistia (reaaliaikaisuus sailyy)
accc8c2 Etusivu: uutisten prosessivalimuisti
9650b50 Etusivu: poistettu animateContentSize + slide
586cb01 Alapalkki: erotinviivat + oma savy jarjestelmapalkin alueelle
50cd744 Splash: minimikesto ~0.9s
12a4da0 Kaynnistyskuva (splash): brandivari + ikoni (core-splashscreen)
5e10dbd Reittihaku: kentan fokus -> hakutila
0ddb68d Reittihaku: alaotsikko + 'Lahto nyt'
43472fd Asetukset: Tarkista paivitykset -ikoni otsikon viereen
531be55 Etusivu: Saavaroitukset + Liikennetiedot + per-lahde-uutiskortit
8f4ca5f Etusivu: sisaantuloanimaatio vain kerran per prosessi
03e8cfb Valikon reunavedos pois (gesturesEnabled=false)
9f3920e Alapalkki: HSL-tyylinen NavigationBar korvaa ylapalkin
f833f62 Sijaintilupa heti ensikaynnistyksessa
693bf35 Etusivun kortit: raahausjarjestely
4177b8a Etusivun saa: laitteen sijainti automaattisesti + Paivita-nappi
4b76b84 Sovellusikoni: pienennetty A
5971c2c Etusivu: saadettavat widgetit + Uutiset/Lahilahdot
4c83637 Ylapalkki: keskitetty otsikko (myoh. korvattu alapalkilla)
9635736 Asetukset: paivitys-ikoni
1519f60 Valikko: ryhmittely otsikoiden alle
5698d1c Compose-UI oletukseksi (LAUNCHER)
664665c Asetukset: versio + paivitystarkistus + GitHub
c6384c6 Reittihaun ulkoasu uusiksi
679f898 Saa-ennusteen variliset stat-ikonit
51c6557 Porssisahkon taysi nakyma
1478c44 Pyha/liputuspaiva etusivulle + kotinappi
83c6c91 GPS-mittari isommaksi + uutisten laskuri
6148ddf Selkea etusivu-navigointi
ae6edc4 Compose OSA A: Paikkakunnat + Askeleet
aefdebf Compose OSA A: Liikennetiedot + GPS-nopeus
07b2817 Compose OSA A: Puhelimen tiedot + kartat/transit hostattuna
a37deeb Compose OSA C: adaptive launcher icon
8bddad7 Compose OSA B: brandipaletti + dynamic-color
c4d07b8 refresh/place-putki
f213997 1.15.0 saa-/sijaintikorjaukset
83ac6ab Saa-ennuste + Uutiset oikealla datalla
de16d02 Anturit + Porssisahko oikealla datalla
b3eac83 Etusivun kortit oikealla datalla
7d22ed9 Navigaatiorunko + dashboard-runko
8015510 Taysi asetusnakyma + Material You -varit
a830e87 Compose-pilotti: toolchain + teema
```

---

## 3. Värit (tarkat hex-koodit)

### Brändipaletti — VAALEA (`ArkikeskusTheme.kt`, `lightColorScheme`)
| Rooli | Hex |
|---|---|
| primary | `#1B53C0` |
| onPrimaryContainer | `#001551` |
| secondary (vihreä) | `#1E7D43` · secondaryContainer `#B8F0C4` |
| tertiary (oranssi) | `#B5530F` · tertiaryContainer `#FFDCC2` |
| background / surface | `#FBFCFF` |
| onSurface | `#1A1B20` · onSurfaceVariant `#43474E` |
| surfaceVariant | `#DEE2F0` |
| outline / outlineVariant | `#74777F` / `#C4C6D0` |
| surfaceContainerLowest→Highest | `#FFFFFF` · `#F3F5FC` · `#EDF0F9` · `#E7EBF5` · `#E1E6F1` |

### Brändipaletti — TUMMA (`darkColorScheme`, ei täysmustaa)
| Rooli | Hex |
|---|---|
| primary | `#B0C6FF` · primaryContainer `#00419E` · onPrimaryContainer `#D9E2FF` |
| secondary | `#8FD89E` · secondaryContainer `#00522B` |
| tertiary | `#FFB68A` |
| background / surface | `#111318` · onSurface `#E3E6ED` |
| surfaceVariant | `#43474E` · onSurfaceVariant `#C4C6D0` |
| outlineVariant | `#43474E` |
| surfaceContainerLowest→Highest | `#0C0E13` · `#191C22` · `#1D2026` · `#272A31` · `#32353C` |

### Muut värit
- **Käynnistyskuva (splash) tausta:** `mobile_splash_bg = #1B53C0` (kiinteä, sama vaalea/tumma).
- **Sovellusikoni (adaptive) taustagradientti:** `#116ADC → #1995D1 → #53BF7A` (ylhäältä alas); etuala valkoinen "A".
  Lähde + työkalu: `tools/make_icon.py` (Python/Pillow/scipy). Kello (etusivu): kaksi sinisen sävyä (`ArkiColors.clockTop/clockBottom`).
- **HSL-moodivärit** (`mobile_colors.xml`, samat molemmissa teemoissa, valkoinen badge-teksti):
  bus `#007AC9`, tram `#00985F`, rail `#8C4799`, subway `#FF6319`, ferry `#00B9E4`.
- **Säävaroitustasot** (`WeatherWarning.Level`): keltainen `0xFFE6C32E`, oranssi `0xFFE89B2C`, punainen `0xFFD0413B`.
- **Semanttiset korttivärit** (`ArkiColors` `ArkikeskusTheme.kt`): pörssisähkön liikennevalo (halpa/normaali/kallis),
  sää (sunny/rain/frost), anturien lämpötilaväri `forTemperature(°C)` lerp kylmä `#1565C0` → lämmin `#D2611A`, news-aksentti.
- **Dynamic color (Material You)** on valinnainen kytkin (asetukset, oletus POIS → oma brändipaletti). Avain `mobile_dynamic_color`.
- **View-app (vanha) värit:** `mobile_colors.xml` (+ values-night, + values-v31/-night-v31 dynaaminen). Yhä olemassa vanhalle
  `MobileMainActivity`lle, mutta uusi Compose-UI käyttää `ArkikeskusTheme.kt`:n ColorSchemejä.

---

## 4. Tiedostot

### Compose-lähdekoodi (`app-mobile/src/main/java/org/jrs82/fsclock/mobile/`)
| Tiedosto | Rooli |
|---|---|
| `MobileComposeMainActivity.kt` | Launcher-Activity. `installSplashScreen()`, sijaintiluvan ensikysely, teema (night mode + dynamic), `setContent { ArkikeskusTheme { ComposeMainScreen() } }`. |
| `ComposeMainScreen.kt` | Scaffold + **kiinteä alapalkki** (NavigationBar Koti/Päivitä/Valikko + erotinviivat + järjestelmäinset-sävy) + **valikko-overlay** (koko ruudun Surface) + sektioreititys (`HomeSection`-enum `when`) + `DrawerContent` (pyöristetyt laatikkokohdat + osio-ikonit). |
| `ComposeHomeContent.kt` | `HomeDashboard` + etusivun widgetit (kello/pyhä/sää/sähkö/anturit) + täydet sektiot Sää-ennuste / Anturit / Pörssisähkö / Uutiset. Säävaroitusten tyhjäsuodatus. |
| `ComposeHomeWidgets.kt` | Etusivun string-id-widgetmalli (`HomeWidget` + per-lähde) + kortit: HomeNewsCard, **HomeNewsSourceCard**, **HomeWarningsCard**, **HomeTrafficCard**, HomeTransitCard. **Lähilähtöjen inline-reitti** (`TripTimelineInline`/`TimelineStopRow`). Uutisten prosessivälimuisti. |
| `ComposeExtraSections.kt` | Fragment-hostaus (Kelikamerat/Lähilähdöt/Reittihaku), Puhelimen tiedot, Liikennetiedot-sektio, GPS-nopeus. |
| `ComposePlacesSteps.kt` | Paikkakunnat- ja Askeleet-sektiot. |
| `SettingsScreen.kt` | Asetukset (pyöristetyt laatikkorivit, osio-otsikot + ikonit, dialogit, Ruuvi-skannaus, omat RSS-syötteet, itsepäivitys). `ItemBoxShape` (jaettu valikon kanssa). |
| `ArkikeskusTheme.kt` | Material 3 -teema: vaalea/tumma brändi-ColorScheme + dynamic color + `ArkiColors`-semanttiset tokenit. |
| `AppUpdater.kt` | Itsepäivitys (GitHub releases/latest → versiovertailu → lataa & asenna APK). |
| `HealthConnectStepsBridge.kt` | Health Connect -askeldata (ainoa "vanha" Kotlin-tiedosto). |
| `MobileSettingsActivity.kt` | Hostaa `SettingsScreen`in (avataan valikosta). |

### Uudet vektori-ikonit (`app-mobile/src/main/res/drawable/`)
`mobile_ic_menu_24`, `_location_24`, `_dashboard_24`, `_news_24`, `_rss_24`, `_add_24`, `_bluetooth_24`, `_bolt_24`,
`_clock_24`, `_palette_24`, `_tune_24`, `_info_24`, `_download_24`, `_code_24` (asetukset/valikko) +
`_weather_24`, `_car_24`, `_bus_24`, `_apps_24` (valikon osio-otsikot). Standardit Material-pathit, valkoinen fill
(Compose värittää `colorScheme.primary`). **18 uutta ikonia.**

### Jaettu logiikka (Java, jota Compose lukee — EI muutettu paitsi `RssRepository.fetchForFeed` lisätty)
Repositoryt: `WeatherRepository`, `ElectricityRepository`, `ElectricityAverages`, `RuuviRepository`, `WarningsRepository`,
`TrafficNoticesRepository`, `RssRepository`, `OpenMeteoRepository`, `TransitRepository`. Datamallit: `WeatherData`,
`WeatherWarning`, `TrafficNotice`, `Departure`, `TripTimeline`/`TimelineStop`, `NewsItem`, `NewsFeed`/`NewsFeedStore`,
`GeoPlace`. API: `DigitransitApi`, `MmlGeocodingClient`. Apurit: `MobileThemeController` (kaikki SharedPreferences-avaimet),
`SettingsManager`, `MobileHolidayProvider`, `FinnishHolidays`, `DeviceInfoReaders`, `StepsHtmlExporter`, `StepCalorieEstimator`.
Fragmentit (hostataan): `RoadCamerasFragment` (MapLibre), `TransitFragment`, `RoutePlannerFragment`.

### Splash / teema / manifest
- `res/values/mobile_styles.xml`: `Theme.Arkikeskus.Splash` (parent `Theme.SplashScreen`, `windowSplashScreenBackground=@color/mobile_splash_bg`,
  `windowSplashScreenAnimatedIcon=@mipmap/ic_launcher`, `postSplashScreenTheme=@style/MobileComposeTheme`). `MobileComposeTheme` (NoActionBar).
- `AndroidManifest.xml`: launcher = `MobileComposeMainActivity` (theme splash). `MobileWidgetOrderActivity` (raahaus). FileProvider (itsepäivitys).
- Sovellusikoni: `res/mipmap-anydpi-v26/ic_launcher.xml` (adaptive: background `@drawable/ic_launcher_background` gradientti +
  foreground `@mipmap/ic_launcher_foreground` valkoinen A + monochrome). Play Store 512: `releases/arkikeskus_play_store_512.png`.

---

## 5. Polut (mistä tämän version tiedot löytyvät)

- **Lähdekoodirepo:** `C:\Android\projects\FsClock` (git, haara `mobile`). Mobiilimoduuli `app-mobile/`.
  Compose-koodi `app-mobile/src/main/java/org/jrs82/fsclock/mobile/`, resurssit `app-mobile/src/main/res/`.
- **Build-tiedosto / versio:** `app-mobile/build.gradle` (versionName/versionCode, riippuvuudet).
- **Manifest:** `app-mobile/src/main/AndroidManifest.xml`.
- **APK (release-allekirjoitettu, esikatselut):** `releases/_compose-preview/` — uusin
  `Arkikeskus-yhtenainen-tyyli-esikatselu.apk`. Build-output: `app-mobile/build/outputs/apk/release/Arkikeskus-1.15.0-mobile.apk`.
- **Tämä auditti:** `releases/AUDIT_2.0.0.md`.
- **Commit-historia:** `git log` haarassa `mobile`, väli `a830e87..852d45f` (48 committia).
- **Ikonityökalu:** `tools/make_icon.py`.
- **Allekirjoitusavain:** `release.keystore` (sama kuin julkaistuissa → päivittyy paikalleen).
- **GitHub (julkaistu vanha):** github.com/jrs8205/Arkikeskus (uusin julkaistu `1.15.1-mobile` = vanha View-UI).

---

## 6. Tunnetut rajat & **tarkistettavat riskialueet** (kohdista tarkistus tänne)

1. **Säikeistys / Compose-state taustasäikeestä.** Listenerit (`WarningsRepository.Listener`, `RuuviRepository.Listener`)
   kutsutaan taustasäikeestä; tila päivitetään `Handler(Looper.getMainLooper()).post { tick++ }`-kautta. **Tarkista**
   ettei mitään Compose `mutableStateOf`-kirjoitusta tehdä suoraan IO-säikeestä. Verkkohaut ovat `withContext(Dispatchers.IO)`.
2. **Lähilähtöjen inline-reitti (`TripTimelineInline`, ComposeHomeWidgets.kt).** Hakee `DigitransitApi.tripTimeline`
   IO:ssa joka avauksella (ei välimuistia, koska live-data). **Tarkista:** ei vuotoa, ei main-thread-verkkoa, oikea
   `remember(d.tripGtfsId, d.stopGtfsId)`-avainnus, ja että pitkä pysäkkilista skrollaa oikein etusivun verticalScrollissa
   (ei sisäkkäistä pystyskrollia — koko sivu vierii).
3. **Valikko-overlay (ComposeMainScreen.kt).** Koko ruudun `Surface` piirtyy Scaffold-sisällön päälle (alapalkki erikseen
   näkyvissä). Sulkeutuu: Valikko-nappi (toggle), `BackHandler`, kohdan valinta, Koti. **Tarkista:** ei jää jumiin, back-käsittely
   oikein, ei kahta back-callbackia (myös Fragmenttien omat back-callbackit, esim. RoutePlanner/Transit detail-overlay).
4. **Alapalkki + edge-to-edge insetit.** `NavigationBar(windowInsets = WindowInsets(0,0,0,0))` + manuaalinen
   `windowInsetsBottomHeight(WindowInsets.navigationBars)`-Spacer (`surfaceContainerHighest`). **Tarkista:** ei tuplainsettiä,
   sisältö ei jää alapalkin alle, toimii ele- ja 3-nappinavigaatiolla, vaalea + tumma.
5. **Uutisten prosessivälimuisti (`sHomeNewsCache`, `sHomeFeedCache`, ComposeHomeWidgets.kt).** Globaali muuttuva tila
   (kirjoitetaan mainissa `withContext`-haun jälkeen). **Tarkista:** ei vanhennu väärin; Päivitä-tick (`LocalRefreshTick`) hakee
   uudet; lähilähtöjä EI cacheta. Yhtenäisyys per prosessi (nollautuu kun prosessi tapetaan).
6. **String-id-widgetmalli + Java-interop (`ComposeHomeWidgets.kt` ↔ `MobileWidgetOrderActivity.java`).**
   `allHomeWidgetIds/homeWidgetTitleForId/defaultVisibleForId` (public, ei `internal`, jotta Java näkee). **Tarkista:**
   per-lähde-id:t (`news:<feedId>`), näkyvyysavaimet, raahausjärjestyksen tallennus/luku.
7. **Yhtenäinen laatikkotyyli (852d45f).** Asetuksissa `SettingsCard`→Column(spacedBy 8dp), `RowDivider`→tyhjä,
   rivit `Card(ItemBoxShape)`. **Tarkista:** ei sisäkkäisiä Cardeja, välistys oikein, 10 uutislähde-laatikkoa ei tee
   listasta liian raskasta, valitun valikkokohdan korostus (primaryContainer) kontrasti molemmissa teemoissa.
8. **Splash `setKeepOnScreenCondition` (~0,9 s, MobileComposeMainActivity).** **Tarkista:** ei viivästytä liikaa
   hitailla laitteilla, ei jää näkyviin.
9. **Fragment-in-Compose (ComposeExtraSections.kt).** MapLibre-kelikamerat raskas natiivi. **Tarkista:** lifecycle/poisto
   (`onDispose` + `commitNowAllowingStateLoss`), ei vuoda, ei kaadu sektiota vaihtaessa.
10. **Sijaintiluvan ensikysely + automaattinen sijainti.** `KEY_INITIAL_LOCATION_PERMISSION_ASKED` kysyy kerran;
    auto-sijainti oletuksena päällä. **Tarkista:** sää + lähilähdöt toimivat heti luvan jälkeen, ei jankuta jos kielletään.
11. **Vanha kuollut koodi.** `MobileMainActivity` (View) jää varalle/HC-perusteluun; `MobileSettingsFragment` + `mobile_preferences.xml`
    voivat olla kuolleita. **Tarkista:** ei tahatonta launchia, ei ristiriitaa.

---

## 7. Build & julkaisu

```
# Build (Windows):
& "C:\Android\projects\FsClock\gradlew.bat" -p "C:\Android\projects\FsClock" :app-mobile:assembleRelease

# Ennen buildia poista pilvisynkka-konfliktit (varmuudeksi):
#   Get-ChildItem -Recurse -Force | Where Name -match "# (Edit conflict|Name clash)" | Remove-Item -Force
```
- **Versionnosto 2.0.0:** `app-mobile/build.gradle` → `versionName '2.0.0-mobile'`, `versionCode 50`.
- **Julkaisu:** push haara + `gh release v2.0.0-mobile` (latest, APK assetiksi). Sama `release.keystore` → päivittyy paikalleen.
- **HUOM:** julkaistu uusin GitHubissa on `1.15.1-mobile` (vanha View-UI). 2.0.0 > 1.15.1 → itsepäivitys ehdottaa oikein.
