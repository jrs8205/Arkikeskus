package org.jrs82.fsclock.mobile

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/**
 * Laitteen koordinaattien paikannimi sääpaikaksi: ensin MML:n käänteinen geokoodaus, sen pettäessä Androidin
 * oma [Geocoder] (vain Suomen osoitteet). Tausta: MML sulki avoimet rajapintansa 22.10.2026, ja koska paikka
 * päivittyi vain MML:n vastauksella, sää jäi päiviksi viimeisimpään onnistuneeseen paikkaan, vaikka laitteen
 * sijainti oli tiedossa. Lähintä kuntakeskusta (KuntaList) ei käytetä varakeinona: Martinlaaksosta lähin keskus
 * on Kauniainen. Kun kumpikaan ei anna paikkaa, palautetaan null ja kutsuja pitää edellisen paikan.
 */
internal object DevicePlaceResolver {

    enum class Source { MML, GEOCODER }

    data class Resolved(val dataPlace: String, val displayPlace: String, val source: Source)

    private const val TAG = "DevicePlaceResolver"

    // Katkon aikana jokainen MML-yritys odottaa aikakatkaisun (8 s) ennen varakeinoa → ei yritetä joka avauksella.
    private const val MML_BACKOFF_MS = 10L * 60_000L
    private const val GEOCODER_TIMEOUT_MS = 8_000L

    // FMI hakee havainnot paikan nimellä ja ennusteen koordinaateilla; ulkomaan paikalla (Tallinna, New York)
    // haku epäonnistuu, jolloin etusivu jäisi lataustilaan ja widget näyttäisi uuden nimen vanhalla lämpötilalla.
    // Ahvenanmaan ISO 3166 -koodi on AX.
    private val SUPPORTED_COUNTRY_CODES = setOf("FI", "AX")

    @Volatile private var mmlSkipUntilMs = 0L

    private val legacyGeocoderCall = TimedBlockingCall("DevicePlaceGeocoder")

    internal fun resetMmlBreaker() {
        mmlSkipUntilMs = 0L
    }

    suspend fun resolve(context: Context, latitude: Double, longitude: Double): Resolved? {
        val app = context.applicationContext
        return resolveWith(
            mml = {
                withContext(Dispatchers.IO) { MmlGeocodingClient.reversePlace(latitude, longitude) }
                    ?.let { Resolved(it.dataPlace, it.displayPlace, Source.MML) }
            },
            geocoder = { androidGeocoderPlace(app, latitude, longitude) },
        )
    }

    /** Ketjun järjestys ja MML:n katkaisija ilman Androidia (yksikkötestattava). Tyhjä MML-vastaus (esim.
     *  ulkomailla) ei laukaise katkaisijaa; vain poikkeus (verkko, HTTP-virhe, rikkinäinen vastaus) laukaisee.
     *  Kello on monotoninen, jottei seinäkellon korjaus taaksepäin venytä MML:n ohitusta. */
    internal suspend fun resolveWith(
        mml: suspend () -> Resolved?,
        geocoder: suspend () -> Resolved?,
        clock: () -> Long = SystemClock::elapsedRealtime,
    ): Resolved? {
        if (clock() >= mmlSkipUntilMs) {
            try {
                mml()?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mmlSkipUntilMs = clock() + MML_BACKOFF_MS
                Log.w(TAG, "MML reverse geocoding failed, using fallback", e)
            }
        }
        return try {
            geocoder()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Android geocoder failed", e)
            null
        }
    }

    /** Androidin osoitekentät samaan muotoon kuin MML:n tulos: data = kunta, näyttö = "kunta, kaupunginosa".
     *  Kaupunginosa on Geocoderin subLocality tai sen puuttuessa saman kunnan [postalArea]. Muu kuin Suomen
     *  (FI/AX) tai maaton osoite → null. */
    internal fun fromAddressFields(
        countryCode: String?,
        locality: String?,
        subLocality: String?,
        subAdminArea: String?,
        postalArea: PostalAreas.Area? = null,
    ): Resolved? {
        if (countryCode?.trim()?.uppercase(Locale.ROOT) !in SUPPORTED_COUNTRY_CODES) return null
        val city = locality?.trim().orEmpty().ifEmpty { subAdminArea?.trim().orEmpty() }
        if (city.isEmpty()) return null
        val district = subLocality?.trim().orEmpty()
            .takeIf { it.isNotEmpty() && !it.equals(city, ignoreCase = true) && !it.all(Char::isDigit) }
            ?: postalArea?.let { PostalAreas.districtFor(it, city) }
        val display = if (district != null) "$city, $district" else city
        return Resolved(city, display, Source.GEOCODER)
    }

    private suspend fun androidGeocoderPlace(context: Context, latitude: Double, longitude: Double): Resolved? {
        if (!Geocoder.isPresent()) return null
        // Suomenkieliset nimet kuten MML:llä myös sv/en-käyttöliittymässä: kuntanimi tallentuu datapaikaksi
        // (varoitusalueet, suosikit), joten kielen on pysyttävä samana.
        val geocoder = Geocoder(context, Locale("fi", "FI"))
        val address = if (Build.VERSION.SDK_INT >= 33) {
            withTimeoutOrNull(GEOCODER_TIMEOUT_MS) {
                suspendCancellableCoroutine<Address?> { cont ->
                    geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                }
            }
        } else {
            // API 30–32: synkroninen Binder-kutsu, jota keskeytys ei pysäytä → oma säie, jonka aikaraja vapauttaa.
            legacyGeocoderCall.runWithTimeout(GEOCODER_TIMEOUT_MS) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
            }
        } ?: return null
        // Google antaa Suomessa harvoin subLocalityn (Raappavuori: vain katu, 01620 ja Vantaa) → postinumeroalue.
        val postalArea = withContext(Dispatchers.IO) { PostalAreas.lookup(context, address.postalCode) }
        return fromAddressFields(
            address.countryCode, address.locality, address.subLocality, address.subAdminArea, postalArea,
        )
    }
}

/**
 * Synkroninen kutsu, joka ei välttämättä reagoi keskeytykseen (Android 11:n Geocoder tekee Binder-kutsun,
 * jota Thread.interrupt ei pysäytä), omassa daemon-säikeessään. Aikaraja vapauttaa odottajan, vaikka kutsu
 * jatkuisi; myöhästynyt tulos hylätään. Kun edellinen kutsu yhä roikkuu, uusi palaa heti nullina, joten toistuvat
 * Päivitä-painallukset eivät kasaa jonoa eivätkä säikeitä. Joutilas säie lopetetaan 30 sekunnin jälkeen.
 */
internal class TimedBlockingCall(threadName: String) {

    private val inFlight = AtomicBoolean(false)

    private val executor = ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
        Thread(task, threadName).apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    val isBusy: Boolean get() = inFlight.get()

    suspend fun <T : Any> runWithTimeout(timeoutMs: Long, block: () -> T?): T? {
        if (!inFlight.compareAndSet(false, true)) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<T?> { cont ->
                try {
                    executor.execute {
                        val result = runCatching(block)
                        inFlight.set(false)
                        if (cont.isActive) cont.resumeWith(result)
                    }
                } catch (e: RejectedExecutionException) {
                    inFlight.set(false)
                    throw e
                }
            }
        }
    }
}
