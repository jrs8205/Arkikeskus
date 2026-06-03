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
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import org.jrs82.fsclock.R;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reittihaku (HSL): Mistä/Minne geokoodauksella, aikavalinta (lähde/perillä klo) ja
 *  planConnection-reittiehdotukset vaihtoineen. MobileMainActivityn sisäinen sektio. */
public class RoutePlannerFragment extends Fragment implements RoutePlannerAdapter.Listener {

    private static final long DEBOUNCE_MS = 280L;
    private static final Locale FI = new Locale("fi", "FI");
    private static final SimpleDateFormat TIME_LABEL = new SimpleDateFormat("EEE d.M. 'klo' HH:mm", FI);
    private static final String MY_LOCATION = "Oma sijainti";
    /** Erikoisehdotus, jonka valinta tarkoittaa "käytä GPS-sijaintia" (lat/lon NaN). */
    private static final GeoPlace MY_LOC =
            new GeoPlace(MY_LOCATION, "Nykyinen sijaintisi (GPS)", Double.NaN, Double.NaN, "my-location");

    private EditText fromField, toField;
    private TextView swapBtn, timeBtn, searchBtn, status;
    private TextView fromClear, toClear;
    private RecyclerView list;
    private RoutePlannerAdapter adapter;

    private View detailOverlay;
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

    private interface LocCb { void onLoc(double lat, double lon); }

    private final Runnable suggestRunnable = this::runSuggest;

