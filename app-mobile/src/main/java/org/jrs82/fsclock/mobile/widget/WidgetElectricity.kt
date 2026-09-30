package org.jrs82.fsclock.mobile.widget

import org.json.JSONArray
import org.json.JSONObject

/**
 * Sähköwidgetin varttilista: worker tallentaa kaikki tiedossa olevat vartit (veroton snt/kWh), ja
 * widget hakee KULUVAN vartin piirtohetkellä. Yksi cacheen tallennettu hinta vanheni, koska
 * WorkManagerin 15 min jaksotyö ei osu varttirajoihin.
 */
object WidgetElectricity {
    private const val QUARTER_MS = 15L * 60_000L

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

    fun currentPrice(list: List<QuarterPrice>, nowMs: Long): Double {
        for (q in list) {
            if (q.timestamp <= nowMs && nowMs < q.timestamp + QUARTER_MS) return q.snt
        }
        return Double.NaN
    }

    /** Seuraavan vartin alku (epoch-ms). Suomen aikavyöhykkeen siirtymä on täysiä tunteja, joten
     *  UTC-varttirajat ovat samat kuin paikalliset. */
    fun nextQuarterBoundary(nowMs: Long): Long = (nowMs / QUARTER_MS + 1) * QUARTER_MS
}
