package org.jrs82.fsclock.mobile

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period

/** Kotlin-silta Health Connectiin. Vain tämä luokka on Kotlinia; muu Arkikeskus on Javaa.
 *  HealthConnectClientin aggregate-funktiot ovat suspend ja main-safe (hoitavat IO:n itse), joten
 *  ne ajetaan Main-scopessa ja tulos palautetaan Javaan SAM-callbackilla pääsäikeessä.
 *
 *  Lähde: Health Connect aggregate / aggregateGroupByPeriod deduplikoi useat lähteet (kello +
 *  puhelin), joten ÄLÄ summaa tätä raw TYPE_STEP_COUNTER -lukemaan. */
object HealthConnectStepsBridge {

    const val PERIOD_DAYS = 1
    const val PERIOD_WEEKS = 2
    const val PERIOD_MONTHS = 3

    // Askellupa on pakollinen HC-lähteelle; kaloriluvat ovat valinnaisia, joten ne pidetään
    // erillään: hasPermission vaatii vain askeleet, jottei vanha käyttäjä (vain Steps-luvalla)
    // menetä HC-askeldataa kun kaloriluvat lisätään pyyntöön.
    private val READ_STEPS = setOf(HealthPermission.getReadPermission(StepsRecord::class))
    private val READ_CALS = setOf(
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
    )
    // Historialupa (VALINNAINEN): ilman tätä HC sallii luvun vain 30 pv ennen ensimmäistä
    // lupahetkeä kirjatusta datasta — pidemmät kyselyt (8 vk / 6 kk / vienti) voivat epäonnistua.
    // Mukana lupaPYYNNÖISSÄ, mutta EI lupatarkistuksissa (hasPermission/hasCaloriePermission);
    // historiafunktioissa on lisäksi 30 pv:n clamp-fallback jos laaja kysely hylätään.
    private const val READ_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"
    // Valinnainen: sallii HC-luvun TAUSTATYÖSSÄ (askeltavoite-ilmoitus). Mukana lupaPYYNNÖISSÄ, EI
    // lupatarkistuksissa (hasPermission) — ilman taustalukulupaa HC-lähde toimii silti etualalla.
    private const val READ_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    private val scope = CoroutineScope(Dispatchers.Main)

    fun interface BoolCallback { fun onResult(value: Boolean) }
    fun interface StepsCallback { fun onResult(steps: Long) }
    fun interface SourcesCallback { fun onResult(packages: Array<String>, steps: LongArray) }
    fun interface HistoryCallback { fun onResult(labels: Array<String>, values: LongArray) }
    fun interface CaloriesCallback { fun onResult(activeKcal: Double, totalKcal: Double, has: Boolean) }
    fun interface CalorieHistoryCallback {
        fun onResult(labels: Array<String>, steps: LongArray,
                     activeKcal: DoubleArray, totalKcal: DoubleArray)
    }

    @JvmStatic
    fun isAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    @JvmStatic
    fun permissions(): Array<String> = (READ_STEPS + READ_CALS + READ_HISTORY + READ_BACKGROUND).toTypedArray()

    /** Vain askelluvat (pakollinen HC-lähteelle) + valinnainen historialupa. */
    @JvmStatic
    fun stepPermissions(): Array<String> = (READ_STEPS + READ_HISTORY + READ_BACKGROUND).toTypedArray()

    /** Vain kaloriluvat (valinnaiset, aktiivinen + kokonais) + valinnainen historialupa. */
    @JvmStatic
    fun caloriePermissions(): Array<String> = (READ_CALS + READ_HISTORY + READ_BACKGROUND).toTypedArray()

    /** Health Connectin oma lupanäkymä-contract Javan registerForActivityResultille. */
    @JvmStatic
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    @JvmStatic
    fun hasPermission(context: Context, cb: BoolCallback) {
        if (!isAvailable(context)) { cb.onResult(false); return }
        scope.launch {
            val granted = try {
                HealthConnectClient.getOrCreate(context)
                    .permissionController.getGrantedPermissions().containsAll(READ_STEPS)
            } catch (e: Exception) {
                false
            }
            cb.onResult(granted)
        }
    }

    /** Onko kaloriluvat (aktiivinen + kokonais) myönnetty? Erillinen askelluvasta. */
    @JvmStatic
    fun hasCaloriePermission(context: Context, cb: BoolCallback) {
        if (!isAvailable(context)) { cb.onResult(false); return }
        scope.launch {
            val granted = try {
                HealthConnectClient.getOrCreate(context)
                    .permissionController.getGrantedPermissions().containsAll(READ_CALS)
            } catch (e: Exception) {
                false
            }
            cb.onResult(granted)
        }
    }

