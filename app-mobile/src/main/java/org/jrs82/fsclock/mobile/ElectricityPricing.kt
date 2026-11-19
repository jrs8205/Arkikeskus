package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.ElectricityData
import java.time.DayOfWeek
import java.time.Instant

/** Sähkösopimuksen tyyppi omissa hinnoissa. */
enum class OwnContract { SPOT, FIXED }

/** Siirtotuote: yksi hinta, päivä/yö tai talviarkipäivä/muu aika. */
enum class TransferProduct { GENERAL, TIME, SEASON }

/** Viikonpäivät, joina kalliimpi siirtohinta on voimassa. */
enum class TransferDays { EVERY_DAY, MON_FRI, MON_SAT }

/**
 * Siirtohinta käyttäjän syöttämänä (snt/kWh arvonlisäveron kanssa). [GENERAL][TransferProduct.GENERAL]
 * käyttää vain [highSnt]:tä. Muissa tuotteissa [highSnt] on voimassa, kun jakson alun tunti on välillä
 * [[startHour], [endHour]) Suomen aikaa, viikonpäivä kuuluu [days]-joukkoon ja kausisiirrossa lisäksi
 * päivämäärä on 1.11.–31.3.; muulloin [lowSnt]. Rajat ovat tasatunteja, joten hinta on vakio tunnin sisällä.
 */
data class OwnTransfer(
    val product: TransferProduct,
    val highSnt: Double,
    val lowSnt: Double,
    val startHour: Int = DEFAULT_START_HOUR,
    val endHour: Int = DEFAULT_END_HOUR,
    val days: TransferDays = TransferDays.EVERY_DAY,
) {
    /** Syötetty siirtohinta jaksolle, jonka alku on [timestampMs]; arvonlisäveroa ei muunneta tässä. */
    fun at(timestampMs: Long): Double {
        if (product == TransferProduct.GENERAL) return highSnt
        val t = Instant.ofEpochMilli(timestampMs).atZone(ElectricityTime.HELSINKI)
        val inHours = t.hour in startHour until endHour
        val inDays = when (days) {
            TransferDays.EVERY_DAY -> true
            TransferDays.MON_FRI -> t.dayOfWeek != DayOfWeek.SATURDAY && t.dayOfWeek != DayOfWeek.SUNDAY
            TransferDays.MON_SAT -> t.dayOfWeek != DayOfWeek.SUNDAY
        }
        val inSeason = product != TransferProduct.SEASON || t.monthValue >= SEASON_FIRST_MONTH || t.monthValue <= SEASON_LAST_MONTH
        return if (inHours && inDays && inSeason) highSnt else lowSnt
    }

    companion object {
        const val DEFAULT_START_HOUR = 7
        const val DEFAULT_END_HOUR = 22
        private const val SEASON_FIRST_MONTH = 11
        private const val SEASON_LAST_MONTH = 3
    }
}

/**
 * Käyttäjän omat hinnat (snt/kWh arvonlisäveron kanssa). [energySnt] on marginaali
 * ([OwnContract.SPOT]) tai kiinteä sähkön hinta ([OwnContract.FIXED]). [taxSnt] on 0, kun sähkövero
 * on jätetty pois. [energySet] kertoo, onko [energySnt] käyttäjän syöttämä: syöttämätön hinta on
 * laskennassa nolla, mutta käyttäjä voi myös syöttää nollan, eikä näitä voi erottaa luvusta.
 */
data class OwnPrices(
    val contract: OwnContract,
    val energySnt: Double,
    val transfer: OwnTransfer,
    val taxSnt: Double,
    val energySet: Boolean = true,
)

/** Näytetyn hinnan osat ALV-asetuksen mukaisina. */
data class PriceParts(val spot: Double, val energy: Double, val transfer: Double, val tax: Double) {
    val total: Double get() = spot + energy + transfer + tax
}

