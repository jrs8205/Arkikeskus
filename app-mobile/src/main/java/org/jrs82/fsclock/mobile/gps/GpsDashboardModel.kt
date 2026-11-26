package org.jrs82.fsclock.mobile.gps

/** Mittariston kolme näkymää. Valinta tallennetaan nimellä ([name]). */
enum class DashboardView {
    BASIC, TRIP, FULL;

    companion object {
        /** Tallennettu valinta; puuttuva tai tuntematon arvo on nykyistä vastaava Perus-näkymä. */
        fun fromPref(value: String?): DashboardView = values().firstOrNull { it.name == value } ?: BASIC
    }
}

/**
 * Mittaristonäkymän päätökset ilman Android- tai Compose-riippuvuuksia: mitkä painikkeet missäkin matkan
 * tilassa, milloin asettelu on vaaka ja milloin sovelluksen alapalkki piilotetaan.
 */
object GpsDashboardModel {
    /** Oletusasetukset: valittu näkymä ([DashboardView.name]) ja Näyttö päällä -kytkin. */
    const val KEY_VIEW = "gps_dashboard_view"
    const val KEY_KEEP_SCREEN_ON = "gps_keep_screen_on"

    /** Tätä matalammassa vaakaikkunassa (puhelin) alapalkki piilotetaan GPS-näkymässä. */
    const val COMPACT_HEIGHT_DP = 480

    /** Matka- ja Täysi-näkymän pääpainikkeet tilan mukaan. */
    fun primaryActions(phase: TripPhase): List<TripAction> = when (phase) {
        TripPhase.NONE -> listOf(TripAction.START)
        TripPhase.RUNNING -> listOf(TripAction.PAUSE, TripAction.FINISH)
        TripPhase.PAUSED -> listOf(TripAction.RESUME, TripAction.FINISH)
        TripPhase.FINISHED -> listOf(TripAction.RESET, TripAction.NEW_TRIP)
    }

    /** Vähemmän korostettu lisätoiminto: lopetettua matkaa voi jatkaa samoista luvuista. */
    fun secondaryAction(phase: TripPhase): TripAction? =
        if (phase == TripPhase.FINISHED) TripAction.CONTINUE else null

    /** Perus-näkymän matkan merkki näkyy vain, kun matka on käynnissä tai tauolla. */
    fun chipVisible(phase: TripPhase): Boolean = phase == TripPhase.RUNNING || phase == TripPhase.PAUSED

    fun isLandscape(widthDp: Int, heightDp: Int): Boolean = widthDp > heightDp

    /** Matkan aikana ylärivi piiloutuu, kun näyttöön ei ole koskettu näin pitkään aikaan. */
    const val HEADER_HIDE_MS = 5_000L

    /**
     * Näkyykö ylärivi (otsikko, näkymän valitsin, Näyttö päällä). Matkan ollessa käynnissä rivi piiloutuu,
     * kun viimeisestä kosketuksesta on kulunut [hideAfterMs], jotta ajon aikana näkyvät vain luvut; mikä
     * tahansa kosketus tai näppäimen painallus tuo sen takaisin. Muissa tiloissa rivi on aina näkyvissä.
     * [hideAfterMs] = [Long.MAX_VALUE] poistaa piilotuksen (esteettömyysasetus tai ruudunlukija), eikä rivi
     * piiloudu, kun näppäimistön kohdistus on jossakin sen painikkeessa ([headerFocused]) tai kun rivi on
     * tuotu esiin esteettömyystoiminnolla ([pinned]): apupalvelun käyttäjä ei tuota kosketuksia, joista
     * aikaraja alkaisi alusta.
     */
    fun headerVisible(
        phase: TripPhase,
        lastTouchRtMs: Long,
        nowRtMs: Long,
        hideAfterMs: Long,
        headerFocused: Boolean = false,
        pinned: Boolean = false,
    ): Boolean {
        if (phase != TripPhase.RUNNING || hideAfterMs == Long.MAX_VALUE || headerFocused || pinned) return true
        val sinceTouchMs = nowRtMs - lastTouchRtMs
        return sinceTouchMs < hideAfterMs
    }

    /** Arvoa ei pienennetä tätä pienemmäksi, vaikka se ei mahtuisi. */
    const val FIT_MIN_SCALE = 0.4f

    /** Laatan otsikkoa pienennetään enintään tähän; sitä pidempi sana saa katketa. */
    const val LABEL_MIN_SCALE = 0.6f

    private const val FIT_MAX_ROUNDS = 10

    /**
     * Pienennyskerroin (enintään 1), jolla sisältö mahtuu leveyteen [availablePx]. [widthAt] mittaa
     * sisällön leveyden annetulla kertoimella.
     *
     * Kerroin haetaan mittaamalla uudelleen eikä yhdellä jakolaskulla: järjestelmän fonttiskaalaus
     * suurentaa pientä tekstiä enemmän kuin isoa, joten leveys ei pienene samassa suhteessa kuin kerroin.
     */
    fun fitScale(availablePx: Int, minScale: Float = FIT_MIN_SCALE, widthAt: (Float) -> Int): Float {
        if (availablePx <= 0) return 1f
        var scale = 1f
        repeat(FIT_MAX_ROUNDS) {
            val width = widthAt(scale)
            if (width <= availablePx || scale <= minScale) return scale
            scale = (scale * availablePx / width * 0.98f).coerceAtLeast(minScale)
        }
        return scale
    }

    fun hideBottomBar(
        isSpeedometer: Boolean,
        widthDp: Int,
        heightDp: Int,
        menuOpen: Boolean,
        settingsOpen: Boolean,
    ): Boolean = isSpeedometer && !menuOpen && !settingsOpen &&
        isLandscape(widthDp, heightDp) && heightDp < COMPACT_HEIGHT_DP
}
