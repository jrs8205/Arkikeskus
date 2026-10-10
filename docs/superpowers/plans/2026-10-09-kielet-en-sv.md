# Arkikeskus 2.27.0 kielet (en + sv) — toteutussuunnitelma

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mobiilisovellus näkyy puhelimen kielen mukaan suomeksi, ruotsiksi tai (muut kielet) englanniksi; kaikki käyttäjälle näkyvät tekstit ovat resursseissa kolmella kielellä.

**Architecture:** `values/` = englanti (oletus), `values-fi/`, `values-sv/`. Kovakoodatut tekstit puretaan aihekohtaisiin `strings_<alue>.xml`-tiedostoihin, Context-vapaat logiikkaluokat palauttavat rakenteisen tuloksen ja muotoilu siirtyy UI:hin. `AppLocale`-apuri antaa kielen muotoilulle ja API-parametreille. Työ jaetaan 7 rinnakkaiseen git-worktree-haaraan tiedostoryhmittäin ja yhdistetään `i18n`-haaraan.

**Tech Stack:** Android resurssit, Jetpack Compose `stringResource`/`pluralStringResource`, `androidx.core.os.LocaleListCompat`, lint `MissingTranslation`, JUnit 4 (puhtaat JVM-testit, ei Robolectricia).

**Spec:** `docs/superpowers/specs/2026-10-09-kielet-en-sv-design.md`, sanasto `docs/superpowers/specs/2026-10-09-sanasto.md`.

## Global Constraints

- Vain `app-mobile`-moduuli. Tablettia (`tablet-compose`) tai ARK-Tablettia ei kosketa.
- `values/` = en, `values-fi/` = fi, `values-sv/` = sv. Samat avaimet samassa järjestyksessä kaikissa kolmessa. Muotoiluargumentit numeroituina (`%1$s`).
- Avain: `<alue>_<asia>`, pienet kirjaimet, alaviivat. Yhteiset sanat (`common_cancel`, `common_save`, …) vain `strings_common.xml`:stä — ei omia kopioita.
- Käännökset sanaston mukaan: suomenruotsi (HRT, börsel, Nyland), brittienglanti (favourites, metres, 24 h). Idiomaattisia, ei sanasta sanaan.
- `Locale("fi","FI")` saa jäädä vain konekäyttöön (API-parametrit, tiedostonimet, CSV). Näkyvä muotoilu → `AppLocale.current(context)`.
- Aikavyöhyke `Europe/Helsinki` ja kellonaikakuviot (`HH:mm`, widget `HH.mm`) pysyvät.
- Nykyiset 282 testiä pysyvät vihreinä; testien määrä ei laske. Uudet testit puhtaita JVM-testejä (ei Robolectric).
- Commitit: ASCII-viesti (ei ääkkösiä), ei AI-mainintoja, ei `Co-Authored-By`. Muoto `i18n: <alue> - <mita tehtiin>`.
- Build/testit: `./gradlew :app-mobile:compileDebugKotlin :app-mobile:testDebugUnitTest` (worktreessä `gradlew.bat` Windowsilla). Ei `assembleRelease`a agenttitehtävissä.
- Kommentit koodissa suomeksi kuten ennenkin, mutta vain kun ne kertovat *miksi*.

## Review Focus

1. Suomalainen käyttäjä, jonka locale-lista on `[fi]`: jokaisen näkymän jokainen teksti on suomeksi (ei yhtään englantia vuotanut). → Task 10 `StringResourceParityTest` + lint `MissingTranslation` fatal + laitetesti.
2. Locale `[de, sv]` → ruotsi; `[de]` → englanti; `[sv-SE]` → ruotsi. → Task 1 `AppLocaleTest`.
3. FMI-varoitus, jonka `info_sv` on tyhjä tai puuttuu → näytetään `info_fi`, ei tyhjää korttia. → Task 8 `FmiWarningLanguageTest`.
4. Kieli vaihtuu sovelluksen ollessa taustalla → ilmoituskanavien nimet ja widgetit päivittyvät, prosessin välimuistit (nimipäivä, varoitukset) eivät näytä vanhan kielen tekstiä. → Task 9 `LocaleChangedReceiver` + välimuistien kieliavain.
5. Monikot: `1 askel` / `2 askelta`, en `1 step` / `2 steps`, sv `1 steg` / `2 steg` — `plurals`-resurssit, ei `if (n == 1)`-koodia. → kunkin alueen tehtävä + parity-testi tarkistaa, että `plurals` on kaikissa kielissä.

---

## Työnjako ja worktreet