    private final ActivityResultLauncher<String[]> permLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean granted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION))
                        || Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                if (granted && pendingSearch) { pendingSearch = false; doSearch(); }
                else if (!granted) showStatus("Sijaintilupa tarvitaan, kun lähtö on oma sijainti.");
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_route_planner, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        fromField = view.findViewById(R.id.route_from);
        toField = view.findViewById(R.id.route_to);
        swapBtn = view.findViewById(R.id.route_swap);
        timeBtn = view.findViewById(R.id.route_time);
        searchBtn = view.findViewById(R.id.route_search);
        status = view.findViewById(R.id.route_status);
        list = view.findViewById(R.id.route_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new RoutePlannerAdapter(this);
        list.setAdapter(adapter);
        fromClear = view.findViewById(R.id.route_from_clear);
        toClear = view.findViewById(R.id.route_to_clear);
        fromClear.setOnClickListener(v -> clearField(1));
        toClear.setOnClickListener(v -> clearField(2));

        detailOverlay = view.findViewById(R.id.route_detail_overlay);
        detailTitle = view.findViewById(R.id.route_detail_title);
        detailSummary = view.findViewById(R.id.route_detail_summary);
        detailList = view.findViewById(R.id.route_detail_list);
        detailList.setLayoutManager(new LinearLayoutManager(requireContext()));
        detailAdapter = new RoutePlannerAdapter(this);
        detailList.setAdapter(detailAdapter);
        view.findViewById(R.id.route_detail_back).setOnClickListener(v -> closeDetail());

        backCallback = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() { closeDetail(); }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backCallback);

        fromField.setText(MY_LOCATION);
        fromField.setOnFocusChangeListener((v, f) -> { if (f) { activeField = 1; offerMyLocationIfEmpty(fromField, 1); } });
        toField.setOnFocusChangeListener((v, f) -> { if (f) { activeField = 2; offerMyLocationIfEmpty(toField, 2); } });
        fromField.addTextChangedListener(new SimpleWatcher(1));
        toField.addTextChangedListener(new SimpleWatcher(2));

        swapBtn.setOnClickListener(v -> swap());
        timeBtn.setOnClickListener(v -> showTimeDialog());
        searchBtn.setOnClickListener(v -> doSearch());

        updateTimeButton();
        updateClearButtons();
        showStatus("Anna määränpää ja hae reitit.\nLähtö on oletuksena oma sijaintisi.");
    }

    /** Avattaessa sivu valikosta: hae sijainti hakuehdotusten kohdistusta varten (best-effort). */
    void onSectionShown() {
        if (isAdded() && hasLocationPermission() && Double.isNaN(gpsLat)) {
            fetchLocation((lat, lon) -> { gpsLat = lat; gpsLon = lon; });
        }
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
                if (!isAdded()) return;
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
                .setItems(new CharSequence[]{"Lähde nyt", "Lähde klo…", "Perillä klo…"}, (d, w) -> {
                    if (w == 0) {
                        timeEpochMs = 0L;
                        arriveBy = false;
                        updateTimeButton();
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
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateTimeButton() {
        if (timeEpochMs <= 0) {
            timeBtn.setText("Lähde nyt");
        } else {
            timeBtn.setText((arriveBy ? "Perillä " : "Lähde ") + TIME_LABEL.format(new Date(timeEpochMs)));
        }
    }

    // --- Reittihaku ---

    private void doSearch() {
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
                if (Double.isNaN(lat)) { showStatus("Sijaintia ei saatu. Yritä uudelleen."); return; }
                gpsLat = lat;
                gpsLon = lon;
                double fLat = fromMy ? lat : fromPlace.lat;
                double fLon = fromMy ? lon : fromPlace.lon;
                double tLat = toMy ? lat : toPlace.lat;
                double tLon = toMy ? lon : toPlace.lon;
                runPlan(fLat, fLon, tLat, tLon);
            });
        } else {
            runPlan(fromPlace.lat, fromPlace.lon, toPlace.lat, toPlace.lon);
        }
    }

    private void runPlan(double fromLat, double fromLon, double toLat, double toLon) {
        showStatus("Haetaan reittejä…");
        adapter.submit(new ArrayList<>());
        final String iso = isoFor(timeEpochMs);
        final boolean arr = arriveBy;
        searchIo.execute(() -> {
            List<Itinerary> res;
            try { res = DigitransitApi.planRoutes(fromLat, fromLon, toLat, toLon, iso, arr, 5); }
            catch (Exception e) { res = null; }
            final List<Itinerary> r = res;
            ui.post(() -> {
                if (!isAdded()) return;
                if (r == null) { showStatus("Reittihaku epäonnistui. Yritä uudelleen."); return; }
                itineraries = r;
                adapter.submit(r);
                if (r.isEmpty()) showStatus("Ei reittejä tälle välille tai ajalle.");
                else hideStatus();
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
        detailAdapter.submit(it.legs);
        detailOverlay.setVisibility(View.VISIBLE);
        if (backCallback != null) backCallback.setEnabled(true);
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
        if (detailOverlay != null) detailOverlay.setVisibility(View.GONE);
        if (backCallback != null) backCallback.setEnabled(false);
    }

    // --- Sijainti ---

    @SuppressLint("MissingPermission") // lupa tarkistettu kutsujassa
    private void fetchLocation(LocCb cb) {
        try {
            FusedLocationProviderClient client =
                    LocationServices.getFusedLocationProviderClient(requireContext());
            CurrentLocationRequest req = new CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setGranularity(Granularity.GRANULARITY_FINE)
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
            cb.onLoc(Double.NaN, Double.NaN);
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
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

    /** X-napit näkyvät vain kun kentässä on tekstiä. */
    private void updateClearButtons() {
        if (fromClear != null && fromField != null) {
            fromClear.setVisibility(fromField.getText().toString().isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (toClear != null && toField != null) {
            toClear.setVisibility(toField.getText().toString().isEmpty() ? View.GONE : View.VISIBLE);
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
        if (detailOverlay != null && detailOverlay.getVisibility() == View.VISIBLE) {
            if (detailList != null) detailList.smoothScrollToPosition(0);
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

    @Override
    public void onDestroyView() {
        ui.removeCallbacks(suggestRunnable);
        fromField = null;
        toField = null;
        fromClear = null;
        toClear = null;
        swapBtn = null;
        timeBtn = null;
        searchBtn = null;
        status = null;
        list = null;
        adapter = null;
        detailOverlay = null;
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
