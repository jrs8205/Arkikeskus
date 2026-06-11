package org.jrs82.fsclock.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.jrs82.fsclock.BuildConfig
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView

/**
 * MapLibre-kartta Composessa. MapLibrella (11.x) ei ole Compose-API:a, joten kartta on aina
 * [MapView] käärittynä AndroidView'hun — tämä apuri luo MapView'n ja sitoo sen elinkaaren
 * Activityn elinkaareen täsmälleen kuten Fragment-versiot tekivät
 * (onStart/onResume/onPause/onStop/onDestroy). Composablen poistuessa (sektiovaihto) kartta
 * vapautetaan onDisposessa → natiiviresurssit eivät vuoda.
 *
 * Huom: [LifecycleEventObserver] saa rekisteröityessään synteettiset ON_START/ON_RESUME-
 * tapahtumat jos Activity on jo etualalla, joten siirtymiä ei kutsuta käsin; started/resumed-
 * liput pitävät kutsut idempotentteina.
 */
@Composable
internal fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}

/** MML-taustakartan rasterityyli (jaettu: kelikamerat + lenkkikartta). HUOM: ei glyphs-
 *  fonttilähdettä → SymbolLayer-tekstit eivät renderöidy; käytä numeroiduille merkeille
 *  bitmap-ikoneita. */
internal fun buildMmlStyleJson(): String {
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
