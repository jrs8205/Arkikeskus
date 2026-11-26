package org.jrs82.fsclock.mobile.gps

/**
 * Muistutuksen yritys: pysähdyksen varaus, ilmoituksen näyttö ja epäonnistuneen yrityksen peruutus.
 *
 * Jos ilmoitusta ei saada näkyviin (lupa evätty, kanava estetty), varaus perutaan ja seuraava yritys
 * sallitaan vasta [retryMs] kuluttua. Sama raja koskee kaikkia kutsujia: palvelun viiden sekunnin tikki ei
 * saa yrittää uudelleen joka kierroksella, ja herätys ajastetaan samaan hetkeen ([retryInMs]).
 * Ei säieturvallinen: palvelu kutsuu tätä vain pääsäikeestä.
 */
internal class ReminderFlow(
    private val retryMs: Long,
    private val claim: () -> ReminderClaim?,
    private val post: (TripSnapshot) -> Boolean,
    private val release: (Long) -> Unit,
) {
    private var retryAtRtMs = 0L

    /** Yrittää näyttää muistutuksen, jos se on ajankohtainen ja yritys on sallittu. true = näytettiin. */
    fun attempt(nowRtMs: Long): Boolean {
        if (nowRtMs < retryAtRtMs) return false
        val c = claim() ?: return false
        if (post(c.snapshot)) {
            retryAtRtMs = 0L
            return true
        }
        release(c.token)
        retryAtRtMs = nowRtMs + retryMs
        return false
    }

    /** Aika seuraavaan sallittuun yritykseen; 0, jos yritys on sallittu heti. */
    fun retryInMs(nowRtMs: Long): Long = (retryAtRtMs - nowRtMs).coerceAtLeast(0L)
}
