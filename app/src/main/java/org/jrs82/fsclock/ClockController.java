package org.jrs82.fsclock;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.app.AlertDialog;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jrs82.fsclock.ruuvi.RuuviRepository;
import org.jrs82.fsclock.ruuvi.RuuviSample;

/** Yhteinen kellon/sään/sivutuksen logiikka.
 *  Käytetään sekä MainActivityssä (etualan kellosovellus) että ClockDreamissä (näytönsäästäjä). */
public class ClockController {

    private static final String TAG = "ClockController";
    private static final Locale FI = new Locale("fi", "FI");

    private static final long TICK_MS = 1000L;
    private static final long TEMP_BROWSE_MS = 30L * 60L * 1000L;
    private static final long[] WEATHER_RETRY_MS = {30_000L, 60_000L, 5L * 60_000L};
    private static final int PAGE_COUNT = 11;

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService io;

    // Pääsivu
    private TextView clockTime, clockDate;
    private TextView currentLineTemp, currentLineLabel, currentLineFeels, currentLineWind, currentLineHumidity, currentLinePrecip;
    private TextView sunRiseText, sunSetText, dayLengthText;
    private WeatherIconView currentIcon;
    private TextView nextHolidayTv;
    private LinearLayout superWeatherList;
    private LinearLayout warningsContainer;
    private TextView warningsHeader;
    private View sensorBedroom, sensorLivingroom, sensorBalcony;
    private OpenMeteoData openMeteoData;

    // Yhteiset (jaettu globaali header näkyy joka sivulla)
    private TextView statusText, batteryText;
    private TextView browsePlaceLabel;
    private android.widget.ImageButton favoriteButton, browsePlaceButton, homePlaceButton;
    private android.widget.ImageButton prevPageButton, nextPageButton, settingsButton;
    private TextView pageIndicatorTop;
    private final PixelShiftController pixelShift;
    private final BrightnessController brightness;
    private final PageController pageController;

    // Sivut — pages jaetaan PageControllerille referenssinä
    private final View[] pages = new View[PAGE_COUNT];

    // Päivä-sivu: 4 saraketta * 6 riviä = 24h
    private final TextView[] dayHeader = new TextView[PAGE_COUNT];
    private final TextView[][] dayHourTv = new TextView[PAGE_COUNT][24];
    private final WeatherIconView[][] dayIcon = new WeatherIconView[PAGE_COUNT][24];
    private final TextView[][] dayTemp = new TextView[PAGE_COUNT][24];
    private final TextView[][] dayRain = new TextView[PAGE_COUNT][24];
    private final TextView[][] dayLabel = new TextView[PAGE_COUNT][24];