| Tehtävä | Haara / worktree | Omistetut lähdetiedostot | Omistetut resurssitiedostot (×3 kieltä) |
|---|---|---|---|
| 2 A | `i18n-settings` | `SettingsScreen.kt`, `DriveBackupScreen.kt`, `DriveBackup.kt`, `BackupManager.kt`, `AutoBackup.kt`, `AppUpdater.kt`, `AppUpdateNotifier.kt`, `MobileWidgetOrderActivity.kt`, `HealthRationaleActivity.kt`, `ViewPermissionUsageActivity.kt` | `strings_settings.xml`, `strings_backup.xml` |
| 3 B | `i18n-transit` | `TransitScreen.kt`, `RoutePlannerFragment.java`, `RoutePlannerAdapter.java`, `layout/fragment_route_planner.xml`, `DigitransitApi.java`, `MmlGeocodingClient.java`, `HslAlertNotifier.kt`, `DepartureReminder.kt`, `DepartureReminderReceiver`, `widget/DepartureWidget.kt`, `widget/DepartureWidgetConfigActivity.kt`, `TrafficNotice.java`, `TrafficNoticesClient.java`, `TampereMqtt*`, `Transit*`-apuluokat | `strings_transit.xml` |
| 4 C | `i18n-workout` | `WorkoutScreen.kt`, `WorkoutTrackingService.kt`, `WorkoutStats.kt`, `WorkoutShareImage.kt`, `WorkoutFileImporter.kt`, `Workout*Exporter`, `ShareFiles.kt`, `IoUtils.kt`, `ComposePlacesSteps.kt`, `StepsHtmlExporter.java`, `StepsHistory.java`, `StepGoalNotifier.kt`, `HealthConnectStepsBridge.kt`, `StepCalorieEstimator`, `widget/StepsWidget.kt`, `KuntaList`, `GeoPlace.java` (mobile) | `strings_workout.xml`, `strings_places_steps.xml` |
| 5 D | `i18n-home` | `ComposeHomeContent.kt`, `ComposeHomeWidgets.kt`, `ComposeExtraSections.kt`, `MobileHolidayProvider.java`, `core/FinnishHolidays.java`, `core/AlmanakkaClient.java`, `core/Astronomy.java`, `ElectricityVat.kt`, `ElectricityTime.kt`, `ElectricityNotifier.kt`, `ElectricityAverages`, `widget/ElectricityWidget.kt`, `widget/WeatherWidget.kt`, `widget/WidgetFormat.kt`, `widget/WidgetTheme.kt`, `core/ElectricityPageBuilder.java` | `strings_home.xml`, `strings_electricity.xml`, `strings_holidays.xml` |
| 6 E | `i18n-main` | `ComposeMainScreen.kt`, `MobileComposeMainActivity.kt`, `ComposeCommon.kt`, `RestartActivity`, `ForeignNewsScreen.kt`, `NewsProfile.kt`, `NewsFeedStore.java`, `News*`-apuluokat, `TeletextScreen.kt`, `Teletext*`, `FlightsScreen.kt`, `FlightDisplay.kt`, `FinaviaAirports.kt`, `Flights*`, `RoadCamerasScreen.kt`, `RoadCameras*`, `DeviceInfoReaders.java`, `DeviceInfo*`, `Notifications.kt`, `GpsSpeed*`, `Ruuvi*`-mobiilinäkymät | `strings_main.xml`, `strings_news.xml`, `strings_flights.xml`, `strings_device.xml`, `strings_notifications.xml`, `strings_cameras.xml` |
| 7 F1 | `i18n-existing` | ei lähdekoodia (vain resurssit) | `core/res/values/strings.xml` → `values-fi/strings.xml` + en `values/strings.xml` + sv; `src/main/res/values/mobile_strings.xml` samoin |
| 8 F2 | `i18n-warnings` | `WarningsScreen.kt`, `WarningDisplay.kt`, `WeatherWarningNotifier.kt`, `FmiWarningFilter.kt`, `FinnishRegions.kt`, `core/WeatherWarning.java`, `core/WarningDetails.java`, `core/FmiWarningsClient.java`, `core/FmiWarningDetailsClient.java`, `core/WarningsRepository.java`, `core/WarningEnricher*`, `core/FmiCounties.java`, `core/GeoPlace.java`, `core/WeatherData.java`, `core/WeatherCondition.java`, `core/FmiRepository.java`, `core/OpenMeteoRepository.java`, `core/MetNorwayClient.java`, `core/FmiClient.java`, `core/ruuvi/*`, `core/db/*`, `core/ClockController.java`, `core/BootReceiver.java` | `strings_warnings.xml`, `strings_weather.xml` |

