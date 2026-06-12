package org.jrs82.fsclock.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import org.jrs82.fsclock.db.WorkoutEntity
import org.jrs82.fsclock.db.WorkoutPointEntity
import org.jrs82.fsclock.db.WorkoutSplitEntity
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Lenkin jakokuva (PNG): MML-pohjakartta renderöidään off-screen MapSnapshotterilla ja reitti,
 * lähtö/maali-pisteet sekä km-merkit piirretään Canvasilla päälle (MapSnapshot.pixelForLatLng-
 * projektio) — sama visuaali kuin yhteenvetokartassa ilman tyyli-JSONin virittelyä. Alle
 * piirretään tilastokaista + pakollinen MML-attribuutio. Jos tiilihaku epäonnistuu (ei verkkoa),
 * reitti piirretään tasaiselle taustalle → jako toimii aina.
 */
internal object WorkoutShareImage {

    private const val MAP_W = 1080
    private const val MAP_H = 1080
    private const val BAND_H = 300
    private const val SNAPSHOT_TIMEOUT_MS = 15_000L

    private val FI_IMG = Locale("fi", "FI")

    /** Viite talteen kunnes callback ajettu — paikallisena snapshotter voisi GC:ttyä kesken. */
    private var active: MapSnapshotter? = null

    /** Kutsuttava main-säikeestä; onDone kutsutaan main-säikeessä, bitmap aina ei-null. */
    fun render(
        context: Context,
        workout: WorkoutEntity,
        points: List<WorkoutPointEntity>,
        splits: List<WorkoutSplitEntity>,
        onDone: (Bitmap) -> Unit,
    ) {
        val app = context.applicationContext
        var finished = false
        val timeoutHandler = Handler(Looper.getMainLooper())
        fun finish(snapshot: MapSnapshot?) {
            if (finished) return
            finished = true
            active = null
            onDone(compose(app, snapshot, workout, points, splits))
        }
        try {
            MapLibre.getInstance(app)
            val options = MapSnapshotter.Options(MAP_W, MAP_H)
                .withStyleBuilder(Style.Builder().fromJson(buildMmlStyleJson()))
                .withRegion(paddedBounds(points))
            val snapshotter = MapSnapshotter(app, options)
            active = snapshotter
            val onTimeout = Runnable {
                try { snapshotter.cancel() } catch (e: Exception) { }
                finish(null)
            }
            timeoutHandler.postDelayed(onTimeout, SNAPSHOT_TIMEOUT_MS)
            snapshotter.start({ snapshot ->
                timeoutHandler.removeCallbacks(onTimeout)
                finish(snapshot)
            }, {
                timeoutHandler.removeCallbacks(onTimeout)
                finish(null)
            })
        } catch (e: Exception) {
            finish(null)
        }
    }

    /** Reitin rajat ~10 % marginaalilla; minimijänne ettei nollakokoinen alue kaada kameraa. */
    private fun paddedBounds(points: List<WorkoutPointEntity>): LatLngBounds {
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        for (p in points) {
            minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
            minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
        }
        val dLat = max((maxLat - minLat) * 0.12, 0.0008)
        val dLon = max((maxLon - minLon) * 0.12, 0.0012)
        return LatLngBounds.from(maxLat + dLat, maxLon + dLon, minLat - dLat, minLon - dLon)
    }

    private fun compose(
        context: Context,
        snapshot: MapSnapshot?,
        workout: WorkoutEntity,
        points: List<WorkoutPointEntity>,
        splits: List<WorkoutSplitEntity>,
    ): Bitmap {
        val mapBmp = snapshot?.bitmap
        val w = mapBmp?.width ?: MAP_W
        val mapH = mapBmp?.height ?: MAP_H
        val s = w / MAP_W.toFloat()
        val bandH = (BAND_H * s).toInt()
        val out = Bitmap.createBitmap(w, mapH + bandH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)

        if (mapBmp != null) {
            c.drawBitmap(mapBmp, 0f, 0f, null)
        } else {
            c.drawColor(0xFFE8EAED.toInt())
        }

        val project: (Double, Double) -> PointF =
            if (snapshot != null) { lat, lon -> snapshot.pixelForLatLng(LatLng(lat, lon)) }
            else fallbackProjector(points, w, mapH)

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

        // Km-merkit (sama bitmap kuin kartalla), skaalattuna jakokuvan kokoon.
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

        drawStatsBand(c, workout, w, mapH, bandH, s)
        return out
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
