package org.jrs82.fsclock.mobile

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
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

    private val READ = setOf(HealthPermission.getReadPermission(StepsRecord::class))
    private val scope = CoroutineScope(Dispatchers.Main)

    fun interface BoolCallback { fun onResult(value: Boolean) }
    fun interface StepsCallback { fun onResult(steps: Long) }
    fun interface HistoryCallback { fun onResult(labels: Array<String>, values: LongArray) }

    @JvmStatic
    fun isAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    @JvmStatic
    fun permissions(): Array<String> = READ.toTypedArray()

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
                    .permissionController.getGrantedPermissions().containsAll(READ)
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
                    PERIOD_WEEKS -> today.minusWeeks((count - 1).toLong())
                    PERIOD_MONTHS -> today.withDayOfMonth(1).minusMonths((count - 1).toLong())
                    else -> today.minusDays((count - 1).toLong())
                }
                val buckets = client.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(
                            startDate.atStartOfDay(), LocalDateTime.now()
                        ),
                        timeRangeSlicer = slicer
                    )
                )
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
}
