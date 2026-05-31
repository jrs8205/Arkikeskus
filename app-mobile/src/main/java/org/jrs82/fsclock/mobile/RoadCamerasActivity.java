package org.jrs82.fsclock.mobile;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;

import org.jrs82.fsclock.BuildConfig;
import org.jrs82.fsclock.R;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonOptions;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Kelikamerakartta: MapLibre + MML-taustakartta, klusteroidut kamera-markerit,
 *  haku ja iso kamerakuva markeria napauttamalla. */
public class RoadCamerasActivity extends AppCompatActivity {

    private static final String SRC = "cameras";
    private static final String LAYER_POINTS = "cam-points";
    private static final String LAYER_CLUSTERS = "cam-clusters";
    private static final String LAYER_COUNT = "cam-count";
    private static final String ICON_CAM = "cam-icon";
    private static final LatLng FINLAND = new LatLng(64.5, 26.0);

    private MapView mapView;
    private MapLibreMap map;
    private GeoJsonSource source;
    private EditText searchField;
    private View imageOverlay;
    private ImageView imageBig;
    private TextView imageTitle;
    private TextView statusText;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        MobileThemeController.apply(this);
        getDelegate().setLocalNightMode(MobileThemeController.nightMode(this));
        MapLibre.getInstance(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_road_cameras);

