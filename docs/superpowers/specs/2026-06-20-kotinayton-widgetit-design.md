# Kotinäytön widgetit (Jetpack Glance) — suunnitelma

**Päivä:** 2026-06-20
**Tila:** hyväksytty suunnitelma; toteutus odottaa erillistä aloituslupaa.

## Konteksti ja tavoite

Useat käyttäjät ovat pyytäneet **kotinäytön (launcher) widgettejä** Arkikeskukseen: sää,
pörssisähkö, askeleet ja seuraava lähtö **vilkaistavissa avaamatta sovellusta**. Tällä hetkellä
nämä tiedot näkyvät vain sovelluksen sisällä (etusivun kortit). Tavoite on tuoda tärkeimmät
"vilkaisutiedot" suoraan kotinäytölle.

Tekninen lähtötilanne (todennettu koodista):
- Sovellus on Compose + View -hybridi, **minSdk 30**, data SharedPreferences + Room + backend.
- **Ei vielä yhtään launcher-widget-koodia** (greenfield).
- WorkManager jo käytössä (AutoBackup-, Notifications-workerit) ja manifestissa receiver-pohja
  (boot, departure) → widgetin taustapäivitys istuu olemassa olevaan malliin.
- `open_section`-deep-link on jo olemassa (`WorkoutTrackingService.EXTRA_OPEN_SECTION`,
  `MobileComposeMainActivity` lukee sen) → widgetin tap voi avata oikean sektion.
- Askelputki olemassa (Room `daily_steps` + `TYPE_STEP_COUNTER` + Health Connect, SPN-käsittely).
- Transit-suosikit olemassa (`TransitFavorites`).

## Laajuus (v1)

- **Neljä erillistä Glance-widgetiä:** Sää, Pörssisähkö, Askeleet, Seuraava lähtö.
- **Erilliset widgetit per tieto** (käyttäjä lisää kotinäytölle vain haluamansa; voi lisätä monta).
- **Jetpack Glance 1.1.1** (yhteensopiva projektin Kotlin 1.9.24:n kanssa; ks. Tekniset reunaehdot).
- Teema seuraa järjestelmän vaaleaa/tummaa, brändivärit. **Material You jätetään v1:stä pois (YAGNI).**

## Arkkitehtuuri

Uusi paketti `org.jrs82.fsclock.mobile.widget`. Kullekin widgetille oma kolmikko:
- `*Widget : GlanceAppWidget` — @Composable-tyylinen UI (tilaton/passiivinen).
- `*WidgetReceiver : GlanceAppWidgetReceiver` — manifestiin rekisteröity.
- `*_info.xml` (`appwidget-provider`) — koot, esikatselu, **`updatePeriodMillis=0`** (päivitys
  WorkManagerilla, ei järjestelmän kellolla), konfigurointi vain lähtö-widgetillä.

Jaetut osat:
- **`WidgetCache`** — yksi paikka widgettien näyttöarvoille (SharedPreferences, omat avaimet
  erillään muusta). Sovellus ja worker kirjoittavat tänne; widgetit lukevat täältä.
- **`WidgetUpdateWorker : CoroutineWorker`** — periodic WorkManager-työ joka hakee datan,
  kirjoittaa `WidgetCacheen` ja kutsuu kunkin widgetin `updateAll(context)`.
- **`DepartureWidgetConfigActivity`** — `APPWIDGET_CONFIGURE`-Activity lähtö-widgetin asetuksiin.

Komponenttien rajat: kukin widget on itsenäinen (oma receiver + provider), jakaa vain `WidgetCachen`
ja workerin. Worker ei tunne widgettien UI:ta (kutsuu vain `updateAll`). UI ei tee verkkohakua.

## Datavirta ja päivitys

- **`WidgetUpdateWorker` periodic ~15 min** (WorkManagerin minimi; Googlen dokumentoima
  "tiheämmän päivityksen" esimerkkiarvo):
  - **Joka kierros:** askeleet (paikallinen, halpa) + valittujen pysäkkien lähdöt (reaaliaikaisin).
  - **Joka toinen kierros (~30 min):** sää + pörssisähkö (muuttuvat hitaasti, verkkohaku → säästö).
  - Kirjoittaa arvot `WidgetCacheen`, lukee "päivitetty klo X", kutsuu `updateAll`.