Agentti saa koskea **vain** omiin tiedostoihinsa sekä sanastoon (lisäykset). Jos tarvitsee muutoksen toisen omistamaan tiedostoon, se kirjaa sen loppuraporttiin ("Tarvitsen X:ään …") ja käyttää väliaikaisesti paikallista apufunktiota omassa tiedostossaan.

Worktreet luodaan `i18n`-haarasta Task 1:n jälkeen:

```bash
cd /c/Android/projects/FsClock-main
for a in settings transit workout home main existing warnings; do
  git worktree add "../FsClock-i18n-$a" -b "i18n-$a" i18n
  cp local.properties "../FsClock-i18n-$a/"
done
```

---

### Task 1: Infrastruktuuri (AppLocale, yhteiset stringit, localeConfig, lint)

**Files:**
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/AppLocale.kt`
- Create: `app-mobile/src/test/java/org/jrs82/fsclock/mobile/AppLocaleTest.kt`
- Create: `app-mobile/src/main/res/values/strings_common.xml`, `values-fi/strings_common.xml`, `values-sv/strings_common.xml`
- Create: `app-mobile/src/main/res/xml/locales_config.xml`
- Create: `app-mobile/lint.xml`
- Modify: `app-mobile/src/main/AndroidManifest.xml` (`<application>` saa `android:localeConfig`)
- Modify: `app-mobile/build.gradle` (`localeFilters`, `lint`)

**Interfaces:**
- Produces: `object AppLocale { fun resolve(tags: List<String>): Locale; fun current(context: Context): Locale; fun language(context: Context): String /* "fi"|"sv"|"en" */; fun fmiInfoKey(context): String /* "info_fi"|"info_sv"|"info_en" */; fun digitransitLanguage(context): String /* "fi"|"sv" */; fun almanakkaType(context): String /* "suomi"|"ruotsi" */ }`
- Produces: `R.string.common_cancel`, `common_save`, `common_close`, `common_delete`, `common_add`, `common_edit`, `common_ok`, `common_yes`, `common_no`, `common_retry`, `common_refresh`, `common_search`, `common_open`, `common_share`, `common_back`, `common_loading`, `common_no_connection`, `common_error`, `common_no_data`, `common_stale`, `common_updated_at` (`%1$s`), `common_today`, `common_tomorrow`, `common_dismiss`, `common_choose`, `common_settings`, `common_more`, `common_less`, `common_unknown`, `common_dash` (`–`).

- [ ] **Step 1: Testi `AppLocaleTest`**

```kotlin
package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLocaleTest {
    @Test fun finnishStaysFinnish() = assertEquals("fi", AppLocale.resolve(listOf("fi-FI")).language)
    @Test fun swedishFinlandIsSwedish() = assertEquals("sv", AppLocale.resolve(listOf("sv-FI")).language)
    @Test fun swedishSwedenIsSwedish() = assertEquals("sv", AppLocale.resolve(listOf("sv-SE")).language)
    @Test fun germanFallsBackToEnglish() = assertEquals("en", AppLocale.resolve(listOf("de-DE")).language)
    @Test fun listPicksFirstSupported() = assertEquals("sv", AppLocale.resolve(listOf("de-DE", "sv-FI", "fi-FI")).language)
    @Test fun emptyListIsEnglish() = assertEquals("en", AppLocale.resolve(emptyList()).language)
    @Test fun fmiKeyFollowsLanguage() {
        assertEquals("info_sv", AppLocale.fmiInfoKey(AppLocale.resolve(listOf("sv"))))
        assertEquals("info_en", AppLocale.fmiInfoKey(AppLocale.resolve(listOf("en-GB"))))
        assertEquals("info_fi", AppLocale.fmiInfoKey(AppLocale.resolve(listOf("fi"))))
    }
    @Test fun digitransitHasNoEnglish() {
        assertEquals("fi", AppLocale.digitransitLanguage(AppLocale.resolve(listOf("en"))))
        assertEquals("sv", AppLocale.digitransitLanguage(AppLocale.resolve(listOf("sv"))))
    }
    @Test fun almanakkaTypeSwedishOnlyForSwedish() {
        assertEquals("ruotsi", AppLocale.almanakkaType(AppLocale.resolve(listOf("sv"))))
        assertEquals("suomi", AppLocale.almanakkaType(AppLocale.resolve(listOf("en"))))
    }
}
```

- [ ] **Step 2: Aja testi → FAIL (AppLocale puuttuu)**

`./gradlew :app-mobile:testDebugUnitTest --tests "org.jrs82.fsclock.mobile.AppLocaleTest"`

- [ ] **Step 3: Toteutus `AppLocale.kt`**

```kotlin
package org.jrs82.fsclock.mobile

