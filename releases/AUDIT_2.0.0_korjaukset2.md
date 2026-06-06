# Arkikeskus 2.0.0-mobile — KORJAUSAUDITTI 2 (Codexin 2. katselmuksen hienosäädöt)

Codexin 2. katselmus: 5 korjausta OK (#1, #3, #7, #8, #9), 5 vaati hienosäätöä (#2, #4, #5, #6, #10),
ei kriittisiä. **Kaikki 5 hienosäätöä on nyt tehty.** Pyydä Codexia tarkistamaan ne koodista.

> **Konteksti:** Repo `C:\Android\projects\FsClock`, haara `mobile`. Compose-koodi
> `app-mobile\src\main\java\org\jrs82\fsclock\mobile\`. **Jos sinulla on tiedostopääsy, lue varsinaiset
> tiedostot ja varmista koodista.** Edelliset auditit: `AUDIT_2.0.0.md`, `AUDIT_2.0.0_korjaukset.md`.
> Hienosäätöcommitit: **`158edd6`** (#2/#6/#7/#10), **`0c090d9`** (#4/#5). Väli `bde1968..HEAD`.

## Ohje tarkistajalle (Codex)
Käy hienosäädöt (#2, #4, #5, #6, #10) läpi koodista: korjaako se aiemman puutteen, onko oikein, tuoko uutta
ongelmaa. Raportoi 🔴/🟡/⚪. Vastaa suomeksi. (Aiemmin OK:t #1/#3/#7/#8/#9 eivät muuttuneet paitsi #7 alla.)

---

## 🟡 2. Sijainnin päivitys — HIENOSÄÄDETTY (commit `158edd6`)
- **Codex 2:** epäonnistuneessa haussa palautettiin rajattoman vanha lastKnown; force hyväksyi ≤10 min cachen;
  ei tulevaisuus-/tarkkuussuodatusta.
- **Hienosäätö:** `ComposePlacesSteps.kt` `deviceLocation`/`requestCurrentLocation`:
  - Uusi `isFreshEnough(loc, now)` = ikä **0..10 min** (hylkää tulevaisuuden ja yli 10 min).
  - Fallback aktiivihaun epäonnistuessa: `last` VAIN jos `isFreshEnough` → muuten `null` (ei väärää kaupunkia).
  - `force=true` (Päivitä): `requestCurrentLocation(force=true)` → `setMaxUpdateAgeMillis(0)` + `PRIORITY_HIGH_ACCURACY`
    → oikeasti tuore lukema.
- **Tarkista:** force hakee aina tuoreen; ei palauta ammoin vanhaa/tulevaisuutta; nopea polku säilyy tuoreella.
  (Tarkkuuskynnystä ei lisätty: kaupunkitason sää sietää verkkosijainnin km-tarkkuuden — riittääkö?)

## 🟡 4. BLE-skannauksen elinkaari — HIENOSÄÄDETTY (commit `0c090d9`)
- **Codex 2:** asetusdialogi pysäytti skannauksen vain onDisposessa → koti-painike/näytön lukitus jätti BLE:n
  päälle. Singleton-skannerissa ei käyttäjälaskentaa.
- **Hienosäätö:** `SettingsScreen.kt` `RuuviScanDialog` käyttää nyt samaa **ON_START/ON_STOP-sidontaa** (start
  ON_START, **stop ON_STOP** + onDispose). (Pää­näkymä jo `rememberRuuviScanTick` ColumnHomeContentissa.)
- **EI TOTEUTETTU: acquire/release-laskenta.** Perustelu: sovelluksessa **ei ole rinnakkaisia sensorikuluttajia**
  — etusivun anturikortti, Anturit-sektio ja asetusdialogi ovat aina erillisissä tiloissa/Activityissä
  (sekventiaalisia, eivät päällekkäin), joten yksi näkymä ei pysäytä toista käytännössä. **Arvioi: onko tämä
  oletus pitävä, vai kannattaako refcount silti lisätä?**
- **Tarkista:** skannaus pysähtyy taustalle/lukitukseen myös asetusdialogissa; pää- ja asetus-skannaus eivät jää
  ristiriitaan (sekventiaaliset).

## 🟡 5. Asetuksista paluun päivitys — HIENOSÄÄDETTY (commit `0c090d9`)
- **Codex 2:** ON_RESUME virkisti, mutta osa arvoista ei seurannut (per-lähde nimi/URL, poistettu widget,
  anturinimet, sähköraja); lisäksi jokainen ON_RESUME saattoi pakottaa useita verkkohakuja.
- **Hienosäätö:**
  - `ComposeMainScreen.kt`: uusi **data-avainkuuntelija** (SharedPreferences.OnSharedPreferenceChangeListener):
    `isHomeDataPrefKey(key)` → `refreshTick++` (+ `invalidateHomeNewsCache()` jos uutisavain). Kohdistettu →
    asetusmuutos virkistää HETI, riippumatta resume-throttlesta.
  - ON_RESUME-virkistys **throttlattu 30 s** (`sLastResumeRefreshMs`) → selaimesta/lupadialogista paluu ei spämmää.
  - `ComposeHomeWidgets.kt`: `isHomeNewsListKey`/`isHomeDataPrefKey`/`invalidateHomeNewsCache`; per-lähde-kortin
    `feed = remember(feedId, refresh)` (nimi/URL päivittyy).
  - `ComposeHomeContent.kt`: etusivun korttilista kuuntelee myös `isHomeNewsListKey` (poistettu/lisätty lähde →
    lista päivittyy); `SensorsCard`/`SensorsSection` `buildSensors` avaimitettu refreshillä (anturinimet).
- **Tarkista:** uutislähde pois → katoaa heti; oma syöte nimi/URL päivittyy; poistettu syöte-kortti katoaa;
  sähköraja päivittyy; ei verkkospämmiä quick-resumessa; data-avainlista kattaa kaikki relevantit avaimet.

## 🟡 6. Ennusteen paikkakunta — HIENOSÄÄDETTY (commit `158edd6`)
- **Codex 2:** paikan nimi päivittyi mutta säätila ei vaihtunut paikan mukana → vanha sää uuden otsikon alla.
- **Hienosäätö:** `ComposeHomeContent.kt` `ForecastSection`: `weather`/`openMeteo` **avaimitettu paikalla**
  (`remember(place) { ... }`) → paikan vaihtuessa re-seedataan (seed = sLastWeather/peek vastaa nykyistä
  koti-/näyttöpaikkaa).
- **Tarkista:** paikan vaihtuessa ei näy vanhan paikan säätä uuden otsikon alla. (Open-Meteo hakee yhä nimellä —
  jääkö teoreettinen monitulkintaisuus? Riittääkö avaimitus vai pitäisikö nollata null ennen hakua?)

## 🟡/⚪ 7. HC-generaatio — PIENI JÄÄNNÖS KORJATTU (commit `158edd6`)
- **Codex 2:** sukupolvi kannattaisi kasvattaa ennen aikaisia return-kohtia, jotta Tänään-välilehdelle siirtyminen
  mitätöi piilossa olevan historiakutsun.
- **Hienosäätö:** `ComposePlacesSteps.kt` `StepsSection`: `historyGen[0]++` siirretty **ennen** `if (tab==0) return`a.
- **Tarkista:** Tänään-välilehdelle siirtyminen invalidoi vireillä olevan historiakutsun.

## 🟡 10. GPS-mittarin koko — HIENOSÄÄDETTY (commit `158edd6`)
- **Codex 2:** modifier-järjestys `fillMaxWidth().widthIn(max=340)` voi estää 340 dp -katon leveällä; GPS/GNSS-
  kuuntelijat poistettiin vain onDisposessa → GPS jatkoi taustalla.
- **Hienosäätö:** `ComposeExtraSections.kt` `GpsSpeedContent`:
  - Modifier-järjestys → `widthIn(max = 340.dp).fillMaxWidth().aspectRatio(1f)`.
  - Kuuntelijat (LocationManager + GnssStatus) **sidottu ON_START/ON_STOP**iin (register/unregister) + onDispose.
- **Tarkista:** mittari ≤340 dp myös leveällä, ei leikkaudu kapealla; GPS lakkaa taustalla/lukituksessa.

---

## Siivous & build
- Poistettu juuren synkka-jäänne `# Delete conflict ... .dex` (Codexin maininta).
- Build OK (debug + release). Esikatselu-APK: `releases\_compose-preview\Arkikeskus-codex-korjaukset-2-esikatselu.apk`.
- Versio yhä `1.15.0-mobile`. Hyväksynnän + tämän tarkistuksen jälkeen → bump `2.0.0-mobile` (versionCode 50) + push + gh release.
