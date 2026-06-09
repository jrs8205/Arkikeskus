package org.jrs82.fsclock.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Granularity;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import org.jrs82.fsclock.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Joukkoliikennesivu: GPS:n lähimmät HSL-lähdöt, haku (suodatus + koko HSL), suosikit (linjat +
 *  pysäkit), vuoron aikajana live-sijainnilla ja linjanäkymä (reitti + aikataulu + live-vuorot). */
public class TransitFragment extends Fragment implements TransitAdapter.Listener {

    private static final long AUTO_REFRESH_MS = 25_000L;
    private static final long SEARCH_DEBOUNCE_MS = 280L;
    private static final long LOCATION_FALLBACK_MAX_AGE_MS = 2L * 60_000L;
    private static final float LOCATION_FALLBACK_MAX_ACCURACY_M = 250f;
    private static final int MAX_PER_STOP = 5;       // lähtöjä per lähipysäkki
    private static final int MAX_NEARBY_STOPS = 10;  // näytettäviä lähipysäkkejä
    private static final int MAX_PER_FAV_STOP = 5;
    private static final int MAX_PER_SELECTED_STOP = 8;

    private EditText searchField;
    private View searchClear;
    private SwipeRefreshLayout swipe;
    private TextView status;
    private TransitAdapter adapter;

    private RecyclerView list, detailList;
    private View detailOverlay;
    private TextView detailBadge, detailDest, detailBanner, detailSwap;
    private TransitTimelineAdapter timelineAdapter;
    private OnBackPressedCallback backCallback;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService searchIo = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean inFlight = false;
    private boolean ticking = false;
    private long viewGeneration = 0L;
    private long detailGeneration = 0L;
    private long placeGeneration = 0L;

    private List<NearbyStop> lastStops = new ArrayList<>();
    private List<NearbyStop> favStopData = new ArrayList<>();
    private String query = "";
    private List<RouteHit> searchResults = null;
    private List<PlaceHit> placeResults = null;
    private NearbyStop selectedStop = null;   // haun tuloksesta avattu pysäkki/asema
    private double lastLat = Double.NaN, lastLon = Double.NaN;  // geokoodauksen focus.point

    // Avoinna oleva näkymä: joko vuoro (trip) tai linja (route).
    private boolean openIsRoute = false;
    private String openTrip, openPattern, openBoardStop, openMode, openShort;
    private RoutePatterns openRoutePatterns;
    private int openPatternIdx;

