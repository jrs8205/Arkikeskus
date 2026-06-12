package org.jrs82.fsclock.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import org.jrs82.fsclock.BuildConfig
import org.jrs82.fsclock.db.WorkoutEntity
import org.jrs82.fsclock.db.WorkoutPointEntity
import org.jrs82.fsclock.db.WorkoutSplitEntity
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Lenkin jakokuva (PNG): MML-taustakartan tiilet haetaan SUORAAN WMTS-rajapinnasta (sama
 * lähde jota live-kartatkin käyttävät) ja kootaan Canvasilla Web Mercator -projektiolla;
 * reitti, lähtö/maali ja km-merkit piirretään päälle ja alle tilastokaista + MML-attribuutio.
 *
 * HUOM: EI MapLibre MapSnapshotteria — sen overlay-piirto kaatuu NPE:hen 11.x:ssä
 * (maplibre_logo_icon on anydpi-vektori, jota kirjaston BitmapFactory-polku ei osaa avata;
 * ajetaan ehdoitta ennen callbackia, joten sitä ei voi kiertää optioilla eikä try/catchilla).
 * Oma tiilikokoonpano on samalla deterministinen: ei Looper-vaatimuksia eikä kirjastotilaa.
 * Jos tiilihaku epäonnistuu (ei verkkoa), reitti piirretään tasaiselle taustalle → jako
 * toimii aina.
 */
internal object WorkoutShareImage {

    private const val MAP_W = 1080
    private const val MAP_H = 1080
    private const val BAND_H = 300
    private const val TILE = 256
    private const val MAX_ZOOM = 18
    private const val MIN_ZOOM = 5
    private const val FETCH_BUDGET_MS = 12_000L

    private val FI_IMG = Locale("fi", "FI")

    /** Voi kutsua mistä säikeestä vain; onDone kutsutaan main-säikeessä, bitmap aina ei-null. */
    fun render(
        context: Context,
        workout: WorkoutEntity,
        points: List<WorkoutPointEntity>,
        splits: List<WorkoutSplitEntity>,
        onDone: (Bitmap) -> Unit,
    ) {
        val app = context.applicationContext
        Thread {
            val bmp = try {
                compose(app, workout, points, splits)
            } catch (e: Exception) {
                // Viimeinen oljenkorsi: pelkkä tilastokaista ilman karttaa.
                Bitmap.createBitmap(MAP_W, MAP_H + BAND_H, Bitmap.Config.ARGB_8888).also {
                    val c = Canvas(it)
                    c.drawColor(0xFFE8EAED.toInt())
                    drawStatsBand(c, workout, MAP_W, MAP_H, BAND_H, 1f)
                }
            }
            Handler(Looper.getMainLooper()).post { onDone(bmp) }
        }.start()
    }

    // ---------- Web Mercator ----------

    private fun worldX(lon: Double, z: Int): Double =
        (lon + 180.0) / 360.0 * TILE * (1 shl z)

    private fun worldY(lat: Double, z: Int): Double {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val rad = Math.toRadians(clamped)
        return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * TILE * (1 shl z)
    }

    private fun compose(
        context: Context,
        workout: WorkoutEntity,
        points: List<WorkoutPointEntity>,
        splits: List<WorkoutSplitEntity>,
    ): Bitmap {
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        for (p in points) {
            minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
            minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
        }

        // Suurin zoom jolla reitti (10 % marginaalilla) mahtuu karttaosaan.
        var zoom = MAX_ZOOM
        while (zoom > MIN_ZOOM) {
            val w = worldX(maxLon, zoom) - worldX(minLon, zoom)
            val h = worldY(minLat, zoom) - worldY(maxLat, zoom)
            if (w <= MAP_W * 0.8 && h <= MAP_H * 0.8) break
            zoom--
        }
        val z = zoom
        val centerX = (worldX(minLon, z) + worldX(maxLon, z)) / 2.0
        val centerY = (worldY(minLat, z) + worldY(maxLat, z)) / 2.0
        val originX = centerX - MAP_W / 2.0
        val originY = centerY - MAP_H / 2.0

        val out = Bitmap.createBitmap(MAP_W, MAP_H + BAND_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFFE8EAED.toInt())

        val gotTiles = drawTiles(c, z, originX, originY)

        val project: (Double, Double) -> PointF =
            if (gotTiles) { lat, lon ->
                PointF((worldX(lon, z) - originX).toFloat(), (worldY(lat, z) - originY).toFloat())
            } else fallbackProjector(points, MAP_W, MAP_H)

        drawRoute(context, c, points, splits, project, 1f)
        drawStatsBand(c, workout, MAP_W, MAP_H, BAND_H, 1f)
        return out
    }