    /** Tämän päivän askeleet (aggregate päivän alusta nyt-hetkeen). -1 jos ei saatavilla/virhe. */
    @JvmStatic
    fun todaySteps(context: Context, cb: StepsCallback) {
        if (!isAvailable(context)) { cb.onResult(-1L); return }
        scope.launch {
            val steps = try {
                val client = HealthConnectClient.getOrCreate(context)
                val res = client.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(
                            LocalDate.now().atStartOfDay(), LocalDateTime.now()
                        )
                    )
                )
                res[StepsRecord.COUNT_TOTAL] ?: 0L
            } catch (e: Exception) {
                -1L
            }
            cb.onResult(steps)
        }
    }

    /** Tämän päivän askeleet LÄHTEITTÄIN. Lähteet TUNNISTETAAN readRecords-raakakirjauksista
     *  (yleismaallinen — listaa kaikki HC:hen kirjoittaneet sovellukset), mutta per-lähde-LUVUT
     *  haetaan aggregatella dataOriginFilter-rajauksella: Androidin ohje varoittaa summaamasta
     *  raakakirjauksia itse (päällekkäiset jaksot tuplaantuisivat). Eri lähteiden luvut voivat
     *  silti mitata samoja askelia keskenään — kokonaisluku on HC:n dedupoima (todaySteps). */
    @JvmStatic
    fun todayStepsBySource(context: Context, cb: SourcesCallback) {
        if (!isAvailable(context)) { cb.onResult(emptyArray(), LongArray(0)); return }
        scope.launch {
            val sums = LinkedHashMap<String, Long>()
            try {
                val client = HealthConnectClient.getOrCreate(context)
                val range = TimeRangeFilter.between(LocalDate.now().atStartOfDay(), LocalDateTime.now())
                // 1) Tunnista kirjoittaneet sovellukset raakakirjauksista
                val origins = LinkedHashSet<String>()
                var pageToken: String? = null
                do {
                    val resp = client.readRecords(
                        ReadRecordsRequest(
                            recordType = StepsRecord::class,
                            timeRangeFilter = range,
                            pageSize = 1000,
                            pageToken = pageToken
                        )
                    )
                    for (r in resp.records) origins.add(r.metadata.dataOrigin.packageName)
                    pageToken = resp.pageToken
                } while (pageToken != null)
                // 2) Per-lähde-luku HC:n omalla aggregaatilla (dedup lähteen sisällä)
                for (pkg in origins) {
                    val res = client.aggregate(
                        AggregateRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = range,
                            dataOriginFilter = setOf(
                                androidx.health.connect.client.records.metadata.DataOrigin(pkg))
                        )
                    )
                    sums[pkg] = res[StepsRecord.COUNT_TOTAL] ?: 0L
                }
            } catch (e: Exception) {
                // virhe → palautetaan siihen asti kerätty (tai tyhjä)
            }
            val sorted = sums.entries.sortedByDescending { it.value }
            cb.onResult(
                sorted.map { it.key }.toTypedArray(),
                LongArray(sorted.size) { i -> sorted[i].value }
            )
        }
    }

    /** Tämän päivän kalorit Health Connectista: aktiivinen (ilman BMR:ää) + kokonais (sis. BMR).
     *  has=false jos dataa/lupaa ei ole → kutsuja käyttää omaa askelarviota. ÄLÄ summaa arvioon. */
    @JvmStatic
    fun todayCalories(context: Context, cb: CaloriesCallback) {
        if (!isAvailable(context)) { cb.onResult(0.0, 0.0, false); return }
        scope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(context)
                val res = client.aggregate(
                    AggregateRequest(
                        metrics = setOf(
                            ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                            TotalCaloriesBurnedRecord.ENERGY_TOTAL
                        ),
                        timeRangeFilter = TimeRangeFilter.between(
                            LocalDate.now().atStartOfDay(), LocalDateTime.now()
                        )
                    )
                )
                val active = res[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0
                val total = res[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0
                cb.onResult(active, total, active > 0.0 || total > 0.0)
            } catch (e: Exception) {
                cb.onResult(0.0, 0.0, false)
            }
        }
    }

    /** Deduplikoitu historia ämpäreittäin (päivä/viikko/kk). labels[i] = ISO-päivä ämpärin alusta. */
    @JvmStatic
    fun history(context: Context, periodType: Int, count: Int, cb: HistoryCallback) {
        if (!isAvailable(context)) { cb.onResult(emptyArray(), LongArray(0)); return }
        scope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(context)
                val slicer = when (periodType) {
                    PERIOD_WEEKS -> Period.ofWeeks(1)
                    PERIOD_MONTHS -> Period.ofMonths(1)
                    else -> Period.ofDays(1)
                }
                val today = LocalDate.now()
                val startDate = when (periodType) {
                    // Aloita ISO-viikon maanantaista (ei rullaava 7 pv tästä viikonpäivästä).
                    PERIOD_WEEKS -> today.with(java.time.temporal.WeekFields.ISO.dayOfWeek(), 1)
                        .minusWeeks((count - 1).toLong())
                    PERIOD_MONTHS -> today.withDayOfMonth(1).minusMonths((count - 1).toLong())
                    else -> today.minusDays((count - 1).toLong())
                }
                // Ilman READ_HEALTH_DATA_HISTORY-lupaa HC voi hylätä kyselyn, joka ulottuu yli
                // 30 pv ennen ensimmäistä lupahetkeä → fallback: kokeile uudelleen 30 päivään
                // rajattuna, jotta käyttäjä näkee edes tuoreen historian (ei tyhjää).
                val buckets = try {
                    client.aggregateGroupByPeriod(
                        AggregateGroupByPeriodRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(
                                startDate.atStartOfDay(), LocalDateTime.now()
                            ),
                            timeRangeSlicer = slicer
                        )
                    )
                } catch (e: Exception) {
                    android.util.Log.w("HcSteps", "Laaja historiakysely hylättiin, rajataan 30 pv:ään", e)
                    client.aggregateGroupByPeriod(
                        AggregateGroupByPeriodRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(
                                maxOf(startDate, today.minusDays(29)).atStartOfDay(), LocalDateTime.now()
                            ),
                            timeRangeSlicer = slicer
                        )
                    )
                }
                val labels = ArrayList<String>(buckets.size)
                val values = ArrayList<Long>(buckets.size)
                for (b in buckets) {
                    labels.add(b.startTime.toLocalDate().toString())
                    values.add(b.result[StepsRecord.COUNT_TOTAL] ?: 0L)
                }
                cb.onResult(labels.toTypedArray(), values.toLongArray())
            } catch (e: Exception) {
                cb.onResult(emptyArray(), LongArray(0))
            }
        }
    }

    /** Historia askeleet + kalorit ämpäreittäin (sama jako kuin historyssa). includeCalories=false →
     *  vain askeleet (kaloriluvat puuttuvat), activeKcal/totalKcal = 0. Kalorit kilokaloreina;
     *  0 jos ämpärissä ei ole kaloridataa → kutsuja näyttää tarvittaessa oman arvion. */
    @JvmStatic
    fun historyWithCalories(context: Context, periodType: Int, count: Int, includeCalories: Boolean,
                            cb: CalorieHistoryCallback) {
        if (!isAvailable(context)) {
            cb.onResult(emptyArray(), LongArray(0), DoubleArray(0), DoubleArray(0)); return
        }
        scope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(context)
                val slicer = when (periodType) {
                    PERIOD_WEEKS -> Period.ofWeeks(1)
                    PERIOD_MONTHS -> Period.ofMonths(1)
                    else -> Period.ofDays(1)
                }
                val today = LocalDate.now()
                val startDate = when (periodType) {
                    // Aloita ISO-viikon maanantaista (ei rullaava 7 pv tästä viikonpäivästä).
                    PERIOD_WEEKS -> today.with(java.time.temporal.WeekFields.ISO.dayOfWeek(), 1)
                        .minusWeeks((count - 1).toLong())
                    PERIOD_MONTHS -> today.withDayOfMonth(1).minusMonths((count - 1).toLong())
                    else -> today.minusDays((count - 1).toLong())
                }
                val metrics = if (includeCalories) setOf(
                    StepsRecord.COUNT_TOTAL,
                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                    TotalCaloriesBurnedRecord.ENERGY_TOTAL
                ) else setOf(StepsRecord.COUNT_TOTAL)
                // Sama 30 pv -fallback kuin history()-funktiossa (historialupa voi puuttua).
                val buckets = try {
                    client.aggregateGroupByPeriod(
                        AggregateGroupByPeriodRequest(
                            metrics = metrics,
                            timeRangeFilter = TimeRangeFilter.between(
                                startDate.atStartOfDay(), LocalDateTime.now()
                            ),
                            timeRangeSlicer = slicer
                        )
                    )
                } catch (e: Exception) {
                    android.util.Log.w("HcSteps", "Laaja historiakysely hylättiin, rajataan 30 pv:ään", e)
                    client.aggregateGroupByPeriod(
                        AggregateGroupByPeriodRequest(
                            metrics = metrics,
                            timeRangeFilter = TimeRangeFilter.between(
                                maxOf(startDate, today.minusDays(29)).atStartOfDay(), LocalDateTime.now()
                            ),
                            timeRangeSlicer = slicer
                        )
                    )
                }
                val labels = ArrayList<String>(buckets.size)
                val steps = ArrayList<Long>(buckets.size)
                val active = ArrayList<Double>(buckets.size)
                val total = ArrayList<Double>(buckets.size)
                for (b in buckets) {
                    labels.add(b.startTime.toLocalDate().toString())
                    steps.add(b.result[StepsRecord.COUNT_TOTAL] ?: 0L)
                    active.add(if (includeCalories)
                        b.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0
                    else 0.0)
                    total.add(if (includeCalories)
                        b.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0
                    else 0.0)
                }
                cb.onResult(labels.toTypedArray(), steps.toLongArray(),
                    active.toDoubleArray(), total.toDoubleArray())
            } catch (e: Exception) {
                cb.onResult(emptyArray(), LongArray(0), DoubleArray(0), DoubleArray(0))
            }
        }
    }
}
