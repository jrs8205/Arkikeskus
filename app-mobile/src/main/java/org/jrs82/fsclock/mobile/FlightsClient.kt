package org.jrs82.fsclock.mobile

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.jrs82.fsclock.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Lennot-Workerin JSON-asiakas. Hakee koko Suomen kevyen lentodatan yhdellä kutsulla;
 *  suodatus/haku tehdään paikallisesti ([FlightsFilter]). Julkinen endpoint, ei avainta. */
object FlightsClient {
    private const val BASE_URL = "https://lennot.jarsi.workers.dev"
    private const val TIMEOUT_MS = 10_000
    private const val MAX_BODY = 4_000_000

    fun fetch(): FlightsData? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$BASE_URL/flights").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "Arkikeskus/" + BuildConfig.VERSION_NAME + " (Android)")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) {
                Log.w("FlightsClient", "HTTP " + conn.responseCode)
                return null
            }
            val baos = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    baos.write(buf, 0, read)
                    if (baos.size() > MAX_BODY) break
                }
            }
            parse(baos.toString("UTF-8"))
        } catch (e: Exception) {
            Log.w("FlightsClient", "fetch failed: " + e.message)
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun parse(json: String): FlightsData {
        val o = JSONObject(json)
        val updated = isoToMs(str(o, "updated")) ?: 0L
        return FlightsData(updated, parseList(o.optJSONArray("arr"), FlightDir.ARR), parseList(o.optJSONArray("dep"), FlightDir.DEP))
    }

    private fun parseList(arr: JSONArray?, dir: FlightDir): List<Flight> {
        if (arr == null) return emptyList()
        val out = ArrayList<Flight>(arr.length())
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val fno = str(f, "fno") ?: continue
            val sch = isoToMs(str(f, "sch")) ?: continue
            val csArr = f.optJSONArray("cs")
            val cs = if (csArr == null) emptyList() else (0 until csArr.length())
                .mapNotNull { j -> csArr.optString(j, "").takeIf { it.isNotBlank() } }
            out.add(
                Flight(
                    dir = dir,
                    airport = str(f, "apt") ?: "",
                    flightNo = fno,
                    scheduledMs = sch,
                    estimatedMs = isoToMs(str(f, "est")),
                    actualMs = isoToMs(str(f, "act")),
                    statusCode = str(f, "scode") ?: "",
                    status = str(f, "st") ?: "",
                    otherAirport = str(f, "apt2") ?: "",
                    city = str(f, "city") ?: "",
                    gate = str(f, "gate"),
                    stand = str(f, "stand"),
                    belt = str(f, "belt"),
                    checkin = str(f, "chk"),
                    aircraft = str(f, "ac"),
                    codeshares = cs,
                ),
            )
        }
        return out
    }

    /** JSON-null → null; muuten trimmattu arvo (tyhjä → null). */
    private fun str(o: JSONObject, key: String): String? {
        if (o.isNull(key)) return null
        val s = o.optString(key, "")
        return s.ifBlank { null }
    }

    /** ISO-8601 (`…Z`) → epoch ms. Fallback: doc-näytteen US-muoto. Parsimaton → null. */
    private fun isoToMs(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            java.time.Instant.parse(s).toEpochMilli()
        } catch (e: Exception) {
            try {
                val fmt = java.text.SimpleDateFormat("M/d/yyyy h:mm:ss a", java.util.Locale.US)
                fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                fmt.parse(s)?.time
            } catch (e2: Exception) {
                null
            }
        }
    }
}
