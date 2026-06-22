package org.jrs82.fsclock.mobile.widget

/** Widget-workerin passiivisen sijainnin valintalogiikka (puhdas, yksikkötestattava). */
object WidgetLocation {
    /** Yhden lähteen ehdokas: lähde, fixin aikaleima (ms) ja tarkkuus (m) tai null jos tuntematon. */
    data class Candidate(val source: String, val timeMs: Long, val accuracyM: Float?)

    /** Tuorein (suurin timeMs) ehdokas, jonka ikä on 0..maxAgeMs ja tarkkuus null tai ≤ maxAccuracyM.
     *  null jos mikään ei kelpaa. Negatiivinen ikä (kelloheitto) ja liian vanhat/epätarkat hylätään. */
    fun choose(
        candidates: List<Candidate>,
        nowMs: Long,
        maxAgeMs: Long,
        maxAccuracyM: Float,
    ): Candidate? = candidates
        .filter { c ->
            val age = nowMs - c.timeMs
            age in 0..maxAgeMs && (c.accuracyM == null || c.accuracyM <= maxAccuracyM)
        }
        .maxByOrNull { it.timeMs }
}
