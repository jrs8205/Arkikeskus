package org.jrs82.fsclock.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Granularity;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.material.bottomsheet.BottomSheetBehavior;

import org.jrs82.fsclock.R;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.location.LocationComponent;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.modes.CameraMode;
import org.maplibre.android.location.modes.RenderMode;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reittihaku (HSL): Mistä/Minne geokoodauksella, aikavalinta (lähde/perillä klo) ja
 *  planConnection-reittiehdotukset vaihtoineen. MobileMainActivityn sisäinen sektio. */
public class RoutePlannerFragment extends Fragment implements RoutePlannerAdapter.Listener {

    private static final long DEBOUNCE_MS = 280L;
    private static final Locale FI = new Locale("fi", "FI");
    private static final SimpleDateFormat TIME_LABEL = new SimpleDateFormat("EEE d.M. 'klo' HH:mm", FI);
    private static final SimpleDateFormat TIME_NOW = new SimpleDateFormat("HH:mm", FI);
    private static final String MY_LOCATION = "Oma sijainti";
    /** Erikoisehdotus, jonka valinta tarkoittaa "käytä GPS-sijaintia" (lat/lon NaN). */
    private static final GeoPlace MY_LOC =
            new GeoPlace(MY_LOCATION, "Nykyinen sijaintisi (GPS)", Double.NaN, Double.NaN, "my-location");
    private static final LatLng HELSINKI = new LatLng(60.1699, 24.9384);

    private EditText fromField, toField;
    private TextView swapBtn, timeBtn, status;
    private TextView fromClear, toClear;
    private View titleView, subtitleView;
    private RecyclerView list;
    private RoutePlannerAdapter adapter;

    private View searchBox;
    private TextView detailTitle, detailSummary;
    private RecyclerView detailList;
    private RoutePlannerAdapter detailAdapter;
    private OnBackPressedCallback backCallback;

    private final ExecutorService searchIo = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private GeoPlace fromPlace = null;   // null = oma sijainti (GPS)
    private GeoPlace toPlace = null;
    private int activeField = 0;         // 1 = Mistä, 2 = Minne
    private boolean suppressWatch = false;
    private boolean arriveBy = false;
    private long timeEpochMs = 0L;       // 0 = nyt
    private double gpsLat = Double.NaN, gpsLon = Double.NaN;
    private boolean pendingSearch = false;
    private List<Itinerary> itineraries = null;

    // Reittikartta (Digitransit/OSM): MapView + vedettävä alapaneeli.
    private MapView mapView;
    private MapLibreMap map;
    private boolean routeReady = false;
    private GeoJsonSource lineSource, pointSource, busSource, allStopsSource;
    private Itinerary pendingItinerary;
    private BottomSheetBehavior<View> sheetBehavior;
    private View sheetView;
    private Style mapStyle;
    private HslMqttClient mqtt;   // reitin live-bussit (V4, kaikki joukkoliikenneosuudet)
    // Live-bussit vehicleId → kartan Feature; värit/ikonit per vehicleId (rakennetaan tilausta tehtäessä).
    private final Map<String, Feature> liveBusFeatures = new HashMap<>();
    private Map<String, String> liveColorByVid;
    private Map<String, String> liveIconByVid;
    private ImageView locButton;  // paikannusnappi (kartan oikea alakulma / paneelin yläreuna)
    private boolean following = false;  // seuraako kamera omaa sijaintia (napin sininen/harmaa)
    private boolean pendingRouteFit = false;
    private int pendingRouteFitRetries = 0;
    private FusedLocationProviderClient followLocationClient;
    private LocationCallback followLocationCallback;
    private boolean followUpdatesActive = false;

    private boolean sectionVisible = false;
    private long viewGeneration = 0L;
    private long planGeneration = 0L;
    private long liveBusGeneration = 0L;

    private interface LocCb { void onLoc(double lat, double lon); }

    private final Runnable suggestRunnable = this::runSuggest;

