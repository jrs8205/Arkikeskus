FsClock v1.0.0 — Vakaa baseline ennen Gradle-migraatiota
==========================================================

Päivämäärä: 2026-05-18

Mitä versio tekee
-----------------
Etualan kellosovellus SM-T819-tabletille (24/7 infotaulu):
- Kello, päivämäärä, suomalaiset juhlapyhät
- Sää (FMI: Helsinki-Vantaa, FMISID 100968) + 10 päivän tuntiennuste swipellä
- Akun varaustila ja lämpötila
- Pixel-shift burn-in-suojaus
- Päivä-/yöaikainen kirkkauden säätö (pitkä painallus → dialogi)

Tunnetut bugit / rajoitteet
---------------------------
- FMI:n forecast::edited tarkkuus harvenee n. 60 h jälkeen — tunteja voi tulla null
- AlmanakkaClient.java mukana mutta ei käytössä (varalla nimipäivä-API)
- Toimii vain kohtuullisella verkkoyhteydellä (FMI WFS)

Buildin tiedot
--------------
FsClock_1.0.0_legacy.apk (alkuperäinen bash-buildi):
- Build-järjestelmä: käsin aapt2 + javac + d8 (r8.jar) + apksigner (build.sh)
- versionCode = 1, versionName = "1.0"
- Allekirjoitettu projektin debug.keystorella (pass:android)
- APK-koko: ~41 kB

FsClock_1.0.0.apk (uusi Gradle-buildi, 2026-05-18):
- Build-järjestelmä: Gradle 8.11.1 + AGP 8.9.1
- compileSdk = 36, minSdk = 30, targetSdk = 30
- versionCode = 100, versionName = "1.0.0"
- Allekirjoitettu release.keystorella (alias fsclock, voimassa 2126-04-24 saakka)
- APK-koko: ~60 kB
- Toiminnallisuus identtinen legacy-version kanssa

Asennusohje
-----------
1. Salli tuntemattomat lähteet asetuksista
2. Kopioi FsClock_1.0.0_legacy.apk tablettiin
3. Avaa ja asenna
4. (Valinnainen) Asetukset → Akku → poista akun optimointi FsClockilta

Tiedostot
---------
FsClock_1.0.0_legacy.apk     - Allekirjoitettu APK (debug-key)
FsClock_source_legacy.zip    - Lähdekoodi (AndroidManifest, src/, res/, build.sh, debug.keystore)

Tämä versio on baseline, johon voi palata jos Gradle-migraatio epäonnistuu.
