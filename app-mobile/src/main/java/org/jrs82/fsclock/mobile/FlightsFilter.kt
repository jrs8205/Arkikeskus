package org.jrs82.fsclock.mobile

/** Paikalliset suodatus-/hakufunktiot Worker-dataan (ei lisähakuja). Puhdas → yksikkötestattava. */
object FlightsFilter {

    /** Valitun kentän + suunnan lennot, järjestettynä AIKATAULUN mukaan nousevasti (kuten Finavia:
     *  myöhästynyt lento pysyy aikataulupaikassaan, arvioitu aika näkyy erikseen).
     *  Näkyvissä: vielä saapumattomat/lähtemättömät (actualMs == null, myös myöhässä olevat) AINA,
     *  sekä äskettäin (alle 60 min sitten) saapuneet/lähteneet. Vanhemmat menneet piiloon. */
    fun board(data: FlightsData?, airport: String, dir: FlightDir, nowMs: Long = System.currentTimeMillis()): List<Flight> {
        if (data == null) return emptyList()
        val src = if (dir == FlightDir.ARR) data.arr else data.dep
        val cutoff = nowMs - 60 * 60_000L
        return src.filter {
            it.airport.equals(airport, ignoreCase = true) && (it.actualMs ?: Long.MAX_VALUE) >= cutoff
        }.sortedBy { it.scheduledMs }
    }

    /** Lentonumerohaku koko Suomesta (kaikki kentät + molemmat suunnat); osuma fno- tai codeshare-numeroon. */
    fun search(data: FlightsData?, query: String): List<Flight> {
        if (data == null) return emptyList()
        val q = query.replace(" ", "").uppercase()
        if (q.isEmpty()) return emptyList()
        fun norm(s: String) = s.replace(" ", "").uppercase()
        fun match(fl: Flight) = norm(fl.flightNo).contains(q) || fl.codeshares.any { norm(it).contains(q) }
        return (data.dep + data.arr).filter(::match).sortedBy { it.effectiveMs }
    }

    /** Kenttä → lentojen määrä juuri nyt (valitsimen apuna). */
    fun airportsWithCounts(data: FlightsData?): Map<String, Int> {
        if (data == null) return emptyMap()
        val m = HashMap<String, Int>()
        for (fl in data.dep + data.arr) m[fl.airport] = (m[fl.airport] ?: 0) + 1
        return m
    }
}
