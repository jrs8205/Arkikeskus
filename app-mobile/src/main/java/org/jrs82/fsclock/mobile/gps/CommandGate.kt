package org.jrs82.fsclock.mobile.gps

/**
 * Suoja tahatonta kaksoisnapautusta vastaan. Kun matkan tila muuttuu, painikkeet vaihtuvat samalla
 * paikalla (Lopeta-painikkeen kohdalle tulee Jatka tätä matkaa), joten toinen napautus osuisi
 * vastakkaiseen toimintoon. Tilan muutoksen jälkeen uusi komento hyväksytään vasta [quietMs] kuluttua.
 * Ei säieturvallinen: omistaja synkronoi.
 */
class CommandGate(private val quietMs: Long) {
    private var lastChangeMs: Long? = null

    /** true, jos komennon saa nyt suorittaa. Ei muuta tilaa. */
    fun open(nowMs: Long): Boolean {
        val last = lastChangeMs ?: return true
        return nowMs < last || nowMs - last >= quietMs
    }

    /** Kirjaa, että matkan tila muuttui komennosta. */
    fun changed(nowMs: Long) {
        lastChangeMs = nowMs
    }
}