- **Heti-päivitys** kun sovellus on edessä ja virkistää dataa muutenkin → push `WidgetCacheen` +
  `updateAll` (ei odoteta worker-kierrosta).
- **Widgetin lisäys** → kertaluonteinen heti-haku (enqueue one-time worker).
- Widget on **tilaton/passiivinen**: lukee aina `WidgetCachesta`, ei muistinvaraista tilaa.
- Hakulogiikka nojaa olemassa oleviin lähteisiin (FMI/Open-Meteo, Elering, askelputki, Digitransit) —
  samat joita Notifications-worker jo käyttää.
- **Mahdollinen myöhempi lisä (ei v1):** sitoa worker-väli sovelluksen olemassa olevaan
  "Tietojen päivitysväli"-asetukseen (10/15/30/60/120 min, alaraja 15) → yhtenäinen käyttäjäsäätö.
  v1:ssä väli on kiinteä 15 min.

## Widgetit (sisältö, koko, tap)

| Widget | Sisältö | Oletuskoko | Tap avaa (`open_section`) |
|---|---|---|---|
| **Sää** | Paikkakunta · lämpötila · sääikoni (+ tuuli/sade jos tilaa) | pieni (2×2) | etusivu (HOME) |
| **Pörssisähkö** | Nykyhinta c/kWh · halpaa/normaali/kallista (väri) · "nyt klo X" | pieni (2×2) | ELECTRICITY |
| **Askeleet** | Tämän päivän askeleet · tavoite (rengas/palkki) · % | pieni (2×2) | STEPS |
| **Seuraava lähtö** | Pysäkki · 2–3 seuraavaa (linja + min) · "päiv. klo X" | keski (4×2) | TRANSIT |

Kaikki widgetit responsiivisia (Glance `SizeMode.Responsive`), tukevat puhelin/tabletti/taittuvan.

## Seuraava lähtö -konfigurointi

- `DepartureWidgetConfigActivity` käynnistyy widgetiä lisättäessä.
- Käyttäjä valitsee **kiinteän suosikkipysäkin/-linjan** (`TransitFavorites`) **TAI "lähin"-tilan**.
- Valinta tallennetaan **`appWidgetId`-kohtaisesti** → monta widgetiä eri pysäkeille.
- "Lähin" vaatii sijaintiluvan; jos ei lupaa, ohjataan valitsemaan suosikki.
- Activity: `enableEdgeToEdge()` + insetit (Android 15/16+ pakottaa edge-to-edgen), palauttaa
  `RESULT_OK` + `appWidgetId`.

## Teema

- `GlanceTheme` vaalealla ja tummalla värijoukolla; brändivärit ArkikeskusTheme-paletista.
- Pyöristetyt kulmat (Android 12+ widget-tausta).
- Material You / dynaaminen väri **ei v1:ssä** (Glance tukisi helposti → mahdollinen myöhempi lisä).

## Tekniset reunaehdot (2026 / Android 17) — todennettu Google-dokumenteista

- **Glance-versio:** vakaa **1.1.1** (2024-10). 1.2.0-rc01 (2025-12), 1.3.0-alpha01 (2026-05).
  Projekti on lukittu **Kotlin 1.9.24 + Compose-kääntäjä 1.5.14 + Compose BOM 2024.06.00** →
  **käytä 1.1.1:tä.** 1.2.0+ saattaa vaatia uudemman Compose-runtimen → varmistettava ennen päivitystä.
  Glance minSdk 23 → projektin 30 ok.
- **Päivitys:** `updatePeriodMillis=0` + **WorkManager (minimi 15 min)** + `updateAll()`
  interaktiossa/edessä. `updatePeriodMillis`:n oma minimi on 30 min → siksi WorkManager.
