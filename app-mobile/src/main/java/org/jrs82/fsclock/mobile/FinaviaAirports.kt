package org.jrs82.fsclock.mobile

/** Finavian lentokentät (IATA → suomenkielinen nimi). HEL ensin (oletus), loput kokoluokan mukaan. */
object FinaviaAirports {
    data class Airport(val iata: String, val name: String)

    val ALL: List<Airport> = listOf(
        Airport("HEL", "Helsinki-Vantaa"),
        Airport("RVN", "Rovaniemi"),
        Airport("OUL", "Oulu"),
        Airport("TKU", "Turku"),
        Airport("TMP", "Tampere-Pirkkala"),
        Airport("VAA", "Vaasa"),
        Airport("KUO", "Kuopio"),
        Airport("KTT", "Kittilä"),
        Airport("IVL", "Ivalo"),
        Airport("KOK", "Kokkola-Pietarsaari"),
        Airport("JOE", "Joensuu"),
        Airport("JYV", "Jyväskylä"),
        Airport("KAJ", "Kajaani"),
        Airport("KEM", "Kemi-Tornio"),
        Airport("KAO", "Kuusamo"),
        Airport("MHQ", "Maarianhamina"),
        Airport("POR", "Pori"),
        Airport("SVL", "Savonlinna"),
        Airport("ENF", "Enontekiö"),
    )

    fun name(iata: String): String = ALL.firstOrNull { it.iata == iata }?.name ?: iata
}