import android.content.Context
import java.util.Locale

/** Sovelluksen kieli: fi ja sv sellaisenaan, kaikki muut englanniksi (sama sääntö kuin resurssiresoluutiolla,
 *  koska values/ on englanti). Puhtaat funktiot ottavat Localen, jotta logiikka on testattavissa ilman Contextia. */
object AppLocale {
    val FINNISH: Locale = Locale("fi", "FI")
    val SWEDISH: Locale = Locale("sv", "FI")
    val ENGLISH: Locale = Locale.UK

    fun resolve(tags: List<String>): Locale {
        for (tag in tags) {
            when (Locale.forLanguageTag(tag).language) {
                "fi" -> return FINNISH
                "sv" -> return SWEDISH
                "en" -> return ENGLISH
            }
        }
        return ENGLISH
    }

    fun current(context: Context): Locale {
        val locales = context.resources.configuration.locales
        val tags = (0 until locales.size()).map { locales[it].toLanguageTag() }
        return resolve(tags)
    }

    fun language(context: Context): String = current(context).language

    fun fmiInfoKey(locale: Locale): String = when (locale.language) {
        "sv" -> "info_sv"
        "en" -> "info_en"
        else -> "info_fi"
    }
    fun fmiInfoKey(context: Context): String = fmiInfoKey(current(context))

    fun digitransitLanguage(locale: Locale): String = if (locale.language == "sv") "sv" else "fi"
    fun digitransitLanguage(context: Context): String = digitransitLanguage(current(context))

    fun almanakkaType(locale: Locale): String = if (locale.language == "sv") "ruotsi" else "suomi"
    fun almanakkaType(context: Context): String = almanakkaType(current(context))
}
```

- [ ] **Step 4: Aja testi → PASS**

- [ ] **Step 5: `strings_common.xml` kolmella kielellä** (en `values/`, fi `values-fi/`, sv `values-sv/`), avaimet yllä olevan Interfaces-listan mukaan, sanaston käännöksillä. Esimerkki en:

```xml
<resources>
    <string name="common_cancel">Cancel</string>
    <string name="common_save">Save</string>
    <string name="common_close">Close</string>
    <string name="common_delete">Delete</string>
    <string name="common_add">Add</string>
    <string name="common_edit">Edit</string>
    <string name="common_ok">OK</string>
    <string name="common_yes">Yes</string>
    <string name="common_no">No</string>
    <string name="common_retry">Try again</string>
    <string name="common_refresh">Refresh</string>
    <string name="common_search">Search</string>
    <string name="common_open">Open</string>
    <string name="common_share">Share</string>
    <string name="common_back">Back</string>
    <string name="common_loading">Loading…</string>
    <string name="common_no_connection">No connection</string>
    <string name="common_error">Error</string>
    <string name="common_no_data">No data</string>
    <string name="common_stale">out of date</string>
    <string name="common_updated_at">Updated at %1$s</string>
    <string name="common_today">Today</string>
    <string name="common_tomorrow">Tomorrow</string>
    <string name="common_dismiss">Dismiss</string>
    <string name="common_choose">Choose</string>
    <string name="common_settings">Settings</string>
    <string name="common_more">More</string>
    <string name="common_less">Less</string>
    <string name="common_unknown">Unknown</string>
    <string name="common_dash" translatable="false">–</string>
</resources>
```

- [ ] **Step 6: `xml/locales_config.xml`, manifesti, build.gradle, lint.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
    <locale android:name="en" />
    <locale android:name="fi" />
    <locale android:name="sv" />
</locale-config>
```

Manifest `<application … android:localeConfig="@xml/locales_config">`.

build.gradle `android { … }` sisään:

```groovy
    androidResources {
        localeFilters += ['en', 'fi', 'sv']
    }
    lint {
        abortOnError false
        lintConfig file('lint.xml')
    }
```

`app-mobile/lint.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<lint>
    <issue id="MissingTranslation" severity="fatal" />
    <issue id="ExtraTranslation" severity="fatal" />
    <issue id="HardcodedText" severity="warning" />
</lint>
```

- [ ] **Step 7: Build + kaikki testit vihreitä, commit `i18n: AppLocale, yhteiset stringit, localeConfig, lint`**

---

### Tasks 2–8: Alueiden purku (yhteinen resepti)

Jokainen alueagentti noudattaa tätä reseptiä omassa worktreessään. Lue ensin spec ja sanasto.

