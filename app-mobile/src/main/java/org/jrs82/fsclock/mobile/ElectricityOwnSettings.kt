package org.jrs82.fsclock.mobile

import android.content.SharedPreferences

/**
 * Asetus "Näytä oma kokonaishinta": käyttäjän marginaali tai kiinteä hinta, siirtohinnat ja sähkövero.
 * Arvot tallennetaan sellaisina kuin käyttäjä ne syötti (snt/kWh arvonlisäveron kanssa); muunnos
 * näytettäväksi hinnaksi tehdään [ElectricityPricing]issa. Lukeminen ei koskaan heitä: puuttuva,
 * kelvoton tai väärän tyyppinen arvo (esimerkiksi palautetusta varmuuskopiosta) korvataan oletuksella,
 * koska samaa lukua käyttävät myös widget ja taustalla ajettava ilmoitustyö.
 */
/** Miten varmuuskopion omat sähköhinnat palautetaan ([ElectricityOwnSettings.restoreDecision]). */
enum class OwnRestore {
    /** Kopiossa ei ole omia hintoja: asennuksen omiin hintoihin ei kosketa. */
    ABSENT,

    /** Kopion omat hinnat ovat kelvolliset: ne korvaavat asennuksen omat hinnat kokonaan. */
    REPLACE,

    /** Kopion omissa hinnoissa on virheellinen kenttä: ryhmästä ei poisteta eikä kirjoiteta mitään. */
    INVALID,
}

object ElectricityOwnSettings {
    const val PREFIX = "mobile_electricity_own_"
    const val KEY_ENABLED = PREFIX + "enabled"
    const val KEY_CONTRACT = PREFIX + "contract"
    const val KEY_MARGIN = PREFIX + "margin"
    const val KEY_FIXED_PRICE = PREFIX + "fixed_price"
    const val KEY_TRANSFER_PRODUCT = PREFIX + "transfer_product"
    const val KEY_TRANSFER_HIGH = PREFIX + "transfer_high"
    const val KEY_TRANSFER_LOW = PREFIX + "transfer_low"
    const val KEY_TRANSFER_START = PREFIX + "transfer_start_hour"
    const val KEY_TRANSFER_END = PREFIX + "transfer_end_hour"
    const val KEY_TRANSFER_DAYS = PREFIX + "transfer_days"
    const val KEY_TAX_ENABLED = PREFIX + "tax_enabled"
    const val KEY_TAX = PREFIX + "tax"

    const val CONTRACT_SPOT = "spot"
    const val CONTRACT_FIXED = "fixed"
    const val PRODUCT_GENERAL = "general"
    const val PRODUCT_TIME = "time"
    const val PRODUCT_SEASON = "season"
    const val DAYS_ALL = "all"
    const val DAYS_MON_FRI = "mon_fri"
    const val DAYS_MON_SAT = "mon_sat"

    /** Sähkövero, veroluokka I 1.4.2026 alkaen: 2,24 + huoltovarmuusmaksu 0,085 = 2,325 snt/kWh, eli
     *  arvonlisäveron 25,5 % kanssa 2,917875. Oletus on pyöristetty kolmeen desimaaliin kuten
     *  käyttäjän syöttämät hinnat: muuten sähkösivun erittelyn osat (jokainen pyöristetty erikseen)
     *  voisivat summautua 0,001 eri lukuun kuin iso hinta. Prefsiin tallennetaan vain käyttäjän
     *  muuttama arvo, jotta tätä oletusta voi päivittää sovelluspäivityksellä. */
    const val DEFAULT_TAX_SNT = 2.918

    /** Suurin hyväksytty hinta (snt/kWh). */
    const val MAX_SNT = 100.0

    /** Ero, jota pienempi ei näy kolmella desimaalilla. */
    private const val PRICE_EPSILON = 0.0005

    private val PRICE = Regex("""\d{1,3}([.,]\d{1,3})?""")

    /**
     * Käyttäjän syöttämä hinta luvuksi. Kelvollinen: 0–[MAX_SNT], pilkku tai piste, enintään kolme
     * desimaalia; ympäröivät välilyönnit sallitaan. Tyhjä on 0. Muu syöte on null.
     */
    fun parsePrice(text: String): Double? {
        val t = text.trim()
        if (t.isEmpty()) return 0.0
        if (!PRICE.matches(t)) return null
        val value = t.replace(',', '.').toDoubleOrNull() ?: return null
        return if (value <= MAX_SNT) value else null
    }

    /** Tallennettu hinta; puuttuva tai kelvoton arvo on [fallback]. */
    fun storedPrice(prefs: SharedPreferences, key: String, fallback: Double = 0.0): Double {
        val raw = string(prefs, key)
        if (raw.isNullOrBlank()) return fallback
        return parsePrice(raw) ?: fallback
    }

    /** True, kun avaimelle on tallennettu kelvollinen hinta (asetusrivi näyttää muuten "Ei asetettu"). */
    fun isSet(prefs: SharedPreferences, key: String): Boolean {
        val raw = string(prefs, key)
        return !raw.isNullOrBlank() && parsePrice(raw) != null
    }

