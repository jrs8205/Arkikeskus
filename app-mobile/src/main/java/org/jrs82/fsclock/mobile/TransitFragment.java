package org.jrs82.fsclock.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
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

import java.util.ArrayList;
import java.util.Comparator;
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
    private static final String[] GROUP_MODES = {"BUS", "RAIL", "TRAM", "SUBWAY"};
    private static final String[] GROUP_TITLES = {"Bussit", "Junat", "Raitiovaunut", "Metro"};
    private static final int MAX_PER_GROUP = 15;
    private static final int MAX_PER_FAV_STOP = 5;
    private static final Locale FI = new Locale("fi", "FI");

    private EditText searchField;
    private View searchClear;
    private SwipeRefreshLayout swipe;
    private TextView status;
    private TransitAdapter adapter;

    private View detailOverlay;
    private TextView detailBadge, detailDest, detailBanner, detailSwap;
    private TransitTimelineAdapter timelineAdapter;
    private OnBackPressedCallback backCallback;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean inFlight = false;
    private boolean ticking = false;

    private List<NearbyStop> lastStops = new ArrayList<>();
    private List<NearbyStop> favStopData = new ArrayList<>();
    private String query = "";
    private List<RouteHit> searchResults = null;

    // Avoinna oleva näkymä: joko vuoro (trip) tai linja (route).
    private boolean openIsRoute = false;
    private String openTrip, openPattern, openBoardStop, openMode, openShort;
    private RoutePatterns openRoutePatterns;
    private int openPatternIdx;

    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            if (detailOverlay != null && detailOverlay.getVisibility() == View.VISIBLE) {
                if (openIsRoute || openTrip != null) reloadTimeline();
            } else {
                View v = getView();
                if (v != null && v.isShown()) refresh(false);
            }
            ui.postDelayed(this, AUTO_REFRESH_MS);
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
        searchField = view.findViewById(R.id.transit_search);
        searchClear = view.findViewById(R.id.transit_search_clear);
        swipe = view.findViewById(R.id.transit_swipe);
        status = view.findViewById(R.id.transit_status);
        RecyclerView list = view.findViewById(R.id.transit_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new TransitAdapter(this);
        list.setAdapter(adapter);
        swipe.setOnRefreshListener(() -> refresh(true));

        detailOverlay = view.findViewById(R.id.transit_detail_overlay);
        detailBadge = view.findViewById(R.id.transit_detail_badge);
        detailDest = view.findViewById(R.id.transit_detail_dest);
        detailBanner = view.findViewById(R.id.transit_detail_banner);
        detailSwap = view.findViewById(R.id.transit_detail_swap);
        RecyclerView detailList = view.findViewById(R.id.transit_detail_list);
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
                query = s.toString().trim();
                searchClear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                if (query.isEmpty()) searchResults = null;
                renderFromCache();
            }
        });
        searchField.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { runGlobalSearch(query); return true; }
            return false;
        });
        searchClear.setOnClickListener(v -> { searchField.setText(""); hideKeyboard(); });

        refresh(false);
    }

    void onSectionShown() {
        if (isAdded() && getView() != null) refresh(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!ticking) { ticking = true; ui.postDelayed(autoRefresh, AUTO_REFRESH_MS); }
    }

    @Override
    public void onPause() {
        ticking = false;
        ui.removeCallbacks(autoRefresh);
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
            CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setGranularity(Granularity.GRANULARITY_FINE)
                    .setMaxUpdateAgeMillis(60_000L)
                    .setDurationMillis(8_000L)
                    .build();
            client.getCurrentLocation(request, new CancellationTokenSource().getToken())
                    .addOnSuccessListener(requireActivity(), location -> {
                        if (!isAdded()) { inFlight = false; return; }
                        if (location == null) { onFetchFail("Sijaintia ei saatu. Vedä alas yrittääksesi uudelleen."); return; }
                        fetch(location.getLatitude(), location.getLongitude());
                    })
                    .addOnFailureListener(requireActivity(),
                            e -> onFetchFail("Sijaintia ei saatu. Vedä alas yrittääksesi uudelleen."));
        } catch (Exception e) {
            onFetchFail("Sijaintia ei voitu lukea.");
        }
    }

    private void fetch(double lat, double lon) {
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
            if (searchResults != null) showStatus("Ei linjoja haulla \"" + query + "\".");
            else if (!query.isEmpty()) showStatus("Ei osumia haulla \"" + query
                    + "\".\nPaina hakunäppäintä hakeaksesi koko HSL:stä.");
            else showStatus("Ei lähtöjä 700 m säteellä.\nHSL-alue kattaa pääkaupunkiseudun.");
        } else {
            hideStatus();
        }
    }

    private List<Object> buildItems() {
        List<Object> items = new ArrayList<>();
        Context ctx = getContext();
        if (ctx == null) return items;

        if (searchResults != null) {
            items.add(new TransitAdapter.Header("Linjahaku: " + query, "FAV"));
            items.addAll(searchResults);
            return items;
        }

        String q = query.toLowerCase(FI);

        for (NearbyStop fs : favStopData) {
            List<Departure> deps = new ArrayList<>(fs.departures);
            deps.sort(Comparator.comparingLong(d -> d.departureEpochSec));
            int n = Math.min(deps.size(), MAX_PER_FAV_STOP);
            if (n == 0) continue;
            items.add(new TransitAdapter.Header(fs.name + " ★", fs.vehicleMode));
            for (int i = 0; i < n; i++) items.add(deps.get(i));
        }

        List<RouteHit> favLines = TransitFavorites.getLines(ctx);
        if (!favLines.isEmpty()) {
            items.add(new TransitAdapter.Header("Suosikkilinjat", "FAV"));
            items.addAll(favLines);
        }

        for (int g = 0; g < GROUP_MODES.length; g++) {
            String mode = GROUP_MODES[g];
            List<Departure> group = new ArrayList<>();
            for (NearbyStop stop : lastStops) {
                for (Departure d : stop.departures) {
                    if (mode.equals(d.mode) && matchesQuery(d, q)) group.add(d);
                }
            }
            if (group.isEmpty()) continue;
            group.sort(Comparator.comparingLong(d -> d.departureEpochSec));
            items.add(new TransitAdapter.Header(GROUP_TITLES[g], mode));
            int n = Math.min(group.size(), MAX_PER_GROUP);
            for (int i = 0; i < n; i++) items.add(group.get(i));
        }
        return items;
    }

    private boolean matchesQuery(Departure d, String q) {
        if (q.isEmpty()) return true;
        return (d.routeShortName != null && d.routeShortName.toLowerCase(FI).contains(q))
                || (d.headsign != null && d.headsign.toLowerCase(FI).contains(q));
    }

    private void runGlobalSearch(String name) {
        hideKeyboard();
        if (name == null || name.trim().isEmpty()) { searchResults = null; renderFromCache(); return; }
        final String q = name.trim();
        showStatus("Haetaan linjaa \"" + q + "\"…");
        io.execute(() -> {
            List<RouteHit> hits;
            try { hits = DigitransitApi.searchRoutes(q); }
            catch (Exception e) { hits = new ArrayList<>(); }
            final List<RouteHit> result = hits;
            ui.post(() -> {
                if (!isAdded()) return;
                searchResults = result;
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
        openRoute(r.gtfsId, r.shortName, r.mode);
    }

    @Override
    public boolean isLineFav(String routeGtfsId) {
        Context ctx = getContext();
        return ctx != null && TransitFavorites.isLineFav(ctx, routeGtfsId);
    }

    // --- Aikajana: vuoronäkymä ---

    private void openTimeline(Departure d) {
        if (d == null || d.tripGtfsId == null || d.tripGtfsId.isEmpty()) return;
        openIsRoute = false;
        openTrip = d.tripGtfsId; openPattern = d.patternCode; openBoardStop = d.stopGtfsId;
        openMode = d.mode; openShort = d.routeShortName;
        openRoutePatterns = null;
        showDetailHeader(d.routeShortName, d.headsign == null ? "" : d.headsign, d.mode, false);
        detailBanner.setText("Haetaan vuoron tietoja…");
        timelineAdapter.submit(new ArrayList<>(), null, -1, -1, openMode);
        detailOverlay.setVisibility(View.VISIBLE);
        if (backCallback != null) backCallback.setEnabled(true);
        reloadTimeline();
    }

    // --- Linjanäkymä: reitti + aikataulu + live-vuorot ---

    private void openRoute(String routeGtfsId, String shortName, String mode) {
        if (routeGtfsId == null || routeGtfsId.isEmpty()) return;
        openIsRoute = true;
        openMode = mode; openShort = shortName; openTrip = null;
        openRoutePatterns = null; openPatternIdx = 0;
        showDetailHeader(shortName, "", mode, false);
        detailBanner.setText("Haetaan linjan tietoja…");
        timelineAdapter.submit(new ArrayList<>(), null, -1, -1, openMode);
        detailOverlay.setVisibility(View.VISIBLE);
        if (backCallback != null) backCallback.setEnabled(true);
        io.execute(() -> {
            RoutePatterns rp;
            try { rp = DigitransitApi.routePatterns(routeGtfsId); }
            catch (Exception e) { rp = null; }
            final RoutePatterns result = rp;
            ui.post(() -> {
                if (!isAdded() || !openIsRoute || detailOverlay == null
                        || detailOverlay.getVisibility() != View.VISIBLE) return;
                if (result == null || result.patterns.isEmpty()) {
                    detailBanner.setText("Linjan tietoja ei saatu.");
                    return;
                }
                openRoutePatterns = result;
                openPatternIdx = 0;
                detailSwap.setVisibility(result.patterns.size() > 1 ? View.VISIBLE : View.GONE);
                loadPattern();
            });
        });
    }

    private void loadPattern() {
        if (openRoutePatterns == null || openRoutePatterns.patterns.isEmpty()) return;
        RoutePatterns.Pat p = openRoutePatterns.patterns.get(openPatternIdx);
        detailDest.setText(p.headsign);
        detailBanner.setText("Haetaan aikataulua…");
        reloadTimeline();
    }

    private void swapDirection() {
        if (openRoutePatterns == null || openRoutePatterns.patterns.size() < 2) return;
        openPatternIdx = (openPatternIdx + 1) % openRoutePatterns.patterns.size();
        loadPattern();
    }

    private void reloadTimeline() {
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
                    if (!isAdded() || detailOverlay == null
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
                    if (!isAdded() || detailOverlay == null
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
        timelineAdapter.submit(tl.stops, veh, tl.boardStopIndex, passedBefore, openMode);
        detailBanner.setText(isRoute ? routeBanner(tl) : tripBanner(tl));
    }

    private String tripBanner(TripTimeline tl) {
        String word = modeWord(openMode);
        if (tl.vehicleStopIndices.isEmpty()) return "Ei live-sijaintia — vuoro ei ole vielä liikkeellä.";
        int idx = tl.vehicleStopIndices.get(0);
        String at = idx >= 0 && idx < tl.stops.size() ? tl.stops.get(idx).name : "";
        if (tl.boardStopIndex >= 0) {
            int n = tl.boardStopIndex - idx;
            if (n > 0) return word + " on " + n + " pysäkän päässä pysäkistäsi (nyt: " + at + ").";
            if (n == 0) return word + " on pysäkilläsi (" + at + ").";
            return word + " on jo ohittanut pysäkkisi (nyt: " + at + ").";
        }
        return word + (tl.vehicleIncoming ? " on tulossa pysäkille " : " on pysäkillä ") + at + ".";
    }

    private String routeBanner(TripTimeline tl) {
        int c = tl.vehicleStopIndices.size();
        if (c == 0) return "Ei liikkeellä olevia vuoroja juuri nyt — alla reitti ja seuraavat lähtöajat.";
        return c + (c == 1 ? " vuoro liikkeellä" : " vuoroa liikkeellä")
                + " — sijainti korostettu. Ajat = seuraava lähtö kultakin pysäkiltä.";
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

    @Override
    public void onDestroyView() {
        ui.removeCallbacks(autoRefresh);
        ticking = false;
        searchField = null;
        searchClear = null;
        swipe = null;
        status = null;
        adapter = null;
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
        super.onDestroy();
    }
}
