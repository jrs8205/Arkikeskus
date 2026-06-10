package org.jrs82.fsclock.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.activity.compose.BackHandler
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jrs82.fsclock.BuildConfig
import org.jrs82.fsclock.R
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kelikamerakartta natiivina Compose-näkymänä — korvaa [RoadCamerasFragment]in.
 * Logiikka replikoitu 1:1: MML-rasteritausta, kaikki ~800 kamera-ikonia kerralla
 * (SymbolLayer, ei klusterointia), ennakoiva nimihaku (max 8), markerin/ehdotuksen
 * napautus avaa täysikokoisen kamerakuvan. Data: [WeathercamRepository] (koskematta).
 * Kartta on MapView AndroidView'na ([rememberMapViewWithLifecycle]) — MapLibrella ei ole
 * Compose-API:a; elinkaari + vapautus hoidetaan apurissa.
 */

/** Turvaverkko: false palauttaa vanhan Fragment-sillan ([RoadCamerasHost]) yhdellä rivillä. */
internal const val USE_COMPOSE_CAMERAS = true

private const val CAM_SRC = "cameras"
private const val CAM_LAYER_POINTS = "cam-points"
private const val CAM_ICON = "cam-icon"
private val CAM_FINLAND = LatLng(64.5, 26.0)

private class CamImage(val presetId: String, val title: String)