    /**
     * Tallentaa käyttäjän syöttämän hinnan. Palauttaa false, jos syöte ei kelpaa ([parsePrice]); silloin
     * mitään ei muuteta. Tyhjä syöte poistaa arvon. [fallback] on oletus, jota käytetään kun arvoa ei ole
     * tallennettu (sähkövero): jos arvoa ei ole aiemmin asetettu ja syöte on sama kuin oletus, avain
     * jätetään tallentamatta, jotta oletusta voi edelleen päivittää sovelluspäivityksellä.
     */
    fun savePrice(prefs: SharedPreferences, key: String, text: String, fallback: Double? = null): Boolean {
        val value = parsePrice(text) ?: return false
        val cleaned = text.trim().replace(',', '.')
        val unchangedDefault = fallback != null && !isSet(prefs, key) && Math.abs(value - fallback) < PRICE_EPSILON
        prefs.edit().apply {
            when {
                cleaned.isEmpty() -> remove(key)
                unchangedDefault -> Unit
                else -> putString(key, cleaned)
            }
        }.apply()
        return true
    }

    /**
     * Omien hintojen ryhmä: tämän version tuntemat avaimet. Varmuuskopiosta ne palautetaan
     * kokonaisuutena ([restoreDecision]). Muu `mobile_electricity_own_`-alkuinen avain (uudemman version
     * kopio) ei kuulu ryhmään: se palautetaan kuten mikä tahansa asetus eikä vaikuta ryhmän käsittelyyn.
     */
    val GROUP_KEYS: Set<String> = setOf(
        KEY_ENABLED, KEY_CONTRACT, KEY_MARGIN, KEY_FIXED_PRICE, KEY_TRANSFER_PRODUCT, KEY_TRANSFER_HIGH,
        KEY_TRANSFER_LOW, KEY_TRANSFER_START, KEY_TRANSFER_END, KEY_TRANSFER_DAYS, KEY_TAX_ENABLED, KEY_TAX,
    )

    /**
     * Varmuuskopion palautus: omat hinnat palautetaan kokonaisuutena. Palautus kirjoittaa vain kopiossa
     * olevat avaimet, ja oletusarvoja (esim. pörssisähkösopimus) ei tallenneta lainkaan; ilman tätä
     * asennuksen vanha kiinteä sopimus jäisi voimaan, vaikka kopiossa on pörssisopimus marginaaleineen.
     * Palauttaa asennuksen ryhmän avaimet ([GROUP_KEYS]), jotka puuttuvat kopiosta ja pitää poistaa. Jos
     * kopiossa ei ole yhtään ryhmän avainta, mitään ei poisteta. Kutsutaan vain, kun kopion omat hinnat
     * korvaavat asennuksen omat hinnat ([OwnRestore.REPLACE]).
     */
    fun keysToClearOnRestore(existingKeys: Set<String>, backupKeys: Set<String>): Set<String> {
        if (backupKeys.none { it in GROUP_KEYS }) return emptySet()
        return GROUP_KEYS.filterTo(HashSet()) { it in existingKeys && it !in backupKeys }
    }

    /**
     * Miten varmuuskopion omat hinnat palautetaan. [values] = kopion omien hintojen avaimet ja arvot
     * tallennustyypissään (String, Boolean, Int); null = kenttä on virheellinen (arvo puuttuu, kenttä ei
     * ole objekti tai tyyppi on väärä). Päätös tehdään vain ryhmän avaimista ([GROUP_KEYS]):
     *  - kopiossa ei ole yhtään ryhmän avainta → [OwnRestore.ABSENT],
     *  - jokainen kopion ryhmän kenttä on arvo, jonka asetukset itse tallentavat → [OwnRestore.REPLACE],
     *  - yksikin ryhmän kenttä on virheellinen → [OwnRestore.INVALID]: kopion sopimusta ei tunneta, eikä
     *    sen varjolla saa poistaa asennuksen nykyistä.
     */
    fun restoreDecision(values: Map<String, Any?>): OwnRestore {
        val group = values.filterKeys { it in GROUP_KEYS }
        if (group.isEmpty()) return OwnRestore.ABSENT
        for ((key, value) in group) {
            val valid = when (key) {
                KEY_ENABLED, KEY_TAX_ENABLED -> value is Boolean
                KEY_CONTRACT -> value == CONTRACT_SPOT || value == CONTRACT_FIXED
                KEY_TRANSFER_PRODUCT -> value == PRODUCT_GENERAL || value == PRODUCT_TIME || value == PRODUCT_SEASON
                KEY_TRANSFER_DAYS -> value == DAYS_ALL || value == DAYS_MON_FRI || value == DAYS_MON_SAT
                KEY_MARGIN, KEY_FIXED_PRICE, KEY_TRANSFER_HIGH, KEY_TRANSFER_LOW, KEY_TAX ->
                    value is String && value.isNotBlank() && parsePrice(value) != null
                KEY_TRANSFER_START -> value is Int && value in 0..23
                KEY_TRANSFER_END -> value is Int && value in 1..24
                else -> false // ryhmän avain ilman tarkistusta hylätään
            }
            if (!valid) return OwnRestore.INVALID
        }
        // Tunnit tallennetaan parina; yksin palautettu tunti yhdistyy toisen oletukseen kuten luettaessa.
        val start = group[KEY_TRANSFER_START] as? Int ?: OwnTransfer.DEFAULT_START_HOUR
        val end = group[KEY_TRANSFER_END] as? Int ?: OwnTransfer.DEFAULT_END_HOUR
        return if (validHours(start, end)) OwnRestore.REPLACE else OwnRestore.INVALID
    }