- **Tilattomuus:** widget lukee aina datakerroksesta (eri prosessi; muistitila voi kadota).
- **Responsiiviset koot:** `SizeMode.Responsive` + `targetCellWidth/Height` + min-koot providerissa.
- **Android 12+ (API 31):** pyöristetyt kulmat, esikatselu — Glance hoitaa suurelta osin.
- **Android 17 (API 37): ei widget-spesifejä rikkovia muutoksia.** Huomioitu: BAL-koventaminen
  (tap-PendingIntentit ovat käyttäjän käynnistämiä → ok), taustatyö (WorkManager = sallittu reitti),
  `static final` -kentät (ei reflektiota → ok), edge-to-edge (vain config-Activity → hoidettu).
- **Health Connect:** widget käyttää **sovelluksen olemassa olevaa askelputkea** (Room `daily_steps`
  + `TYPE_STEP_COUNTER` workerissa, ei verkkoa eikä `READ_HEALTH_DATA_IN_BACKGROUND`-lupaa).
  ⚠️ **Kesäkuu 2026:** HC siirtää laitteen askeleet laitekohtaiseen SPN:ään → nojaa sovelluksen
  jo olemassa olevaan SPN-käsittelyyn, ei omaan HC-lukuun.

Lähteet: [Glance releases](https://developer.android.com/jetpack/androidx/releases/glance) ·
[Manage/update GlanceAppWidget](https://developer.android.com/develop/ui/compose/glance/glance-app-widget) ·
[Advanced widget](https://developer.android.com/develop/ui/views/appwidgets/advanced) ·
[Android 17 behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17) ·
[Health Connect background reads](https://android-developers.googleblog.com/2025/03/health-connect-jetpack-sdk-now-in-beta.html)

## Testaus

- **Yksikkötestit** puhtaille muotoilufunktioille (hintastatus + väri, askel-% / tavoite,
  lähtöjen min-laskenta + "päivitetty klo X" -muotoilu). Nämä erotetaan UI:sta → JVM-testattavia.
- **Manuaali:** kukin widget lisätään emulaattorille ja Pixel 8a:lle → sisältö, tap → oikea sektio,
  vaalea/tumma teema, taustapäivitys (worker), lähtö-widgetin konfigurointi (suosikki + lähin).
- Debug-build asennetaan rinnalle (`Arkikeskus DEBUG`) kuten muutkin testit.

## Toteutusjärjestys (implementaatioplaniin)

1. **Jaettu infra:** `WidgetCache`, `WidgetUpdateWorker`, Glance-riippuvuus + teema, manifest-pohja.
2. **Sää-widget** (yksinkertaisin → validoi koko putken: provider → worker → cache → UI → tap).
3. **Pörssisähkö-widget.**
4. **Askeleet-widget.**
5. **Seuraava lähtö -widget + `DepartureWidgetConfigActivity`** (monimutkaisin viimeisenä).

## Riskit ja lievennykset

- **Glance-yhteensopivuus** projektin Kotlin 1.9.24:n kanssa → lukittu 1.1.1:een; varmistetaan
  käännös heti vaiheessa 1.
- **Askelten tuoreus taustalla:** worker näyttää viimeisintä synkattua arvoa (ei sekuntitarkkaa) —
  hyväksyttävä vilkaisuun (vastaa sovelluksen nykykäytöstä).
- **Lähtöjen reaaliaikaisuus:** WorkManagerin 15 min lattia rajoittaa; widget näyttää "päiv. klo X"
  ja tap avaa live-näkymän tuoreuteen.
- **Akku:** sää+sähkö joka toisella kierroksella (~30 min); askeleet paikallinen (halpa).

## Päätetyt valinnat (ei avoimia kohtia)

- Paketointi: erilliset widgetit per tieto. ✓
- v1: kaikki neljä widgetiä. ✓
- Lähtö-widget: valittu suosikki TAI lähin. ✓
- Toteutustapa: Jetpack Glance 1.1.1. ✓
- Päivitysväli: WorkManager 15 min (sää+sähkö ~30 min). ✓
