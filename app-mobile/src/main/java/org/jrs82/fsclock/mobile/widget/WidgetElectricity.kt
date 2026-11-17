package org.jrs82.fsclock.mobile.widget

import org.jrs82.fsclock.mobile.ElectricityHourly
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Sähköwidgetin varttilista: worker tallentaa kaikki tiedossa olevat vartit (veroton snt/kWh), ja
 * widget hakee KULUVAN vartin piirtohetkellä. Yksi cacheen tallennettu hinta vanheni, koska
 * WorkManagerin 15 min jaksotyö ei osu varttirajoihin.
 */
object WidgetElectricity {
    private const val QUARTER_MS = 15L * 60_000L
    private val HELSINKI: ZoneId = ZoneId.of("Europe/Helsinki")

    data class QuarterPrice(val timestamp: Long, val snt: Double)

    fun encode(list: List<QuarterPrice>): String {
        val arr = JSONArray()
        for (q in list) arr.put(JSONObject().put("t", q.timestamp).put("p", q.snt))
        return arr.toString()
    }

    fun decode(json: String): List<QuarterPrice> = try {
        val arr = JSONArray(json)
        val out = ArrayList<QuarterPrice>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(QuarterPrice(o.getLong("t"), o.getDouble("p")))
        }
        out
    } catch (e: Exception) {
        emptyList()
    }

    /** [slotMs] = listan jakson pituus: vartti, tai tunti kun lista on [hourly]n tuottama. */
    fun currentPrice(list: List<QuarterPrice>, nowMs: Long, slotMs: Long = QUARTER_MS): Double {
        for (q in list) {
            if (q.timestamp <= nowMs && nowMs < q.timestamp + slotMs) return q.snt
        }
        return Double.NaN
    }

    /** Widgetin näytettävät jaksot ja niiden pituus. */
    data class Slots(val list: List<QuarterPrice>, val slotMs: Long)

    /** Tuntitilassa kuluvan päivän tunnit, jos sen jokainen tunti on täysi
     *  ([ElectricityHourly.completeDayHourAverages]); muuten koko varttilista sellaisenaan, jolloin
     *  vajaan tunnin tunnetut vartit pysyvät mukana hinnassa ja hintajanan asteikossa. */
    fun todaySlots(list: List<QuarterPrice>, nowMs: Long, hourly: Boolean, vat: Boolean): Slots {
        if (hourly) {
            val start = dayStart(nowMs)
            val end = Instant.ofEpochMilli(nowMs).atZone(HELSINKI).toLocalDate().plusDays(1)
                .atStartOfDay(HELSINKI).toInstant().toEpochMilli()
            val today = list.filter { it.timestamp >= start && it.timestamp < end }.map { it.timestamp to it.snt }
            val hours = ElectricityHourly.completeDayHourAverages(today, vat)
            if (hours != null) return Slots(hours.map { (timestamp, snt) -> QuarterPrice(timestamp, snt) }, ElectricityHourly.HOUR_MS)
        }
        return Slots(list, QUARTER_MS)
    }

    /** Kuluvan päivän alku Suomen aikaa (epoch-ms). */
    fun dayStart(nowMs: Long): Long =
        Instant.ofEpochMilli(nowMs).atZone(HELSINKI).toLocalDate().atStartOfDay(HELSINKI).toInstant().toEpochMilli()

    /** Kuluvan päivän (Suomen aikaa) halvin ja kallein vartti. Lasketaan piirtohetkellä, joten väli
     *  vaihtuu keskiyöllä ilman workerin ajoa. null jos lista ei kata päivää ensimmäisestä vartista
     *  viimeiseen: vajaa lista (sovelluspäivitystä edeltävä cache, joka alkoi tuntia ennen tallennusta,
     *  tai keskiyön jälkeen pelkät aamuyön vartit) näyttäisi loppupäivän ääriarvot koko päivän asteikkona. */
    fun dayRange(list: List<QuarterPrice>, nowMs: Long, slotMs: Long = QUARTER_MS): Pair<Double, Double>? {
        val today = Instant.ofEpochMilli(nowMs).atZone(HELSINKI).toLocalDate()
        val start = today.atStartOfDay(HELSINKI).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(HELSINKI).toInstant().toEpochMilli()
        var min = Double.NaN
        var max = Double.NaN
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        for (q in list) {
            if (q.timestamp < start || q.timestamp >= end) continue
            if (min.isNaN() || q.snt < min) min = q.snt
            if (max.isNaN() || q.snt > max) max = q.snt
            if (q.timestamp < first) first = q.timestamp
            if (q.timestamp > last) last = q.timestamp
        }
        if (first != start || last != end - slotMs) return null
        return min to max
    }

    /** Seuraavan vartin alku (epoch-ms). Suomen aikavyöhykkeen siirtymä on täysiä tunteja, joten
     *  UTC-varttirajat ovat samat kuin paikalliset. */
    fun nextQuarterBoundary(nowMs: Long): Long = (nowMs / QUARTER_MS + 1) * QUARTER_MS
}