    private final ActivityResultLauncher<String[]> permLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean granted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION))
                        || Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                if (granted) {
                    enableLocationDotIfReady();
                    if (pendingSearch) {
                        pendingSearch = false;
                        if (hasLiveView()) doSearch();
                    } else if (sectionVisible) {
                        centerOnUser();
                    }
                } else {
                    pendingSearch = false;
                    showStatus("Sijaintilupa tarvitaan, kun lähtö on oma sijainti.");
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        MapLibre.getInstance(requireContext());
        return inflater.inflate(R.layout.fragment_route_planner, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final long viewToken = ++viewGeneration;
        fromField = view.findViewById(R.id.route_from);
        toField = view.findViewById(R.id.route_to);
        swapBtn = view.findViewById(R.id.route_swap);
        timeBtn = view.findViewById(R.id.route_time);
        status = view.findViewById(R.id.route_status);
        titleView = view.findViewById(R.id.route_title);
        subtitleView = view.findViewById(R.id.route_subtitle);
        list = view.findViewById(R.id.route_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new RoutePlannerAdapter(this);
        list.setAdapter(adapter);
        fromClear = view.findViewById(R.id.route_from_clear);
        toClear = view.findViewById(R.id.route_to_clear);
        fromClear.setOnClickListener(v -> clearField(1));
        toClear.setOnClickListener(v -> clearField(2));

        searchBox = view.findViewById(R.id.route_search_box);
        filterRow = view.findViewById(R.id.route_filter_row);
        android.content.SharedPreferences fp =
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext());
        planTransitMode = fp.getString(KEY_ROUTE_MODE_FILTER, "");
        planDirectOnly = fp.getBoolean(KEY_ROUTE_DIRECT_ONLY, false);
        buildFilterChips();
        detailTitle = view.findViewById(R.id.route_detail_title);
        detailSummary = view.findViewById(R.id.route_detail_summary);
        detailList = view.findViewById(R.id.route_detail_list);
        detailList.setLayoutManager(new LinearLayoutManager(requireContext()));
        detailAdapter = new RoutePlannerAdapter(this);
        detailList.setAdapter(detailAdapter);
        view.findViewById(R.id.route_detail_back).setOnClickListener(v -> closeDetail());

        // Reittikartta + vedettävä alapaneeli.
        mapView = view.findViewById(R.id.route_map);
        mapView.onCreate(savedInstanceState);
        locButton = view.findViewById(R.id.route_loc_btn);
        locButton.setOnClickListener(v -> centerOnUser());
        setFollowing(false);
        mapView.getMapAsync(m -> {
            if (!isViewCurrent(viewToken)) return;
            map = m;
            m.getUiSettings().setRotateGesturesEnabled(false);
            m.getUiSettings().setTiltGesturesEnabled(false);
            // Käyttäjän kartan raahaus → ei enää seuraa sijaintia (nappi harmaaksi).
            m.addOnCameraMoveStartedListener(reason -> {
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) setFollowing(false);
            });
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(HELSINKI, 11));
            m.setStyle(new Style.Builder().fromJson(DigitransitMapStyle.rasterStyleJson()), style -> {
                if (!isViewCurrent(viewToken)) return;
                mapStyle = style;
                addRouteLayers(style);
                enableLocationDot(style);
                routeReady = true;
                updateMapPadding();
                if (pendingItinerary != null) requestRouteFit(pendingItinerary);
                else if (sectionVisible) centerOnUser();
            });
        });
        sheetView = view.findViewById(R.id.route_sheet);
        sheetBehavior = BottomSheetBehavior.from(sheetView);
        sheetBehavior.setFitToContents(false);
        sheetBehavior.setHalfExpandedRatio(0.52f);
        sheetBehavior.setHideable(false);
        sheetBehavior.setPeekHeight(dpPx(150));
        sheetBehavior.setState(BottomSheetBehavior.STATE_HALF_EXPANDED);
        sheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override public void onStateChanged(@NonNull View bottomSheet, int newState) {
                updateMapPadding();
                adjustSheetContentPadding(bottomSheet);
                if (newState == BottomSheetBehavior.STATE_COLLAPSED) fitPendingRouteIfReady();
            }

            @Override public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                updateMapPadding();
                adjustSheetContentPadding(bottomSheet);
            }
        });
        sheetView.post(() -> {
            updateMapPadding();
            if (sheetView != null) adjustSheetContentPadding(sheetView);
        });

        backCallback = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() { closeDetail(); }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backCallback);

        fromField.setText(MY_LOCATION);
        // Kentän fokusoituessa siirrytään "hakutilaan" (kuten HSL Reittiopas): otsikko, alaotsikko,
        // aikavalinta ja Hae reitit -nappi piilotetaan, jolloin hakukenttä nousee ylös ja ennakoiville
        // ehdotuksille jää koko ruutu. Kun molemmat kentät menettävät fokuksen (esim. ehdotus valittu),
        // palataan normaalitilaan.
        fromField.setOnFocusChangeListener((v, f) -> {
            if (f) { activeField = 1; setSearchMode(true); offerMyLocationIfEmpty(fromField, 1); }
            else { ui.post(this::updateSearchModeFromFocus); }
        });
        toField.setOnFocusChangeListener((v, f) -> {
            if (f) { activeField = 2; setSearchMode(true); offerMyLocationIfEmpty(toField, 2); }
            else { ui.post(this::updateSearchModeFromFocus); }
        });
        fromField.addTextChangedListener(new SimpleWatcher(1));
        toField.addTextChangedListener(new SimpleWatcher(2));

        swapBtn.setOnClickListener(v -> swap());
        timeBtn.setOnClickListener(v -> showTimeDialog());

        updateTimeButton();
        updateClearButtons();
        showStatus("Anna määränpää — reitit haetaan heti.\nLähtö on oletuksena oma sijaintisi.");
    }

    /** Avattaessa sivu valikosta: hae sijainti hakuehdotusten kohdistusta varten (best-effort). */
    void onSectionShown() {
        sectionVisible = true;
        enableLocationDotIfReady();
        if (isAdded() && hasLocationPermission() && Double.isNaN(gpsLat)) {
            final long token = viewGeneration;
            fetchLocation((lat, lon) -> {
                if (!isViewCurrent(token) || !validCoordinate(lat, lon)) return;
                gpsLat = lat;
                gpsLon = lon;
            });
        }
    }

    /** Kutsutaan kun sektiosta poistutaan: sulje osat-overlay, ettei takaisin-callback jää
     *  sieppaamaan back-painallusta muilla sivuilla. */
    void onSectionHidden() {
        sectionVisible = false;
        planGeneration++;
        closeDetail(false);
        setFollowing(false);
        updateLocationComponentEnabled();
    }

    /** "Hakutila" (kuten HSL): piilottaa ylimääräisen kromin (otsikko/alaotsikko/aika/Hae reitit),
     *  jolloin hakukenttä nousee ylös ja ennakoiville ehdotuksille jää koko ruutu. */
    private void setSearchMode(boolean active) {
        int chrome = active ? View.GONE : View.VISIBLE;
        if (titleView != null) titleView.setVisibility(chrome);
        if (subtitleView != null) subtitleView.setVisibility(chrome);
        if (timeBtn != null) timeBtn.setVisibility(chrome);
        // Fokusoituna paneeli laajenee → ehdotuksille koko tila; muuten takaisin puoliväliin.
        if (sheetBehavior != null) {
            sheetBehavior.setState(active ? BottomSheetBehavior.STATE_EXPANDED
                    : BottomSheetBehavior.STATE_HALF_EXPANDED);
        }
    }

    /** Palaa normaalitilaan kun kumpikaan hakukenttä ei ole enää fokuksessa. */
    private void updateSearchModeFromFocus() {
        if (fromField == null || toField == null) return;
        if (!fromField.hasFocus() && !toField.hasFocus()) setSearchMode(false);
    }

    // --- Ehdotushaku (geokoodaus kirjoittaessa) ---

    private final class SimpleWatcher implements TextWatcher {
        private final int field;
        SimpleWatcher(int field) { this.field = field; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void afterTextChanged(Editable s) {
            if (suppressWatch) return;
            activeField = field;
            if (field == 1) fromPlace = null; else toPlace = null;
            updateClearButtons();
            ui.removeCallbacks(suggestRunnable);
            ui.postDelayed(suggestRunnable, DEBOUNCE_MS);
        }
    }

    private void runSuggest() {
        if (!hasLiveView()) return;
        final long viewToken = viewGeneration;
        final int field = activeField;
        final String q = (field == 2 ? toField : fromField).getText().toString().trim();
        if (q.isEmpty()) { offerMyLocationIfEmpty(field == 2 ? toField : fromField, field); return; }
        if (q.length() < 2 || q.equals(MY_LOCATION)) return;
        searchIo.execute(() -> {
            List<GeoPlace> res;
            try { res = DigitransitApi.geocodePlaces(q, gpsLat, gpsLon); }
            catch (Exception e) { res = new ArrayList<>(); }
            final List<GeoPlace> r = res;
            ui.post(() -> {
                if (!isViewCurrent(viewToken) || !sectionVisible || adapter == null) return;
                String cur = (field == 2 ? toField : fromField).getText().toString().trim();
                if (field != activeField || !q.equals(cur)) return;  // vanhentunut
                adapter.submit(r);
                if (r.isEmpty()) showStatus("Ei paikkoja haulla \"" + q + "\".");
                else hideStatus();
            });
        });
    }

    @Override
    public void onSuggestClick(GeoPlace p) {
        boolean my = "my-location".equals(p.layer);
        String label = my ? MY_LOCATION : p.name;
        final int chosenField = activeField;
        suppressWatch = true;
        if (chosenField == 2) {
            toPlace = my ? null : p;
            toField.setText(label);
            toField.setSelection(label.length());
        } else {
            fromPlace = my ? null : p;
            fromField.setText(label);
            fromField.setSelection(label.length());
        }
        suppressWatch = false;
        updateClearButtons();
        hideKeyboard();
        fromField.clearFocus();
        toField.clearFocus();
        adapter.submit(new ArrayList<>());   // tyhjennä ehdotukset; ei näytetä vanhoja reittejä
        // Valinta hakee heti, jos molemmat päät ovat valmiit — yhtenäinen "Hae reitit" -painikkeen
        // kanssa (ennen tämä jätti vanhat reitit näkyviin → tulos vaihteli painikkeen vs ehdotuksen
        // mukaan). Muuten ohjataan täydentämään puuttuva pää.
        if (bothEndsReady()) {
            doSearch();
        } else if (chosenField == 2) {
            showStatus("Valitse vielä lähtö, tai jätä se omaan sijaintiisi.");
        } else {
            showStatus("Valitse vielä määränpää.");
        }
    }

    private void swap() {
        GeoPlace tp = fromPlace;
        fromPlace = toPlace;
        toPlace = tp;
        // Vaihda myös näkyvät tekstit, jotta "Oma sijainti" tai vapaa teksti seuraa suuntaa.
        String ft = fromField.getText().toString();
        String tt = toField.getText().toString();
        suppressWatch = true;
        fromField.setText(tt);
        toField.setText(ft);
        suppressWatch = false;
        updateClearButtons();
        // Hae uudet reitit heti uudella suunnalla, jos molemmat päät on asetettu.
        if (bothEndsReady()) doSearch();
    }

    // --- Aikavalinta ---

    private void showTimeDialog() {
        Context ctx = getContext();
        if (ctx == null) return;
        new AlertDialog.Builder(ctx)
                .setItems(new CharSequence[]{"Lähtö nyt", "Lähtö klo…", "Perillä klo…"}, (d, w) -> {
                    if (w == 0) {
                        timeEpochMs = 0L;
                        arriveBy = false;
                        updateTimeButton();
                        if (bothEndsReady()) doSearch(); // HSL-tyyli: ei erillistä hakunappia
                    } else {
                        pickDateTime(w == 2);
                    }
                })
                .show();
    }

    private void pickDateTime(boolean arrive) {
        Context ctx = getContext();
        if (ctx == null) return;
        final Calendar c = Calendar.getInstance();
        if (timeEpochMs > 0) c.setTimeInMillis(timeEpochMs);
        new DatePickerDialog(ctx, (dp, y, m, day) -> {
            c.set(Calendar.YEAR, y);
            c.set(Calendar.MONTH, m);
            c.set(Calendar.DAY_OF_MONTH, day);
            new TimePickerDialog(ctx, (tp, h, min) -> {
                c.set(Calendar.HOUR_OF_DAY, h);
                c.set(Calendar.MINUTE, min);
                c.set(Calendar.SECOND, 0);
                timeEpochMs = c.getTimeInMillis();
                arriveBy = arrive;
                updateTimeButton();
                if (bothEndsReady()) doSearch(); // HSL-tyyli: ei erillistä hakunappia
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateTimeButton() {
        if (timeEpochMs <= 0) {
            // HSL-tyyli: "Lähtö nyt" + sen hetkinen kellonaika.
            timeBtn.setText("Lähtö nyt · " + TIME_NOW.format(new Date()));
        } else {
            timeBtn.setText((arriveBy ? "Perillä " : "Lähtö ") + TIME_LABEL.format(new Date(timeEpochMs)));
        }
    }

    // --- Suodattimet: kulkuväline + vain suorat ---

    private static final String KEY_ROUTE_MODE_FILTER = "route_mode_filter";
    private static final String KEY_ROUTE_DIRECT_ONLY = "route_direct_only";
    private android.widget.LinearLayout filterRow;
    private String planTransitMode = "";   // "" = kaikki; BUS/RAIL/TRAM/SUBWAY/FERRY
    private boolean planDirectOnly = false;

    private void buildFilterChips() {
        if (filterRow == null || !isAdded()) return;
        filterRow.removeAllViews();
        String[][] modes = {
                {"", "Kaikki"}, {"BUS", "Bussi"}, {"RAIL", "Juna"},
                {"TRAM", "Ratikka"}, {"SUBWAY", "Metro"}, {"FERRY", "Lautta"}};
        for (String[] m : modes) {
            final String mode = m[0];
            filterRow.addView(makeFilterChip(m[1], routeModeChipIcon(mode), mode.equals(planTransitMode), v -> {
                planTransitMode = mode;
                persistRouteFilters();
                buildFilterChips();
                if (bothEndsReady()) doSearch();
            }));
        }
        filterRow.addView(makeFilterChip("Vain suorat", R.drawable.mobile_ic_arrow_right_alt_24, planDirectOnly, v -> {
            planDirectOnly = !planDirectOnly;
            persistRouteFilters();
            buildFilterChips();
            if (bothEndsReady()) doSearch();
        }));
    }

    /** Suodatinchipin ikoni: "" (Kaikki) → done_all, muut kulkuvälineen oma moodi-ikoni
     *  (sama lähde kuin ajoneuvomerkit, [TransitStyle.modeIcon]) → yhtenäinen Lähilähtöjen kanssa. */
    private static int routeModeChipIcon(String mode) {
        if (mode == null || mode.isEmpty()) return R.drawable.mobile_ic_done_all_24;
        return TransitStyle.modeIcon(mode);
    }

    private android.view.View makeFilterChip(String label, int iconRes, boolean selected,
                                             android.view.View.OnClickListener onClick) {
        android.widget.TextView t = new android.widget.TextView(requireContext());
        float dp = getResources().getDisplayMetrics().density;
        t.setText(label);
        t.setTextSize(13f);
        t.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        // Vaakapadding hieman pienempi (12→10 dp), koska ikoni vie tilaa → rivi pysyy tiiviinä.
        t.setPadding((int) (10 * dp), (int) (7 * dp), (int) (12 * dp), (int) (7 * dp));
        t.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final int textColor;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(18 * dp);
        if (selected) {
            bg.setColor(0xFF1A73E8);
            textColor = 0xFFFFFFFF;
        } else {
            bg.setColor(0x00000000);
            bg.setStroke((int) (1 * dp), androidx.core.content.ContextCompat.getColor(
                    requireContext(), R.color.mobile_text_muted));
            textColor = androidx.core.content.ContextCompat.getColor(
                    requireContext(), R.color.mobile_text_primary);
        }
        t.setTextColor(textColor);
        // Ikoni tekstin eteen, sama väri kuin teksti, ~18 dp + 6 dp väli.
        android.graphics.drawable.Drawable icon =
                androidx.core.content.ContextCompat.getDrawable(requireContext(), iconRes);
        if (icon != null) {
            icon = icon.mutate();
            int sz = (int) (18 * dp);
            icon.setBounds(0, 0, sz, sz);
            icon.setTint(textColor);
            t.setCompoundDrawablesRelative(icon, null, null, null);
            t.setCompoundDrawablePadding((int) (6 * dp));
        }
        t.setBackground(bg);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd((int) (8 * dp));
        t.setLayoutParams(lp);
        t.setOnClickListener(onClick);
        return t;
    }

    private void persistRouteFilters() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                .putString(KEY_ROUTE_MODE_FILTER, planTransitMode)
                .putBoolean(KEY_ROUTE_DIRECT_ONLY, planDirectOnly)
                .apply();
    }

    // --- Reittihaku ---

    private void doSearch() {
        if (!isAdded() || fromField == null || toField == null) return;
        if (timeEpochMs <= 0) updateTimeButton(); // "Lähtö nyt · HH:mm" pysyy ajassa
        final long request = ++planGeneration;
        hideKeyboard();
        fromField.clearFocus();
        toField.clearFocus();
        final boolean fromMy = (fromPlace == null) && isMyLocation(fromField);
        final boolean toMy = (toPlace == null) && isMyLocation(toField);
        if (toPlace == null && !toMy) {
            showStatus("Valitse määränpää: kirjoita Minne-kenttään ja valitse ehdotus.");
            return;
        }
        if (fromPlace == null && !fromMy) {
            showStatus("Valitse lähtö: kirjoita Mistä-kenttään ja valitse ehdotus, tai valitse Oma sijainti.");
            return;
        }
        if (fromMy && toMy) {
            showStatus("Lähtö ja määränpää eivät voi molemmat olla oma sijainti.");
            return;
        }
        if (fromMy || toMy) {
            if (!hasLocationPermission()) {
                pendingSearch = true;
                showStatus("Salli sijainti, jotta reitti voidaan laskea omasta sijainnistasi.");
                permLauncher.launch(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
                return;
            }
            showStatus("Haetaan sijaintia…");
            fetchLocation((lat, lon) -> {
                if (!hasLiveView() || request != planGeneration || !sectionVisible) return;
                if (!validCoordinate(lat, lon)) {
                    showStatus("Sijaintia ei saatu. Yritä uudelleen.");
                    return;
                }
                gpsLat = lat;
                gpsLon = lon;
                double fLat = fromMy ? lat : fromPlace.lat;
                double fLon = fromMy ? lon : fromPlace.lon;
                double tLat = toMy ? lat : toPlace.lat;
                double tLon = toMy ? lon : toPlace.lon;
                runPlan(request, fLat, fLon, tLat, tLon);
            });
        } else {
            runPlan(request, fromPlace.lat, fromPlace.lon, toPlace.lat, toPlace.lon);
        }
    }

    private void runPlan(long request, double fromLat, double fromLon, double toLat, double toLon) {
        showStatus("Haetaan reittejä…");
        adapter.submit(new ArrayList<>());
        final String iso = isoFor(timeEpochMs);
        final boolean arr = arriveBy;
        final String mode = planTransitMode;
        final boolean directOnly = planDirectOnly;
        searchIo.execute(() -> {
            List<Itinerary> res;
            try {
                // Vain suorat -tilassa haetaan isompi joukko, jotta suodatuksen jälkeenkin jää ehdotuksia.
                res = DigitransitApi.planRoutes(fromLat, fromLon, toLat, toLon, iso, arr,
                        directOnly ? 10 : 5, mode.isEmpty() ? null : mode);
            }
            catch (Exception e) { res = null; }
            final List<Itinerary> r = res;
            ui.post(() -> {
                if (!hasLiveView() || !sectionVisible || request != planGeneration || adapter == null) return;
                if (r == null) { showStatus("Reittihaku epäonnistui. Yritä uudelleen."); return; }
                List<Itinerary> shown = r;
                if (directOnly) {
                    shown = new ArrayList<>();
                    for (Itinerary it : r) if (it.transfers == 0) shown.add(it);
                }
                itineraries = shown;
                adapter.submit(shown);
                if (shown.isEmpty()) {
                    String msg = "Ei reittejä tälle välille tai ajalle.";
                    if (directOnly && !r.isEmpty()) msg = "Ei suoria reittejä tällä haulla — salli vaihdot tai vaihda kulkuvälinettä.";
                    else if (!mode.isEmpty()) msg = "Ei reittejä valitulla kulkuvälineellä.";
                    showStatus(msg);
                } else {
                    hideStatus();
                    // HSL-malli: reittiehdotukset koko korkeudella (puoliasennossa alin kortti
                    // leikkautuisi alapalkkiin); karttaan pääsee vetämällä alas / valitsemalla reitin.
                    if (sheetBehavior != null) sheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                }
            });
        });
    }

    private static String isoFor(long epochMs) {
        OffsetDateTime t = epochMs <= 0
                ? OffsetDateTime.now()
                : Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toOffsetDateTime();
        return t.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    // --- Reitin osat -overlay ---

    @Override
    public void onItineraryClick(Itinerary it) {
        if (it == null) return;
        detailTitle.setText("Reitti");
        detailSummary.setText(routeSummary(it));
        // Reitin linjojen häiriöt osat-listan kärkeen, sitten reitin osat.
        List<Object> detailItems = new ArrayList<>(collectAlerts(it));
        detailItems.addAll(it.legs);
        detailAdapter.submit(detailItems);
        hideKeyboard();
        setDetailMode(true);
        if (sheetBehavior != null) sheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        if (backCallback != null) backCallback.setEnabled(true);
        pendingItinerary = it;
        if (map != null && routeReady) requestRouteFit(it);
    }

    /** Reitin joukkoliikenneosuuksien häiriöt: samat tuplat poistettu, vakavin ensin. */
    private static List<TransitAlert> collectAlerts(Itinerary it) {
        LinkedHashMap<String, TransitAlert> map = new LinkedHashMap<>();
        for (Leg leg : it.legs) {
            for (TransitAlert a : leg.alerts) {
                String key = a.displayText();
                if (!map.containsKey(key)) map.put(key, a);
            }
        }
        List<TransitAlert> out = new ArrayList<>(map.values());
        out.sort((x, y) -> Integer.compare(y.severityRank(), x.severityRank()));
        return out;
    }

    @Override
    public void onAlertClick(TransitAlert a) {
        Context ctx = getContext();
        if (ctx == null || a == null) return;
        String body = !a.description.isEmpty() ? a.description : a.displayText();
        new AlertDialog.Builder(ctx)
                .setTitle(!a.header.isEmpty() ? a.header : "Häiriötiedote")
                .setMessage(body)
                .setPositiveButton("Sulje", null)
                .show();
    }

    private void requestRouteFit(Itinerary it) {
        if (it == null) return;
        pendingRouteFit = true;
        pendingRouteFitRetries = 0;
        if (sheetBehavior == null || sheetBehavior.getState() == BottomSheetBehavior.STATE_COLLAPSED) {
            fitPendingRouteIfReady();
        } else if (sheetView != null) {
            sheetView.postDelayed(this::fitPendingRouteIfReady, 260L);
        }
    }

    /** Sheet on koko kontin korkuinen → puoliasennossa sen alaosa jatkuu alapalkin alle.
     *  Pidetään listasisältö näkyvällä alueella säätämällä alapehmuste sheetin yläreunan
     *  (= piiloon jäävän osan korkeuden) mukaan. clipToPadding=false on jo layoutissa. */
    private void adjustSheetContentPadding(View bottomSheet) {
        int off = Math.max(0, bottomSheet.getTop());
        int base = dpPx(20);
        if (list != null) {
            list.setPadding(list.getPaddingLeft(), list.getPaddingTop(),
                    list.getPaddingRight(), base + off);
        }
        if (detailList != null) {
            detailList.setPadding(detailList.getPaddingLeft(), detailList.getPaddingTop(),
                    detailList.getPaddingRight(), base + off);
        }
    }

    private void fitPendingRouteIfReady() {
        if (!pendingRouteFit || pendingItinerary == null || map == null || !routeReady) return;
        if (sheetBehavior != null
                && sheetBehavior.getState() != BottomSheetBehavior.STATE_COLLAPSED
                && pendingRouteFitRetries++ < 10) {
            if (sheetView != null) sheetView.postDelayed(this::fitPendingRouteIfReady, 100L);
            return;
        }
        pendingRouteFit = false;
        updateMapPadding();
        drawItinerary(pendingItinerary);
    }

    /** Vaihtaa hakutilan ja reitin osat -tilan välillä (jaettu kartta + paneeli). */
    private void setDetailMode(boolean detail) {
        if (searchBox != null) searchBox.setVisibility(detail ? View.GONE : View.VISIBLE);
        if (list != null) list.setVisibility(detail ? View.GONE : View.VISIBLE);
        if (detailTitle != null) detailTitle.setVisibility(detail ? View.VISIBLE : View.GONE);
        if (detailSummary != null) detailSummary.setVisibility(detail ? View.VISIBLE : View.GONE);
        if (detailList != null) detailList.setVisibility(detail ? View.VISIBLE : View.GONE);
        View v = getView();
        View back = v == null ? null : v.findViewById(R.id.route_detail_back);
        if (back != null) back.setVisibility(detail ? View.VISIBLE : View.GONE);
        if (detail) hideStatus();
    }

    private void clearRoute(boolean recenter) {
        pendingItinerary = null;
        pendingRouteFit = false;
        stopLiveBus();
        if (lineSource != null) lineSource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));
        if (pointSource != null) pointSource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));
        if (allStopsSource != null) allStopsSource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));
        if (recenter && sectionVisible) centerOnUser();
    }

    /** Keskittää kameran omaan sijaintiin (kartan padding nostaa pisteen paneelin yläpuolelle). */
    @SuppressLint("MissingPermission") // lupa tarkistettu hasLocationPermission():lla
    private void centerOnUser() {
        if (!sectionVisible || !isAdded() || !hasLocationPermission() || map == null) return;
        final long token = viewGeneration;
        fetchLocation((lat, lon) -> {
            if (!isViewCurrent(token) || !sectionVisible || map == null) return;
            if (!validCoordinate(lat, lon)) {
                setFollowing(false);
                Toast.makeText(requireContext(), "Sijaintia ei saatu. Yritä uudelleen.", Toast.LENGTH_SHORT).show();
                return;
            }
            gpsLat = lat;
            gpsLon = lon;
            // Kohde pidetään täsmälleen GPS-koordinaatissa. Näkyvä alue varataan alapaneelilta
            // MapLibren paddingilla, jotta sijainti ei vääristy kilometrejä ruudun ulkopuolelle.
            updateMapPadding();
            map.easeCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lon), 14), 500);
            setFollowing(true);
        });
    }

    /** Paikannusnapin tila: sininen = kamera seuraa omaa sijaintia, harmaa = ei. */
    private void setFollowing(boolean f) {
        following = f;
        if (locButton != null) locButton.setColorFilter(f ? 0xFF1A73E8 : 0xFF5F6368);
        if (f) startFollowLocationUpdates();
        else stopFollowLocationUpdates();
        if (map != null) {
            try {
                LocationComponent lc = map.getLocationComponent();
                if (lc.isLocationComponentActivated() && lc.isLocationComponentEnabled()) {
                    // CameraMode.TRACKING keskittää sijainnin paneelin alle. Pidetään kamera
                    // manuaalisesti nostetussa kohteessa; nappi kertoo vain viimeisimmän keskityksen.
                    lc.setCameraMode(CameraMode.NONE);
                }
            } catch (Exception ignored) { }
        }
    }

    /** Elävä sininen sijaintipiste (MapLibre LocationComponent): näyttää sijainnin + suunnan, seuraa GPS:ää. */
    @SuppressLint("MissingPermission") // lupa tarkistettu hasLocationPermission():lla
    private void enableLocationDot(Style style) {
        if (map == null || !hasLocationPermission()) return;
        try {
            LocationComponent lc = map.getLocationComponent();
            if (!lc.isLocationComponentActivated()) {
                lc.activateLocationComponent(LocationComponentActivationOptions
                        .builder(requireContext(), style)
                        .useDefaultLocationEngine(true)
                        .build());
            }
            lc.setRenderMode(RenderMode.GPS);
            updateLocationComponentEnabled();
        } catch (Exception ignored) { }
    }

    private void enableLocationDotIfReady() {
        if (mapStyle != null) enableLocationDot(mapStyle);
    }

    @SuppressLint("MissingPermission") // enabled=false ilman lupaa; enabled=true vain hasLocationPermission():n jälkeen
    private void updateLocationComponentEnabled() {
        if (map == null) return;
        try {
            LocationComponent lc = map.getLocationComponent();
            if (!lc.isLocationComponentActivated()) return;
            boolean enabled = sectionVisible && isResumed() && hasLocationPermission();
            lc.setLocationComponentEnabled(enabled);
            lc.setCameraMode(CameraMode.NONE);
        } catch (Exception ignored) { }
    }

    private void updateMapPadding() {
        if (map == null || mapView == null || sheetView == null) return;
        map.setPadding(0, dpPx(12), 0, mapBottomPadding(72));
        updateFloatingControls();
    }

    private int sheetCoveredHeight() {
        int fallback = Math.round(getResources().getDisplayMetrics().heightPixels * 0.52f);
        if (mapView == null) return fallback;
        int mapHeight = mapView.getHeight();
        if (mapHeight <= 0) return fallback;
        int top = sheetView == null ? 0 : sheetView.getTop();
        if (top <= 0) {
            if (sheetBehavior != null && sheetBehavior.getState() == BottomSheetBehavior.STATE_COLLAPSED) {
                return dpPx(150);
            }
            if (sheetBehavior != null && sheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
                return mapHeight;
            }
            return Math.round(mapHeight * 0.52f);
        }
        return Math.max(0, mapHeight - top);
    }

    private int mapBottomPadding(int extraDp) {
        int bottom = sheetCoveredHeight() + dpPx(extraDp);
        if (mapView == null || mapView.getHeight() <= 0) return bottom;
        return Math.min(bottom, Math.max(0, mapView.getHeight() - dpPx(96)));
    }

    @SuppressLint("MissingPermission") // kutsutaan vain kun hasLocationPermission() on true
    private void startFollowLocationUpdates() {
        if (followUpdatesActive || !sectionVisible || !isResumed() || !hasLocationPermission()) return;
        if (followLocationClient == null) {
            followLocationClient = LocationServices.getFusedLocationProviderClient(requireContext());
        }
        if (followLocationCallback == null) {
            followLocationCallback = new LocationCallback() {
                @Override public void onLocationResult(@NonNull LocationResult result) {
                    android.location.Location loc = result.getLastLocation();
                    if (loc == null || !validCoordinate(loc.getLatitude(), loc.getLongitude())) return;
                    onFollowLocation(loc.getLatitude(), loc.getLongitude());
                }
            };
        }
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_500L)
                .setMinUpdateIntervalMillis(700L)
                .setMaxUpdateDelayMillis(0L)
                .build();
        try {
            followLocationClient.requestLocationUpdates(req, followLocationCallback, Looper.getMainLooper());
            followUpdatesActive = true;
        } catch (Exception ignored) { }
    }

    private void stopFollowLocationUpdates() {
        if (!followUpdatesActive || followLocationClient == null || followLocationCallback == null) return;
        try { followLocationClient.removeLocationUpdates(followLocationCallback); }
        catch (Exception ignored) { }
        followUpdatesActive = false;
    }

    private void onFollowLocation(double lat, double lon) {
        gpsLat = lat;
        gpsLon = lon;
        if (!following || map == null || !sectionVisible || !hasLiveView()) return;
        updateMapPadding();
        map.easeCamera(CameraUpdateFactory.newLatLng(new LatLng(lat, lon)), 350);
    }

    private void updateFloatingControls() {
        if (locButton == null || mapView == null) return;
        // Nayta paikannusnappi VAIN kun paneeli on levossa kartan nakyviin jattavassa tilassa
        // (collapsed/half). Vetamisen/asettumisen aikana ja laajennettuna piilota HETI: aiemmin
        // nappi siirrettiin (setY) joka onSlide-kehyksella paneelin ylareunan mukana, jolloin se
        // "lensi" ylos oikeaa reunaa pitkin ja vilahti paneelin alta paneelia ylos vedettaessa.
        int sheetState = sheetBehavior != null
                ? sheetBehavior.getState() : BottomSheetBehavior.STATE_HALF_EXPANDED;
        if (sheetState != BottomSheetBehavior.STATE_COLLAPSED
                && sheetState != BottomSheetBehavior.STATE_HALF_EXPANDED) {
            locButton.setVisibility(View.GONE);
            return;
        }
        int buttonW = locButton.getWidth() > 0 ? locButton.getWidth() : dpPx(44);
        int buttonH = locButton.getHeight() > 0 ? locButton.getHeight() : dpPx(44);
        int margin = dpPx(14);
        int mapW = mapView.getWidth() > 0 ? mapView.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int mapH = mapView.getHeight() > 0 ? mapView.getHeight() : getResources().getDisplayMetrics().heightPixels;
        int sheetTop = mapH - sheetCoveredHeight();
        // Paneelin ollessa laajennettuna (haku/reittilista) kartta ei juuri näy → piilota
        // paikannusnappi, ettei se jää kellumaan ruudun yläreunaan paneelin päälle.
        if (sheetTop <= dpPx(140)) {
            locButton.setVisibility(View.GONE);
            return;
        }
        locButton.setVisibility(View.VISIBLE);
        locButton.setX(mapW - buttonW - margin);
        locButton.setY(Math.max(dpPx(16), sheetTop - buttonH - margin));
        locButton.bringToFront();
    }

    // --- Reittikartta: piirrä valitun reitin osat (legGeometry) + markerit + sovita kamera ---

    private void addRouteLayers(Style style) {
        lineSource = new GeoJsonSource("route-lines", FeatureCollection.fromFeatures(new ArrayList<>()));
        pointSource = new GeoJsonSource("route-points", FeatureCollection.fromFeatures(new ArrayList<>()));
        style.addSource(lineSource);
        style.addSource(pointSource);

        LineLayer walk = new LineLayer("route-walk", "route-lines");
        walk.setFilter(Expression.eq(Expression.get("walk"), Expression.literal(true)));
        walk.setProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineDasharray(new Float[]{1.5f, 1.5f}),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND));
        style.addLayer(walk);

        LineLayer transit = new LineLayer("route-transit", "route-lines");
        transit.setFilter(Expression.neq(Expression.get("walk"), Expression.literal(true)));
        transit.setProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND));
        style.addLayer(transit);

        // Kaikki reitin pysäkit pieninä valkoisina pisteinä (kuten HSL) — viivan päällä, isojen alla.
        allStopsSource = new GeoJsonSource("route-allstops", FeatureCollection.fromFeatures(new ArrayList<>()));
        style.addSource(allStopsSource);
        CircleLayer allStops = new CircleLayer("route-allstops-dot", "route-allstops");
        allStops.setProperties(
                PropertyFactory.circleColor("#FFFFFF"),
                PropertyFactory.circleRadius(3.2f),
                PropertyFactory.circleStrokeColor("#5F6368"),
                PropertyFactory.circleStrokeWidth(1.4f));
        style.addLayer(allStops);

        CircleLayer stops = new CircleLayer("route-stops", "route-points");
        stops.setProperties(
                PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f));
        style.addLayer(stops);

        // Live-bussit (V4, kaikki joukkoliikenneosuudet) — päällimmäisenä.
        busSource = new GeoJsonSource("route-bus", FeatureCollection.fromFeatures(new ArrayList<>()));
        style.addSource(busSource);

        // Yksi yhtenäinen merkki: moodivärinen levy + valkoinen moodi-ikoni leivottuna samaan
        // bittikarttaan (ei erillistä pohjaympyräkerrosta) + suuntanuoli kehälle, joka kääntyy
        // HFP:n hdg-suunnan mukaan kartan suhteen.
        String[] vehModes = {"BUS", "RAIL", "TRAM", "SUBWAY", "FERRY"};
        for (String m : vehModes) {
            style.addImage("veh-icon-" + m,
                    vehicleMarkerBitmap(TransitStyle.modeColor(requireContext(), m), TransitStyle.modeIcon(m)));
        }
        style.addImage("veh-arrow", arrowBitmap(38));
        SymbolLayer busIcon = new SymbolLayer("route-bus-icon", "route-bus");
        busIcon.setProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true));
        style.addLayer(busIcon);
        SymbolLayer busArrow = new SymbolLayer("route-bus-arrow", "route-bus");
        busArrow.setProperties(
                PropertyFactory.iconImage("veh-arrow"),
                PropertyFactory.iconRotate(Expression.toNumber(Expression.get("hdg"))),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true));
        busArrow.setFilter(Expression.eq(Expression.get("hasHdg"), true));
        style.addLayer(busArrow);
    }

    /** Yhdistetty ajoneuvomerkki: moodivärinen täytetty ympyrä + valkoinen reunus + valkoinen
     *  moodi-ikoni keskellä. Korvaa erillisen pohjaympyräkerroksen yhdellä luettavalla symbolilla. */
    private android.graphics.Bitmap vehicleMarkerBitmap(int fillColor, int glyphRes) {
        int s = dpPx(26);
        android.graphics.Bitmap b =
                android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float cx = s / 2f;
        float r = dpPx(11);
        p.setColor(0xFFFFFFFF);                       // valkoinen reunus
        c.drawCircle(cx, cx, r, p);
        p.setColor(0xFF000000 | (fillColor & 0xFFFFFF)); // moodiväritäyttö
        c.drawCircle(cx, cx, r - dpPx(2), p);
        android.graphics.drawable.Drawable d =
                androidx.core.content.ContextCompat.getDrawable(requireContext(), glyphRes).mutate();
        d.setTint(TransitStyle.onColorFor(fillColor));  // musta/valk levyn kirkkauden mukaan
        int g = dpPx(14);
        int off = Math.round((s - g) / 2f);
        d.setBounds(off, off, off + g, off + g);
        d.draw(c);
        return b;
    }

    /** Valkoinen suuntanuoli (kolmio kankaan yläreunassa) — iconRotate kiertää sen kehälle. */
    private android.graphics.Bitmap arrowBitmap(int sizeDp) {
        int s = dpPx(sizeDp);
        android.graphics.Bitmap b =
                android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setShadowLayer(dpPx(1), 0, 0, 0x66000000);
        android.graphics.Path path = new android.graphics.Path();
        float cx = s / 2f;
        path.moveTo(cx, 0f);
        path.lineTo(cx - dpPx(5), dpPx(7));
        path.lineTo(cx + dpPx(5), dpPx(7));
        path.close();
        c.drawPath(path, p);
        return b;
    }

    private void drawItinerary(Itinerary it) {
        if (map == null || lineSource == null || pointSource == null || getContext() == null) return;
        stopLiveBus();
        lineSource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));
        pointSource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));
        if (allStopsSource != null) {
            allStopsSource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));
        }
        setFollowing(false);   // kamera siirtyy reitille → ei enää seuraa omaa sijaintia
        Context ctx = getContext();
        List<Feature> lines = new ArrayList<>();
        List<Feature> pts = new ArrayList<>();
        List<LatLng> all = new ArrayList<>();
        List<double[]> firstGeom = null, lastGeom = null;
        for (Leg leg : it.legs) {
            if (leg.geometry == null || leg.geometry.size() < 2) continue;
            List<Point> line = new ArrayList<>();
            for (double[] p : leg.geometry) {
                line.add(Point.fromLngLat(p[1], p[0]));   // [lat,lon] → lngLat(lon,lat)
                all.add(new LatLng(p[0], p[1]));
            }
            Feature f = Feature.fromGeometry(LineString.fromLngLats(line));
            String hex = String.format(FI, "#%06X", 0xFFFFFF & TransitStyle.modeColor(ctx, leg.mode));
            f.addStringProperty("color", leg.isWalk() ? "#9AA0A6" : hex);
            f.addBooleanProperty("walk", leg.isWalk());
            lines.add(f);
            if (firstGeom == null) firstGeom = leg.geometry;
            lastGeom = leg.geometry;
        }
        if (lines.isEmpty()) return;
        addPoint(pts, firstGeom.get(0), "#34A853");                          // lähtö (vihreä)
        addPoint(pts, lastGeom.get(lastGeom.size() - 1), "#EA4335");         // määränpää (punainen)
        for (int i = 0; i < it.legs.size() - 1; i++) {
            Leg leg = it.legs.get(i);
            if (leg.geometry != null && leg.geometry.size() >= 2) {
                addPoint(pts, leg.geometry.get(leg.geometry.size() - 1), "#FFFFFF");  // vaihtopiste
            }
        }
        lineSource.setGeoJson(FeatureCollection.fromFeatures(lines));
        pointSource.setGeoJson(FeatureCollection.fromFeatures(pts));

        // Kaikki joukkoliikenneosuuksien pysäkit pieninä pisteinä.
        List<Feature> stopFeats = new ArrayList<>();
        for (Leg leg : it.legs) {
            if (leg.isWalk()) continue;
            for (double[] s : leg.stops) stopFeats.add(Feature.fromGeometry(Point.fromLngLat(s[1], s[0])));
        }
        if (allStopsSource != null) allStopsSource.setGeoJson(FeatureCollection.fromFeatures(stopFeats));

        if (all.size() >= 2) {
            LatLngBounds.Builder b = new LatLngBounds.Builder();
            for (LatLng ll : all) b.include(ll);
            try {
                map.easeCamera(CameraUpdateFactory.newLatLngBounds(
                        b.build(),
                        dpPx(42),
                        dpPx(56),
                        dpPx(42),
                        mapBottomPadding(56)), 600);
            } catch (Exception ignored) { }
        }
        subscribeLiveBus(it, ctx);
    }

    // --- Live-bussi reittikartalla (V4): MQTT-VP → liikkuva merkki ---

    private void subscribeLiveBus(Itinerary it, Context ctx) {
        stopLiveBus();
        if (!sectionVisible || !isResumed() || pendingItinerary != it) return;
        final long request = liveBusGeneration;
        final List<Leg> transitLegs = new ArrayList<>();
        for (Leg leg : it.legs) {
            if (!leg.isWalk() && !leg.tripGtfsId.isEmpty() && !leg.patternCode.isEmpty()) {
                transitLegs.add(leg);
            }
        }
        if (transitLegs.isEmpty()) return;
        searchIo.execute(() -> {
            // Selvitä jokaiselle joukkoliikenneosuudelle liikkeellä oleva ajoneuvo (vehicleId).
            final List<String> vehicleIds = new ArrayList<>();
            final Map<String, String> colorByVid = new HashMap<>();
            final Map<String, String> iconByVid = new HashMap<>();
            for (Leg leg : transitLegs) {
                String vid;
                try { vid = DigitransitApi.vehicleForTrip(leg.patternCode, leg.tripGtfsId); }
                catch (Exception e) { vid = ""; }
                if (vid == null || vid.isEmpty() || colorByVid.containsKey(vid)) continue;
                vehicleIds.add(vid);
                colorByVid.put(vid, String.format(FI, "#%06X", 0xFFFFFF & TransitStyle.modeColor(ctx, leg.mode)));
                iconByVid.put(vid, "veh-icon-" + (leg.mode == null ? "BUS" : leg.mode));
            }
            if (vehicleIds.isEmpty()) return;
            ui.post(() -> {
                if (!hasLiveView() || !sectionVisible || !isResumed() || busSource == null
                        || request != liveBusGeneration || pendingItinerary != it) return;
                if (mqtt == null) mqtt = new HslMqttClient();
                liveBusFeatures.clear();
                liveColorByVid = colorByVid;
                liveIconByVid = iconByVid;
                mqtt.subscribeVehicles(vehicleIds,
                        (vehicleId, lat, lon, spd, dl, loc, hdg, tsi) ->
                                ui.post(() -> updateBus(vehicleId, lat, lon, hdg, tsi, request)));
            });
        });
    }

    private void updateBus(String vehicleId, double lat, double lon, double hdg, long tsi, long request) {
        if (!hasLiveView() || !sectionVisible || busSource == null || request != liveBusGeneration
                || !validCoordinate(lat, lon)) return;
        long nowSec = System.currentTimeMillis() / 1000L;
        if (tsi > 0 && (tsi < nowSec - 60 || tsi > nowSec + 30)) return;
        String hex = liveColorByVid == null ? null : liveColorByVid.get(vehicleId);
        if (hex == null) hex = "#1A73E8";
        String iconName = liveIconByVid == null ? null : liveIconByVid.get(vehicleId);
        if (iconName == null) iconName = "veh-icon-BUS";
        Feature f = Feature.fromGeometry(Point.fromLngLat(lon, lat));
        f.addStringProperty("color", hex);
        f.addStringProperty("icon", iconName);
        boolean hasHdg = !Double.isNaN(hdg);
        f.addBooleanProperty("hasHdg", hasHdg);
        f.addNumberProperty("hdg", hasHdg ? hdg : 0.0);
        liveBusFeatures.put(vehicleId, f);
        busSource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>(liveBusFeatures.values())));
    }

    private void stopLiveBus() {
        liveBusGeneration++;
        if (mqtt != null) mqtt.disconnect();
        liveBusFeatures.clear();
        if (busSource != null) busSource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));
    }

    private static void addPoint(List<Feature> pts, double[] latlon, String colorHex) {
        Feature f = Feature.fromGeometry(Point.fromLngLat(latlon[1], latlon[0]));
        f.addStringProperty("color", colorHex);
        pts.add(f);
    }

    private int dpPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private String routeSummary(Itinerary it) {
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", FI);
        String t = (it.startEpochMs > 0 ? hm.format(new Date(it.startEpochMs)) : "")
                + " – " + (it.endEpochMs > 0 ? hm.format(new Date(it.endEpochMs)) : "");
        int min = Math.max(1, Math.round(it.durationSec / 60f));
        String tr = it.transfers <= 0 ? "suora yhteys"
                : (it.transfers == 1 ? "1 vaihto" : it.transfers + " vaihtoa");
        return t + "  ·  " + min + " min  ·  " + tr;
    }

    private void closeDetail() {
        closeDetail(true);
    }

    private void closeDetail(boolean recenter) {
        setDetailMode(false);
        if (backCallback != null) backCallback.setEnabled(false);
        clearRoute(recenter);
    }

    // --- Sijainti ---

    @SuppressLint("MissingPermission") // lupa tarkistettu kutsujassa
    private void fetchLocation(LocCb cb) {
        try {
            FusedLocationProviderClient client =
                    LocationServices.getFusedLocationProviderClient(requireContext());
            CurrentLocationRequest req = new CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    // PERMISSION_LEVEL: pelkkä coarse-lupa riittää (likimääräinen sijainti) —
                    // GRANULARITY_FINE heittäisi SecurityExceptionin ilman fine-lupaa.
                    .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                    .setMaxUpdateAgeMillis(60_000L)
                    .setDurationMillis(8_000L)
                    .build();
            client.getCurrentLocation(req, new CancellationTokenSource().getToken())
                    .addOnSuccessListener(requireActivity(), loc -> {
                        if (!isAdded()) return;
                        if (loc == null) cb.onLoc(Double.NaN, Double.NaN);
                        else cb.onLoc(loc.getLatitude(), loc.getLongitude());
                    })
                    .addOnFailureListener(requireActivity(), e -> {
                        if (isAdded()) cb.onLoc(Double.NaN, Double.NaN);
                    });
        } catch (Exception e) {
            if (isAdded()) cb.onLoc(Double.NaN, Double.NaN);
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLiveView() {
        return isAdded() && getView() != null && fromField != null && toField != null;
    }

    private boolean isViewCurrent(long token) {
        return token == viewGeneration && hasLiveView();
    }

    private static boolean validCoordinate(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private boolean isMyLocation(EditText field) {
        return field != null && MY_LOCATION.equalsIgnoreCase(field.getText().toString().trim());
    }

    /** Onko toinen pää (kuin annettu kenttä) jo "Oma sijainti". */
    private boolean otherFieldIsMyLocation(int fieldNum) {
        return fieldNum == 2 ? isMyLocation(fromField) : isMyLocation(toField);
    }

    /** Ovatko sekä lähtö että määränpää valittu (paikka tai oma sijainti)? */
    private boolean bothEndsReady() {
        boolean fromOk = (fromPlace != null) || isMyLocation(fromField);
        boolean toOk = (toPlace != null) || isMyLocation(toField);
        return fromOk && toOk;
    }

    /** Tyhjään kenttään fokusoitaessa tarjoa "Oma sijainti" — mutta vain jos toinen pää EI ole jo
     *  oma sijainti (muuten ehdotus olisi turha toisto). Ehdotus näkyy heti kentän alle. */
    private void offerMyLocationIfEmpty(EditText field, int fieldNum) {
        if (field == null || adapter == null) return;
        if (!field.getText().toString().trim().isEmpty()) return;
        if (otherFieldIsMyLocation(fieldNum)) {
            adapter.submit(new ArrayList<>());
        } else {
            adapter.submit(java.util.Collections.singletonList(MY_LOC));
            hideStatus();
        }
    }

    /** X-napit näkyvät vain kun kentässä on tekstiä. INVISIBLE (ei GONE) → ×-napin tila varataan
     *  aina, joten molemmat kentät pysyvät samanlevyisinä (alkunäkymässä Mistä/Minne eivät hyppää). */
    private void updateClearButtons() {
        if (fromClear != null && fromField != null) {
            fromClear.setVisibility(fromField.getText().toString().isEmpty() ? View.INVISIBLE : View.VISIBLE);
        }
        if (toClear != null && toField != null) {
            toClear.setVisibility(toField.getText().toString().isEmpty() ? View.INVISIBLE : View.VISIBLE);
        }
    }

    /** Tyhjennä kenttä, kohdista se ja tarjoa oma sijainti tarvittaessa. */
    private void clearField(int fieldNum) {
        EditText f = (fieldNum == 2) ? toField : fromField;
        if (f == null) return;
        if (fieldNum == 2) toPlace = null; else fromPlace = null;
        suppressWatch = true;
        f.setText("");
        suppressWatch = false;
        activeField = fieldNum;
        f.requestFocus();
        updateClearButtons();
        offerMyLocationIfEmpty(f, fieldNum);
        InputMethodManager imm = (InputMethodManager) requireContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(f, InputMethodManager.SHOW_IMPLICIT);
    }

    /** Back-to-top: skrollaa näkyvän listan (reittilista tai osat-overlay) alkuun. */
    void scrollToTop() {
        if (detailList != null && detailList.getVisibility() == View.VISIBLE) {
            detailList.smoothScrollToPosition(0);
        } else if (list != null) {
            list.smoothScrollToPosition(0);
        }
    }

    // --- Apurit ---

    private void hideKeyboard() {
        View f = getView();
        if (f == null) return;
        InputMethodManager imm = (InputMethodManager) requireContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    private void showStatus(String text) {
        if (status == null) return;
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        if (status != null) status.setVisibility(View.GONE);
    }

    // --- MapView lifecycle (fragmentin elinkaaressa) ---
    @Override public void onStart() { super.onStart(); if (mapView != null) mapView.onStart(); }
    @Override public void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
        updateLocationComponentEnabled();
        if (following) startFollowLocationUpdates();
        if (sectionVisible && pendingItinerary != null && getContext() != null) {
            subscribeLiveBus(pendingItinerary, getContext());
        }
    }
    @Override public void onPause() {
        stopFollowLocationUpdates();
        stopLiveBus();
        if (mapView != null) mapView.onPause();
        super.onPause();
        updateLocationComponentEnabled();
    }
    @Override public void onStop() { if (mapView != null) mapView.onStop(); super.onStop(); }
    @Override public void onLowMemory() { super.onLowMemory(); if (mapView != null) mapView.onLowMemory(); }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) mapView.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroyView() {
        viewGeneration++;
        planGeneration++;
        liveBusGeneration++;
        ui.removeCallbacks(suggestRunnable);
        stopFollowLocationUpdates();
        followLocationCallback = null;
        followLocationClient = null;
        if (mqtt != null) { mqtt.disconnect(); mqtt = null; }
        if (mapView != null) { mapView.onDestroy(); mapView = null; }
        map = null;
        lineSource = null;
        pointSource = null;
        busSource = null;
        allStopsSource = null;
        sheetBehavior = null;
        sheetView = null;
        mapStyle = null;
        pendingItinerary = null;
        pendingRouteFit = false;
        routeReady = false;
        searchBox = null;
        locButton = null;
        titleView = null;
        subtitleView = null;
        fromField = null;
        toField = null;
        fromClear = null;
        toClear = null;
        swapBtn = null;
        timeBtn = null;
        status = null;
        list = null;
        adapter = null;
        detailTitle = null;
        detailSummary = null;
        detailList = null;
        detailAdapter = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        searchIo.shutdownNow();
        super.onDestroy();
    }
}