    private WeatherData data;
    private WeatherData homeData;
    private String browsePlace;
    private long browseUntilMs;
    private int retryStep = 0;
    private int lastRefreshDay = -1;
    private Runnable settingsClickCallback;
    private final AtomicBoolean active = new AtomicBoolean(false);

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
            if (key == null) return;
            switch (key) {
                case SettingsManager.KEY_DAY_BRIGHTNESS:
                case SettingsManager.KEY_NIGHT_BRIGHTNESS:
                case SettingsManager.KEY_DAY_MORNING_HOUR:
                case SettingsManager.KEY_NIGHT_EVENING_HOUR:
                    brightness.applyNow();
                    break;
                case SettingsManager.KEY_RUUVI_MAC_BEDROOM:
                case SettingsManager.KEY_RUUVI_MAC_LIVINGROOM:
                case SettingsManager.KEY_RUUVI_MAC_BALCONY:
                    renderSensors();
                    break;
                case SettingsManager.KEY_TEST_MODE_TYPE:
                case SettingsManager.KEY_TEST_MODE_UNTIL:
                    brightness.applyNow();
                    updateStatus();
                    break;
                case SettingsManager.KEY_HOME_PLACE:
                    homeData = null;
                    renderStaticContent();
                    updatePlaceControls();
                    retryStep = 0;
                    ui.removeCallbacks(fetchWeather);
                    ui.post(fetchWeather);
                    break;
                case SettingsManager.KEY_WEATHER_UPDATE_MINUTES:
                    retryStep = 0;
                    ui.removeCallbacks(fetchWeather);
                    ui.post(fetchWeather);
                    break;
            }
        }
    };

    public ClockController(Context ctx, Window window, View root) {
        this.ctx = ctx;
        SettingsManager.get().init(ctx.getApplicationContext());

        statusText = root.findViewById(R.id.status_text);
        batteryText = root.findViewById(R.id.battery_text);
        FrameLayout pagesContainer = root.findViewById(R.id.pages_container);
        pixelShift = new PixelShiftController(root.findViewById(R.id.shift_container));
        brightness = new BrightnessController(window, SettingsManager.get());

        // Globaali header näkyy joka sivulla; etusivulta on poistettu omat napit
        browsePlaceLabel = root.findViewById(R.id.browse_place_label);
        favoriteButton = root.findViewById(R.id.favorite_button);
        browsePlaceButton = root.findViewById(R.id.browse_place_button);
        homePlaceButton = root.findViewById(R.id.home_place_button);
        prevPageButton = root.findViewById(R.id.prev_page_button);
        nextPageButton = root.findViewById(R.id.next_page_button);
        pageIndicatorTop = root.findViewById(R.id.page_indicator_top);
        settingsButton = root.findViewById(R.id.settings_button);

        browsePlaceButton.setOnClickListener(v -> showBrowsePlaceDialog());
        homePlaceButton.setOnClickListener(v -> returnToHomeWeather());
        favoriteButton.setOnClickListener(v -> showFavoritesMenu());
        favoriteButton.setOnLongClickListener(v -> { toggleFavoriteForCurrentPlace(); return true; });
        settingsButton.setOnClickListener(v -> {
            if (settingsClickCallback != null) settingsClickCallback.run();
        });

        buildPages(pagesContainer);

        pageController = new PageController(ctx, pagesContainer, null, pages);
        pageController.setTopIndicator(pageIndicatorTop, ctx.getString(R.string.page_indicator_format));

        final PageController pc = pageController;
        prevPageButton.setOnClickListener(v -> pc.goTo(pc.getCurrentPage() - 1));
        nextPageButton.setOnClickListener(v -> pc.goTo(pc.getCurrentPage() + 1));

        pageController.start();

        updateSettingsButtonVisibility();
        updatePlaceControls();
    }

    private void toggleFavoriteForCurrentPlace() {
        String name = currentPlaceLabel();
        if (name == null || name.trim().isEmpty()) return;
        SettingsManager sm = SettingsManager.get();
        boolean nowFav;
        if (sm.isFavoritePlace(name)) {
            sm.removeFavoritePlace(name);
            nowFav = false;
        } else {
            sm.addFavoritePlace(name);
            nowFav = true;
        }
        refreshFavoriteHeaderIcon();
        int msg = nowFav ? R.string.favorite_added : R.string.favorite_removed;
        android.widget.Toast.makeText(ctx,
                String.format(FI, ctx.getString(msg), name.trim()),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void showFavoritesMenu() {
        SettingsManager sm = SettingsManager.get();
        java.util.List<String> favs = sm.getFavoritePlaces();
        String current = currentPlaceLabel();
        boolean isFav = sm.isFavoritePlace(current);
        String toggleLabel = isFav
                ? ctx.getString(R.string.favorites_menu_remove, current == null ? "" : current.trim())
                : ctx.getString(R.string.favorites_menu_add, current == null ? "" : current.trim());

        java.util.List<String> items = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();

        if (favs.isEmpty()) {
            items.add(ctx.getString(R.string.favorites_menu_empty));
            actions.add(null);
        } else {
            for (String f : favs) {
                final String place = f;
                items.add("\u2665  " + f);
                actions.add(() -> startTemporaryBrowse(place));
            }
        }
        items.add(toggleLabel);
        actions.add(() -> toggleFavoriteForCurrentPlace());

        String[] arr = items.toArray(new String[0]);
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.favorites_menu_title)
                .setItems(arr, (d, which) -> {
                    Runnable r = actions.get(which);
                    if (r != null) r.run();
                })
                .setNegativeButton(R.string.browse_place_cancel, null)
                .show();
    }

    private void refreshFavoriteHeaderIcon() {
        if (favoriteButton == null) return;
        boolean fav = SettingsManager.get().isFavoritePlace(currentPlaceLabel());
        favoriteButton.setImageResource(
                fav ? R.drawable.ic_heart_filled_24 : R.drawable.ic_heart_outline_24);
    }

    /** Kutsutaan kun pitkä painallus aloittaa asetussivun (Activity-tilassa). */
    public void setLongPressCallback(Runnable r) {
        pageController.setLongPressCallback(r);
    }

    public void setSettingsClickCallback(Runnable r) {
        settingsClickCallback = r;
        updateSettingsButtonVisibility();
        updatePlaceControls();
    }

    public void start() {
        active.set(true);
        lastRefreshDay = Calendar.getInstance(FI).get(Calendar.DAY_OF_YEAR);
        io = Executors.newSingleThreadExecutor();
        SettingsManager.get().registerListener(prefsListener);
        renderStaticContent();
        updatePlaceControls();
        ui.post(tickClock);
        pixelShift.start();
        brightness.start();
        ui.post(fetchWeather);
        WarningsRepository.get().addListener(warningsListener);
        ui.post(fetchWarnings);
        RuuviRepository ruuvi = RuuviRepository.get(ctx);
        ruuvi.start();
        ruuvi.addListener(ruuviListener);
        renderSensors();
        ui.post(sensorStalenessTick);
    }

    public void stop() {
        active.set(false);
        try { SettingsManager.get().unregisterListener(prefsListener); } catch (Exception ignored) { }
        WarningsRepository.get().removeListener(warningsListener);
        try {
            RuuviRepository repo = RuuviRepository.get(ctx);
            repo.removeListener(ruuviListener);
            repo.stop();
        } catch (Exception ignored) { }
        pixelShift.stop();
        brightness.stop();
        ui.removeCallbacks(returnHomeRunnable);
        ui.removeCallbacksAndMessages(null);
        if (io != null) { io.shutdownNow(); io = null; }
    }

    private final Runnable fetchWarnings = new Runnable() {
        @Override public void run() {
            if (!active.get()) return;
            WarningsRepository.get().refreshIfStale();
            ui.postDelayed(this, 15L * 60_000L);
        }
    };

    private final WarningsRepository.Listener warningsListener = warnings ->
            ui.post(() -> renderWarnings(warnings));

    /** Anturin näytetään offline-tilassa kun viimeisin mittaus on tätä vanhempi. */
    private static final long SENSOR_STALE_MS = 15L * 60_000L;

    private final RuuviRepository.Listener ruuviListener = (mac, sample) ->
            ui.post(this::renderSensors);

    /** Pakotettu uudelleenrenderöinti minuutin välein jotta offline-tila päivittyy
     *  vaikkei uusia mittauksia tulekaan. */
    private final Runnable sensorStalenessTick = new Runnable() {
        @Override public void run() {
            if (!active.get()) return;
            renderSensors();
            ui.postDelayed(this, 60_000L);
        }
    };

    private void renderSensors() {
        SettingsManager sm = SettingsManager.get();
        RuuviRepository repo = RuuviRepository.get(ctx);
        renderOneSensor(sensorBedroom, sm.getRuuviMac(SettingsManager.RUUVI_SLOT_BEDROOM), repo);
        renderOneSensor(sensorLivingroom, sm.getRuuviMac(SettingsManager.RUUVI_SLOT_LIVINGROOM), repo);
        renderOneSensor(sensorBalcony, sm.getRuuviMac(SettingsManager.RUUVI_SLOT_BALCONY), repo);
    }

    private void renderOneSensor(View container, String mac, RuuviRepository repo) {
        if (container == null) return;
        TextView tempTv = container.findViewById(R.id.sensor_temp);
        if (tempTv == null) return;
        if (mac == null || mac.isEmpty()) {
            tempTv.setText(R.string.sensor_temp_missing);
            applySensorBackground(container, Double.NaN);
            return;
        }
        RuuviSample s = repo.getLatest(mac);
        long now = System.currentTimeMillis();
        if (s == null || s.temperatureC() == null
                || (now - s.timestamp) > SENSOR_STALE_MS) {
            tempTv.setText(R.string.sensor_temp_no_signal);
            applySensorBackground(container, Double.NaN);
            return;
        }
        double tC = s.temperatureC();
        tempTv.setText(String.format(FI, "%+.1f°", tC));
        applySensorBackground(container, tC);
    }

    // ============================================================
    // Layout
    // ============================================================

    private void buildPages(FrameLayout pagesContainer) {
        pagesContainer.removeAllViews();
        pages[0] = buildMainPageXml(pagesContainer);
        pagesContainer.addView(pages[0]);
        for (int i = 1; i < PAGE_COUNT; i++) {
            pages[i] = buildDayPage(i);
            pagesContainer.addView(pages[i]);
        }
    }

    private View buildMainPageXml(ViewGroup parent) {
        View root = LayoutInflater.from(ctx).inflate(R.layout.page_home, parent, false);

        clockTime = root.findViewById(R.id.clock_time);
        clockDate = root.findViewById(R.id.clock_date);
        currentIcon = root.findViewById(R.id.current_icon);
        currentLineTemp = root.findViewById(R.id.current_line_temp);
        currentLineLabel = root.findViewById(R.id.current_line_label);
        currentLineFeels = root.findViewById(R.id.current_line_feels);
        currentLineWind = root.findViewById(R.id.current_line_wind);
        currentLineHumidity = root.findViewById(R.id.current_line_humidity);
        currentLinePrecip = root.findViewById(R.id.current_line_precip);
        sunRiseText = root.findViewById(R.id.sun_rise_text);
        sunSetText = root.findViewById(R.id.sun_set_text);
        dayLengthText = root.findViewById(R.id.day_length_text);
        nextHolidayTv = root.findViewById(R.id.next_holiday);

        superWeatherList = root.findViewById(R.id.super_weather_list);
        warningsContainer = root.findViewById(R.id.warnings_container);
        warningsHeader = root.findViewById(R.id.warnings_header);
        sensorBedroom = root.findViewById(R.id.sensor_bedroom);
        sensorLivingroom = root.findViewById(R.id.sensor_livingroom);
        sensorBalcony = root.findViewById(R.id.sensor_balcony);
        initSensorCircle(sensorBedroom, ctx.getString(R.string.sensor_label_bedroom));
        initSensorCircle(sensorLivingroom, ctx.getString(R.string.sensor_label_livingroom));
        initSensorCircle(sensorBalcony, ctx.getString(R.string.sensor_label_balcony));

        if (UiMetrics.isCompactHeight(ctx.getResources())) {
            currentLineFeels.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        }
        return root;
    }

    private void initSensorCircle(View container, String label) {
        if (container == null) return;
        TextView labelTv = container.findViewById(R.id.sensor_label);
        TextView tempTv = container.findViewById(R.id.sensor_temp);
        if (labelTv != null) labelTv.setText(label);
        if (tempTv != null) {
            tempTv.setText(ctx.getString(R.string.sensor_temp_missing));
            tempTv.setTextColor(0xFFFFFFFF);
        }
        applySensorBackground(container, Double.NaN);
    }

    private void applySensorBackground(View container, double tC) {
        if (container == null) return;
        View circle = container.findViewById(R.id.sensor_circle);
        if (circle == null) return;
        android.graphics.drawable.Drawable bg = circle.getBackground();
        if (bg instanceof android.graphics.drawable.GradientDrawable) {
            ((android.graphics.drawable.GradientDrawable) bg.mutate())
                    .setColor(TempColor.forTemperature(tC));
        } else if (bg != null) {
            bg.mutate().setColorFilter(TempColor.forTemperature(tC),
                    android.graphics.PorterDuff.Mode.SRC_IN);
        }
    }

    private void updateSettingsButtonVisibility() {
        if (settingsButton == null) return;
        settingsButton.setVisibility(settingsClickCallback == null ? View.GONE : View.VISIBLE);
    }

    private void updatePlaceControls() {
        if (browsePlaceLabel == null || homePlaceButton == null) return;
        boolean interactive = settingsClickCallback != null;
        int vis = interactive ? View.VISIBLE : View.GONE;
        browsePlaceLabel.setText(currentPlaceLabel());
        browsePlaceButton.setVisibility(vis);
        favoriteButton.setVisibility(vis);
        prevPageButton.setVisibility(vis);
        nextPageButton.setVisibility(vis);
        if (pageIndicatorTop != null) pageIndicatorTop.setVisibility(vis);
        homePlaceButton.setVisibility(interactive && isBrowsingPlace() ? View.VISIBLE : View.GONE);
        refreshFavoriteHeaderIcon();
    }

    private void showBrowsePlaceDialog() {
        if (settingsClickCallback == null) return;
        final AutoCompleteTextView input = new AutoCompleteTextView(ctx);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setHint(R.string.browse_place_hint);
        input.setThreshold(0);
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(ctx,
                android.R.layout.simple_dropdown_item_1line, buildPlaceDropdown(GeoPlace.placeNames()));
        input.setAdapter(adapter);
        input.setText(currentPlaceLabel());
        input.selectAll();

        final TextView heart = new TextView(ctx);
        heart.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        heart.setPadding(dp(12), 0, dp(12), 0);
        heart.setGravity(Gravity.CENTER);
        heart.setContentDescription(ctx.getString(R.string.action_favorite_toggle));
        refreshHeart(heart, input.getText().toString());
        heart.setOnClickListener(v -> {
            String name = input.getText() == null ? null : input.getText().toString();
            if (name == null || name.trim().isEmpty()) return;
            SettingsManager sm = SettingsManager.get();
            boolean nowFav;
            if (sm.isFavoritePlace(name)) {
                sm.removeFavoritePlace(name);
                nowFav = false;
            } else {
                sm.addFavoritePlace(name);
                nowFav = true;
            }
            refreshHeart(heart, name);
            adapter.clear();
            adapter.addAll(buildPlaceDropdown(GeoPlace.placeNames()));
            adapter.notifyDataSetChanged();
            int msg = nowFav ? R.string.favorite_added : R.string.favorite_removed;
            android.widget.Toast.makeText(ctx,
                    String.format(FI, ctx.getString(msg), name.trim()),
                    android.widget.Toast.LENGTH_SHORT).show();
        });

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(input, inLp);
        row.addView(heart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                refreshHeart(heart, s == null ? null : s.toString());
            }
        });

        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(R.string.browse_place_title)
                .setView(row)
                .setPositiveButton(R.string.browse_place_ok, (d, which) -> {
                    String raw = input.getText() == null ? null : input.getText().toString();
                    String sep = ctx.getString(R.string.favorites_separator);
                    if (raw == null || raw.trim().equals(sep)) return;
                    startTemporaryBrowse(raw);
                })
                .setNegativeButton(R.string.browse_place_cancel, null)
                .create();
        dialog.setOnShowListener(d -> loadFmiPlaceSuggestions(adapter, input, dialog));
        dialog.show();
    }

    private void refreshHeart(TextView heart, String name) {
        boolean fav = name != null && SettingsManager.get().isFavoritePlace(name);
        heart.setText(fav ? "♥" : "♡");
        heart.setTextColor(fav ? 0xFFFF4D6D : 0xFFCCCCCC);
    }

    /** Yhdistää suosikkilistan ja täyden paikkakuntalistan dropdown-näkymäksi:
     *  suosikit ensin, sitten erottava viiva, lopuksi loput aakkosjärjestyksessä. */
    private List<String> buildPlaceDropdown(String[] allNames) {
        SettingsManager sm = SettingsManager.get();
        List<String> favs = sm.getFavoritePlaces();
        java.util.LinkedHashSet<String> favKeys = new java.util.LinkedHashSet<>();
        for (String f : favs) favKeys.add(f.toLowerCase(Locale.ROOT));
        List<String> out = new java.util.ArrayList<>(favs);
        if (!favs.isEmpty()) out.add(ctx.getString(R.string.favorites_separator));
        for (String n : allNames) {
            if (n == null) continue;
            if (favKeys.contains(n.toLowerCase(Locale.ROOT))) continue;
            out.add(n);
        }
        return out;
    }

    private void loadFmiPlaceSuggestions(final ArrayAdapter<String> adapter,
                                         final AutoCompleteTextView input,
                                         final AlertDialog dialog) {
        if (io == null) return;
        io.execute(() -> {
            try {
                final String[] names = FmiPlaceSearch.fetchCityNames();
                if (!active.get()) return;
                ui.post(() -> {
                    if (!dialog.isShowing()) return;
                    adapter.clear();
                    adapter.addAll(buildPlaceDropdown(names));
                    adapter.notifyDataSetChanged();
                    if (input.hasFocus()) input.showDropDown();
                });
            } catch (Exception e) {
                Log.w(TAG, "FMI place suggestions failed", e);
            }
        });
    }

    private View buildDayPage(int idx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView header = new TextView(ctx);
        header.setTextColor(Color.WHITE);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f);
        header.setGravity(Gravity.CENTER);
        header.setText("--");
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = dp(8);
        header.setLayoutParams(hlp);
        dayHeader[idx] = header;
        root.addView(header);

        LinearLayout cols = new LinearLayout(ctx);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(cols);

        for (int c = 0; c < 4; c++) {
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            colLp.setMarginStart(dp(4));
            colLp.setMarginEnd(dp(4));
            col.setLayoutParams(colLp);
            cols.addView(col);

            boolean showShortLabel = UiMetrics.isTabletLike(ctx.getResources())
                    && !UiMetrics.isCompactHeight(ctx.getResources());

            for (int r = 0; r < 6; r++) {
                int hour = c * 6 + r;
                LinearLayout cell = new LinearLayout(ctx);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                col.addView(cell);

                LinearLayout row1 = new LinearLayout(ctx);
                row1.setOrientation(LinearLayout.HORIZONTAL);
                row1.setGravity(Gravity.CENTER_VERTICAL);
                row1.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                cell.addView(row1);

                TextView hourTv = new TextView(ctx);
                hourTv.setTextColor(0xFFB0B0B0);
                hourTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
                hourTv.setText(String.format(FI, "%02d", hour));
                hourTv.setLayoutParams(new LinearLayout.LayoutParams(dp(28),
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                row1.addView(hourTv);

                WeatherIconView icon = new WeatherIconView(ctx);
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(28), dp(28));
                iconLp.setMarginStart(dp(2));
                iconLp.setMarginEnd(dp(4));
                icon.setLayoutParams(iconLp);
                row1.addView(icon);

                TextView temp = new TextView(ctx);
                temp.setTextColor(Color.WHITE);
                temp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
                temp.setText("--");
                temp.setLayoutParams(new LinearLayout.LayoutParams(dp(48),
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                row1.addView(temp);

                TextView rain = new TextView(ctx);
                rain.setTextColor(0xFF4FA8E0);
                rain.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                rain.setText("");
                rain.setIncludeFontPadding(false);
                LinearLayout.LayoutParams rnlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rnlp.setMarginStart(dp(4));
                rain.setLayoutParams(rnlp);
                row1.addView(rain);

                TextView labelTv = new TextView(ctx);
                labelTv.setTextColor(0xFF909090);
                labelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                labelTv.setMaxLines(1);
                labelTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                labelTv.setIncludeFontPadding(false);
                labelTv.setText("");
                LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lblLp.setMarginStart(dp(30));   // tasaa kellon kanssa
                labelTv.setLayoutParams(lblLp);
                labelTv.setVisibility(showShortLabel ? View.VISIBLE : View.GONE);
                cell.addView(labelTv);

                dayHourTv[idx][hour] = hourTv;
                dayIcon[idx][hour] = icon;
                dayTemp[idx][hour] = temp;
                dayRain[idx][hour] = rain;
                dayLabel[idx][hour] = labelTv;
            }
        }
        return root;
    }

    private int dp(float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ============================================================
    // Tick: kello, akku, kirkkaus
    // ============================================================

    private final Runnable tickClock = new TickClockRunnable();
    private class TickClockRunnable implements Runnable {
        @Override public void run() {
            updateClock();
            updateBattery();
            checkDailyRefresh();
            ui.postDelayed(this, TICK_MS - (System.currentTimeMillis() % TICK_MS));
        }
    }

    private void updateBattery() {
        Intent bi = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bi == null) { batteryText.setText(""); return; }
        int level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int tempDeci = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        int statusInt = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = statusInt == BatteryManager.BATTERY_STATUS_CHARGING
                || statusInt == BatteryManager.BATTERY_STATUS_FULL;
        if (level < 0 || scale <= 0) { batteryText.setText(""); return; }
        int pct = (int) Math.round(level * 100.0 / scale);
        float tempC = tempDeci == Integer.MIN_VALUE ? Float.NaN : tempDeci / 10f;
        String fmt = charging
                ? ctx.getString(R.string.battery_format_charging)
                : ctx.getString(R.string.battery_format);
        if (Float.isNaN(tempC)) {
            batteryText.setText((charging ? "⚡ " : "") + "Akun varaus: " + pct + " %");
        } else {
            batteryText.setText(String.format(FI, fmt, pct, tempC));
        }
    }

    private void updateClock() {
        Date now = new Date();
        clockTime.setText(new SimpleDateFormat("HH:mm:ss", FI).format(now));
        clockDate.setText(formatFinnishDate(now));
    }

    private String formatFinnishDate(Date d) {
        String[] weekdays = {"Sunnuntai", "Maanantai", "Tiistai", "Keskiviikko",
                "Torstai", "Perjantai", "Lauantai"};
        Calendar c = Calendar.getInstance(FI);
        c.setTime(d);
        int dow = c.get(Calendar.DAY_OF_WEEK) - 1;
        return String.format(FI, "%s %d.%d.%d",
                weekdays[dow], c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR));
    }

    private String shortFinnishDay(Date d) {
        String[] weekdays = {"Sunnuntai", "Maanantai", "Tiistai", "Keskiviikko",
                "Torstai", "Perjantai", "Lauantai"};
        Calendar c = Calendar.getInstance(FI);
        c.setTime(d);
        return weekdays[c.get(Calendar.DAY_OF_WEEK) - 1];
    }

    private void checkDailyRefresh() {
        int today = Calendar.getInstance(FI).get(Calendar.DAY_OF_YEAR);
        if (today != lastRefreshDay) {
            lastRefreshDay = today;
            renderStaticContent();
            renderForecastAll();
        }
    }

    /** MainActivity.onResume kutsuu tätä, jotta Asetukset-paluun jälkeinen
     *  kirkkausarvo astuu varmasti voimaan (vaikka sama pct kuin ennen). */
    public void reapplyBrightness() {
        brightness.reapply();
    }

    private void startTemporaryBrowse(String rawPlace) {
        if (rawPlace == null) return;
        String place = rawPlace.trim();
        if (place.isEmpty()) return;
        browsePlace = place;
        browseUntilMs = System.currentTimeMillis() + TEMP_BROWSE_MS;
        ui.removeCallbacks(returnHomeRunnable);
        ui.postDelayed(returnHomeRunnable, TEMP_BROWSE_MS);
        updatePlaceControls();
        renderSunMoon();
        updateStatus();
        if (io != null) io.execute(new BrowseFetchWorker(place, null));
    }

    private void returnToHomeWeather() {
        browsePlace = null;
        browseUntilMs = 0L;
        ui.removeCallbacks(returnHomeRunnable);
        data = homeData;
        updatePlaceControls();
        renderSunMoon();
        if (data != null) {
            renderCurrent();
            renderForecastAll();
        } else {
            updateStatus();
            ui.removeCallbacks(fetchWeather);
            ui.post(fetchWeather);
        }
    }

    private boolean isBrowsingPlace() {
        return browsePlace != null && System.currentTimeMillis() < browseUntilMs;
    }

    private String currentPlaceLabel() {
        return isBrowsingPlace() ? browsePlace : SettingsManager.get().getHomePlace();
    }

    private final Runnable returnHomeRunnable = new Runnable() {
        @Override public void run() {
            if (browsePlace != null) returnToHomeWeather();
        }
    };

    // ============================================================
    // Sää-haku
    // ============================================================

    private final Runnable fetchWeather = new FetchWeatherRunnable();
    private class FetchWeatherRunnable implements Runnable {
        @Override public void run() { if (io != null) io.execute(new FetchWorker(homeData)); }
    }
    private class FetchWorker implements Runnable {
        final WeatherData cached;
        FetchWorker(WeatherData cached) { this.cached = cached; }
        @Override public void run() {
            if (!active.get()) return;
            // Offline-testitila: ei lähetä pyyntöä, simuloidaan virhe
            if (SettingsManager.get().getActiveTestMode() == SettingsManager.TEST_OFFLINE) {
                if (!active.get()) return;
                Log.i(TAG, "Offline-testitila päällä, ohitetaan FMI-haku");
                ui.post(new RetryWeather());
                return;
            }
            try {
                WeatherData wd = WeatherRepository.get(ctx).fetchHome(cached);
                if (!active.get()) return;
                ui.post(new ApplyWeather(wd));
            } catch (Exception e) {
                if (!active.get()) return;
                Log.w(TAG, "FMI fetch failed", e);
                ui.post(new RetryWeather());
            }
        }
    }
    private class BrowseFetchWorker implements Runnable {
        final String place;
        final WeatherData cached;
        BrowseFetchWorker(String place, WeatherData cached) {
            this.place = place;
            this.cached = cached;
        }
        @Override public void run() {
            if (!active.get()) return;
            if (SettingsManager.get().getActiveTestMode() == SettingsManager.TEST_OFFLINE) {
                ui.post(new BrowseWeatherFailed(place));
                return;
            }
            try {
                WeatherData wd = WeatherRepository.get(ctx).fetchBrowse(place, cached);
                if (!active.get()) return;
                ui.post(new ApplyBrowseWeather(place, wd));
            } catch (Exception e) {
                if (!active.get()) return;
                Log.w(TAG, "FMI browse fetch failed: " + place, e);
                ui.post(new BrowseWeatherFailed(place));
            }
        }
    }
    private class ApplyWeather implements Runnable {
        final WeatherData wd;
        ApplyWeather(WeatherData wd) { this.wd = wd; }
        @Override public void run() {
            if (!active.get()) return;
            homeData = wd;
            retryStep = 0;
            SettingsManager.get().setLastSuccessfulFmiUpdate(wd.fetchedAt);
            if (!isBrowsingPlace()) {
                data = wd;
                renderCurrent();
                renderForecastAll();
                kickOpenMeteoFetch(SettingsManager.get().getHomePlace());
            } else {
                updateStatus();
            }
            ui.removeCallbacks(fetchWeather);
            long okMs = SettingsManager.get().getWeatherUpdateMinutes() * 60_000L;
            ui.postDelayed(fetchWeather, okMs);
        }
    }

    private void kickOpenMeteoFetch(final String placeName) {
        if (io == null || placeName == null) return;
        io.execute(() -> {
            try {
                OpenMeteoData om = OpenMeteoRepository.get(ctx).fetch(placeName);
                if (!active.get()) return;
                ui.post(() -> {
                    if (!active.get()) return;
                    openMeteoData = om;
                    renderSuperWeather();
                });
            } catch (Exception e) {
                Log.w(TAG, "Open-Meteo fetch failed: " + placeName, e);
            }
        });
    }
    private class ApplyBrowseWeather implements Runnable {
        final String place;
        final WeatherData wd;
        ApplyBrowseWeather(String place, WeatherData wd) {
            this.place = place;
            this.wd = wd;
        }
        @Override public void run() {
            if (!active.get() || !isBrowsingPlace() || !place.equals(browsePlace)) return;
            data = wd;
            renderSunMoon();
            renderCurrent();
            renderForecastAll();
            kickOpenMeteoFetch(place);
            updatePlaceControls();
        }
    }
    private class BrowseWeatherFailed implements Runnable {
        final String place;
        BrowseWeatherFailed(String place) { this.place = place; }
        @Override public void run() {
            if (!active.get() || !isBrowsingPlace() || !place.equals(browsePlace)) return;
            statusText.setText(ctx.getString(R.string.browse_fetch_failed));
            statusText.setTextColor(0xFFFFAA00);
        }
    }
    private class RetryWeather implements Runnable {
        @Override public void run() {
            if (!active.get()) return;
            long delay = WEATHER_RETRY_MS[Math.min(retryStep, WEATHER_RETRY_MS.length - 1)];
            retryStep++;
            updateStatus();
            ui.removeCallbacks(fetchWeather);
            ui.postDelayed(fetchWeather, delay);
        }
    }

    // ============================================================
    // Renderointi
    // ============================================================

    private void renderStaticContent() {
        Calendar from = Calendar.getInstance(FI);
        List<FinnishHolidays.Holiday> hols = FinnishHolidays.upcoming(from, 1);
        if (hols.isEmpty() || nextHolidayTv == null) {
            if (nextHolidayTv != null) nextHolidayTv.setText("");
        } else {
            FinnishHolidays.Holiday h = hols.get(0);
            Calendar hc = Calendar.getInstance(FI);
            hc.clear();
            hc.set(h.year, h.month - 1, h.day);
            int days = daysBetween(from, hc);
            boolean flag = (h.type == FinnishHolidays.EventType.FLAG_DAY);
            int resId;
            if (days <= 0) {
                resId = flag ? R.string.flag_today_format : R.string.holiday_today_format;
                nextHolidayTv.setText(String.format(FI, ctx.getString(resId), h.name));
            } else if (days == 1) {
                resId = flag ? R.string.flag_tomorrow_format : R.string.holiday_tomorrow_format;
                nextHolidayTv.setText(String.format(FI, ctx.getString(resId), h.name));
            } else {
                resId = flag ? R.string.flag_in_days_format : R.string.holiday_in_days_format;
                nextHolidayTv.setText(String.format(FI, ctx.getString(resId), h.name, days));
            }
        }
        renderSunMoon();
    }

    /** Rakentaa keskisarakkeen "Tunti tunnilta" -listan: 24 riviä alkaen seuraavalta
     *  tasatunnilta, jokaisella rivillä FMI:n ja Open-Meteon lämpö + sääikoni vierekkäin. */
    private void renderSuperWeather() {
        if (superWeatherList == null) return;
        superWeatherList.removeAllViews();

        Calendar cal = Calendar.getInstance(FI);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.HOUR_OF_DAY, 1);
        long startMs = cal.getTimeInMillis();

        for (int i = 0; i < 24; i++) {
            long slotMs = startMs + i * 60_000L * 60L;
            int hour = (cal.get(Calendar.HOUR_OF_DAY) + i) % 24;

            WeatherData.Hour fmiH = findFmiHour(slotMs);
            OpenMeteoData.Hour omH = findOpenMeteoHour(slotMs);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(2), 0, dp(2));
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            Double fmiT = (fmiH == null || Double.isNaN(fmiH.temperature))
                    ? null : WeatherData.cleanZero(fmiH.temperature);
            WeatherCondition fmiC = (fmiH == null) ? null : fmiH.condition;
            row.addView(buildSuperCell(fmiT, fmiC));

            row.addView(buildHourCell(hour));

            Double omT = (omH == null || omH.temperature == null)
                    ? null : WeatherData.cleanZero(omH.temperature);
            WeatherCondition omC = (omH == null) ? null : omH.condition;
            row.addView(buildSuperCell(omT, omC));

            superWeatherList.addView(row);
        }
    }

    /** Yksittäinen FMI/OM-solu: lämpö vasemmalla, pieni sääikoni oikealla. */
    private View buildSuperCell(Double tempC, WeatherCondition condition) {
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER);
        cell.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tv = new TextView(ctx);
        tv.setTextColor(0xFFE0E0E0);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                ctx.getResources().getDimension(R.dimen.super_weather_text_size));
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(1);
        if (tempC == null) {
            tv.setText(ctx.getString(R.string.super_weather_no_data));
        } else {
            tv.setText(String.format(FI, "%+.0f°", tempC));
        }
        cell.addView(tv);

        if (condition != null) {
            WeatherIconView icon = new WeatherIconView(ctx);
            int sz = dp(20);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(sz, sz);
            ip.leftMargin = dp(6);
            icon.setLayoutParams(ip);
            icon.setCondition(condition);
            cell.addView(icon);
        }
        return cell;
    }

    private View buildHourCell(int hour) {
        TextView hourTv = new TextView(ctx);
        hourTv.setText(String.format(FI, ctx.getString(R.string.super_weather_hour_format), hour));
        hourTv.setTextColor(0xFFB0B0B0);
        hourTv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                ctx.getResources().getDimension(R.dimen.super_weather_text_size));
        hourTv.setGravity(Gravity.CENTER);
        hourTv.setLayoutParams(new LinearLayout.LayoutParams(dp(56),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return hourTv;
    }

    private WeatherData.Hour findFmiHour(long ms) {
        if (data == null || data.hours == null) return null;
        long best = Long.MAX_VALUE;
        WeatherData.Hour bestH = null;
        for (WeatherData.Hour h : data.hours) {
            long diff = Math.abs(h.timestamp - ms);
            if (diff < best && diff <= 30L * 60_000L) {
                best = diff;
                bestH = h;
            }
        }
        return bestH;
    }

    private OpenMeteoData.Hour findOpenMeteoHour(long ms) {
        if (openMeteoData == null) return null;
        long best = Long.MAX_VALUE;
        OpenMeteoData.Hour bestH = null;
        for (OpenMeteoData.Hour h : openMeteoData.hours) {
            long diff = Math.abs(h.timestamp - ms);
            if (diff < best && diff <= 30L * 60_000L) {
                best = diff;
                bestH = h;
            }
        }
        return bestH;
    }

    private void renderSunMoon() {
        if (sunRiseText == null || sunSetText == null || dayLengthText == null) return;
        try {
            GeoPlace place = GeoPlace.forPlace(currentPlaceLabel());
            Astronomy.SunMoon sm = Astronomy.calculate(
                    new Date(), place.latitude, place.longitude, TimeZone.getDefault());
            sunRiseText.setText(Astronomy.formatSunrise(sm.sun));
            sunSetText.setText(Astronomy.formatSunset(sm.sun));
            String len = Astronomy.formatDayLength(sm.sun);
            dayLengthText.setText(len.isEmpty() ? "" : ctx.getString(R.string.day_length_format, len));
        } catch (Exception e) {
            sunRiseText.setText("");
            sunSetText.setText("");
            dayLengthText.setText("");
        }
    }

    // ============================================================
    // Sääoitukset (MeteoAlarm / FMI)
    // ============================================================

    private void renderWarnings(java.util.List<WeatherWarning> warnings) {
        if (warningsContainer == null) return;
        warningsContainer.removeAllViews();
        if (warningsHeader != null) {
            int n = warnings == null ? 0 : warnings.size();
            String base = ctx.getString(R.string.warnings_header);
            warningsHeader.setText(n > 0 ? base + " (" + n + ")" : base);
        }
        if (warnings == null || warnings.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText(R.string.warnings_empty);
            empty.setTextColor(0xFF888888);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    ctx.getResources().getDimension(R.dimen.warning_body_text_size));
            warningsContainer.addView(empty);
            return;
        }
        for (WeatherWarning w : warnings) {
            warningsContainer.addView(buildWarningCard(w));
        }
    }

    private View buildWarningCard(WeatherWarning w) {
        int padding = ctx.getResources().getDimensionPixelSize(R.dimen.warning_card_padding);
        int dot = ctx.getResources().getDimensionPixelSize(R.dimen.warning_dot_size);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(padding, padding, padding, padding);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = padding;
        card.setLayoutParams(cardLp);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF1A1A1A);
        bg.setCornerRadius(dot);
        bg.setStroke(1, (w.level.color & 0x00FFFFFF) | 0x66000000);
        card.setBackground(bg);

        // Otsikkorivi: pieni värinen pallo + event
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        View dotView = new View(ctx);
        android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(w.level.color);
        dotView.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dot, dot);
        dotLp.rightMargin = padding / 2;
        titleRow.addView(dotView, dotLp);

        TextView title = new TextView(ctx);
        title.setText(w.event);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                ctx.getResources().getDimension(R.dimen.warning_title_text_size));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(titleRow);

        // Aikaväli + tason värisana
        TextView meta = new TextView(ctx);
        meta.setText(buildWarningMeta(w));
        meta.setTextColor(w.level.color);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                ctx.getResources().getDimension(R.dimen.warning_meta_text_size));
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = 2;
        card.addView(meta, metaLp);

        // Kuvaus (description)
        if (w.description != null && !w.description.isEmpty()) {
            TextView body = new TextView(ctx);
            body.setText(w.description);
            body.setTextColor(0xFFD8D8D8);
            body.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    ctx.getResources().getDimension(R.dimen.warning_body_text_size));
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bodyLp.topMargin = 4;
            card.addView(body, bodyLp);
        }

        // Alueet
        if (w.areaDesc != null && !w.areaDesc.isEmpty()) {
            TextView areas = new TextView(ctx);
            areas.setText(ctx.getString(R.string.warning_area_prefix, w.areaDesc));
            areas.setTextColor(0xFF9BB0C8);
            areas.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    ctx.getResources().getDimension(R.dimen.warning_meta_text_size));
            LinearLayout.LayoutParams areasLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            areasLp.topMargin = 4;
            card.addView(areas, areasLp);
        }
        return card;
    }

    private String buildWarningMeta(WeatherWarning w) {
        SimpleDateFormat dayFmt = new SimpleDateFormat("d.M.", FI);
        SimpleDateFormat timeFmt = new SimpleDateFormat("H.mm", FI);
        Calendar cOn = Calendar.getInstance(FI); cOn.setTimeInMillis(w.onsetMs);
        Calendar cEx = Calendar.getInstance(FI); cEx.setTimeInMillis(w.expiresMs);
        boolean sameDay = cOn.get(Calendar.YEAR) == cEx.get(Calendar.YEAR)
                && cOn.get(Calendar.DAY_OF_YEAR) == cEx.get(Calendar.DAY_OF_YEAR);
        String range;
        if (sameDay) {
            range = ctx.getString(R.string.warning_valid_same_day,
                    dayFmt.format(cOn.getTime()),
                    timeFmt.format(cOn.getTime()),
                    timeFmt.format(cEx.getTime()));
        } else {
            range = ctx.getString(R.string.warning_valid_multi_day,
                    dayFmt.format(cOn.getTime()),
                    timeFmt.format(cOn.getTime()),
                    dayFmt.format(cEx.getTime()),
                    timeFmt.format(cEx.getTime()));
        }
        String levelWord = w.level.fiName;
        if (levelWord == null || levelWord.isEmpty()) return range;
        return levelWord + " · " + range;
    }

    private static int daysBetween(Calendar from, Calendar to) {
        Calendar a = (Calendar) from.clone();
        a.set(Calendar.HOUR_OF_DAY, 0); a.set(Calendar.MINUTE, 0);
        a.set(Calendar.SECOND, 0); a.set(Calendar.MILLISECOND, 0);
        Calendar b = (Calendar) to.clone();
        b.set(Calendar.HOUR_OF_DAY, 0); b.set(Calendar.MINUTE, 0);
        b.set(Calendar.SECOND, 0); b.set(Calendar.MILLISECOND, 0);
        long diff = b.getTimeInMillis() - a.getTimeInMillis();
        return (int) Math.round(diff / (24.0 * 60 * 60 * 1000));
    }

    private void renderCurrent() {
        if (data == null) return;
        WeatherSnapshot s = WeatherSnapshot.fromFmi(
                data.current, currentPlaceLabel(), data.fetchedAt);
        renderCurrentSnapshot(s);
        renderSuperWeather();
        updateStatus();
    }

    private void renderCurrentSnapshot(WeatherSnapshot s) {
        if (s.temperature == null) {
            currentLineTemp.setText(ctx.getString(R.string.temp_missing));
        } else {
            currentLineTemp.setText(String.format(FI, ctx.getString(R.string.temp_format),
                    WeatherData.cleanZero(s.temperature)));
        }

        String weatherLabel = WeatherTextFormatter.label(ctx, s.condition);
        currentLineLabel.setText(weatherLabel != null ? weatherLabel : "");
        if (s.feelsLike == null) {
            currentLineFeels.setText("");
        } else {
            currentLineFeels.setText(String.format(FI,
                    ctx.getString(R.string.feels_format),
                    WeatherData.cleanZero(s.feelsLike)));
        }
        currentIcon.setCondition(s.condition);

        if (s.windSpeed == null) {
            currentLineWind.setText(ctx.getString(R.string.wind_missing));
        } else if (s.windDirection == null) {
            currentLineWind.setText(String.format(FI,
                    ctx.getString(R.string.wind_format_nodir), s.windSpeed));
        } else {
            currentLineWind.setText(String.format(FI,
                    ctx.getString(R.string.wind_format),
                    s.windSpeed, WeatherData.windDirToCompass(s.windDirection)));
        }

        if (s.humidity == null) {
            currentLineHumidity.setText(ctx.getString(R.string.humidity_missing));
        } else {
            currentLineHumidity.setText(String.format(FI,
                    ctx.getString(R.string.humidity_format), s.humidity));
        }

        if (s.rain24h == null) {
            currentLinePrecip.setText(ctx.getString(R.string.rain24h_missing));
        } else {
            currentLinePrecip.setText(String.format(FI,
                    ctx.getString(R.string.rain24h_format), s.rain24h));
        }
    }

    private void updateStatus() {
        SettingsManager sm = SettingsManager.get();
        int testMode = sm.getActiveTestMode();
        if (testMode == SettingsManager.TEST_OFFLINE) {
            statusText.setText(ctx.getString(R.string.status_offline_test));
            statusText.setTextColor(0xFFFFAA00);
            return;
        }
        if (testMode == SettingsManager.TEST_WARNING) {
            statusText.setText(ctx.getString(R.string.status_alert_format,
                    ctx.getString(R.string.status_warning_test)));
            statusText.setTextColor(0xFFFFAA00);
            return;
        }
        if (data == null) {
            statusText.setText(statusForCurrentPlace(ctx.getString(R.string.loading_weather)));
            statusText.setTextColor(0xFFE0E0E0);
            return;
        }
        long ageMin = (System.currentTimeMillis() - data.fetchedAt) / 60_000L;
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", FI);
        String s = ctx.getString(R.string.weather_label) + " " + hm.format(new Date(data.fetchedAt));
        if (ageMin > 30) {
            String stale = ctx.getString(R.string.weather_stale_format,
                    s, ctx.getString(R.string.weather_stale));
            statusText.setText(ctx.getString(R.string.status_alert_format,
                    statusForCurrentPlace(stale)));
            statusText.setTextColor(0xFFFFAA00);
        } else {
            statusText.setText(statusForCurrentPlace(s));
            statusText.setTextColor(0xFFE0E0E0);
        }
    }

    private String statusForCurrentPlace(String status) {
        if (!isBrowsingPlace()) return status;
        return String.format(FI, ctx.getString(R.string.browse_status_prefix), browsePlace, status);
    }

    /** Sade/lumi-symbolit ennusteruutuihin: sininen ● sateelle, valkoinen ❅ lumelle.
     *  Lukumäärä WeatherCondition.intensity:n mukaan (1-3). */
    private void setPrecipText(TextView tv, double mm, WeatherCondition cond) {
        if (Double.isNaN(mm) || mm < 0.05) {
            tv.setText("");
            return;
        }
        int count;
        if (cond != null && cond.intensity != null) {
            switch (cond.intensity) {
                case LIGHT: count = 1; break;
                case MODERATE: count = 2; break;
                case HEAVY: count = 3; break;
                default:
                    // intensity ei kerrottu (esim. PARTLY_CLOUDY mutta sade > 0) -> mm-ohjattu
                    if (mm < 0.5) count = 1;
                    else if (mm < 2.0) count = 2;
                    else count = 3;
            }
        } else {
            if (mm < 0.5) count = 1;
            else if (mm < 2.0) count = 2;
            else count = 3;
        }

        WeatherCondition.Type type = cond != null ? cond.type : WeatherCondition.Type.RAIN;
        StringBuilder sym = new StringBuilder();
        if (type == WeatherCondition.Type.SLEET) {
            sym.append("\u2745");
            for (int i = 1; i < count; i++) sym.append("\u25CF");
            tv.setTextColor(0xFFB8D8F0);
        } else if (type == WeatherCondition.Type.SNOW) {
            for (int i = 0; i < count; i++) sym.append("\u2745");
            tv.setTextColor(0xFFFFFFFF);
        } else {
            for (int i = 0; i < count; i++) sym.append("\u25CF");
            tv.setTextColor(0xFF4FA8E0);
        }
        tv.setText(sym.toString());
    }

    private void renderForecastAll() {
        if (data == null) return;

        java.util.LinkedHashMap<Integer, java.util.Map<Integer, WeatherData.Hour>> byDay
                = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<Integer, WeatherData.Hour> dayAny
                = new java.util.LinkedHashMap<>();
        for (WeatherData.Hour h : data.hours) {
            java.util.Map<Integer, WeatherData.Hour> map = byDay.get(h.dayOfMonth);
            if (map == null) {
                map = new java.util.HashMap<>();
                byDay.put(h.dayOfMonth, map);
                dayAny.put(h.dayOfMonth, h);
            }
            map.put(h.hour, h);
        }
        Integer[] days = byDay.keySet().toArray(new Integer[0]);
        int dayPagesAvailable = Math.min(days.length, PAGE_COUNT - 1);
        pageController.setAvailablePages(1 + dayPagesAvailable);

        for (int pageIdx = 1; pageIdx < PAGE_COUNT; pageIdx++) {
            int dayIdx = pageIdx - 1;
            if (dayIdx >= days.length) {
                dayHeader[pageIdx].setText("");
                for (int h = 0; h < 24; h++) {
                    dayTemp[pageIdx][h].setText("");
                    dayRain[pageIdx][h].setText("");
                    dayIcon[pageIdx][h].setVisibility(View.INVISIBLE);
                    if (dayLabel[pageIdx][h] != null) dayLabel[pageIdx][h].setText("");
                }
                continue;
            }
            WeatherData.Hour any = dayAny.get(days[dayIdx]);
            Calendar dc = Calendar.getInstance(FI);
            dc.setTimeInMillis(any.timestamp);
            String dayName = shortFinnishDay(dc.getTime());
            dayHeader[pageIdx].setText(String.format(FI, "%s %d.%d.",
                    dayName, any.dayOfMonth, any.month));

            java.util.Map<Integer, WeatherData.Hour> dayMap = byDay.get(days[dayIdx]);
            for (int hour = 0; hour < 24; hour++) {
                WeatherData.Hour h = dayMap.get(hour);
                if (h == null) {
                    dayTemp[pageIdx][hour].setText("--");
                    dayRain[pageIdx][hour].setText("");
                    dayIcon[pageIdx][hour].setVisibility(View.INVISIBLE);
                    if (dayLabel[pageIdx][hour] != null) dayLabel[pageIdx][hour].setText("");
                } else {
                    dayTemp[pageIdx][hour].setText(Double.isNaN(h.temperature)
                            ? "--" : String.format(FI, ctx.getString(R.string.temp_short_format),
                                    WeatherData.cleanZero(h.temperature)));
                    setPrecipText(dayRain[pageIdx][hour], h.precipitation, h.condition);
                    dayIcon[pageIdx][hour].setCondition(h.condition);
                    dayIcon[pageIdx][hour].setVisibility(View.VISIBLE);
                    if (dayLabel[pageIdx][hour] != null) {
                        dayLabel[pageIdx][hour].setText(
                                WeatherTextFormatter.shortLabel(ctx, h.condition));
                    }
                }
            }
        }
    }
}