    fun enabled(prefs: SharedPreferences): Boolean = boolean(prefs, KEY_ENABLED, false)

    fun contract(prefs: SharedPreferences): OwnContract =
        if (string(prefs, KEY_CONTRACT) == CONTRACT_FIXED) OwnContract.FIXED else OwnContract.SPOT

    fun product(prefs: SharedPreferences): TransferProduct = when (string(prefs, KEY_TRANSFER_PRODUCT)) {
        PRODUCT_TIME -> TransferProduct.TIME
        PRODUCT_SEASON -> TransferProduct.SEASON
        else -> TransferProduct.GENERAL
    }

    /**
     * Tallentaa siirtotuotteen. Tuotteen vaihtuessa päivävalinta poistetaan, jolloin uusi tuote saa oman
     * oletuksensa: aikasiirrolle tallennettu "joka päivä" ei saa siirtyä kausisiirtoon, jonka kalliimpi
     * hinta on tavallisesti voimassa vain ma–la. Tunnit ja hinnat säilyvät.
     */
    fun saveProduct(prefs: SharedPreferences, value: String) {
        val changed = string(prefs, KEY_TRANSFER_PRODUCT) != value
        prefs.edit().apply {
            putString(KEY_TRANSFER_PRODUCT, value)
            if (changed) remove(KEY_TRANSFER_DAYS)
        }.apply()
    }

    /** Tallennettu päivävalinta, tai tuotteen oletus: aikasiirrossa joka päivä, kausisiirrossa ma–la. */
    fun days(prefs: SharedPreferences, product: TransferProduct = product(prefs)): TransferDays =
        when (string(prefs, KEY_TRANSFER_DAYS)) {
            DAYS_ALL -> TransferDays.EVERY_DAY
            DAYS_MON_FRI -> TransferDays.MON_FRI
            DAYS_MON_SAT -> TransferDays.MON_SAT
            else -> if (product == TransferProduct.SEASON) TransferDays.MON_SAT else TransferDays.EVERY_DAY
        }

    /** Kalliimman siirtohinnan alku- ja lopputunti. Kelvoton pari (alku ≥ loppu tai alueen ulkopuolella) on 7–22. */
    fun hours(prefs: SharedPreferences): Pair<Int, Int> {
        val start = int(prefs, KEY_TRANSFER_START, OwnTransfer.DEFAULT_START_HOUR)
        val end = int(prefs, KEY_TRANSFER_END, OwnTransfer.DEFAULT_END_HOUR)
        return if (validHours(start, end)) start to end else OwnTransfer.DEFAULT_START_HOUR to OwnTransfer.DEFAULT_END_HOUR
    }

    fun validHours(start: Int, end: Int): Boolean = start in 0..23 && end in 1..24 && start < end

    fun taxEnabled(prefs: SharedPreferences): Boolean = boolean(prefs, KEY_TAX_ENABLED, true)

    /** Omat hinnat, tai null kun oma kokonaishinta ei ole käytössä. */
    fun own(prefs: SharedPreferences): OwnPrices? {
        if (!enabled(prefs)) return null
        val contract = contract(prefs)
        val product = product(prefs)
        val (start, end) = hours(prefs)
        val energyKey = if (contract == OwnContract.FIXED) KEY_FIXED_PRICE else KEY_MARGIN
        return OwnPrices(
            contract = contract,
            energySnt = storedPrice(prefs, energyKey),
            energySet = isSet(prefs, energyKey),
            transfer = OwnTransfer(
                product = product,
                highSnt = storedPrice(prefs, KEY_TRANSFER_HIGH),
                lowSnt = storedPrice(prefs, KEY_TRANSFER_LOW),
                startHour = start,
                endHour = end,
                days = days(prefs, product),
            ),
            taxSnt = if (taxEnabled(prefs)) storedPrice(prefs, KEY_TAX, DEFAULT_TAX_SNT) else 0.0,
        )
    }

    /** Hinnan näyttöasetukset: ALV-kytkin ja omat hinnat. */
    fun pricing(prefs: SharedPreferences): ElectricityPricing = ElectricityPricing(ElectricityVat.enabled(prefs), own(prefs))

    private fun string(prefs: SharedPreferences, key: String): String? =
        try { prefs.getString(key, null) } catch (e: ClassCastException) { null }

    private fun int(prefs: SharedPreferences, key: String, fallback: Int): Int =
        try { prefs.getInt(key, fallback) } catch (e: ClassCastException) { fallback }

    private fun boolean(prefs: SharedPreferences, key: String, fallback: Boolean): Boolean =
        try { prefs.getBoolean(key, fallback) } catch (e: ClassCastException) { fallback }
}
