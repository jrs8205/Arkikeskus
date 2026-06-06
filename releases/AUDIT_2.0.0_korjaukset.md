# Arkikeskus 2.0.0-mobile — KORJAUSAUDITTI (Codexin löydösten korjaukset)

Tämä dokumentti listaa, miten Codexin edellisen katselmoinnin **10 löydöstä korjattiin**. Pyydä Codexia
**tarkistamaan jokainen korjaus koodista**: onko se oikea, täydellinen, eikö se tuo uutta bugia tai regressiota.

> **Konteksti:** Repo `C:\Android\projects\FsClock`, haara `mobile`. Compose-koodi
> `app-mobile\src\main\java\org\jrs82\fsclock\mobile\`. Jos sinulla on tiedostopääsy (esim. Codex CLI),
> **lue varsinaiset tiedostot ja varmista korjaukset koodista** — älä luota pelkkään tähän dokumenttiin.
> Edellinen täysauditti: `releases\AUDIT_2.0.0.md`.

## Ohje tarkistajalle (Codex)
Käy jokainen korjaus (#1–#10) läpi koodista ja arvioi:
1. **Korjaako se alkuperäisen vian?** (Kuvattu kohdassa "Codex löysi".)
2. **Onko se oikein toteutettu?** (Säikeistys, elinkaari, null-tapaukset, Compose-tila oikeasta säikeestä.)
3. **Tuoko se uuden ongelman / regressiota?** (Esim. liikaa verkkoa/akkua, jumi, kaksoiskutsu.)
Raportoi prioriteetilla (🔴/🟡/⚪): korjaus OK / korjaus puutteellinen (miksi + ehdotus) / uusi ongelma.
Vastaa suomeksi. Korjauscommitit: `a6ada8d`, `aea4327`, `5c161de`, `3f37d11`, `c88186c` (väli `12487b8..HEAD`).

---

## 🔴 1. Automaattinen päivitysväli — KORJATTU (commit `a6ada8d`)
- **Codex löysi:** päivitysväli-asetus ei käynnistänyt mitään; data vanheni rajatta.
- **Korjaus:** `ComposeMainScreen.kt` `ComposeMainScreen()` — uusi `LaunchedEffect(lifecycleOwner)` +
  `lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { while(true){ delay(väli) ; refreshTick++ } }`.
  Väli luetaan joka kierroksella prefseistä (`KEY_UPDATE_INTERVAL_MINUTES`), `coerceAtLeast(1)`. Silmukka käy
  vain etualalla (repeatOnLifecycle) → ei taustaverkkoa/akkua.
- **Tarkista:** silmukka pysähtyy taustalla; väliarvo päivittyy asetusmuutoksella; ei pinoa useaa silmukkaa.

## 🔴 2. Sijainti vain viimeisin tunnettu — KORJATTU TURVALLISESTI (commit `aea4327`)
- **Codex löysi:** vain `getLastKnownLocation()`; null (puhdas asennus) / vanhentunut (väärä kaupunki);
  `force=true` ei hae tuoretta.
- **Korjaus:** `ComposePlacesSteps.kt` uusi `deviceLocation(context, force)`: **nopea polku** = jos viimeisin
  tunnettu < 10 min → palauta heti; muuten **aktiivinen** `FusedLocationProviderClient.getCurrentLocation`
  (Priority BALANCED, maxUpdateAge 10 min, timeout 8 s, `suspendCancellableCoroutine`, fallback vanhaan jos
  haku epäonnistuu). `force=true` (Päivitä-nappi) **ohittaa nopean polun → aina aktiivinen tuore haku**.
  `ComposeMainScreen.maybeRefreshDeviceLocation` kutsuu `deviceLocation(context, force)`.
- **Tarkista:** nopea polku säilyy (tuore last-known → ei odotusta); aktiivihaku peruuntuu oikein (cts.cancel
  invokeOnCancellationissa, `cont.isActive`-vartija); ei tuplaresumea; Päivitä hakee tuoreen.

## 🔴 3. Fragmentti/sektio palautuu eri tilaan recreatessa — KORJATTU (commit `a6ada8d`)
- **Codex löysi:** `section` tavallinen `remember` → Activityn uudelleenluonnissa (esim. dynamic-color-vaihto)
  sektio→HOME, hostattu fragmentti orpoutuu.
- **Korjaus:** `ComposeMainScreen.kt` `section` → `rememberSaveable(stateSaver = HomeSectionSaver)`
  (enum → name → enum, fallback HOME). Container-id oli jo `rememberSaveable`.
- **Tarkista:** sektio säilyy teemanvaihdossa/prosessin palautuksessa → fragmentti palautuu samaan näkyvään
  konttiin; ei orpofragmenttia/kaatumista. (Onko container-id vakaa myös kun sektio palautuu?)

## 🟡 4. BLE-skannaus jää päälle — KORJATTU (commit `5c161de`)
- **Codex löysi:** `RuuviRepository.start()` ilman `stop()`ia → skanneri jää päälle (myös tausta).
- **Korjaus:** `ComposeHomeContent.kt` uusi `rememberRuuviScanTick(ruuvi)`: lisää listenerin, käynnistää
  skannauksen `ON_START`issa, **pysäyttää `ON_STOP`issa ja `onDispose`ssa**. Käytössä `HomeSensorsWidget` +
  `SensorsSection`. `SettingsScreen.kt` `RuuviScanDialog` pysäyttää skannauksen `onDispose`ssa.
- **Tarkista:** skannaus käy vain kun sensorinäkymä auki + etualalla; pää- ja asetus-Activityn skannauksen
  start/stop eivät jää ristiriitaan (jaettu singleton-scanner); ei jää päälle taustalle.

## 🟡 5. Asetusmuutokset eivät päivity etusivulle — KORJATTU (commit `a6ada8d`)
- **Codex löysi:** etusivu kuunteli vain widget-avaimia; uutislähteet/sähköraja/anturinimet jäivät vanhaksi.
- **Korjaus:** `ComposeMainScreen.kt` `ON_RESUME`-tarkkailija kasvattaa nyt **aina** `refreshTick`iä (ei vain
  sijainnin vaihtuessa). Asetuksista (eri Activity) palatessa → ON_RESUME → refresh → kaikki LocalRefreshTickiä
  lukevat sektiot/kortit hakevat tuoreet (uutiset forced, sähkö re-read, sää, liikenne). In-app-sektiovaihto
  EI laukaise ON_RESUMEa → uutisten prosessivälimuisti säilyy (käyttäjän vaatimus).
- **Tarkista:** uutislähde pois → palatessa katoaa; sähköraja muuttuu → näkyy; ei liiallista verkkoa
  (resume ei jatkuvaa). Anturinimi päivittyy seuraavalla mittauksella (ei refreshTickistä) — riittääkö?

## 🟡 6. Ennuste voi yhdistää kahden paikan tiedot — KORJATTU (commit `aea4327`)
- **Codex löysi:** `place` muistettu ilman avainta → sijainnin vaihtuessa Open-Meteo/otsikko vanhalla paikalla.
- **Korjaus:** `ComposeHomeContent.kt` `ForecastSection` `place = remember(refresh) { displayPlace(prefs) }`
  → refresh (sijaintipäivitys) lukee paikan uudelleen, FMI + Open-Meteo + otsikko samalla paikalla.
- **Tarkista:** riittääkö avaimitus refreshillä? (Open-Meteo hakee yhä nimellä, ei koordinaatilla — onko
  vielä mahdollisuus eri kaupunkiin jos nimi monitulkintainen?)

## 🟡 7. Health Connect -pyynnöt väärässä järjestyksessä — KORJATTU (commit `c88186c`)
- **Codex löysi:** callback-pyyntö ei peruunnu LaunchedEffectin mukana → nopea välilehtivaihto → vanha vastaus
  kirjoittaa uuden välilehden `historyText`in päälle.
- **Korjaus:** `ComposePlacesSteps.kt` `StepsSection` historian `LaunchedEffect`: **generaatiovartija**
  `historyGen[0]++` per pyyntö; HC-callback kirjoittaa `historyText`in vain jos `myGen == historyGen[0]`
  (yhä uusin). Non-HC-polku suojattu samalla + withContext-peruutus.
- **Tarkista:** vartija kattaa kaikki callback-kirjoitukset; gatherHcReportThenExport (HTML-vienti) on erillinen
  kertakäyttö — tarvitseeko sekin vartijan?

## 🟡 8. Uutisnäkymä pitää poistettuja View-olioita — KORJATTU KEVYESTI (commit `c88186c`)
- **Codex löysi:** ImageLoaderin executor-tehtävä piti vahvaa viitettä kohde-ImageViewhin → 50 riviä +
  Activity voi jäädä muistiin pitkän latausjonon ajaksi.
- **Korjaus:** `ImageLoader.java` `load()`: kohde-ImageView nyt `WeakReference<ImageView>`; callback hakee
  `ref.get()` ja kirjoittaa vain jos != null ja tag täsmää. (Kevyt korjaus; ei LazyColumn-refaktoria — sovittu.)
- **Tarkista:** ei piirrä väärää kuvaa; bittikartta cachetaan silti; ei NPE; riittääkö WeakReference vai jääkö
  NewsSection silti luomaan 50 ImageViewta heti (erillinen tehoasia, ei vuoto)?

## ⚪ 9. "Viimeisin sääpäivitys" -aikaleima — KORJATTU (commit `3f37d11`)
- **Codex löysi:** Compose ei tallentanut `setLastSuccessfulFmiUpdate()`-arvoa.
- **Korjaus:** `ComposeHomeContent.kt` `HomeWeatherWidget` + `ForecastSection`: onnistuneen `fetchHome`-haun
  jälkeen `if (fresh.fetchedAt > 0) SettingsManager.get().setLastSuccessfulFmiUpdate(fresh.fetchedAt)`.
- **Tarkista:** aikaleima päivittyy; ei kirjoiteta nollaa/virhearvoa.

## ⚪ 10. GPS-mittari leikkautuu pienessä vaakanäkymässä — KORJATTU (commit `3f37d11`)
- **Codex löysi:** sisältö ei vieri, mittari kiinteä 340.dp.
- **Korjaus:** `ComposeExtraSections.kt` `SpeedometerSection` → `verticalScroll`; mittari
  `fillMaxWidth().widthIn(max = 340.dp).aspectRatio(1f)` (skaalautuu leveyteen, pysyy neliönä).
- **Tarkista:** ei leikkaudu vaaka/kapea; mittari ei veny; vieritys toimii.

---

## Build & APK
- Build OK (debug + release). Esikatselu-APK: `releases\_compose-preview\Arkikeskus-codex-korjaukset-esikatselu.apk`.
- Versio yhä `1.15.0-mobile`. Hyväksynnän + tämän tarkistuksen jälkeen → bump `2.0.0-mobile` (versionCode 50) + push + gh release.
