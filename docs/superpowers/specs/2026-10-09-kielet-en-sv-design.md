# Arkikeskus 2.27.0 — englanti ja ruotsi (design)

Päiväys 9.10.2026. Koskee vain julkista mobiilisovellusta (`app-mobile`), ei tablettia eikä ARK-Tablettia.

## Tavoite

Sovellus näkyy puhelimen kielen mukaan suomeksi, ruotsiksi tai englanniksi:

| Puhelimen kieli (locale-listan paras osuma) | Sovelluksen kieli |
|---|---|
| `fi`, `fi-FI` | suomi |
| `sv`, `sv-FI`, `sv-SE` | ruotsi (suomenruotsi) |
| mikä tahansa muu | englanti (brittienglanti) |

Ei omaa kieliasetusta sovelluksessa. Android 13+ sovelluskohtainen kieli järjestelmäasetuksista toimii (`localeConfig`).

## Resurssirakenne

- `values/` = **englanti** (oletus). `values-fi/` = suomi. `values-sv/` = ruotsi.
- Nykyiset suomenkieliset `values/*strings*.xml` siirretään sellaisenaan `values-fi/`-kansioon; `values/` saa englannin.
- Uudet tekstit puretaan aihekohtaisiin tiedostoihin, jotta rinnakkaistyö ei törmää:
  `strings_home.xml`, `strings_settings.xml`, `strings_transit.xml`, `strings_workout.xml`, `strings_places_steps.xml`, `strings_news.xml`, `strings_flights.xml`, `strings_backup.xml`, `strings_notifications.xml`, `strings_widgets.xml`, `strings_device.xml`, `strings_warnings.xml`, `strings_common.xml`.
  Sama tiedostonimi kaikissa kolmessa kansiossa, samat avaimet samassa järjestyksessä.
- Avainkäytäntö: `<alue>_<asia>` pienillä kirjaimilla, esim. `transit_fav_add`, `settings_vat_title`. Muotoiluargumentit aina numeroituina (`%1$s`, `%2$d`). Monikot `<plurals>`-resursseina, kun lukumäärä vaihtelee (en vaatii one/other, fi ja sv samoin).
- `android:localeConfig="@xml/locales_config"` manifestiin, `xml/locales_config.xml` listaa `en`, `fi`, `sv`.
- `build.gradle`: `androidResources { localeFilters += ["en", "fi", "sv"] }`, jotta kirjastojen (appcompat, material) muunkieliset tekstit eivät vuoda ja APK pienenee.
- Sovelluksen nimi `Arkikeskus` pysyy samana kaikissa kielissä.

## Turvaverkko suomelle

1. Lint: `MissingTranslation` ja `ExtraTranslation` vakavuudella `fatal` (`lint.xml`), `abortOnError` pysyy false mutta `fatal` katkaisee silti release-buildin → suomi ei voi puuttua.
2. JVM-testi `StringResourceParityTest`: lukee `values`, `values-fi`, `values-sv` -kansioiden kaikki `strings*.xml` ja varmistaa, että avainjoukot ovat identtiset ja muotoiluargumenttien määrät/tyypit täsmäävät kielittäin. Tarkistaa myös, ettei `values-fi` sisällä englanninkielistä tekstiä, joka on identtinen `values`-tekstin kanssa (paitsi sallittu lista: brändit, yksiköt, URL:t).
3. `HardcodedText`-lint varoitukseksi, ja Kotlin-koodille oma kevyt tarkistus (`HardcodedFinnishTest`): ei `"…ä…"`/`"…ö…"`-literaaleja UI-tiedostoissa sallittujen poikkeusten (Log, URL, avaimet) ulkopuolella.

## Koodimuutokset

### Compose-näytöt
`stringResource(R.string.x)` / `pluralStringResource`. Ei-composable-apufunktiot, jotka palauttavat tekstiä (esim. `fun label(...)`) saavat `Context`-parametrin tai palauttavat `@StringRes Int`.

### Fragmentit, palvelut, ilmoitukset, widgetit
`context.getString(...)`. Glance-widgetit lukevat tekstit `LocalContext`in kautta.

### Context-vapaat logiikkaluokat
`WarningDisplay`, `FlightDisplay`, `WorkoutStats`, `ElectricityVat`-tekstit, `FmiWarningFilter`, `HslAlertNotifier`, `WeatherWarningNotifier`, `StepGoalNotifier`, `DepartureReminder`, `NewsProfile`, `FinnishRegions`, `FmiCounties`, `FinnishHolidays`:
- Logiikka palauttaa rakenteisen tuloksen (enum / data class / `@StringRes Int` + argumentit). Muotoilu tekstiksi tapahtuu UI:ssa tai ohuessa `Context`-kerroksessa.
- Olemassa olevat JVM-testit muutetaan vertaamaan rakenteeseen tai resurssi-id:hen, ei suomenkieliseen merkkijonoon. Testien lukumäärä ei saa laskea.