        findViewById(R.id.cam_back).setOnClickListener(v -> finish());
        searchField = findViewById(R.id.cam_search);
        statusText = findViewById(R.id.cam_status);
        imageOverlay = findViewById(R.id.cam_image_overlay);
        imageBig = findViewById(R.id.cam_image_big);
        imageTitle = findViewById(R.id.cam_image_title);
        findViewById(R.id.cam_image_close).setOnClickListener(v -> hideImage());
        imageOverlay.setOnClickListener(v -> hideImage());

        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { onSearch(s.toString()); }
        });

        mapView = findViewById(R.id.cam_map);
        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(m -> {
            map = m;
            m.getUiSettings().setRotateGesturesEnabled(false);
            m.getUiSettings().setTiltGesturesEnabled(false);
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(FINLAND, 4.5));
            m.addOnMapClickListener(this::onMapClick);
            m.setStyle(new Style.Builder().fromJson(buildMmlStyleJson()), style -> {
                source = new GeoJsonSource(SRC,
                        FeatureCollection.fromFeatures(new ArrayList<>()),
                        new GeoJsonOptions().withCluster(true)
                                .withClusterRadius(50).withClusterMaxZoom(11));
                style.addSource(source);
                addLayers(style);
                loadCameras();
            });
        });
    }

    private void addLayers(Style style) {
        style.addImage(ICON_CAM, drawableToBitmap(R.drawable.mobile_ic_camera_marker));

        SymbolLayer points = new SymbolLayer(LAYER_POINTS, SRC);
        points.setProperties(
                PropertyFactory.iconImage(ICON_CAM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconSize(0.85f));
        points.setFilter(Expression.not(Expression.has("point_count")));
        style.addLayer(points);

        CircleLayer clusters = new CircleLayer(LAYER_CLUSTERS, SRC);
        clusters.setProperties(
                PropertyFactory.circleColor(0xFF1E88E5),
                PropertyFactory.circleRadius(18f),
                PropertyFactory.circleStrokeColor(0xFFFFFFFF),
                PropertyFactory.circleStrokeWidth(2f));
        clusters.setFilter(Expression.has("point_count"));
        style.addLayer(clusters);

        SymbolLayer count = new SymbolLayer(LAYER_COUNT, SRC);
        count.setProperties(
                PropertyFactory.textField(Expression.toString(Expression.get("point_count"))),
                PropertyFactory.textSize(12f),
                PropertyFactory.textColor(0xFFFFFFFF),
                PropertyFactory.textIgnorePlacement(true),
                PropertyFactory.textAllowOverlap(true));
        count.setFilter(Expression.has("point_count"));
        style.addLayer(count);
    }

    private String buildMmlStyleJson() {
        String tiles = "https://avoin-karttakuva.maanmittauslaitos.fi/avoin/wmts/1.0.0/"
                + "taustakartta/default/WGS84_Pseudo-Mercator/{z}/{y}/{x}.png?api-key="
                + BuildConfig.MML_API_KEY;
        return "{"
                + "\"version\":8,"
                + "\"sources\":{\"mml\":{\"type\":\"raster\",\"tiles\":[\"" + tiles
                + "\"],\"tileSize\":256,\"attribution\":\"\\u00a9 Maanmittauslaitos\"}},"
                + "\"layers\":[{\"id\":\"mml\",\"type\":\"raster\",\"source\":\"mml\"}]"
                + "}";
    }

    private void loadCameras() {
        statusText.setText("Kaikkia kameroita ladataan…");
        statusText.setVisibility(View.VISIBLE);
        WeathercamRepository.get().load(this, false, (stations, error) -> {
            if (stations == null || stations.isEmpty()) {
                statusText.setText(error != null
                        ? "Kelikameroiden haku epäonnistui." : "Ei kelikameroita.");
                statusText.setVisibility(View.VISIBLE);
                return;
            }
            statusText.setVisibility(View.GONE);
            updateSource(stations);
        });
    }

    private void updateSource(List<WeathercamStation> stations) {
        if (source == null) return;
        // Rakenna FeatureCollection taustasäikeessä (n. 800 asemaa) — ei nykimistä pääsäikeellä.
        io.execute(() -> {
            List<Feature> feats = new ArrayList<>();
            for (WeathercamStation s : stations) {
                if (s.presets.isEmpty()) continue;
                Feature f = Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat));
                f.addStringProperty("name", s.name);
                f.addStringProperty("presetId", s.presets.get(0).id);
                f.addStringProperty("presetName", s.presets.get(0).presentationName);
                feats.add(f);
            }
            final FeatureCollection fc = FeatureCollection.fromFeatures(feats);
            ui.post(() -> {
                if (isFinishing() || isDestroyed() || source == null) return;
                source.setGeoJson(fc);
                statusText.setText("Kamerat ladattu");
                statusText.setVisibility(View.VISIBLE);
                ui.postDelayed(() -> statusText.setVisibility(View.GONE), 2500);
            });
        });
    }

    private boolean onMapClick(@NonNull LatLng point) {
        if (map == null) return false;
        PointF screen = map.getProjection().toScreenLocation(point);
        List<Feature> pts = map.queryRenderedFeatures(screen, LAYER_POINTS);
        if (!pts.isEmpty()) {
            Feature f = pts.get(0);
            showImage(f.getStringProperty("presetId"),
                    f.getStringProperty("name"), f.getStringProperty("presetName"));
            return true;
        }
        List<Feature> cl = map.queryRenderedFeatures(screen, LAYER_CLUSTERS);
        if (!cl.isEmpty()) {
            double zoom = map.getCameraPosition().zoom + 2.0;
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(point, zoom));
            return true;
        }
        return false;
    }

    private void onSearch(String query) {
        if (map == null || query == null || query.trim().length() < 2) return;
        List<WeathercamStation> hits = WeathercamRepository.get().search(query);
        if (!hits.isEmpty()) {
            WeathercamStation s = hits.get(0);
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(s.lat, s.lon), 11));
        }
    }

    private void showImage(String presetId, String name, String presetName) {
        if (presetId == null || presetId.isEmpty()) return;
        imageTitle.setText(name + (presetName == null || presetName.isEmpty()
                ? "" : " · " + presetName));
        imageBig.setImageDrawable(null);
        imageOverlay.setVisibility(View.VISIBLE);
        final String url = "https://weathercam.digitraffic.fi/" + presetId + ".jpg";
        io.execute(() -> {
            Bitmap bmp = downloadFull(url);
            ui.post(() -> {
                if (imageOverlay.getVisibility() == View.VISIBLE && bmp != null) {
                    imageBig.setImageBitmap(bmp);
                }
            });
        });
    }

    private void hideImage() {
        imageOverlay.setVisibility(View.GONE);
        imageBig.setImageDrawable(null);
    }

    /** Lataa täysikokoisen kamerakuvan (ei ImageLoaderin 160 px alinäytteistystä).
     *  EI Authorization-headeria — weathercam-kuva palauttaa sille 400. */
    private static Bitmap downloadFull(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Digitraffic-User", "Arkikeskus/" + BuildConfig.VERSION_NAME);
            if (conn.getResponseCode() != 200) return null;
            try (InputStream is = conn.getInputStream()) {
                return BitmapFactory.decodeStream(is);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Renderöi vektoridrawablen bitmapiksi MapLibren SymbolLayer-ikoniksi. */
    private Bitmap drawableToBitmap(int resId) {
        Drawable d = AppCompatResources.getDrawable(this, resId);
        int w = Math.max(1, d.getIntrinsicWidth());
        int h = Math.max(1, d.getIntrinsicHeight());
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return bmp;
    }

    // --- MapView lifecycle ---
    @Override protected void onStart() { super.onStart(); if (mapView != null) mapView.onStart(); }
    @Override protected void onResume() { super.onResume(); if (mapView != null) mapView.onResume(); }
    @Override protected void onPause() { if (mapView != null) mapView.onPause(); super.onPause(); }
    @Override protected void onStop() { if (mapView != null) mapView.onStop(); super.onStop(); }
    @Override public void onLowMemory() { super.onLowMemory(); if (mapView != null) mapView.onLowMemory(); }

    @Override protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) mapView.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        if (mapView != null) mapView.onDestroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (imageOverlay != null && imageOverlay.getVisibility() == View.VISIBLE) {
            hideImage();
            return;
        }
        super.onBackPressed();
    }
}