    // Live-sijainti (HSL HFP MQTT): tarkka etäisyys reittiä pitkin omalle pysäkille.
    private HslMqttClient mqtt;
    private String mqttVehicleId = "";
    private TripTimeline liveTrip;
    private double[] liveCum;
    private double[] liveStopDist;
    private int[] liveStopVertex;
    private double liveLastAlong = Double.NaN;

    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            if (detailOverlay != null && detailOverlay.getVisibility() == View.VISIBLE) {
                if (openIsRoute || openTrip != null) reloadTimeline();
            } else {
                View v = getView();
                // Päivitä lähilista vain kun se on aidosti näkyvissä EIKÄ olla haku-/valittu-pysäkki-
                // tilassa — muuten herätettäisiin GPS + N suosikkihakua turhaan (tuloksia ei näytetä).
                if (v != null && v.isShown() && query.isEmpty() && selectedStop == null) refresh(false);
            }
            ui.postDelayed(this, AUTO_REFRESH_MS);
        }
    };

    // Ennakoiva haku: kirjoituksen tauottua haetaan linjat + pysäkit/asemat.
    private final Runnable searchDebounce = new Runnable() {
        @Override public void run() {
            final String q = query;
            if (q.length() >= 2) runLiveSearch(q);
        }
    };

    private final ActivityResultLauncher<String[]> permLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean granted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION))
                        || Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                if (granted) refresh(false);
                else showStatus("Sijaintilupa tarvitaan lähimpien lähtöjen näyttämiseen.");
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_transit, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewGeneration++;
        searchField = view.findViewById(R.id.transit_search);
        searchClear = view.findViewById(R.id.transit_search_clear);
        swipe = view.findViewById(R.id.transit_swipe);
        status = view.findViewById(R.id.transit_status);
        list = view.findViewById(R.id.transit_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new TransitAdapter(this);
        list.setAdapter(adapter);
        swipe.setOnRefreshListener(() -> refresh(true));

        detailOverlay = view.findViewById(R.id.transit_detail_overlay);
        detailBadge = view.findViewById(R.id.transit_detail_badge);
        detailDest = view.findViewById(R.id.transit_detail_dest);
        detailBanner = view.findViewById(R.id.transit_detail_banner);
        detailSwap = view.findViewById(R.id.transit_detail_swap);
        detailList = view.findViewById(R.id.transit_detail_list);
        detailList.setLayoutManager(new LinearLayoutManager(requireContext()));
        timelineAdapter = new TransitTimelineAdapter();
        detailList.setAdapter(timelineAdapter);
        view.findViewById(R.id.transit_detail_back).setOnClickListener(v -> closeDetail());
        detailSwap.setOnClickListener(v -> swapDirection());

        backCallback = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() { closeDetail(); }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backCallback);

        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                placeGeneration++;
                query = s.toString().trim();
                searchClear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                selectedStop = null;             // tekstin muokkaus poistaa valitun pysäkin
                ui.removeCallbacks(searchDebounce);
                if (query.isEmpty()) {
                    searchResults = null;
                    placeResults = null;
                    renderFromCache();
                } else {
                    renderFromCache();           // näyttää "Haetaan…" kunnes tulokset saapuvat
                    ui.postDelayed(searchDebounce, SEARCH_DEBOUNCE_MS);
                }
            }
        });
        searchField.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                ui.removeCallbacks(searchDebounce);
                hideKeyboard();
                if (query.length() >= 2) runLiveSearch(query);
                return true;
            }
            return false;
        });
        searchClear.setOnClickListener(v -> { searchField.setText(""); hideKeyboard(); });

        refresh(false);
    }

    void onSectionShown() {
        if (isAdded() && getView() != null) {
            selectedStop = null;   // palaa lähilistaan kun sivu avataan valikosta
            refresh(false);
        }
    }

    /** Kutsutaan kun sektiosta poistutaan (esim. drawer-valinta). Sulkee mahdollisen aikajana-/
     *  linja-overlayn, ettei takaisin-callback jää päälle sieppaamaan back-painallusta muilla sivuilla. */
    void onSectionHidden() {
        closeDetail();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (detailOverlay != null && detailOverlay.getVisibility() == View.VISIBLE) {
            if (openIsRoute || openTrip != null) reloadTimeline();
        } else if (query.isEmpty() && selectedStop == null) {
            refresh(false);
        }
        if (!ticking) { ticking = true; ui.postDelayed(autoRefresh, AUTO_REFRESH_MS); }
    }

    @Override
    public void onPause() {
        ticking = false;
        ui.removeCallbacks(autoRefresh);
        stopLiveTracking();
        super.onPause();
    }

    // --- Lähimpien lähtöjen + suosikkipysäkkien haku ---

    private void refresh(boolean userInitiated) {
        if (!isAdded()) return;
        if (!hasLocationPermission()) {
            if (swipe != null) swipe.setRefreshing(false);
            showStatus("Salli sijainti nähdäksesi lähimmät lähdöt.");
            permLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
            return;
        }
        if (inFlight) return;
        inFlight = true;
        if (adapter == null || adapter.getItemCount() == 0) showStatus("Haetaan lähimpiä lähtöjä…");
        requestLocationThenFetch();
    }

    @SuppressLint("MissingPermission") // lupa tarkistettu refresh():ssä
    private void requestLocationThenFetch() {
        try {
            FusedLocationProviderClient client =
                    LocationServices.getFusedLocationProviderClient(requireContext());
            final Location fast = lastKnownFromLocationManager();
            final boolean usedFast = fast != null;
            if (usedFast) {
                fetch(fast.getLatitude(), fast.getLongitude());
                if (swipe != null) swipe.setRefreshing(true);
            }
            CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setGranularity(Granularity.GRANULARITY_FINE)
                    .setMaxUpdateAgeMillis(usedFast ? 3_000L : 10_000L)
                    .setDurationMillis(usedFast ? 5_000L : 8_000L)
                    .build();
            client.getCurrentLocation(request, new CancellationTokenSource().getToken())
                    .addOnSuccessListener(requireActivity(), location -> {
                        if (!isAdded()) { inFlight = false; return; }
                        if (location == null) {
                            if (!usedFast) {
                                fetchFallbackLocation(client,
                                        "Sijaintia ei saatu. Vedä alas yrittääksesi uudelleen.");
                            }
                            return;
                        }
                        if (!usedFast || shouldFetchRefinedLocation(fast, location)) {
                            fetch(location.getLatitude(), location.getLongitude());
                        }
                    })
                    .addOnFailureListener(requireActivity(), e -> {
                        if (!usedFast) {
                            fetchFallbackLocation(client,
                                    "Sijaintia ei saatu. Vedä alas yrittääksesi uudelleen.");
                        }
                    });
        } catch (Exception e) {
            onFetchFail("Sijaintia ei voitu lukea.");
        }
    }

    @SuppressLint("MissingPermission") // lupa tarkistettu refresh():ssä
    private void fetchFallbackLocation(FusedLocationProviderClient client, String errorMessage) {
        if (!isAdded()) { inFlight = false; return; }
        try {
            client.getLastLocation()
                    .addOnSuccessListener(requireActivity(), location -> {
                        if (!isAdded()) { inFlight = false; return; }
                        Location best = betterTransitLocation(null, location);
                        best = betterTransitLocation(best, lastKnownFromLocationManager());
                        if (best != null) {
                            fetch(best.getLatitude(), best.getLongitude());
                        } else {
                            onFetchFail(errorMessage);
                        }
                    })
                    .addOnFailureListener(requireActivity(), e -> {
                        Location best = lastKnownFromLocationManager();
                        if (best != null) fetch(best.getLatitude(), best.getLongitude());
                        else onFetchFail(errorMessage);
                    });
        } catch (Exception e) {
            Location best = lastKnownFromLocationManager();
            if (best != null) fetch(best.getLatitude(), best.getLongitude());
            else onFetchFail(errorMessage);
        }
    }

    private Location lastKnownFromLocationManager() {
        if (!isAdded()) return null;
        LocationManager lm = (LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        try {
            best = betterTransitLocation(best, lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER));
        } catch (SecurityException | IllegalArgumentException ignored) { }
        try {
            best = betterTransitLocation(best, lm.getLastKnownLocation(LocationManager.GPS_PROVIDER));
        } catch (SecurityException | IllegalArgumentException ignored) { }
        return best;
    }

    private static Location betterTransitLocation(Location current, Location candidate) {
        if (!isUsableTransitLocation(candidate)) return current;
        if (current == null || !isUsableTransitLocation(current)) return candidate;
        float ca = candidate.hasAccuracy() ? candidate.getAccuracy() : LOCATION_FALLBACK_MAX_ACCURACY_M;
        float ba = current.hasAccuracy() ? current.getAccuracy() : LOCATION_FALLBACK_MAX_ACCURACY_M;
        if (candidate.getTime() > current.getTime() + 60_000L) return candidate;
        return ca <= ba ? candidate : current;
    }

    private static boolean isUsableTransitLocation(Location location) {
        if (location == null || location.getTime() <= 0L) return false;
        long age = System.currentTimeMillis() - location.getTime();
        if (age < 0L || age > LOCATION_FALLBACK_MAX_AGE_MS) return false;
        return !location.hasAccuracy() || location.getAccuracy() <= LOCATION_FALLBACK_MAX_ACCURACY_M;
    }

    private static boolean shouldFetchRefinedLocation(Location first, Location next) {
        if (next == null) return false;
        if (first == null) return true;
        float firstAcc = first.hasAccuracy() ? first.getAccuracy() : LOCATION_FALLBACK_MAX_ACCURACY_M;
        float nextAcc = next.hasAccuracy() ? next.getAccuracy() : firstAcc;
        if (nextAcc + 15f < firstAcc) return true;
        return metersBetween(first.getLatitude(), first.getLongitude(),
                next.getLatitude(), next.getLongitude()) >= 20.0;
    }

    private static double metersBetween(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2.0) * Math.sin(dp / 2.0)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2.0) * Math.sin(dl / 2.0);
        return 2.0 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }

    private void fetch(double lat, double lon) {
        lastLat = lat;
        lastLon = lon;
        final Context app = requireContext().getApplicationContext();
        io.execute(() -> {
            try {
                final List<NearbyStop> stops = TransitRepository.get().fetch(lat, lon);
                final List<NearbyStop> favStops = new ArrayList<>();
                for (TransitFavorites.FavStop fs : TransitFavorites.getStops(app)) {
                    try {
                        NearbyStop ns = DigitransitApi.stopDepartures(fs.gtfsId);
                        if (ns != null) favStops.add(ns);
                    } catch (Exception ignored) { }
                }
                ui.post(() -> {
                    inFlight = false;
                    if (!isAdded()) return;
                    lastStops = stops;
                    favStopData = favStops;
                    renderFromCache();
                });
            } catch (Exception e) {
                ui.post(() -> onFetchFail("Lähtöjen haku epäonnistui. Vedä alas yrittääksesi uudelleen."));
            }
        });
    }

    private void onFetchFail(String msg) {
        inFlight = false;
        if (!isAdded()) return;
        if (swipe != null) swipe.setRefreshing(false);
        if (adapter == null || adapter.getItemCount() == 0) showStatus(msg);
    }

    // --- Listan rakennus ---

    private void renderFromCache() {
        if (!isAdded() || adapter == null) return;
        if (swipe != null) swipe.setRefreshing(false);
        List<Object> items = buildItems();
        adapter.submit(items);
        if (items.isEmpty()) {
            if (selectedStop != null) {
                showStatus("Ei tulevia lähtöjä tältä pysäkiltä.");
            } else if (!query.isEmpty()) {
                if (query.length() < 2) showStatus("Kirjoita vähintään 2 merkkiä.");
                else if (searchResults == null && placeResults == null)
                    showStatus("Haetaan \"" + query + "\"…");
                else showStatus("Ei osumia haulla \"" + query
                        + "\".\nKokeile pysäkin, aseman tai linjan nimeä/numeroa.");
            } else {
                showStatus("Ei lähtöjä 700 m säteellä.\nHSL-alue kattaa pääkaupunkiseudun.");
            }
        } else {
            hideStatus();
        }
    }

    private List<Object> buildItems() {
        List<Object> items = new ArrayList<>();
        Context ctx = getContext();
        if (ctx == null) return items;

        // Haun tuloksesta avattu pysäkki/asema: näytä sen lähdöt.
        if (selectedStop != null) {
            List<Departure> deps = new ArrayList<>(selectedStop.departures);
            deps.sort(Comparator.comparingLong(d -> d.departureEpochSec));
            String title = selectedStop.name == null || selectedStop.name.isEmpty()
                    ? "Pysäkki" : selectedStop.name;
            String sectionKey = "selected-stop|" + selectedStop.gtfsId;
            items.add(new TransitAdapter.Header(stopHeader(selectedStop), headerMode(selectedStop),
                    sectionKey, zoneLabel(selectedStop)));
            int n = Math.min(deps.size(), MAX_PER_SELECTED_STOP);
            addDepartureRows(items, deps, n, sectionKey);
            return items;
        }

        // Ennakoiva haku: linjat (numerolla) + pysäkit/asemat (paikan nimellä).
        if (!query.isEmpty()) {
            if (searchResults != null && !searchResults.isEmpty()) {
                items.add(new TransitAdapter.Header("Linjat", "FAV", "search-routes"));
                items.addAll(searchResults);
            }
            if (placeResults != null && !placeResults.isEmpty()) {
                items.add(new TransitAdapter.Header("Pysäkit ja asemat", "BUS", "search-places"));
                items.addAll(placeResults);
            }
            return items;
        }

        // Oletus: suosikit + lähimmät lähdöt PYSÄKEITTÄIN (lähin ensin) — selkeä mikä on oma pysäkki.
        for (NearbyStop fs : favStopData) {
            List<Departure> deps = new ArrayList<>(fs.departures);
            deps.sort(Comparator.comparingLong(d -> d.departureEpochSec));
            int n = Math.min(deps.size(), MAX_PER_FAV_STOP);
            if (n == 0) continue;
            String sectionKey = "favorite-stop|" + fs.gtfsId;
            items.add(new TransitAdapter.Header(stopHeader(fs) + " ★", fs.vehicleMode,
                    sectionKey, zoneLabel(fs)));
            addDepartureRows(items, deps, n, sectionKey);
        }

        List<RouteHit> favLines = TransitFavorites.getLines(ctx);
        if (!favLines.isEmpty()) {
            items.add(new TransitAdapter.Header("Suosikkilinjat", "FAV", "favorite-routes"));
            items.addAll(favLines);
        }

        int stopsShown = 0;
        for (NearbyStop stop : lastStops) {
            if (stop.departures.isEmpty()) continue;
            if (stopsShown >= MAX_NEARBY_STOPS) break;
            List<Departure> deps = new ArrayList<>(stop.departures);
            deps.sort(Comparator.comparingLong(d -> d.departureEpochSec));
            String sectionKey = "nearby-stop|" + stop.gtfsId;
            items.add(new TransitAdapter.Header(stopHeader(stop), headerMode(stop),
                    sectionKey, zoneLabel(stop)));
            int n = Math.min(deps.size(), MAX_PER_STOP);
            addDepartureRows(items, deps, n, sectionKey);
            stopsShown++;
        }
        return items;
    }

    private static void addDepartureRows(List<Object> items, List<Departure> departures,
                                         int count, String sectionKey) {
        for (int i = 0; i < count; i++) {
            Departure d = departures.get(i);
            String key = sectionKey + "|" + d.tripGtfsId + "|" + d.stopGtfsId
                    + "|" + d.departureEpochSec;
            items.add(new TransitAdapter.DepartureRow(d, key));
        }
    }

    private static String headerMode(NearbyStop s) {
        if (s.vehicleMode != null && !s.vehicleMode.isEmpty()) return s.vehicleMode;
        for (Departure d : s.departures) {
            if (d.mode != null && !d.mode.isEmpty()) return d.mode;
        }
        return "BUS";
    }

    /** Lähipysäkin osio-otsikko: nimi + pysäkkikoodi + etäisyys (esim. "Laajametsänkuja H1234 · 210 m"). */
    private static String stopHeader(NearbyStop s) {
        String name = s.name == null || s.name.isEmpty() ? "Pysäkki" : s.name;
        String code = s.code == null || s.code.isEmpty() ? "" : " " + s.code;
        if (Double.isNaN(s.distanceMeters) || s.distanceMeters < 0) return name + code;
        long m = Math.round(s.distanceMeters);
        return m < 1000 ? name + code + " · " + m + " m"
                : name + code + " · " + String.format(new Locale("fi", "FI"), "%.1f km", m / 1000.0);
    }

    private static String zoneLabel(NearbyStop s) {
        if (s == null || s.zoneId == null) return "";
        String z = s.zoneId.trim();
        if (z.isEmpty()) return "";
        int colon = z.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < z.length()) z = z.substring(colon + 1);
        return z.toUpperCase(Locale.ROOT);
    }

    /** Ennakoiva haku: linjat (routes) + pysäkit/asemat (geokoodaus) rinnakkain samalla kyselyllä. */
    private void runLiveSearch(String q) {
        searchIo.execute(() -> {
            List<RouteHit> routes;
            try { routes = DigitransitApi.searchRoutes(q); }
            catch (Exception e) { routes = new ArrayList<>(); }
            List<PlaceHit> places;
            try { places = DigitransitApi.searchPlaces(q, lastLat, lastLon); }
            catch (Exception e) { places = new ArrayList<>(); }
            final List<RouteHit> fr = routes;
            final List<PlaceHit> fp = places;
            ui.post(() -> {
                if (!isAdded()) return;
                if (selectedStop != null || !q.equals(query)) return;  // vanhentunut tulos
                searchResults = fr;
                placeResults = fp;
                renderFromCache();
            });
        });
    }

    // --- TransitAdapter.Listener ---

    @Override public void onDepartureClick(Departure d) { openTimeline(d); }

    @Override
    public void onDepartureLongClick(Departure d) {
        Context ctx = getContext();
        if (ctx == null) return;
        boolean lineFav = TransitFavorites.isLineFav(ctx, d.routeGtfsId);
        boolean stopFav = TransitFavorites.isStopFav(ctx, d.stopGtfsId);
        String lineLabel = (lineFav ? "Poista suosikeista: linja " : "Lisää suosikiksi: linja ")
                + (d.routeShortName == null ? "" : d.routeShortName);
        String stopLabel = (stopFav ? "Poista suosikeista: pysäkki " : "Lisää suosikiksi: pysäkki ")
                + (d.stopName == null ? "" : d.stopName);
        new AlertDialog.Builder(ctx)
                .setItems(new CharSequence[]{lineLabel, stopLabel}, (di, which) -> {
                    if (which == 0) {
                        TransitFavorites.toggleLineFav(ctx, d.routeGtfsId, d.routeShortName, "", d.mode);
                        renderFromCache();
                    } else {
                        TransitFavorites.toggleStopFav(ctx, d.stopGtfsId, d.stopName);
                        refresh(false);
                    }
                })
                .show();
    }

    @Override
    public void onLineStar(String routeGtfsId, String shortName, String longName, String mode) {
        Context ctx = getContext();
        if (ctx == null) return;
        boolean now = TransitFavorites.toggleLineFav(ctx, routeGtfsId, shortName, longName, mode);
        Toast.makeText(ctx, now ? "Linja lisätty suosikkeihin" : "Linja poistettu suosikeista",
                Toast.LENGTH_SHORT).show();
        renderFromCache();
    }

    @Override
    public void onRouteClick(RouteHit r) {
        // Napautus AVAA linjan (reitti + aikataulu); suosikki hoidetaan tähdellä.
        Departure preferred = preferredDepartureForRoute(r.gtfsId);
        openRoute(r.gtfsId, r.shortName, r.mode, preferred);
    }

    @Override
    public void onPlaceClick(PlaceHit p) {
        if (p == null) return;
        hideKeyboard();
        if (p.gtfsId == null || p.gtfsId.isEmpty()) {
            Toast.makeText(requireContext(), "Tälle kohteelle ei ole lähtötietoja.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        showStatus("Haetaan lähtöjä: " + p.name + "…");
        final String id = p.gtfsId;
        final boolean station = p.station;
        final String nm = p.name;
        final long request = ++placeGeneration;
        final long viewToken = viewGeneration;
        searchIo.execute(() -> {
            NearbyStop ns;
            try {
                ns = station ? DigitransitApi.stationDepartures(id) : DigitransitApi.stopDepartures(id);
            } catch (Exception e) {
                ns = null;
            }
            final NearbyStop res = ns;
            ui.post(() -> {
                if (!isViewCurrent(viewToken) || request != placeGeneration) return;
                if (res == null || res.departures.isEmpty()) {
                    showStatus("Ei tulevia lähtöjä: " + nm);
                    return;
                }
                searchResults = null;
                placeResults = null;
                selectedStop = res;
                renderFromCache();
            });
        });
    }

    @Override
    public boolean isLineFav(String routeGtfsId) {
        Context ctx = getContext();
        return ctx != null && TransitFavorites.isLineFav(ctx, routeGtfsId);
    }

    private Departure preferredDepartureForRoute(String routeGtfsId) {
        if (routeGtfsId == null || routeGtfsId.isEmpty()) return null;
        Departure best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        long now = System.currentTimeMillis() / 1000L;
        for (NearbyStop stop : lastStops) {
            if (stop == null || stop.departures == null) continue;
            double dist = Double.isFinite(stop.distanceMeters) ? Math.max(0, stop.distanceMeters) : 1000.0;
            for (Departure d : stop.departures) {
                if (d == null || !routeGtfsId.equals(d.routeGtfsId)) continue;
                long waitSec = Math.max(0, d.departureEpochSec - now);
                double score = dist + Math.min(waitSec / 60.0, 60.0) * 2.0;
                if (score < bestScore) {
                    bestScore = score;
                    best = d;
                }
            }
        }
        return best;
    }

    // --- Aikajana: vuoronäkymä ---

    private void openTimeline(Departure d) {
        if (d == null || d.tripGtfsId == null || d.tripGtfsId.isEmpty()) return;
        detailGeneration++;
        openIsRoute = false;
        openTrip = d.tripGtfsId; openPattern = d.patternCode; openBoardStop = d.stopGtfsId;
        openMode = d.mode; openShort = d.routeShortName;
        openRoutePatterns = null;
        showDetailHeader(d.routeShortName, d.directionLabel(), d.mode, false);
        detailBanner.setText("Haetaan vuoron tietoja…");
        timelineAdapter.submit(new ArrayList<>(), null, -1, -1, openMode, false);
        detailOverlay.setVisibility(View.VISIBLE);
        if (backCallback != null) backCallback.setEnabled(true);
        reloadTimeline();
    }

    // --- Linjanäkymä: reitti + aikataulu + live-vuorot ---

    private void openRoute(String routeGtfsId, String shortName, String mode) {
        openRoute(routeGtfsId, shortName, mode, null);
    }

    private void openRoute(String routeGtfsId, String shortName, String mode, Departure preferred) {
        if (routeGtfsId == null || routeGtfsId.isEmpty()) return;
        final long request = ++detailGeneration;
        final long viewToken = viewGeneration;
        final String preferredPattern = preferred == null ? "" : preferred.patternCode;
        final String preferredHeadsign = preferred == null ? "" : preferred.headsign;
        openIsRoute = true;
        openMode = mode; openShort = shortName; openTrip = null;
        openRoutePatterns = null; openPatternIdx = 0;
        showDetailHeader(shortName, preferredHeadsign, mode, false);
        detailBanner.setText("Haetaan linjan tietoja…");
        timelineAdapter.submit(new ArrayList<>(), null, -1, -1, openMode, false);
        detailOverlay.setVisibility(View.VISIBLE);
        if (backCallback != null) backCallback.setEnabled(true);
        io.execute(() -> {
            RoutePatterns rp;
            try { rp = DigitransitApi.routePatterns(routeGtfsId); }
            catch (Exception e) { rp = null; }
            final RoutePatterns result = rp;
            ui.post(() -> {
                if (!isViewCurrent(viewToken) || request != detailGeneration
                        || !openIsRoute || detailOverlay == null
                        || detailOverlay.getVisibility() != View.VISIBLE) return;
                if (result == null || result.patterns.isEmpty()) {
                    detailBanner.setText("Linjan tietoja ei saatu.");
                    return;
                }
                openRoutePatterns = result;
                openPatternIdx = preferredPatternIndex(result, preferredPattern, preferredHeadsign);
                detailSwap.setVisibility(result.patterns.size() > 1 ? View.VISIBLE : View.GONE);
                loadPattern();
            });
        });
    }

    private int preferredPatternIndex(RoutePatterns patterns, String patternCode, String headsign) {
        if (patterns == null || patterns.patterns == null || patterns.patterns.isEmpty()) return 0;
        if (patternCode != null && !patternCode.isEmpty()) {
            for (int i = 0; i < patterns.patterns.size(); i++) {
                RoutePatterns.Pat p = patterns.patterns.get(i);
                if (patternCode.equals(p.code)) return i;
            }
        }
        if (headsign != null && !headsign.isEmpty()) {
            for (int i = 0; i < patterns.patterns.size(); i++) {
                RoutePatterns.Pat p = patterns.patterns.get(i);
                if (headsign.equalsIgnoreCase(p.headsign)) return i;
            }
        }
        return 0;
    }

    private void loadPattern() {
        if (openRoutePatterns == null || openRoutePatterns.patterns.isEmpty()) return;
        RoutePatterns.Pat p = openRoutePatterns.patterns.get(openPatternIdx);
        detailDest.setText(p.directionLabel());
        detailBanner.setText("Haetaan aikataulua…");
        reloadTimeline();
    }

    private void swapDirection() {
        if (openRoutePatterns == null || openRoutePatterns.patterns.size() < 2) return;
        detailGeneration++;
        openPatternIdx = (openPatternIdx + 1) % openRoutePatterns.patterns.size();
        loadPattern();
    }

    private void reloadTimeline() {
        final long request = detailGeneration;
        final long viewToken = viewGeneration;
        if (openIsRoute) {
            if (openRoutePatterns == null || openRoutePatterns.patterns.isEmpty()) return;
            final String code = openRoutePatterns.patterns.get(openPatternIdx).code;
            final String shortN = openShort, mode = openMode;
            io.execute(() -> {
                TripTimeline tl;
                try { tl = DigitransitApi.patternTimetable(code, shortN, mode); }
                catch (Exception e) { tl = null; }
                final TripTimeline res = tl;
                ui.post(() -> {
                    if (!isViewCurrent(viewToken) || request != detailGeneration || detailOverlay == null
                            || detailOverlay.getVisibility() != View.VISIBLE || !openIsRoute) return;
                    applyTimeline(res, true);
                });
            });
        } else {
            final String trip = openTrip, pat = openPattern, board = openBoardStop;
            if (trip == null) return;
            io.execute(() -> {
                TripTimeline tl;
                try { tl = DigitransitApi.tripTimeline(trip, pat, board); }
                catch (Exception e) { tl = null; }
                final TripTimeline res = tl;
                ui.post(() -> {
                    if (!isViewCurrent(viewToken) || request != detailGeneration || detailOverlay == null
                            || detailOverlay.getVisibility() != View.VISIBLE
                            || openIsRoute || !trip.equals(openTrip)) return;
                    applyTimeline(res, false);
                });
            });
        }
    }

    private void applyTimeline(TripTimeline tl, boolean isRoute) {
        if (tl == null || tl.stops.isEmpty()) {
            detailBanner.setText(isRoute ? "Linjan aikataulua ei saatu." : "Vuoron tietoja ei saatu.");
            return;
        }
        Set<Integer> veh = new HashSet<>(tl.vehicleStopIndices);
        int passedBefore = (!isRoute && tl.vehicleStopIndices.size() == 1)
                ? tl.vehicleStopIndices.get(0) : -1;
        timelineAdapter.submit(tl.stops, veh, tl.boardStopIndex, passedBefore, openMode,
                !isRoute && tl.vehicleIncoming);
        detailBanner.setText(isRoute ? routeBanner(tl) : tripBanner(tl));
        if (isRoute) stopLiveTracking();
        else setupLiveTracking(tl);
    }

    private String tripBanner(TripTimeline tl) {
        String word = modeWord(openMode);
        if (tl.vehicleStopIndices.isEmpty()) return "Ei live-sijaintia — vuoro ei ole vielä liikkeellä.";
        int relIdx = tl.vehicleStopIndices.get(0);
        boolean approaching = tl.vehicleIncoming;  // IN_TRANSIT_TO / INCOMING_AT: ei vielä relIdx:llä
        String at = relIdx >= 0 && relIdx < tl.stops.size() ? tl.stops.get(relIdx).name : "";
        // Lähestyttäessä bussi on vielä edellisellä välillä → efektiivinen "viimeksi ohitettu" = relIdx-1.
        int effIdx = approaching ? relIdx - 1 : relIdx;
        if (tl.boardStopIndex >= 0) {
            int n = tl.boardStopIndex - effIdx;
            if (n > 0) {
                String pos = approaching ? "matkalla pysäkille " + at : "pysäkillä " + at;
                return word + " on " + n + " pysäkin päässä pysäkistäsi (nyt " + pos + ").";
            }
            if (n == 0) return word + " on pysäkilläsi tai aivan vieressä (" + at + ").";
            return word + " on jo ohittanut pysäkkisi (nyt: " + at + ").";
        }
        return word + (approaching ? " on matkalla pysäkille " : " on pysäkillä ") + at + ".";
    }

    private String routeBanner(TripTimeline tl) {
        int c = tl.vehicleStopIndices.size();
        if (c == 0) return "Ei liikkeellä olevia vuoroja juuri nyt — alla reitti ja seuraavat lähtöajat.";
        return c + (c == 1 ? " vuoro liikkeellä" : " vuoroa liikkeellä")
                + " — sijainti korostettu. Ajat = seuraava lähtö kultakin pysäkiltä.";
    }

    // --- Live-sijainti MQTT:llä (HSL HFP): tarkka etäisyys reittiä pitkin pysäkillesi ---

    private void setupLiveTracking(TripTimeline tl) {
        if (tl == null || tl.boardStopIndex < 0 || tl.vehicleId == null || tl.vehicleId.isEmpty()
                || tl.shape == null || tl.shape.size() < 2) {
            stopLiveTracking();
            return;
        }
        liveTrip = tl;
        liveCum = RouteProjection.cumulative(tl.shape);
        liveStopVertex = new int[tl.stops.size()];
        liveStopDist = RouteProjection.stopDistances(tl.shape, liveCum, tl.stops, liveStopVertex);
        liveLastAlong = Double.NaN;
        if (!tl.vehicleId.equals(mqttVehicleId)) {
            if (mqtt == null) mqtt = new HslMqttClient();
            mqttVehicleId = tl.vehicleId;
            mqtt.subscribeVehicle(tl.vehicleId,
                    (lat, lon, spd, dl, loc, hdg, tsi) ->
                            ui.post(() -> onLiveVehicle(lat, lon, dl, loc, tsi)));
        }
    }

    private void stopLiveTracking() {
        mqttVehicleId = "";
        liveTrip = null;
        liveCum = null;
        liveStopDist = null;
        liveStopVertex = null;
        liveLastAlong = Double.NaN;
        if (mqtt != null) mqtt.disconnect();
    }

    /** VP-päivitys (UI-säie): projisoi bussin GPS reitille → "~X m ennen pysäkkiäsi (~Y min)". */
    private void onLiveVehicle(double lat, double lon, int delay, String loc, long tsi) {
        if (!isAdded() || openIsRoute || liveTrip == null || detailBanner == null) return;
        if (detailOverlay == null || detailOverlay.getVisibility() != View.VISIBLE) return;
        if (!Double.isFinite(lat) || !Double.isFinite(lon)
                || lat < -90 || lat > 90 || lon < -180 || lon > 180) return;
        long nowSec = System.currentTimeMillis() / 1000L;
        if (tsi > 0 && (tsi < nowSec - 60 || tsi > nowSec + 30)) return;
        TripTimeline tl = liveTrip;
        int board = tl.boardStopIndex;
        if (liveStopDist == null || board < 0 || board >= liveStopDist.length) return;

        int loV = 0, hiV = tl.shape.size() - 1;
        if (!tl.vehicleStopIndices.isEmpty() && liveStopVertex != null) {
            int relIdx = Math.max(0, Math.min(tl.vehicleStopIndices.get(0), liveStopVertex.length - 1));
            int loIdx = Math.max(0, relIdx - 2);
            int hiIdx = Math.min(liveStopVertex.length - 1, relIdx + 3);
            loV = Math.max(0, liveStopVertex[loIdx] - 4);
            hiV = Math.min(tl.shape.size() - 1, liveStopVertex[hiIdx] + 4);
            if (hiV - loV < 2) { loV = 0; hiV = tl.shape.size() - 1; }
        }
        double minAlong = Double.isFinite(liveLastAlong) ? Math.max(0, liveLastAlong - 75) : 0;
        double[] pr = RouteProjection.project(tl.shape, liveCum, lat, lon, loV, hiV, minAlong);
        if (!Double.isFinite(pr[0]) || pr[1] > 250) return;
        if (Double.isFinite(liveLastAlong) && pr[0] + 30 < liveLastAlong) return;
        liveLastAlong = Double.isFinite(liveLastAlong) ? Math.max(liveLastAlong, pr[0]) : pr[0];
        double toBoard = liveStopDist[board] - liveLastAlong;
        String word = modeWord(openMode);
        String src = "GPS".equalsIgnoreCase(loc) ? "" : " (arvio)";
        if (toBoard < -30) {
            detailBanner.setText(word + " on jo ohittanut pysäkkisi" + src + ".");
            return;
        }
        long boardEpoch = tl.stops.get(board).depEpochSec;
        String eta = "";
        if (boardEpoch > 0) {
            long secs = boardEpoch - System.currentTimeMillis() / 1000L;
            if (secs >= 0 && secs < 3600) {
                eta = " (saapuu ~klo " + liveClock(boardEpoch)
                        + ", ~" + Math.max(1, Math.round(secs / 60.0)) + " min)";
            } else {
                eta = " (saapuu ~klo " + liveClock(boardEpoch) + ")";
            }
        }
        detailBanner.setText(word + " ~" + formatMeters(Math.max(0, toBoard))
                + " ennen pysäkkiäsi" + eta + src + ".");
    }

    private static final SimpleDateFormat LIVE_CLOCK =
            new SimpleDateFormat("HH:mm", new Locale("fi", "FI"));

    private static String liveClock(long epochSec) {
        return LIVE_CLOCK.format(new Date(epochSec * 1000L));
    }

    private static String formatMeters(double m) {
        if (m >= 1000) return String.format(new Locale("fi", "FI"), "%.1f km", m / 1000.0);
        long r = Math.round(m / 10.0) * 10;
        return r + " m";
    }

    private void showDetailHeader(String shortName, String dest, String mode, boolean swapVisible) {
        Context ctx = getContext();
        detailBadge.setText(shortName == null || shortName.isEmpty() ? "?" : shortName);
        if (ctx != null) {
            detailBadge.setBackgroundTintList(ColorStateList.valueOf(TransitAdapter.modeColor(ctx, mode)));
        }
        detailDest.setText(dest == null ? "" : dest);
        detailSwap.setVisibility(swapVisible ? View.VISIBLE : View.GONE);
    }

    private void closeDetail() {
        detailGeneration++;
        stopLiveTracking();
        if (detailOverlay != null) detailOverlay.setVisibility(View.GONE);
        if (backCallback != null) backCallback.setEnabled(false);
        openTrip = null;
        openIsRoute = false;
        openRoutePatterns = null;
    }

    // --- Apurit ---

    private static String modeWord(String mode) {
        if ("TRAM".equals(mode)) return "Ratikka";
        if ("RAIL".equals(mode)) return "Juna";
        if ("SUBWAY".equals(mode)) return "Metro";
        if ("FERRY".equals(mode)) return "Lautta";
        return "Bussi";
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isViewCurrent(long token) {
        return token == viewGeneration && isAdded() && getView() != null && searchField != null;
    }

    private void hideKeyboard() {
        if (searchField == null) return;
        searchField.clearFocus();
        InputMethodManager imm = (InputMethodManager) requireContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
    }

    private void showStatus(String text) {
        if (status == null) return;
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        if (status != null) status.setVisibility(View.GONE);
    }

    /** Back-to-top: skrollaa näkyvän listan (lähilista tai aikajana-overlay) alkuun. */
    void scrollToTop() {
        if (detailOverlay != null && detailOverlay.getVisibility() == View.VISIBLE) {
            if (detailList != null) detailList.smoothScrollToPosition(0);
        } else if (list != null) {
            list.smoothScrollToPosition(0);
        }
    }

    @Override
    public void onDestroyView() {
        viewGeneration++;
        detailGeneration++;
        placeGeneration++;
        ui.removeCallbacks(autoRefresh);
        ui.removeCallbacks(searchDebounce);
        ticking = false;
        stopLiveTracking();
        searchField = null;
        searchClear = null;
        swipe = null;
        status = null;
        adapter = null;
        list = null;
        detailList = null;
        detailOverlay = null;
        detailBadge = null;
        detailDest = null;
        detailBanner = null;
        detailSwap = null;
        timelineAdapter = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        io.shutdownNow();
        searchIo.shutdownNow();
        if (mqtt != null) { mqtt.disconnect(); mqtt = null; }
        super.onDestroy();
    }
}
