package org.jrs82.fsclock;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Yhteinen kellon/sään/sivutuksen logiikka.
 *  Käytetään sekä MainActivityssä (etualan kellosovellus) että ClockDreamissä (näytönsäästäjä). */
public class ClockController {

    private static final String TAG = "ClockController";
    private static final Locale FI = new Locale("fi", "FI");

    private static final long TICK_MS = 1000L;
    private static final long[] WEATHER_RETRY_MS = {30_000L, 60_000L, 5L * 60_000L};
    private static final int PAGE_COUNT = 11;

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService io;

    // Pääsivu
    private TextView clockTime, clockDate;
    private TextView currentTemp, currentFeels, currentDetails;
    private WeatherIconView currentIcon;
    private TextView nextHolidayTv;

    // Yhteiset
    private TextView statusText, batteryText;
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
    private int retryStep = 0;
    private int lastRefreshDay = -1;
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
                case SettingsManager.KEY_TEST_MODE_TYPE:
                case SettingsManager.KEY_TEST_MODE_UNTIL:
                    brightness.applyNow();
                    updateStatus();
                    break;
                case SettingsManager.KEY_HOME_PLACE:
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
        TextView pageIndicator = root.findViewById(R.id.page_indicator);
        batteryText = root.findViewById(R.id.battery_text);
        FrameLayout pagesContainer = root.findViewById(R.id.pages_container);
        pixelShift = new PixelShiftController(root.findViewById(R.id.shift_container));
        brightness = new BrightnessController(window, SettingsManager.get());

        buildPages(pagesContainer);

