package org.jrs82.fsclock.mobile.gps

/**
 * Yksi GPS-mittaus ilman Android-luokkia, jotta laskenta on testattavissa JVM:ssä.
 * [rtMs] on aika laitteen käynnistyksestä millisekunteina (elapsedRealtime), ei seinäkellon aika.
 * null tarkoittaa, ettei vastaanotin antanut kyseistä tietoa.
 */
data class GpsFix(
    val rtMs: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Float?,
    val accuracyM: Float?,
    val speedAccuracyMps: Float?,
)

/**
 * GPS-lähteen viimeisin tila: mittaus, satelliitit (käytössä / näkyvissä), onko paikannus päällä ja onko
 * laitteessa GPS-paikanninta lainkaan ([providerPresent] on epätosi, kun kuuntelua ei voitu rekisteröidä,
 * koska paikanninta ei ole: silloin mittausta ei kannata odottaa).
 */
data class GpsReading(
    val fix: GpsFix? = null,
    val satUsed: Int = 0,
    val satVisible: Int = 0,
    val providerEnabled: Boolean = true,
    val providerPresent: Boolean = true,
)

/** Mittaus, josta kelvottomat luvut (ääretön, NaN, negatiivinen) on merkitty puuttuviksi. */
fun GpsFix.clean(): GpsFix = copy(
    speedMps = speedMps.validOrNull(),
    accuracyM = accuracyM.validOrNull(),
    speedAccuracyMps = speedAccuracyMps.validOrNull(),
)

private fun Float?.validOrNull(): Float? = this?.takeIf { it.isFinite() && it >= 0f }

/**
 * Lukema, kun paikannus kytketään päälle tai pois. Pois kytkettäessä vanha mittaus ja satelliittiluvut
 * mitätöidään heti; päälle kytkettäessä odotetaan uutta mittausta.
 */
fun GpsReading.withProvider(enabled: Boolean): GpsReading = when {
    !enabled -> GpsReading(providerEnabled = false)
    providerEnabled -> this
    else -> GpsReading(providerEnabled = true)
}

/** Uusi mittaus. Paikannuksen ollessa pois päältä myöhässä tullut mittaus ei palauta vanhaa näyttöä. */
fun GpsReading.withFix(fix: GpsFix): GpsReading = if (providerEnabled) copy(fix = fix) else this

fun GpsReading.withSatellites(used: Int, visible: Int): GpsReading =
    if (providerEnabled) copy(satUsed = used, satVisible = visible) else this
