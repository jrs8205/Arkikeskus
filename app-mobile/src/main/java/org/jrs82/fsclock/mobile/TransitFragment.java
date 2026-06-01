package org.jrs82.fsclock.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Joukkoliikennesivu sovelluksen sisäisenä näkymänä (MobileMainActivityn pääheaderin alla):
 *  GPS-sijainnin lähimmät HSL-lähdöt reaaliajassa, ryhmiteltynä moodeittain. SwipeRefresh +
 *  25 s auto-refresh. Malli: {@link RoadCamerasFragment} (io/ui-säikeet, isAdded-guardit). */
public class TransitFragment extends Fragment {

    private static final long AUTO_REFRESH_MS = 25_000L;
    // Osioiden järjestys ja suomenkieliset otsikot.
    private static final String[] GROUP_MODES = {"BUS", "RAIL", "TRAM", "SUBWAY"};
    private static final String[] GROUP_TITLES = {"Bussit", "Junat", "Raitiovaunut", "Metro"};
    private static final int MAX_PER_GROUP = 15;

    private SwipeRefreshLayout swipe;
    private TextView status;
    private TransitAdapter adapter;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean inFlight = false;
    private boolean ticking = false;

    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            View v = getView();
            if (v != null && v.isShown()) refresh(false);
            ui.postDelayed(this, AUTO_REFRESH_MS);
        }
    };

    private final ActivityResultLauncher<String[]> permLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean granted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION))
                        || Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                if (granted) {
                    refresh(false);
                } else {
                    showStatus("Sijaintilupa tarvitaan lähimpien lähtöjen näyttämiseen.");
                }
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
        swipe = view.findViewById(R.id.transit_swipe);
        status = view.findViewById(R.id.transit_status);
        RecyclerView list = view.findViewById(R.id.transit_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new TransitAdapter();
        list.setAdapter(adapter);
        swipe.setOnRefreshListener(() -> refresh(true));
        refresh(false);
    }

    /** Kutsutaan kun sivulle palataan (MobileMainActivity.showTransit) → tuore haku. */
    void onSectionShown() {
        if (isAdded() && getView() != null) refresh(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!ticking) {
            ticking = true;
            ui.postDelayed(autoRefresh, AUTO_REFRESH_MS);
        }
    }

    @Override
    public void onPause() {
        ticking = false;
        ui.removeCallbacks(autoRefresh);
        super.onPause();
    }

    private void refresh(boolean userInitiated) {
        if (!isAdded()) return;
        if (!hasLocationPermission()) {
            if (swipe != null) swipe.setRefreshing(false);
            showStatus("Salli sijainti nähdäksesi lähimmät lähdöt.");
            permLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION});
            return;
        }
        if (inFlight) return;
        inFlight = true;
        if (adapter == null || adapter.getItemCount() == 0) {
            showStatus("Haetaan lähimpiä lähtöjä…");
        }
        requestLocationThenFetch();
    }

    @SuppressLint("MissingPermission") // lupa tarkistettu refresh():ssä ennen kutsua
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
                        if (location == null) {
                            inFlight = false;
                            if (swipe != null) swipe.setRefreshing(false);
                            if (adapter == null || adapter.getItemCount() == 0) {
                                showStatus("Sijaintia ei saatu. Vedä alas yrittääksesi uudelleen.");
                            }
                            return;
                        }
                        fetch(location.getLatitude(), location.getLongitude());
                    })
                    .addOnFailureListener(requireActivity(), e -> {
                        inFlight = false;
                        if (!isAdded()) return;
                        if (swipe != null) swipe.setRefreshing(false);
                        if (adapter == null || adapter.getItemCount() == 0) {
                            showStatus("Sijaintia ei saatu. Vedä alas yrittääksesi uudelleen.");
                        }
                    });
        } catch (Exception e) {
            inFlight = false;
            if (swipe != null) swipe.setRefreshing(false);
            showStatus("Sijaintia ei voitu lukea.");
        }
    }

    private void fetch(double lat, double lon) {
        io.execute(() -> {
            try {
                final List<NearbyStop> stops = TransitRepository.get().fetch(lat, lon);
                ui.post(() -> render(stops));
            } catch (Exception e) {
                ui.post(() -> {
                    inFlight = false;
                    if (!isAdded()) return;
                    if (swipe != null) swipe.setRefreshing(false);
                    if (adapter == null || adapter.getItemCount() == 0) {
                        showStatus("Lähtöjen haku epäonnistui. Vedä alas yrittääksesi uudelleen.");
                    }
                });
            }
        });
    }

    private void render(List<NearbyStop> stops) {
        inFlight = false;
        if (!isAdded() || adapter == null) return;
        if (swipe != null) swipe.setRefreshing(false);

        List<Object> items = buildGroupedItems(stops);
        adapter.submit(items);
        if (items.isEmpty()) {
            showStatus("Ei lähtöjä 700 m säteellä.\nHSL-alue kattaa pääkaupunkiseudun "
                    + "(Helsinki, Espoo, Vantaa, Kauniainen, Kerava, Sipoo, Siuntio, Tuusula).");
        } else {
            hideStatus();
        }
    }

    /** Litistää lähdöt moodeittain ja rakentaa otsikko+lähtö-rivilistan adapterille. */
    private List<Object> buildGroupedItems(List<NearbyStop> stops) {
        List<Object> items = new ArrayList<>();
        if (stops == null) return items;
        for (int g = 0; g < GROUP_MODES.length; g++) {
            String mode = GROUP_MODES[g];
            List<Departure> group = new ArrayList<>();
            for (NearbyStop stop : stops) {
                for (Departure d : stop.departures) {
                    if (mode.equals(d.mode)) group.add(d);
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

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
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
        swipe = null;
        status = null;
        adapter = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
