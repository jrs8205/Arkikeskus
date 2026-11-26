package org.jrs82.fsclock.mobile.gps

/**
 * Harventaa toistuvan työn enintään kerran [intervalMs]:ssa. Matkan tila tallennetaan GPS-mittausten
 * tahdissa (ne herättävät puhelimen myös näytön ollessa pois), mutta ei jokaisesta mittauksesta.
 * Ei säieturvallinen: omistaja synkronoi.
 */
class Throttle(private val intervalMs: Long) {
    private var lastMs: Long? = null

    /** true, jos työ on nyt ajankohtainen; merkitsee samalla ajankohdan tehdyksi. */
    fun due(nowMs: Long): Boolean {
        val last = lastMs
        if (last == null || nowMs < last || nowMs - last >= intervalMs) {
            lastMs = nowMs
            return true
        }
        return false
    }

    fun reset() {
        lastMs = null
    }
}