- [ ] **Step 1: Inventaario.** Listaa omista tiedostoistasi kaikki käyttäjälle näkyvät literaalit (myös ilman ä/ö: "Tallenna", "Suosikit", `contentDescription`, `Toast`, `snackbar`, ilmoitusten otsikot/tekstit, `title=`/`summary=`-parametrit, dialogit, placeholderit, `android:text` layouteissa). Ohita: Log-viestit, URL:t, prefs-avaimet, JSON-kentät, tiedostonimet, MIME-tyypit, regexit, ääniteksti jota ei näytetä.
- [ ] **Step 2: Kirjoita resurssit kolmeen tiedostoon** samassa järjestyksessä: `values/strings_<alue>.xml` (en), `values-fi/…` (fi = nykyinen teksti, tarvittaessa siistittynä), `values-sv/…` (sv). Argumentit numeroituina. Lukumäärät `<plurals>`:
  ```xml
  <plurals name="steps_count">
      <item quantity="one">%1$d step</item>
      <item quantity="other">%1$d steps</item>
  </plurals>
  ```
- [ ] **Step 3: Korvaa koodissa.**
  - Composessa: `Text(stringResource(R.string.transit_fav_add))`, argumentit `stringResource(R.string.common_updated_at, time)`, monikot `pluralStringResource(R.plurals.steps_count, n, n)`.
  - Ei-composable-apufunktiot, jotka palauttavat tekstiä: lisää `context: Context`-parametri tai palauta `@StringRes Int`.
  - Fragment/Service/Receiver/Worker/Glance: `context.getString(...)`, Glancessa `LocalContext.current`.
  - Context-vapaa logiikka (testattu JVM-testeillä): palauta rakenne, esim.
    ```kotlin
    sealed class WarningText { data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : WarningText(); data class Raw(val text: String) : WarningText() }
    fun WarningText.resolve(context: Context): String = when (this) { is Res -> context.getString(id, *args.toTypedArray()); is Raw -> text }
    ```
    ja testit vertaavat `Res(R.string.x, listOf(…))`-arvoon. `R`-luokka on JVM-testissä käytettävissä (`org.jrs82.fsclock.R`), kunhan testi ei tarvitse Contextia.
  - Päivämäärät/numerot: `val loc = AppLocale.current(context)`; `SimpleDateFormat(context.getString(R.string.<alue>_date_pattern), loc)` kun kuvio riippuu kielestä (`EEE d.M.` fi/sv, `EEE d MMM` en), muuten `String.format(loc, "%.1f", x)`.
- [ ] **Step 4: Testit.** Päivitä olemassa olevat testit rakenteisiin tuloksiin. Lisää vähintään yksi uusi testi per muutettu logiikkaluokka (esim. että `Res`-id ja argumentit ovat oikeat). Aja `./gradlew :app-mobile:compileDebugKotlin :app-mobile:testDebugUnitTest`.
- [ ] **Step 5: Oma paritytarkistus.** Aja worktreessä:
  ```bash
  python -I tools/i18n_parity.py app-mobile/src/main/res   # luodaan Task 1:ssä, ks. alla
  ```
  (Jos skripti puuttuu haarassasi, tarkista käsin, että avaimet ovat samat kolmessa tiedostossa.)
- [ ] **Step 6: Commit** `i18n: <alue> - tekstit resursseihin fi/sv/en` (voi olla useampi commit).
- [ ] **Step 7: Loppuraportti** (vastauksessa, ei tiedostoon): stringien määrä, muutetut julkiset signatuurit, muiden omistamiin tiedostoihin tarvittavat muutokset, epävarmat käännökset (fi → sv/en) listana, sanastoon lisätyt termit.

Aluekohtaiset erityisohjeet:

**Task 2 (A, settings):** `SettingsScreen.kt`:n `title`/`summary`-parametrit ja kaikki dialogit; teeman nimet; "Sovelluksen versio" näyttää `BuildConfig.VERSION_NAME`; `AppUpdater`-ilmoituksen tekstit `Notifications`-kutsuun menevät `context.getString`-muodossa. Varmuuskopioinnin tiedostokoot `kt/Mt` → fi `kt/Mt`, sv/en `kB/MB` (resurssi `backup_size_kb` = `%1$.0f kB`).