### Muotoilu (Locale)
- Uusi `AppLocale`-apuri: `AppLocale.current(context) = context.resources.configuration.locales[0]`. Kaikki 50 `Locale("fi","FI")`-käyttöä, jotka tuottavat käyttäjälle näkyvää tekstiä (viikonpäivä, kuukausi, desimaali, `NumberFormat`), vaihdetaan siihen.
- `Locale.US`/`ROOT` konekäyttöön (API-parametrit, CSV, tiedostonimet) pysyvät ennallaan.
- Kellonaikakuviot (`HH:mm`, `HH.mm`) pysyvät ennallaan kaikissa kielissä. Aikavyöhyke pysyy `Europe/Helsinki`.
- Päivämäärän muoto: fi `ma 9.10.`, sv `mån 9.10.`, en `Mon 9 Oct`. Toteutus `DateTimeFormatter`/`SimpleDateFormat` kuviolla kieltä kohti (`strings`-resurssi `date_pattern_weekday_short` tms.), ei Localen oletuskuviolla.
- Yksiköt: `c/kWh`, `°C`, `m/s`, `km`, `hPa`, `mm` samat kaikissa; en-tekstissä desimaalipiste.

### Dynaaminen sisältö
| Sisältö | fi | sv | en |
|---|---|---|---|
| Säätekstit (`WeatherTextFormatter`, resursseissa) | käännetään | käännetään | käännetään |
| FMI-varoitukset | `info_fi` | `info_sv` | `info_en` (feed tarjoaa kaikki kolme; tarkistettu 9.10.) |
| Varoitustyypit, -tasot, maakunnat (`FmiCounties`, `FinnishRegions`) | nykyiset | viralliset ruotsinkieliset (Nyland, Egentliga Finland, Birkaland …) | suomenkieliset nimet (Uusimaa …), Åland |
| Liputus- ja juhlapäivät (`FinnishHolidays`, vapaapaivat.com antaa vain fi) | API-nimi | nimi→resurssi-kartta (esim. "Itsenäisyyspäivä"→`holiday_independence`), tuntematon näytetään suomeksi | sama |
| Nimipäivät (almanakka-API, `type`) | `suomi` | `ruotsi` (suomenruotsalainen kalenteri) | `suomi` |
| Joukkoliikenne (Digitransit) | `Accept-Language: fi` | `Accept-Language: sv` → ruotsinkieliset pysäkkinimet (tarkistettu: "Centralstationen") | `fi` (englanninkielisiä nimiä ei ole) |
| Geokoodaus (`lang=`) | fi | sv | fi |
| Uutiset, Teksti-TV, lennot (Finavia), kelikamerat (nimet), Ruuvi | sisältö ennallaan; vain kehys käännetään |
| Askel-HTML-vienti, lenkin jakokuva | käännetään |
| Ilmoituskanavien nimet, widgetien nimet ja kuvaukset | käännetään |

### Kielen vaihto lennossa
`LocaleChangedReceiver` (`ACTION_LOCALE_CHANGED`): luo ilmoituskanavat uudelleen uusilla nimillä ja päivittää kaikki Glance-widgetit. Compose-aktiviteetit luodaan uudelleen Androidin toimesta. Prosessin sisäiset tekstivälimuistit (nimipäivä, varoitukset) avaimistetaan kielellä.

## Käännösperiaatteet

- **Ruotsi**: suomenruotsi, ei riikinruotsi. HRT (ei HSL), börsel/spotpris, Text-TV, namnsdag, flaggdag, Helsingfors/Esbo/Vanda/Tammerfors. Kohteliaisuus: du-muoto. Lyhenteet: `kl.` vain tarvittaessa; viikonpäivät `mån tis ons tors fre lör sön`.
- **Englanti**: brittioikeinkirjoitus (`favourites`, `colour`, `metres`), 24 h -kello, `°C`. Suomalaiset erisnimet sellaisenaan (FMI = Finnish Meteorological Institute ensimmäisellä kerralla), HSL pysyy HSL.
- Käännökset idiomaattisia: lauseet muotoillaan uudelleen kohdekielen tapaan, ei sanasta sanaan. Nappitekstit lyhyitä. Sama termi samalle asialle läpi sovelluksen (sanasto `docs/superpowers/specs/2026-10-09-sanasto.md`, jota kaikki toteuttajat käyttävät).
- Resurssien `translatable="false"` vain brändeille, URL:eille ja yksiköille.

## Testaus

- 282 nykyistä testiä pysyvät vihreinä (päivitettyinä rakenteisiin tuloksiin).
- Uudet: `StringResourceParityTest`, `HardcodedFinnishTest`, `AppLocaleTest` (fi/sv/muu → odotettu locale), `FmiWarningLanguageTest` (info_sv/en valinta ja fallback fi:hin kun kenttä tyhjä), nimipäivä-`type`-valinta, holiday-nimikartta.
- Lint release-variantille puhdas `MissingTranslation`/`ExtraTranslation`.
- Laite/emulaattori: kolme kieltä, käydään läpi etusivu, sää, sähkö, joukkoliikenne, lenkki, askeleet, asetukset, widgetit, ilmoitukset. Kuvakaappaukset `screenshots/i18n/` (≤540 px).

## Julkaisu

- Versio 2.27.0, versionCode 88, haara `i18n`. Commitit ASCII, ei AI-mainintoja, ei Co-Authored-By.
- Ennen julkaisua: `review-request-2.27.0-kielet.md` Codexille (koodimuutosten kuvaus tiedostoittain, kaikki käännökset fi/sv/en-taulukkona, testit, tunnetut rajoitukset). Julkaisu vasta Codexin katselmuksen ja käyttäjän päätöksen jälkeen.

## Rajaukset

- Ei backend-muutoksia (uutiset pysyvät suomeksi).
- Ei kuntaluettelon (`KuntaList`) ruotsinnosta tässä versiossa; haku toimii geokoodauksen `lang=sv`-tuloksilla.
- Ei verkkosivun/wikin kieliversioita.
