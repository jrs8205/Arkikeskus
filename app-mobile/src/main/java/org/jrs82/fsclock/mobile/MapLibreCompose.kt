package org.jrs82.fsclock.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