        pageController = new PageController(ctx, pagesContainer, pageIndicator, pages);
        pageController.start();
    }

    /** Kutsutaan kun pitkä painallus aloittaa asetussivun (Activity-tilassa). */
    public void setLongPressCallback(Runnable r) {
        pageController.setLongPressCallback(r);
    }

    public void start() {
        active.set(true);
        lastRefreshDay = Calendar.getInstance(FI).get(Calendar.DAY_OF_YEAR);
        io = Executors.newSingleThreadExecutor();
        SettingsManager.get().registerListener(prefsListener);
        renderStaticContent();
        ui.post(tickClock);
        pixelShift.start();
        brightness.start();
        ui.post(fetchWeather);
    }

    public void stop() {
        active.set(false);
        try { SettingsManager.get().unregisterListener(prefsListener); } catch (Exception ignored) { }
        pixelShift.stop();
        brightness.stop();
        ui.removeCallbacksAndMessages(null);
        if (io != null) { io.shutdownNow(); io = null; }
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
        currentTemp = root.findViewById(R.id.current_temp);
        currentFeels = root.findViewById(R.id.current_feels);
        currentDetails = root.findViewById(R.id.current_details);
        nextHolidayTv = root.findViewById(R.id.next_holiday);

        if (UiMetrics.isCompactHeight(ctx.getResources())) {
            currentFeels.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        }
        return root;
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
            batteryText.setText(charging ? "⚡ " + pct + " %" : pct + " %");
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

    // ============================================================
    // Sää-haku
    // ============================================================

    private final Runnable fetchWeather = new FetchWeatherRunnable();
    private class FetchWeatherRunnable implements Runnable {
        @Override public void run() { if (io != null) io.execute(new FetchWorker(data)); }
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
    private class ApplyWeather implements Runnable {
        final WeatherData wd;
        ApplyWeather(WeatherData wd) { this.wd = wd; }
        @Override public void run() {
            if (!active.get()) return;
            data = wd;
            retryStep = 0;
            SettingsManager.get().setLastSuccessfulFmiUpdate(wd.fetchedAt);
            renderCurrent();
            renderForecastAll();
            ui.removeCallbacks(fetchWeather);
            long okMs = SettingsManager.get().getWeatherUpdateMinutes() * 60_000L;
            ui.postDelayed(fetchWeather, okMs);
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
        if (hols.isEmpty()) {
            nextHolidayTv.setText("");
        } else {
            FinnishHolidays.Holiday h = hols.get(0);
            Calendar hc = Calendar.getInstance(FI);
            hc.clear();
            hc.set(h.year, h.month - 1, h.day);
            String[] wd = {"su", "ma", "ti", "ke", "to", "pe", "la"};
            String day = wd[hc.get(Calendar.DAY_OF_WEEK) - 1];

            String suffix;
            if (h.year == from.get(Calendar.YEAR)
                    && h.month == (from.get(Calendar.MONTH) + 1)
                    && h.day == from.get(Calendar.DAY_OF_MONTH)) {
                suffix = " — tänään";
            } else {
                int daysBetween = daysBetween(from, hc);
                if (daysBetween == 1) suffix = " — huomenna";
                else suffix = " — " + daysBetween + " päivän päästä";
            }
            nextHolidayTv.setText(String.format(FI, "Seuraava juhlapyhä: %s %d.%d.  %s%s",
                    day, h.day, h.month, h.name, suffix));
        }
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
        WeatherData.Current c = data.current;
        if (Double.isNaN(c.temperature)) {
            currentTemp.setText(ctx.getString(R.string.temp_missing));
        } else {
            currentTemp.setText(String.format(FI, ctx.getString(R.string.temp_format),
                    WeatherData.cleanZero(c.temperature)));
        }
        String weatherLabel = WeatherTextFormatter.label(ctx, c.condition);
        String feelsText = Double.isNaN(c.feelsLike) ? null
                : String.format(FI, ctx.getString(R.string.feels_format),
                        WeatherData.cleanZero(c.feelsLike));
        if (weatherLabel != null && feelsText != null) {
            currentFeels.setText(String.format(FI,
                    ctx.getString(R.string.weather_label_with_feels), weatherLabel, feelsText));
        } else if (weatherLabel != null) {
            currentFeels.setText(weatherLabel);
        } else if (feelsText != null) {
            currentFeels.setText(feelsText);
        } else {
            currentFeels.setText("");
        }
        currentIcon.setCondition(c.condition);

        String sep = ctx.getString(R.string.separator);
        StringBuilder sb = new StringBuilder();
        if (!Double.isNaN(c.windSpeed)) {
            if (Double.isNaN(c.windDirection)) {
                sb.append(String.format(FI, ctx.getString(R.string.wind_format_nodir),
                        c.windSpeed));
            } else {
                sb.append(String.format(FI, ctx.getString(R.string.wind_format),
                        c.windSpeed, WeatherData.windDirToCompass(c.windDirection)));
            }
        }
        if (!Double.isNaN(c.humidity)) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(String.format(FI, ctx.getString(R.string.humidity_format), c.humidity));
        }
        if (sb.length() > 0) sb.append(sep);
        if (c.rain24hAllMissing) {
            sb.append(ctx.getString(R.string.rain24h_missing));
        } else {
            sb.append(String.format(FI, ctx.getString(R.string.rain24h_format), c.rain24h));
        }
        currentDetails.setText(sb.toString());

        updateStatus();
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
            statusText.setText("! " + ctx.getString(R.string.status_warning_test));
            statusText.setTextColor(0xFFFFAA00);
            return;
        }
        if (data == null) {
            statusText.setText(ctx.getString(R.string.loading_weather));
            statusText.setTextColor(0xFFE0E0E0);
            return;
        }
        long ageMin = (System.currentTimeMillis() - data.fetchedAt) / 60_000L;
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", FI);
        String s = ctx.getString(R.string.weather_label) + " " + hm.format(new Date(data.fetchedAt));
        if (ageMin > 30) {
            statusText.setText("! " + s + " (" + ctx.getString(R.string.weather_stale) + ")");
            statusText.setTextColor(0xFFFFAA00);
        } else {
            statusText.setText(s);
            statusText.setTextColor(0xFFE0E0E0);
        }
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
