package org.jrs82.fsclock.mobile

import androidx.annotation.StringRes
import org.jrs82.fsclock.R

enum class FlightStatusCat { ON_TIME, ATTENTION, DELAYED, CANCELLED, COMPLETED }

/** Näytettävä teksti: resurssi (+ argumentit) tai lähteen teksti sellaisenaan. Muotoilu tehdään UI:ssa. */
sealed class FlightText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : FlightText()
    data class Raw(val text: String) : FlightText()
}

/** Lennon tilan visuaalinen kategoria. Johdetaan suomenkielisestä statustekstistä + myöhästymisestä
 *  (väri on toissijainen signaali; teksti näytetään aina). */
object FlightDisplay {
    fun category(f: Flight): FlightStatusCat {
        val s = f.status.lowercase()
        return when {
            s.contains("peru") -> FlightStatusCat.CANCELLED
            s.contains("lähtenyt") || s.contains("laskeutunut") || s.contains("saapunut") -> FlightStatusCat.COMPLETED
            s.contains("arvioitu") || s.contains("myöhässä") || s.contains("myohassa") -> FlightStatusCat.DELAYED
            s.contains("portil") || s.contains("koneeseen") || s.contains("viimeinen") ||
                s.contains("kutsu") || s.contains("lähdössä") || s.contains("lahdossa") ||
                s.contains("lähestyy") || s.contains("lahestyy") || s.contains("selvit") -> FlightStatusCat.ATTENTION
            f.delayMin >= 5L -> FlightStatusCat.DELAYED
            else -> FlightStatusCat.ON_TIME
        }
    }

    /** Tilapillerin teksti. Finavia antaa tilan vain suomeksi (prt_f): suomeksi se näytetään sellaisenaan
     *  (lähteen sanamuoto säilyy), muilla kielillä tunnettu tila käännetään ja tuntematon jää suomeksi. */
    fun statusText(f: Flight, language: String): FlightText {
        if (language == "fi") return FlightText.Raw(f.status)
        return statusRes(f.status)?.let { FlightText.Res(it) } ?: FlightText.Raw(f.status)
    }

    /** Finavian suomenkielinen tila → resurssi. Järjestys ratkaisee: tarkemmat ilmaukset ennen yleisempiä
     *  ("Lähtöselvitys suljettu" ennen "suljettu", "Viimeinen kutsu" ennen "kutsu"). */
    @StringRes
    fun statusRes(status: String): Int? {
        val s = status.lowercase()
        return when {
            s.isBlank() -> null
            s.contains("peru") -> R.string.flights_status_cancelled
            s.contains("lähtenyt") || s.contains("lahtenyt") -> R.string.flights_status_departed
            s.contains("laskeutunut") -> R.string.flights_status_landed
            s.contains("saapunut") -> R.string.flights_status_arrived
            s.contains("viimeinen") -> R.string.flights_status_final_call
            s.contains("selvit") && s.contains("suljet") -> R.string.flights_status_checkin_closed
            s.contains("suljet") -> R.string.flights_status_gate_closed
            s.contains("koneeseen") -> R.string.flights_status_boarding
            s.contains("portil") -> R.string.flights_status_go_to_gate
            s.contains("selvit") -> R.string.flights_status_checkin
            s.contains("lähdössä") || s.contains("lahdossa") -> R.string.flights_status_departing
            s.contains("lähestyy") || s.contains("lahestyy") -> R.string.flights_status_approaching
            s.contains("myöhässä") || s.contains("myohassa") -> R.string.flights_status_delayed
            s.contains("uusi aika") -> R.string.flights_status_new_time
            s.contains("arvioitu") -> R.string.flights_status_estimated
            s.contains("aikataulu") -> R.string.flights_status_on_time
            else -> null
        }
    }

    /** Matkatavarahihnan tila (Workerin beltStatus-koodi) → resurssi; tuntematon → null (ei näytetä). */
    @StringRes
    fun beltStatusRes(code: String?): Int? = when (code) {
        "not-started" -> R.string.flights_belt_not_started
        "started" -> R.string.flights_belt_started
        "completed" -> R.string.flights_belt_completed
        else -> null
    }

    /** Saapuvan lennon matkatavaratieto: hihna (+ tila) ja alue, tai pelkkä alue. null = ei tietoa. */
    data class Baggage(val belt: String?, @StringRes val beltStatusRes: Int?, val area: String?)

    fun baggage(f: Flight): Baggage? = when {
        !f.belt.isNullOrBlank() -> Baggage(f.belt, beltStatusRes(f.beltStatus), f.baggageArea)
        !f.baggageArea.isNullOrBlank() -> Baggage(null, null, f.baggageArea)
        else -> null
    }
}