**Task 3 (B, transit):** `DigitransitApi.java` saa `Accept-Language`-otsakkeen `AppLocale.digitransitLanguage(context)`-arvolla kaikkiin GraphQL-pyyntöihin (tarvittaessa `DigitransitApi`-metodeille `Context`- tai `String language`-parametri; valitse se, jota kutsujat jo tarjoavat). Geokoodaus `lang=fi` → `lang=` + sama kieli. HSL → "HRT" vain sv-tekstissä. Kulkuvälineiden nimet, häiriöiden tilat, lähtömuistutuksen ilmoitustekstit, `fragment_route_planner.xml`:n `android:text` → `@string/…`. Pysäkkihaun välimuistiavaimeen kieli, jos välimuisti on olemassa.

**Task 4 (C, workout/steps):** `WorkoutStats`-, `StepGoalNotifier`-, `WorkoutTrackingService`-ilmoitustekstit; kilometrikuulutus (`TextToSpeech`-kieli → `AppLocale.current`); askel-HTML-vienti (`<html lang>` + otsikot) ja lenkin jakokuva (`WorkoutShareImage`) käännetään; askelten monikot `plurals`; `KuntaList`-nimet pysyvät suomeksi.

**Task 5 (D, home/electricity/holidays):** nimipäivä `AlmanakkaClient.fetchNames(month, day, type)` + `AppLocale.almanakkaType`, välimuistiavaimeen kieli; `FinnishHolidays`/`MobileHolidayProvider`: API:n suomenkielinen nimi → resurssi-id kartalla (`HolidayNames.resId(fiName): Int?`), tuntematon → suomenkielinen nimi sellaisenaan; liputuspäivien nimet resursseihin; sähkön ALV-tekstit (`ElectricityVat` palauttaa resurssi-id:n tai rakenteen); `ElectricityNotifier`-ilmoitus; widgetien tekstit; viikonpäivä/kuukausinimet `AppLocale.current`.

**Task 6 (E, main/news/flights/device/notifications):** `ComposeMainScreen` nav-otsikot sanaston mukaan; `Notifications.ensureChannels` lukee kanavien nimet resursseista (kutsutaan Task 9:ssä kielen vaihtuessa uudelleen); "Mitä uutta"-dialogin runko (releasetekstit pysyvät suomeksi, kehys käännetään); uutiskategorioiden nimet (`NewsProfile`); lentojen tilat (`FlightDisplay` → rakenne); `DeviceInfoReaders` otsikot ja arvot ("Ei tiedossa" → `common_unknown`); kelikameroiden UI.

**Task 7 (F1, existing):** `git mv app-mobile/core/res/values/strings.xml app-mobile/core/res/values-fi/strings.xml` ja `git mv app-mobile/src/main/res/values/mobile_strings.xml app-mobile/src/main/res/values-fi/mobile_strings.xml`; luo englanninkieliset `values/strings.xml` ja `values/mobile_strings.xml` sekä ruotsinkieliset `values-sv/…` **samoilla avaimilla samassa järjestyksessä** (246 + 72 avainta). `translatable="false"`-rivit (app_name, placeholderit `--:--:--`) vain `values/`-tiedostoon, ei fi/sv-tiedostoihin. Säätekstit (`wt_*`) idiomaattisesti (en "Light rain showers", sv "Lätta regnskurar"). Tarkista, mitkä avaimet ovat käytössä mobiilissa (`grep -r "R.string.<avain>"`), ja poista käyttämättömät kaikista kolmesta (merkitse raporttiin, mitä poistit).

**Task 8 (F2, warnings/weather):** `FmiWarningsClient`/`FmiWarningDetailsClient` lukevat `AppLocale.fmiInfoKey`-kentän ja putoavat `info_fi`:hin, jos tyhjä → testi `FmiWarningLanguageTest` (JSON-näyte, jossa `info_sv` tyhjä). Varoitustyyppien/-tasojen nimet (`WarningDisplay`, `WeatherWarningNotifier`, `FmiWarningFilter`) rakenteiksi; `FinnishRegions`/`FmiCounties` saavat sv-nimet resursseina (`region_uusimaa` …) ja en = fi-nimi; ruuvi-/db-/clock-luokkien harvat tekstit resursseihin; `WeatherWarning`-välimuistiin kieli.

---

### Task 9: LocaleChangedReceiver (kielen vaihto lennossa)

**Files:**
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/LocaleChangedReceiver.kt`
- Modify: `app-mobile/src/main/AndroidManifest.xml`

- [ ] **Step 1: Toteutus**

```kotlin
package org.jrs82.fsclock.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jrs82.fsclock.mobile.widget.DepartureWidget
import org.jrs82.fsclock.mobile.widget.ElectricityWidget
import org.jrs82.fsclock.mobile.widget.StepsWidget
import org.jrs82.fsclock.mobile.widget.WeatherWidget

