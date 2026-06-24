# Arkikeskus

**Suomalaiseen kotiin tehty Android-kojelauta:** kello, sää, pörssisähkö, joukkoliikenne (HSL ja Tampereen Nysse), liikennetiedot, kelikamerat, anturit, askelmittari ja uutiset — kaikki yhdellä säädettävällä etusivulla.

**🌐 Verkkosivu: [arkikeskus.com](https://arkikeskus.com)**

[![Verkkosivu](https://img.shields.io/badge/🌐%20Verkkosivu-arkikeskus.com-1B45C0)](https://arkikeskus.com)

> ## ✅ Varmennettu kehittäjä
>
> **Arkikeskuksen kehittäjä ja sovelluksen pakettinimi (`org.jrs82.arkikeskus`) on rekisteröity Googlen Android Developer Verification ‑järjestelmässä.**
> Sovellus on allekirjoitettu pysyvällä julkaisuavaimella, joten voit varmistua siitä, että päivitykset tulevat samalta kehittäjältä myös Google Playn ulkopuolelta asennettaessa.
>
> *Huomio: tämä ei ole sama asia kuin Google Playn sovellustarkastus. Android voi silti näyttää tavanomaisen varoituksen, koska sovellus asennetaan Play Storen ulkopuolelta.*

---

## 📥 Lataus

**[➡️ Lataa uusin versio (APK)](https://github.com/jrs8205/Arkikeskus/releases/latest)**

> ⚠️ **Päivität vanhasta versiosta (≤ 2.0.0)?** Sovelluksen tunnus vaihtui versiossa 2.1.0, joten uusi versio **ei** päivity vanhan päälle automaattisesti: **poista** vanha Arkikeskus ja **asenna** uusi. Asetukset eivät siirry; askelhistoria säilyy Health Connectin kautta.

Vaatii **Android 11 (API 30)** tai uudemman. APK on tarkoitettu ARM-laitteille (`arm64-v8a` / `armeabi-v7a`).

---

## 📱 Kuvakaappauksia

### 🏠 Etusivu
| | |
|:---:|:---:|
| ![Etusivu](screenshots/etusivu-kello.png) | ![Etusivu](screenshots/etusivu-kortit.png) |
| Kello, nimipäivät & sää | Pyhät, pörssisähkö & varoitukset |

### 🌤️ Sää, pörssisähkö ja anturit
| | | |
|:---:|:---:|:---:|
| ![Sää-ennuste](screenshots/saa-ennuste.png) | ![Pörssisähkö – tänään](screenshots/porssisahko-tanaan.png) | ![Pörssisähkö – huomenna](screenshots/porssisahko-huomenna.png) |
| Sää-ennuste | Pörssisähkö – tänään | Pörssisähkö – huomenna |
| ![Pörssisähkö – vertailu](screenshots/porssisahko-vertailu.png) | ![Anturit](screenshots/anturit.png) | ![Paikkakunnat](screenshots/paikkakunnat_suosikit.png) |
| Pörssisähkö – vertailu | Anturit | Paikkakunnat & suosikit |

### 🚌 Joukkoliikenne — HSL ja Tampere (Nysse)

HSL:n reittiopas (pääkaupunkiseutu) ja Tampereen **Nysse** samassa näkymässä: lähilähdöt, reittihaku, koko päivän aikataulut, linjat, häiriöt ja bussien live-sijainti. Alue valitaan ylhäältä (HSL / Tampere) tai automaattisesti sijainnin mukaan.

| | | |
|:---:|:---:|:---:|
| ![Lähilähdöt HSL](screenshots/lahilahdot_1.png) | ![Reittihaku HSL](screenshots/reittihaku_1.png) | ![Tampere – lähilähdöt (Nysse)](screenshots/tampere-lahilahdot.png) |
| Lähilähdöt (HSL) | Reittihaku (HSL) | Tampere – lähilähdöt (Nysse) |
| ![Tampere – reittihaku](screenshots/tampere-reittihaku.png) | ![Tampere – häiriöt](screenshots/tampere-hairiot.png) | ![HSL / Tampere -valinta](screenshots/valikko-joukkoliikenne.png) |
| Tampere – reittihaku kartalla | Tampere – häiriöt | HSL / Tampere -valinta |

### 🚦 Liikennetiedot ja kelikamerat
| | | |
|:---:|:---:|:---:|
| ![Liikennetiedot](screenshots/liikennetiedot.png) | ![Onnettomuudet](screenshots/onnettomuudet.png) | ![Tietyöt](screenshots/tietyot.png) |
| Tiedotteet | Onnettomuudet | Tietyöt |
| ![Kelikamerat](screenshots/kelikamerat_1.png) | ![Kelikamerat](screenshots/kelikamerat_2.png) | |
| Kelikamerat | Kelikamerat | |

### 📰 Uutiset, GPS ja askelmittari
| | | |
|:---:|:---:|:---:|
| ![Uutiset](screenshots/uutiset.png) | ![Uutissuodatin](screenshots/uutissuodatin.png) | ![GPS-nopeusmittari](screenshots/gps-nopeus.png) |
| Uutiset | Uutissuodatin – mykistä sanat ja aiheet | GPS-nopeusmittari |
| ![Askelmittari](screenshots/askeleet_1.png) | ![Askelmittari](screenshots/askeleet_2.png) | |
| Askelmittari | Askelmittari | |

### 📱 Puhelimen tiedot ja asetukset
| | | |
|:---:|:---:|:---:|
| ![Puhelimen tiedot](screenshots/puhelimen-tiedot.png) | ![Asetukset](screenshots/asetukset_1.png) | ![Asetukset](screenshots/asetukset_2.png) |
| Puhelimen tiedot | Asetukset | Asetukset |
| ![Asetukset](screenshots/asetukset_3.png) | ![Asetukset](screenshots/asetukset_4.png) | |
| Asetukset | Asetukset | |

---

## ✨ Ominaisuudet

### Säädettävä etusivu
Jokaisen kortin voi **piilottaa ja järjestää raahaamalla** (Asetukset → Etusivun kortit):

- 🕐 **Kello, päivämäärä ja viikko**
- 🇫🇮 **Pyhä- ja liputuspäivät** (seuraava pyhä + seuraava virallinen liputuspäivä, vaihtuu automaattisesti keskiyöllä)
- 🌤️ **Sää** — lämpötila, tuntuu kuin, tuuli, sade ja loppupäivän tuntiennuste
- ⚡ **Pörssisähkö** — nykyinen varttihinta värikoodattuna (halpa / normaali / kallis)
- ⚠️ **Säävaroitukset** — näkyy vain kun voimassa olevia varoituksia on
- 🌡️ **Anturit** — Ruuvi-antureiden lämpötila ja kosteus
- 🚦 **Liikennetiedot** — lähimmät tiedotteet
- 📰 **Uutiset** — uusimmat otsikot kuvineen, uutissuodatin (mykistä aiheita ja sanoja) sekä omat per-lähde-uutiskortit
- 🚌 **Lähilähdöt** — lähimmät HSL- tai Nysse-lähdöt; napautus avaa koko reitin ja bussin live-sijainnin

### Kaikki toiminnot
- **Sää-ennuste:** FMI ja Open-Meteo rinnakkain, 7 päivää tunti tunnilta
- **Paikkakunnat:** ennakoiva paikkahaku, suosikit ja automaattinen laitteen sijainti
- **Pörssisähkö:** Tänään / Huomenna / Vertailu (kk- ja vuosikeskiarvot), hinnat ALV 0 %
- **Joukkoliikenne (HSL ja Tampere):** HSL:n reittiopas ja Tampereen Nysse — lähilähdöt, reittihaku (Mistä → Minne), koko päivän aikataulut, linjat ja häiriöt; alue valitaan tai tunnistuu sijainnista
- **Kelikamerat:** kartta lähimmistä kameroista
- **Liikennetiedot:** onnettomuudet, tietyöt, painorajoitukset, tiedotteet, ruuhkat
- **GPS-nopeusmittari**
- **Askelmittari:** Health Connect, kalorit, historia ja HTML-vienti
- **Puhelimen tiedot:** akku, verkko, SIM, laitteisto, muisti, näyttö, anturit
- **Uutiset:** 10 valmista lähdettä + omat RSS-syötteet + uutissuodatin (mykistetyt sanat ja aihepaketit)
- **Teemat:** vaalea ja tumma + valinnainen dynaaminen väritys (Material You, Android 12+)
- **Itsepäivitys:** tarkistaa uudet versiot ja lataa & asentaa suoraan

Käyttöliittymä on rakennettu **Jetpack Composella ja Material 3:lla**.

---

## 🔒 Turvallisuus ja varmennus

Arkikeskuksen **kehittäjä ja sovelluksen pakettinimi on rekisteröity Googlen Android Developer Verification ‑järjestelmässä**. Sovellus on allekirjoitettu **pysyvällä julkaisuavaimella**, jotta käyttäjä voi varmistua siitä, että päivitykset tulevat samalta kehittäjältä.

Android Developer Verification on lisäturvakerros, jossa kehittäjän henkilöllisyys vahvistetaan ja sovelluksen pakettinimi yhdistetään kehittäjätiliin sekä allekirjoitusavaimeen. Se **ei** ole sama asia kuin Google Playn täysi sovellustarkastus eikä lupaus virheettömyydestä.

> **Huomio:** Android voi silti näyttää tavanomaisen varoituksen, koska sovellus asennetaan Google Playn ulkopuolelta.

Julkaisuvarmenteen SHA-256-tunniste (julkinen):
```
09:22:DC:7E:4E:04:61:F3:B1:DC:70:33:31:2E:66:D8:CD:38:68:FB:D8:26:B9:34:DF:4A:AA:59:10:58:CB:BC
```

---

## 🛠️ Build (kehittäjille)

Tarvitset Android SDK:n ja Java 17:n. Konfiguroi `local.properties`:

```properties
sdk.dir=C:\\Users\\<sinä>\\AppData\\Local\\Android\\Sdk
MML_API_KEY=<oma MML-kehittäjäavain>
digitransit_subscription_key=<oma Digitransit-avain>
KEYSTORE_PASSWORD=<release.keystore-salasana, vapaaehtoinen>
KEY_PASSWORD=<avaimen salasana, vapaaehtoinen>
```

```bash
./gradlew :app-mobile:assembleRelease
```

Tuloksena `app-mobile/build/outputs/apk/release/Arkikeskus-<versio>.apk`. Allekirjoitukseen tarvitaan oma `release.keystore` (versionhallinnan ulkopuolella). Sovelluskoodi on paketissa `org.jrs82.fsclock.mobile`; julkinen sovellustunnus on `org.jrs82.arkikeskus`.

---

## 🌐 Käytetyt rajapinnat

Kaikki ovat ilmaisia; MML ja Digitransit vaativat oman kehittäjäavaimen:

- **FMI** (Ilmatieteen laitos) — havainnot, ennusteet, säävaroitukset
- **Open-Meteo** — vertailuennuste
- **Elering / Nord Pool** — 15 minuutin pörssisähkön hinnat
- **Digitraffic** — liikennetiedotteet ja kelikamerat
- **Digitransit (HSL ja Waltti / Tampere–Nysse)** — joukkoliikenteen lähdöt ja reittihaku
- **Maanmittauslaitos (MML)** — paikkahaku, geokoodaus, taustakartta

---

## 📄 Lisenssi

Henkilökohtaiseen käyttöön, ei kaupallista jakelua.