@Composable
internal fun RoadCamerasScreen() {
    val context = LocalContext.current
    val mapView = rememberMapViewWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<WeathercamStation>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusAutoHide by remember { mutableIntStateOf(0) }
    val openImage = remember { mutableStateOf<CamImage?>(null) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var camSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    var stations by remember { mutableStateOf<List<WeathercamStation>?>(null) }

    // Kartan alustus: asetukset, alkukamera (koko Suomi), klikkihaku, MML-tyyli + kamerakerros.
    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(CAM_FINLAND, 4.5))
            m.addOnMapClickListener { point ->
                val screen = m.projection.toScreenLocation(point)
                val hits = m.queryRenderedFeatures(screen, CAM_LAYER_POINTS)
                if (hits.isNotEmpty()) {
                    val f = hits[0]
                    val pid = f.getStringProperty("presetId") ?: ""
                    if (pid.isNotEmpty()) {
                        openImage.value = CamImage(
                            pid,
                            camTitle(f.getStringProperty("name") ?: "", f.getStringProperty("presetName")),
                        )
                    }
                    true
                } else {
                    false
                }
            }
            m.setStyle(Style.Builder().fromJson(buildMmlStyleJson())) { style ->
                style.addImage(CAM_ICON, drawableToBitmap(context, R.drawable.mobile_ic_camera_marker))
                val src = GeoJsonSource(CAM_SRC, FeatureCollection.fromFeatures(ArrayList()))
                style.addSource(src)
                val points = SymbolLayer(CAM_LAYER_POINTS, CAM_SRC)
                points.setProperties(
                    PropertyFactory.iconImage(CAM_ICON),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    // Ikoni pienenee kaukana (koko Suomi ei tukkoinen) ja kasvaa lähellä.
                    PropertyFactory.iconSize(
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(4, 0.35f),
                            Expression.stop(9, 0.7f),
                            Expression.stop(13, 1.0f),
                        ),
                    ),
                )
                style.addLayer(points)
                mapRef = m
                camSource = src
            }
        }
    }

    // Kamerat: muistivälimuisti heti, täydennys repositorystä (callback pääsäikeellä).
    LaunchedEffect(camSource) {
        if (camSource == null) return@LaunchedEffect
        val ready = WeathercamRepository.get().peek()
        if (!ready.isNullOrEmpty()) {
            stations = ready
        } else {
            status = "Kaikkia kameroita ladataan…"
        }
        WeathercamRepository.get().load(context, false) { result, error ->
            if (result.isNullOrEmpty()) {
                status = if (error != null) "Kelikameroiden haku epäonnistui." else "Ei kelikameroita."
            } else {
                stations = result
            }
        }
    }

    // FeatureCollection rakennetaan taustasäikeessä (~800 asemaa) — ei nykimistä pääsäikeellä.
    LaunchedEffect(stations, camSource) {
        val src = camSource ?: return@LaunchedEffect
        val list = stations ?: return@LaunchedEffect
        val fc = withContext(Dispatchers.Default) {
            val feats = ArrayList<Feature>()
            for (s in list) {
                if (s.presets.isEmpty()) continue
                val f = Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat))
                f.addStringProperty("name", s.name)
                f.addStringProperty("presetId", s.presets[0].id)
                f.addStringProperty("presetName", s.presets[0].presentationName)
                feats.add(f)
            }
            FeatureCollection.fromFeatures(feats)
        }
        src.setGeoJson(fc)
        status = "Kamerat ladattu"
        statusAutoHide++
    }

    // "Kamerat ladattu" piilotetaan 2,5 s kuluttua (kuten View-versiossa).
    LaunchedEffect(statusAutoHide) {
        if (statusAutoHide > 0) {
            delay(2500)
            status = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, end = 12.dp),
        ) {
            SearchTextField(
                value = query,
                onValueChange = { q ->
                    query = q
                    suggestions = if (q.trim().length >= 2) {
                        WeathercamRepository.get().search(q).take(8)
                    } else {
                        emptyList()
                    }
                },
                placeholder = "Hae tie tai paikka",
                onClear = {
                    // Tyhjennys palauttaa koko Suomen yleisnäkymään (kuten View-versiossa).
                    query = ""
                    suggestions = emptyList()
                    mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(CAM_FINLAND, 4.5))
                },
                onSearch = {
                    suggestions = emptyList()
                    if (query.trim().length >= 2) {
                        val hits = WeathercamRepository.get().search(query)
                        if (hits.isNotEmpty()) {
                            val s = hits[0]
                            mapRef?.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(LatLng(s.lat, s.lon), 11.0),
                            )
                        }
                    }
                },
            )
            if (suggestions.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 6.dp,
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        suggestions.forEach { s ->
                            Text(
                                s.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // Valinta: lennä asemalle ja avaa sen kamerakuva suoraan.
                                        suggestions = emptyList()
                                        keyboard?.hide()
                                        focusManager.clearFocus()
                                        mapRef?.animateCamera(
                                            CameraUpdateFactory.newLatLngZoom(LatLng(s.lat, s.lon), 12.0),
                                        )
                                        if (s.presets.isNotEmpty()) {
                                            val p = s.presets[0]
                                            openImage.value = CamImage(p.id, camTitle(s.name, p.presentationName))
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }

        status?.let {
            MapStatusChip(
                it,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            )
        }

        openImage.value?.let { img ->
            CameraImageOverlay(img) { openImage.value = null }
        }
    }
}

/** Täysikokoinen kamerakuva tummalla overlaylla; napautus mihin tahansa tai Sulje sulkee. */
@Composable
private fun CameraImageOverlay(img: CamImage, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var bmp by remember(img.presetId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(img.presetId) {
        bmp = withContext(Dispatchers.IO) {
            downloadFullCamImage("https://weathercam.digitraffic.fi/" + img.presetId + ".jpg")
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xDD000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                img.title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            val b = bmp
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            Text(
                "Sulje",
                color = colorResource(R.color.mobile_accent),
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.End)
                    .clickable(onClick = onClose)
                    .padding(12.dp),
            )
        }
    }
}

private fun camTitle(name: String, presetName: String?): String =
    if (presetName.isNullOrEmpty()) name else "$name · $presetName"

/** MML-taustakartan tyyli-JSON (sama kuin RoadCamerasFragment.buildMmlStyleJson). */
private fun buildMmlStyleJson(): String {
    val tiles = "https://avoin-karttakuva.maanmittauslaitos.fi/avoin/wmts/1.0.0/" +
        "taustakartta/default/WGS84_Pseudo-Mercator/{z}/{y}/{x}.png?api-key=" +
        BuildConfig.MML_API_KEY
    return "{" +
        "\"version\":8," +
        "\"sources\":{\"mml\":{\"type\":\"raster\",\"tiles\":[\"" + tiles +
        "\"],\"tileSize\":256,\"attribution\":\"\\u00a9 Maanmittauslaitos\"}}," +
        "\"layers\":[{\"id\":\"mml\",\"type\":\"raster\",\"source\":\"mml\"}]" +
        "}"
}

/** Renderöi vektoridrawablen bitmapiksi MapLibren SymbolLayer-ikoniksi. */
private fun drawableToBitmap(context: Context, resId: Int): Bitmap {
    val d = AppCompatResources.getDrawable(context, resId)!!
    val w = maxOf(1, d.intrinsicWidth)
    val h = maxOf(1, d.intrinsicHeight)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    d.setBounds(0, 0, w, h)
    d.draw(c)
    return bmp
}

/** Lataa täysikokoisen kamerakuvan. EI Authorization-headeria — weathercam palauttaa sille 400. */
private fun downloadFullCamImage(urlStr: String): Bitmap? {
    var conn: HttpURLConnection? = null
    return try {
        conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Digitraffic-User", "Arkikeskus/" + BuildConfig.VERSION_NAME)
        if (conn.responseCode != 200) {
            null
        } else {
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        }
    } catch (e: Exception) {
        null
    } finally {
        conn?.disconnect()
    }
}