/** Kanavien nimet ja widgetien tekstit eivät päivity itsestään kielen vaihtuessa. */
class LocaleChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCALE_CHANGED) return
        val app = context.applicationContext
        Notifications.ensureChannels(app)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WeatherWidget().updateAll(app)
                ElectricityWidget().updateAll(app)
                StepsWidget().updateAll(app)
                DepartureWidget().updateAll(app)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
```

Manifest:

```xml
<receiver android:name="org.jrs82.fsclock.mobile.LocaleChangedReceiver" android:exported="false">
    <intent-filter><action android:name="android.intent.action.LOCALE_CHANGED" /></intent-filter>
</receiver>
```

- [ ] **Step 2: Tarkista, että `Notifications.ensureChannels` käyttää resurssinimiä (Task 6) ja että prosessin välimuistit (nimipäivä Task 5, varoitukset Task 8) on avaimistettu kielellä.**
- [ ] **Step 3: Build, commit `i18n: LocaleChangedReceiver`**

---

### Task 10: Integraatio, parity- ja hardcoded-testit, lint

**Files:**
- Create: `app-mobile/src/test/java/org/jrs82/fsclock/mobile/StringResourceParityTest.kt`
- Create: `app-mobile/src/test/java/org/jrs82/fsclock/mobile/HardcodedFinnishTest.kt`
- Create: `tools/i18n_parity.py` (sama logiikka komentoriviltä)

- [ ] **Step 1: Merge** haarat `i18n-settings`, `i18n-transit`, `i18n-workout`, `i18n-home`, `i18n-main`, `i18n-existing`, `i18n-warnings` → `i18n` (`git merge --no-ff`). Ratkaise konfliktit (odotettavissa vain sanastossa ja mahdollisesti `Notifications.kt`/`ComposeCommon.kt`:ssä).
- [ ] **Step 2: Parity-testi**

```kotlin
package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StringResourceParityTest {
    private val resDirs = listOf(File("src/main/res"), File("core/res"))
    private val locales = listOf("values", "values-fi", "values-sv")

    private fun keys(dir: File): Map<String, Map<String, String>> {
        val out = HashMap<String, Map<String, String>>()
        dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }?.forEach { f ->
            val text = f.readText()
            val re = Regex("<(string|plurals)\\s+name=\"([^\"]+)\"([^>]*)>([\\s\\S]*?)</\\1>")
            re.findAll(text).forEach { m ->
                val kind = m.groupValues[1]; val name = m.groupValues[2]
                val attrs = m.groupValues[3]; val body = m.groupValues[4]
                val args = Regex("%(\\d+)\\$[sdf.0-9]+").findAll(body).map { it.groupValues[1] }.toSortedSet().joinToString(",")
                out["$kind:$name"] = mapOf("file" to f.name, "args" to args, "translatable" to (!attrs.contains("translatable=\"false\"")).toString())
            }
        }
        return out
    }

    @Test fun everyTranslatableKeyExistsInAllLocalesWithSameArgs() {
        for (res in resDirs) {
            val en = keys(File(res, "values"))
            val fi = keys(File(res, "values-fi"))
            val sv = keys(File(res, "values-sv"))
            val missing = ArrayList<String>()
            for ((k, v) in en) {
                if (v["translatable"] == "false") continue
                if (k !in fi) missing.add("fi puuttuu: $k (${v["file"]})")
                if (k !in sv) missing.add("sv puuttuu: $k (${v["file"]})")
                fi[k]?.let { if (it["args"] != v["args"]) missing.add("fi args: $k") }
                sv[k]?.let { if (it["args"] != v["args"]) missing.add("sv args: $k") }
            }
            for (k in fi.keys) if (k !in en) missing.add("fi ylimaarainen: $k")
            for (k in sv.keys) if (k !in en) missing.add("sv ylimaarainen: $k")
            assertTrue(missing.joinToString("\n"), missing.isEmpty())
        }
    }

    @Test fun finnishIsNotJustEnglishCopy() {
        for (res in resDirs) {
            val en = keys(File(res, "values")); val fi = keys(File(res, "values-fi"))
            // Sama teksti fi ja en → todennäköisesti kääntämättä. Sallitaan lyhyet (≤3 merkkiä), yksiköt ja numerot.
            val same = en.keys.filter { k -> fi[k] != null && rawText(res, "values", k) == rawText(res, "values-fi", k) && rawText(res, "values", k).length > 3 && !rawText(res, "values", k).matches(Regex("[\\d\\s%°.,:/–-]+|[A-Z]{2,5}")) }
            assertEquals("fi = en: $same", 0, same.size)
        }
    }

    private fun rawText(res: File, locale: String, key: String): String {
        val name = key.substringAfter(':'); val kind = key.substringBefore(':')
        for (f in File(res, locale).listFiles { f -> f.name.startsWith("strings") }!!) {
            val m = Regex("<$kind\\s+name=\"$name\"[^>]*>([\\s\\S]*?)</$kind>").find(f.readText()) ?: continue
            return m.groupValues[1].trim()
        }
        return ""
    }
}
```

Sallittujen identtisten tekstien lista (brändit, `OK`, `Health Connect`, `Open-Meteo`) merkitään resursseissa `translatable="false"` tai jätetään `values-fi`-tiedostoon kommentilla `<!-- sama kaikissa -->` — testi lukee kommentin edeltävältä riviltä ja ohittaa.

- [ ] **Step 3: Hardcoded-testi** (`HardcodedFinnishTest`): lukee `src/main/java` ja `core/java`, etsii literaalit, joissa on ä/ö tai sanaston suomenkielinen termi, ohittaa rivit joissa `Log.`, `http`, `//`, `*`, `@Preview`, `Regex(`, `"fi"`, `"info_fi"`, `"suomi"`, ja sallii tiedostolistan `ALLOWED = setOf("KuntaList.kt", "FinaviaAirports.kt", "FmiCounties.java" /* datalistat, joilla on resurssivastine */)`. Odotus: 0 osumaa; virheviesti listaa tiedosto:rivi.
- [ ] **Step 4: Aja kaikki**: `./gradlew :app-mobile:compileDebugKotlin :app-mobile:testDebugUnitTest :app-mobile:lintDebug`. Lint-raportti: `MissingTranslation` = 0, `ExtraTranslation` = 0.
- [ ] **Step 5: Commit `i18n: parity- ja hardcoded-testit, lint puhdas`.**