    /** Hakee näkymän tiilet rinnakkain ja piirtää ne. True jos vähintään puolet onnistui. */
    private fun drawTiles(c: Canvas, z: Int, originX: Double, originY: Double): Boolean {
        val maxIndex = (1 shl z) - 1
        val x0 = floor(originX / TILE).toInt()
        val x1 = floor((originX + MAP_W) / TILE).toInt()
        val y0 = floor(originY / TILE).toInt()
        val y1 = floor((originY + MAP_H) / TILE).toInt()
        data class Job(val tx: Int, val ty: Int)
        val jobs = ArrayList<Job>()
        for (ty in y0..y1) for (tx in x0..x1) {
            if (tx in 0..maxIndex && ty in 0..maxIndex) jobs.add(Job(tx, ty))
        }
        if (jobs.isEmpty()) return false
        val results = java.util.concurrent.ConcurrentHashMap<Job, Bitmap>()
        val pool = Executors.newFixedThreadPool(4)
        for (j in jobs) {
            pool.execute {
                fetchTile(z, j.tx, j.ty)?.let { results[j] = it }
            }
        }
        pool.shutdown()
        try {
            pool.awaitTermination(FETCH_BUDGET_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) { }
        pool.shutdownNow()
        for ((j, bmp) in results) {
            c.drawBitmap(bmp, (j.tx * TILE - originX).toFloat(), (j.ty * TILE - originY).toFloat(), null)
            bmp.recycle()
        }
        return results.size >= jobs.size / 2
    }

    /** Sama WMTS-osoite kuin buildMmlStyleJson-tyylissä — huom. polkujärjestys {z}/{y}/{x}. */
    private fun fetchTile(z: Int, x: Int, y: Int): Bitmap? {
        return try {
            val url = URL(
                "https://avoin-karttakuva.maanmittauslaitos.fi/avoin/wmts/1.0.0/" +
                    "taustakartta/default/WGS84_Pseudo-Mercator/$z/$y/$x.png?api-key=" +
                    BuildConfig.MML_API_KEY)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            try {
                if (conn.responseCode != 200) return null
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Piirto ----------

    private fun drawRoute(
        context: Context,
        c: Canvas,
        points: List<WorkoutPointEntity>,
        splits: List<WorkoutSplitEntity>,
        project: (Double, Double) -> PointF,
        s: Float,
    ) {
        // Reittiviiva segmenteittäin (tauot katkaisevat) — samat värit kuin yhteenvetokartassa.
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1A73E8.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 10f * s
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        var seg = Int.MIN_VALUE
        var path = Path()
        var started = false
        fun flushPath() {
            if (started) c.drawPath(path, line)
            path = Path(); started = false
        }
        for (p in points) {
            if (p.segment != seg) { flushPath(); seg = p.segment }
            val pt = project(p.lat, p.lon)
            if (!started) { path.moveTo(pt.x, pt.y); started = true } else path.lineTo(pt.x, pt.y)
        }
        flushPath()

        // Km-merkit (sama bitmap kuin kartalla).
        val markerSize = 56f * s
        for (sp in splits) {
            val pt = project(sp.endLat, sp.endLon)
            val mk = kmMarkerBitmap(context, sp.splitIndex)
            val dst = RectF(pt.x - markerSize / 2, pt.y - markerSize / 2,
                pt.x + markerSize / 2, pt.y + markerSize / 2)
            c.drawBitmap(mk, null, dst, null)
            mk.recycle()
        }

        // Lähtö (vihreä) ja maali (punainen) valkoisella reunuksella.
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        fun drawDot(lat: Double, lon: Double, color: Int) {
            val pt = project(lat, lon)
            dot.color = 0xFFFFFFFF.toInt()
            c.drawCircle(pt.x, pt.y, 16f * s, dot)
            dot.color = color
            c.drawCircle(pt.x, pt.y, 12f * s, dot)
        }
        drawDot(points.first().lat, points.first().lon, 0xFF188038.toInt())
        drawDot(points.last().lat, points.last().lon, 0xFFC5221F.toInt())
    }

    /** Fallback-projektio ilman karttaa: ekvirektangulaarinen sovitus kuvan keskelle. */
    private fun fallbackProjector(
        points: List<WorkoutPointEntity>,
        w: Int,
        h: Int,
    ): (Double, Double) -> PointF {
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        for (p in points) {
            minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
            minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
        }
        val midLat = (minLat + maxLat) / 2
        val midLon = (minLon + maxLon) / 2
        val kx = cos(Math.toRadians(midLat))
        val spanX = max((maxLon - minLon) * kx, 1e-4)
        val spanY = max(maxLat - minLat, 1e-4)
        val scale = min(w * 0.8 / spanX, h * 0.8 / spanY)
        return { lat, lon ->
            PointF(
                (w / 2 + (lon - midLon) * kx * scale).toFloat(),
                (h / 2 - (lat - midLat) * scale).toFloat(),
            )
        }
    }

    /** Valkoinen tilastokaista kartan alle: nimi, tunnusluvut, pvm + attribuutio. */
    private fun drawStatsBand(
        c: Canvas,
        workout: WorkoutEntity,
        w: Int,
        mapH: Int,
        bandH: Int,
        s: Float,
    ) {
        val band = Paint().apply { color = 0xFFFFFFFF.toInt() }
        c.drawRect(0f, mapH.toFloat(), w.toFloat(), (mapH + bandH).toFloat(), band)

        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        val left = 44f * s

        text.color = 0xFF202124.toInt()
        text.textSize = 52f * s
        text.isFakeBoldText = true
        c.drawText(workoutDisplayName(workout), left, mapH + 80f * s, text)

        text.textSize = 46f * s
        c.drawText(
            String.format(FI_IMG, "%.2f km", workout.distanceM / 1000.0) +
                "   " + formatDurationLong(workout.movingTimeMs) +
                "   " + avgSpeedText(workout.distanceM, workout.movingTimeMs),
            left, mapH + 158f * s, text,
        )

        text.color = 0xFF5F6368.toInt()
        text.textSize = 36f * s
        text.isFakeBoldText = false
        val laji = if (workout.type == WorkoutEntity.TYPE_BIKE) "Pyöräily" else "Kävely"
        val pvm = SimpleDateFormat("d.M.yyyy HH:mm", FI_IMG).format(Date(workout.startedAtMs))
        c.drawText("$laji · $pvm", left, mapH + 222f * s, text)

        // MML-lisenssi vaatii attribuution johdetuissa kuvissa.
        text.textSize = 28f * s
        text.textAlign = Paint.Align.RIGHT
        c.drawText("Arkikeskus · © Maanmittauslaitos", w - 24f * s, mapH + bandH - 24f * s, text)
    }
}
