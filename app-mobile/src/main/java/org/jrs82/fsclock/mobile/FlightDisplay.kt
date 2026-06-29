package org.jrs82.fsclock.mobile

enum class FlightStatusCat { ON_TIME, ATTENTION, DELAYED, CANCELLED, COMPLETED }

/** Lennon tilan visuaalinen kategoria. Johdetaan suomenkielisestä statustekstistä + myöhästymisestä
 *  (väri on toissijainen signaali; teksti näytetään aina). */
object FlightDisplay {
    fun category(f: Flight): FlightStatusCat {
        val s = f.status.lowercase()
        return when {
            s.contains("peru") -> FlightStatusCat.CANCELLED
            s.contains("lähtenyt") || s.contains("laskeutunut") || s.contains("saapunut") -> FlightStatusCat.COMPLETED
            s.contains("selvit") || s.contains("portti") || s.contains("portil") ||
                s.contains("koneeseen") || s.contains("kuulutus") || s.contains("viimeinen") -> FlightStatusCat.ATTENTION
            f.delayMin >= 5L -> FlightStatusCat.DELAYED
            else -> FlightStatusCat.ON_TIME
        }
    }
}