---

### Task 11: Laitetarkistus kolmella kielellä

- [ ] `assembleRelease` → asenna Pixel 10 Pro:lle (`adb -s 192.168.1.108:…`) tai emulaattoriin.
- [ ] Vaihda laitteen kieli `adb shell "settings put system system_locales sv-FI"` ei toimi ilman root → käytä Asetukset → Järjestelmä → Kielet, tai sovelluskohtainen kieli (Android 13+): Asetukset → Sovellukset → Arkikeskus → Kieli.
- [ ] Käy läpi fi, sv, en: etusivu (kello, nimipäivä, sää, sähkö, varoitukset), 7 vrk sää, sähkö (tänään/huomenna/vertailu), lähilähdöt + reittihaku (pysäkkinimet sv), lenkki (aloitus/lopetus/ilmoitus), askeleet, uutiset, lennot, asetukset (kaikki osiot), widgetit (4), ilmoituskanavat (Asetukset → Ilmoitukset).
- [ ] Kuvakaappaukset `screenshots/i18n/<kieli>_<nakyma>.png` (≤540 px, `MSYS_NO_PATHCONV=1`).
- [ ] Kirjaa löydökset ja korjaa; commit `i18n: laitetestin korjaukset`.

---

### Task 12: Versio, Codex-katselmuspyyntö

- [ ] `build.gradle`: `versionCode 88`, `versionName '2.27.0'`. Commit `release: 2.27.0 (versionCode 88) - englanti ja ruotsi`? **EI** — versiocommit vasta Codexin katselmuksen jälkeen; nyt vain bumppi työhaaraan commitilla `i18n: versio 2.27.0 (88)`.
- [ ] Kirjoita `review-request-2.27.0-kielet.md` repojuureen: tausta ja spec-linkki; arkkitehtuurimuutos (AppLocale, resurssirakenne, lint, receiver); tiedostoittain mitä muuttui ja miksi; API-kielimuutokset (FMI info_*, Digitransit Accept-Language, almanakka type); testit (uudet + muutetut, lukumäärät ennen/jälkeen); **kaikki käännökset** taulukkona `avain | fi | sv | en` tiedostoittain (generoidaan skriptillä `tools/i18n_table.py`); epävarmat käännökset erikseen; tunnetut rajaukset (uutiset fi, KuntaList fi, en-pysäkkinimet fi); katselmoijalle kysymykset (P1: suomi ei saa regressoitua; P2: ruotsin idiomaattisuus; P3: koodin rakennemuutokset).
- [ ] Handoff `Downloads\arkikeskus\mobiili\handoffit\HANDOFF_2.27.0_kielet_9_10.md` ja muistipäivitys.
