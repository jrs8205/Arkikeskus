package org.jrs82.fsclock.mobile

enum class FlightDir { ARR, DEP }

/** Yksi lento (kevyt malli; lähde = lennot-Worker). Ajat epoch ms (UTC), null = puuttuu. */
data class Flight(
    val dir: FlightDir,
    val airport: String,        // h_apt (IATA)
    val flightNo: String,       // fno
    val scheduledMs: Long,      // sch
    val estimatedMs: Long?,     // est
    val actualMs: Long?,        // act
    val statusCode: String,     // scode (prm)
    val status: String,         // st (prt_f, suomi)
    val otherAirport: String,   // apt2 (kohde dep / lähtö arr)
    val city: String,           // city
    val gate: String?,
    val stand: String?,
    val belt: String?,          // vain arr
    val checkin: String?,       // vain dep
    val aircraft: String?,
    val codeshares: List<String>,
    val gatePrev: String? = null,
    val via: List<String> = emptyList(),
    val aircraftReg: String? = null,
    val callsign: String? = null,
    val callGateMs: Long? = null,
    val callBoardingMs: Long? = null,
    val callFinalMs: Long? = null,
    val callClosedMs: Long? = null,
) {
    /** Paras tiedossa oleva aika: toteutunut → arvio → aikataulu. */
    val effectiveMs: Long get() = actualMs ?: estimatedMs ?: scheduledMs

    /** Myöhästyminen minuutteina (+ = myöhässä) suhteessa aikatauluun. */
    val delayMin: Long get() = (effectiveMs - scheduledMs) / 60000L
}

data class FlightsData(val updatedMs: Long, val arr: List<Flight>, val dep: List<Flight>)