/**
 * Sähkön hinnan näyttöasetukset yhtenä arvona: ALV-kytkin ja käyttäjän omat hinnat ([own] on null,
 * kun oma kokonaishinta ei ole käytössä). Lähdedata on aina veroton pörssihinta; kaikki näkymät
 * laskevat näytettävän luvun tästä, jotta sama hetki näyttää saman hinnan kaikkialla.
 *
 * Pörssisähkösopimus: näytetty = ALV(pörssi) + marginaali + siirto + sähkövero.
 * Kiinteä sopimus (vertailutila): näytetty = ALV(pörssi) + siirto + sähkövero, eli mitä pörssisähkö
 * maksaisi käyttäjälle; oma kiinteä kokonaishinta ([ownFixedAt]) on vertailuna (ElectricityFixedCompare.kt).
 */
data class ElectricityPricing(val vat: Boolean, val own: OwnPrices? = null) {

    /** Pörssihinta ALV-asetuksen mukaan. Hintataso ja halvan sähkön raja lasketaan tästä, eivät
     *  kokonaishinnasta. */
    fun spot(rawSnt: Double): Double = ElectricityVat.apply(rawSnt, vat)

    /** Näytetyn hinnan osat jaksolle, jonka alku on [timestampMs] ja veroton pörssihinta [rawSnt]. */
    fun parts(timestampMs: Long, rawSnt: Double): PriceParts {
        val o = own ?: return PriceParts(spot(rawSnt), 0.0, 0.0, 0.0)
        val energy = if (o.contract == OwnContract.SPOT) ownPart(o.energySnt) else 0.0
        return PriceParts(spot(rawSnt), energy, ownPart(o.transfer.at(timestampMs)), ownPart(o.taxSnt))
    }

    /** Näytettävä hinta. Puuttuva pörssihinta (NaN) pysyy puuttuvana. */
    fun shown(timestampMs: Long, rawSnt: Double): Double = parts(timestampMs, rawSnt).total

    fun shown(q: ElectricityData.Quarter): Double = shown(q.timestamp, q.sntPerKwh)

    /**
     * Arvo, jolla jaksot järjestetään (halvin, kallein) ja lämpöväri skaalataan. Ilman omia hintoja
     * se on veroton pörssihinta kuten ennen; omilla hinnoilla näytetty hinta, koska halvempi yösiirto
     * voi vaihtaa jaksojen järjestyksen.
     */
    fun scaleValue(timestampMs: Long, rawSnt: Double): Double =
        if (own == null) rawSnt else shown(timestampMs, rawSnt)

    /** True, kun käyttäjällä on kiinteä sopimus: näkymät näyttävät pörssisähkön hinnan omalla siirrolla ja verolla. */
    val fixedCompare: Boolean get() = own?.contract == OwnContract.FIXED

    /** True, kun kiinteä hinta on myös syötetty ([OwnPrices.energySet]). Vasta silloin pörssihintaa
     *  verrataan siihen: vertailu syöttämättömään hintaan väittäisi pörssisähköä aina kalliimmaksi.
     *  Käyttäjän syöttämä nolla on kelvollinen hinta ja sitä verrataan kuten muitakin. */
    val fixedPriceSet: Boolean get() = fixedCompare && own?.energySet == true

    /** Oma kiinteä kokonaishinta: kiinteä hinta + siirto + sähkövero. NaN ilman kiinteää sopimusta. */
    fun ownFixedAt(timestampMs: Long): Double {
        val o = own?.takeIf { it.contract == OwnContract.FIXED } ?: return Double.NaN
        return ownPart(o.energySnt) + ownPart(o.transfer.at(timestampMs)) + ownPart(o.taxSnt)
    }

    /** Omat hinnat on syötetty arvonlisäveron kanssa; ilman ALV:tä näytettäessä vero poistetaan. */
    private fun ownPart(snt: Double): Double = if (vat) snt else ElectricityVat.unapply(snt, true)
}
