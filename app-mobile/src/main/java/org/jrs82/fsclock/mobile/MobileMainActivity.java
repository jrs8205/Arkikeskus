package org.jrs82.fsclock.mobile;

import android.animation.ObjectAnimator;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.location.Address;
import android.location.Geocoder;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceManager;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Granularity;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import org.jrs82.fsclock.ElectricityData;
import org.jrs82.fsclock.ElectricityRepository;
import org.jrs82.fsclock.FmiPlaceSearch;
import org.jrs82.fsclock.FinnishHolidays;
import org.jrs82.fsclock.GeoPlace;
import org.jrs82.fsclock.OpenMeteoData;
import org.jrs82.fsclock.OpenMeteoRepository;
import org.jrs82.fsclock.R;
import org.jrs82.fsclock.SettingsManager;
import org.jrs82.fsclock.WeatherData;
import org.jrs82.fsclock.WeatherIconView;
import org.jrs82.fsclock.WeatherRepository;
import org.jrs82.fsclock.WeatherTextFormatter;
import org.jrs82.fsclock.WeatherWarning;
import org.jrs82.fsclock.WarningsRepository;
import org.jrs82.fsclock.ruuvi.RuuviRepository;
import org.jrs82.fsclock.ruuvi.RuuviSample;

import java.io.IOException;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class MobileMainActivity extends AppCompatActivity {

    private static final Locale FI = new Locale("fi", "FI");
    private static final TimeZone HELSINKI = TimeZone.getTimeZone("Europe/Helsinki");
    private static final int TOMORROW_PRICE_PUBLISH_HOUR = 14;
    private static final int TOMORROW_PRICE_PUBLISH_MINUTE = 30;
    private static final int TOMORROW_PRICE_POLL_END_HOUR = 22;
    private static final int LOCATION_PERMISSION_REQUEST = 7107;
    private static final long TOMORROW_PRICE_POLL_MS = 10L * 60_000L;
    private static final long MIN_AUTO_REFRESH_MS = 5L * 60_000L;
    private static final long MAX_AUTO_REFRESH_MS = 180L * 60_000L;
    private static final long MAX_AUTO_LOCATION_AGE_MS = 10L * 60_000L;
    private static final long FUSED_LOCATION_FAST_MAX_AGE_MS = 60_000L;
    private static final long SEARCH_REFERENCE_MAX_AGE_MS = 24L * 60L * 60_000L;
    private static final long CURRENT_LOCATION_TIMEOUT_MS = 7_000L;
    private static final long LOCATION_TIME_MARGIN_MS = 2L * 60_000L;
    private static final float MAX_AUTO_LOCATION_ACCURACY_M = 1500f;
    private static final float LOCATION_ACCURACY_MARGIN_M = 150f;
    private static final String[][] FINNISH_DISTRICT_SEEDS = {
            {"Helsinki", "Alppila"},
            {"Helsinki", "Arabianranta"},
            {"Helsinki", "Etu-Töölö"},
            {"Helsinki", "Hakaniemi"},
            {"Helsinki", "Herttoniemi"},
            {"Helsinki", "Itäkeskus"},
            {"Helsinki", "Jakomäki"},
            {"Helsinki", "Kallio"},
            {"Helsinki", "Kamppi"},
            {"Helsinki", "Kannelmäki"},
            {"Helsinki", "Kontula"},
            {"Helsinki", "Kruununhaka"},
            {"Helsinki", "Käpylä"},
            {"Helsinki", "Laajasalo"},
            {"Helsinki", "Lauttasaari"},
            {"Helsinki", "Malmi"},
            {"Helsinki", "Munkkiniemi"},
            {"Helsinki", "Oulunkylä"},
            {"Helsinki", "Pasila"},
            {"Helsinki", "Pitäjänmäki"},
            {"Helsinki", "Punavuori"},
            {"Helsinki", "Sörnäinen"},
            {"Helsinki", "Töölö"},
            {"Helsinki", "Vallila"},
            {"Helsinki", "Viikki"},
            {"Helsinki", "Vuosaari"},
            {"Espoo", "Espoon keskus"},
            {"Espoo", "Haukilahti"},
            {"Espoo", "Kauklahti"},
            {"Espoo", "Kilo"},
            {"Espoo", "Leppävaara"},
            {"Espoo", "Matinkylä"},
            {"Espoo", "Niittykumpu"},
            {"Espoo", "Olari"},
            {"Espoo", "Otaniemi"},
            {"Espoo", "Soukka"},
            {"Espoo", "Tapiola"},
            {"Espoo", "Viherlaakso"},
            {"Vantaa", "Aviapolis"},
            {"Vantaa", "Hakunila"},
            {"Vantaa", "Hiekkaharju"},
            {"Vantaa", "Koivukylä"},
            {"Vantaa", "Korso"},
            {"Vantaa", "Länsimäki"},
            {"Vantaa", "Martinlaakso"},
            {"Vantaa", "Myyrmäki"},
            {"Vantaa", "Pakkala"},
            {"Vantaa", "Rekola"},
            {"Vantaa", "Tikkurila"},
            {"Tampere", "Amuri"},
            {"Tampere", "Hatanpää"},
            {"Tampere", "Hervanta"},
            {"Tampere", "Kaleva"},
            {"Tampere", "Kaukajärvi"},
            {"Tampere", "Kissanmaa"},
            {"Tampere", "Lielahti"},
            {"Tampere", "Linnainmaa"},
            {"Tampere", "Nekala"},
            {"Tampere", "Pispala"},
            {"Tampere", "Tesoma"},
            {"Tampere", "Turtola"},
            {"Turku", "Halinen"},
            {"Turku", "Hirvensalo"},
            {"Turku", "Itäharju"},
            {"Turku", "Kupittaa"},
            {"Turku", "Lauste"},
            {"Turku", "Nummi"},
            {"Turku", "Pansio"},
            {"Turku", "Runosmäki"},
            {"Turku", "Varissuo"},
            {"Turku", "Ylioppilaskylä"},
            {"Oulu", "Höyhtyä"},
            {"Oulu", "Kaijonharju"},
            {"Oulu", "Karjasilta"},
            {"Oulu", "Kaukovainio"},
            {"Oulu", "Keskusta"},
            {"Oulu", "Linnanmaa"},
            {"Oulu", "Maikkula"},
            {"Oulu", "Myllytulli"},
            {"Oulu", "Pateniemi"},
            {"Oulu", "Rajakylä"},
            {"Oulu", "Toppila"},
            {"Oulu", "Tuira"},
            {"Jyväskylä", "Halssila"},
            {"Jyväskylä", "Kangas"},
            {"Jyväskylä", "Keljo"},
            {"Jyväskylä", "Kortepohja"},
            {"Jyväskylä", "Kuokkala"},
            {"Jyväskylä", "Kypärämäki"},
            {"Jyväskylä", "Lutakko"},
            {"Jyväskylä", "Mäki-Matti"},
            {"Jyväskylä", "Palokka"},
            {"Jyväskylä", "Vaajakoski"},
            {"Lahti", "Ahtiala"},
            {"Lahti", "Hennala"},
            {"Lahti", "Jalkaranta"},
            {"Lahti", "Laune"},
            {"Lahti", "Liipola"},
            {"Lahti", "Mukkula"},
            {"Lahti", "Nastola"},
            {"Kuopio", "Haapaniemi"},
            {"Kuopio", "Kelloniemi"},
            {"Kuopio", "Neulamäki"},
            {"Kuopio", "Petonen"},
            {"Kuopio", "Puijonlaakso"},
            {"Kuopio", "Saaristokaupunki"},
            {"Porvoo", "Gammelbacka"},
            {"Porvoo", "Kevätkumpu"},
            {"Porvoo", "Keskusta"},
            {"Porvoo", "Näsi"},
            {"Lappeenranta", "Lauritsala"},
            {"Lappeenranta", "Sammonlahti"},
            {"Lappeenranta", "Skinnarila"},
            {"Vaasa", "Hietalahti"},
            {"Vaasa", "Palosaari"},
            {"Vaasa", "Suvilahti"},
            {"Vaasa", "Vöyrinkaupunki"},
            {"Kouvola", "Koria"},
            {"Kouvola", "Kuusankoski"},
            {"Kouvola", "Myllykoski"},
            {"Joensuu", "Noljakka"},
            {"Joensuu", "Rantakylä"},
            {"Joensuu", "Utra"},
            {"Hämeenlinna", "Jukola"},
            {"Hämeenlinna", "Kauriala"},
            {"Pori", "Pormestarinluoto"},
            {"Pori", "Sampola"},
            {"Seinäjoki", "Hyllykallio"},
            {"Seinäjoki", "Jouppi"},
            {"Rovaniemi", "Korkalovaara"},
            {"Rovaniemi", "Ounasrinne"}
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    // Oma säie sähkön Vertailu-keskiarvoille: ensiavaus voi tehdä ~17 sarjallista
    // HTTP-kutsua, eikä se saa blokata pää-io:n sää-/uutis-/liikennehakuja.
    private final ExecutorService compareIo = Executors.newSingleThreadExecutor();
    private final TrafficNoticesRepository trafficRepository = new TrafficNoticesRepository();
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm:ss", FI);
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE d.M.yyyy", FI);
    private final SimpleDateFormat statusTimeFormat = new SimpleDateFormat("HH:mm", FI);
    private final SimpleDateFormat forecastDayFormat = new SimpleDateFormat("EEE d.M.", FI);

    private TextView toolbarTitle;
    private View toolbar;
    private View refreshButton;
    private View searchButton;
    private View locationButton;
    private View backToTopButton;
    private View drawer;
    private View drawerScrim;
    private View scroll;
    private View roadCamerasView;
    private View homeView;
    private LinearLayout widgetsContainer;
    private View forecastView;
    private View electricityView;
    private View sensorsView;
    private View trafficView;
    private View placesView;
    private View speedometerView;
    private GpsSpeedometerView gpsSpeedometerWidget;
    private GpsSpeedometerView gpsSpeedometerFull;
    private TextView gpsSpeedDigital;
    private TextView gpsSpeedSignal;
    private TextView gpsSpeedFullDigital;
    private TextView gpsSpeedFullSignal;
    private TextView gpsSpeedFullDetails;
    private Location lastGpsLocation;
    /** Viimeisin onnistuneesti haettu sää. Staattinen, joten se säilyy prosessin
     *  elinajan myös silloin kun aktiviteetti tuhotaan ("älä säilytä aktiviteetteja"),
     *  jolloin onRetainCustomNonConfigurationInstance ei säilytä tilaa. */
    private static WeatherData sLastWeather;
    private int gpsSatellitesUsed = 0;
    private int gpsSatellitesVisible = 0;
    private boolean gpsListenerActive = false;
    private GnssStatus.Callback gnssStatusCallback;
    private LocationListener gpsSpeedListener;
    private TextView clockText;
    private TextView dateText;
    private TextView nextHolidayText;
    private TextView placeText;
    private TextView weatherUpdatedText;
    private ImageButton weatherFavoriteButton;
    private WeatherIconView currentWeatherIcon;
    private TextView temperatureText;
    private TextView conditionText;
    private TextView weatherDetailsText;
    private LinearLayout weatherQuickStats;
    private LinearLayout hourlyForecastList;
    private View weatherCard;
    private View electricityCard;
    private TextView electricityText;
    private LinearLayout warningsCard;
    private LinearLayout warningsList;
    private View sensorsCard;
    private LinearLayout sensorsContainer;
    private TextView sensorsEmptyText;
    private View trafficCard;
    private TextView trafficWidgetStatusText;
    private LinearLayout trafficWidgetList;
    private TextView trafficTitleText;
    private TextView trafficStatusText;
    private LinearLayout trafficList;
    private View gpsSpeedCard;
    private TextView gpsSpeedText;
    private View newsCard;
    private TextView newsWidgetTitle;
    private LinearLayout newsWidgetList;
    private TextView newsWidgetStatus;
    private View newsView;
    private TextView newsViewStatus;
    private LinearLayout newsViewList;
    /** Per-lähde-uutissivun aktiivinen syöte; null = yhdistetty virta (kaikki lähteet). */
    private String newsViewFeedId = null;
    private List<NewsItem> newsItems = new ArrayList<>();
    private long newsFetchedAt = 0L;
    private boolean newsFetchInFlight = false;
    /** Per-lähde-uutiswidgettien kortit avaimella widget-id ("news:<feedId>").
     *  Kierrätetään ettei recreate/uudelleenjärjestely luo duplikaatteja. */
    private final Map<String, View> newsFeedCards = new HashMap<>();
    private final Map<String, LinearLayout> newsFeedCardLists = new HashMap<>();
    private final Map<String, TextView> newsFeedCardStatuses = new HashMap<>();
    private TextView statusText;
    private TextView cheapElectricityText;
    private TextView forecastStatusText;
    private LinearLayout forecastDays;
    private LinearLayout forecastList;
    private TextView electricitySummaryText;
    private TextView electricityTodayTab;
    private TextView electricityTomorrowTab;
    private TextView electricityCompareTab;
    private LinearLayout electricityQuarterList;
    private LinearLayout electricityCompareList;
    private boolean compareFetchInFlight = false;
    private LinearLayout sensorsList;
    private TextView placesStatusText;
    private TextView placeSearchStatusText;
    private EditText placeSearchInput;
    private LinearLayout placeSuggestionsList;
    private LinearLayout favoritePlacesList;
    private View placeAutoButton;
    private View placeLockButton;
    private View placeFavoriteCurrentButton;

    private WeatherData weather;
    private OpenMeteoData openMeteo;
    private ElectricityData electricity;
    private List<TrafficNotice> trafficNotices = new ArrayList<>();
    private MobileHolidayProvider.HolidayEvent nextHoliday;
    private ObjectAnimator refreshAnimator;
    private long refreshAnimationStartedAt;
    private int selectedForecastDayKey = 0;
    private int selectedElectricityDayOffset = 0;
    private boolean destroyed;
    private boolean refreshInFlight;
    private boolean trafficRefreshInFlight;
    private boolean tomorrowPriceFetchInFlight;
    private long lastTomorrowPriceFetchAttemptMs;
    private AlertDialog placeSearchDialog;
    private TrafficNotice.Kind selectedTrafficKind = TrafficNotice.Kind.ACCIDENT;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            renderClock();
            main.postDelayed(this, 1_000L);
        }
    };

    private final Runnable tomorrowPricePoll = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            if (ElectricityRepository.get(MobileMainActivity.this).hasTomorrow()) return;
            if (!isTomorrowPollWindowNow()) {
                scheduleTomorrowPricePolling();
                return;
            }
            fetchTomorrowPricesNow();
        }
    };

    private final Runnable autoRefreshTick = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            refreshAll(false);
        }
    };

    private final Runnable placeSearchDebounce = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            searchPlaces();
        }
    };

    private final WarningsRepository.Listener warningsListener = warnings -> {
        if (destroyed) return;
        main.post(() -> renderWarnings(warnings));
    };

    private final RuuviRepository.Listener ruuviListener = (mac, sample) -> {
        if (destroyed) return;
        main.post(this::renderSensors);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        MobileThemeController.apply(this);
        // Pakota tämän näkymän yötila sovelluksen omaan asetukseen (ei järjestelmän),
        // jottei vaalea sovellus vuoda Androidin tummaa tilaa recreate-/paluuhetkellä.
        getDelegate().setLocalNightMode(MobileThemeController.nightMode(this));
        super.onCreate(savedInstanceState);
        // Säilytä haetut tiedot teema-/orientaatio-recreate:n yli, jotta käyttäjä
        // ei näe tyhjää sää-/sähkö-tilaa kun palaa asetuksista.
        Object retained = getLastCustomNonConfigurationInstance();
        if (retained instanceof Object[]) {
            Object[] arr = (Object[]) retained;
            if (arr.length >= 1 && arr[0] instanceof WeatherData) weather = (WeatherData) arr[0];
            if (arr.length >= 2 && arr[1] instanceof OpenMeteoData) openMeteo = (OpenMeteoData) arr[1];
            if (arr.length >= 3 && arr[2] instanceof ElectricityData) electricity = (ElectricityData) arr[2];
            if (arr.length >= 4 && arr[3] instanceof List<?>) {
                @SuppressWarnings("unchecked")
                List<TrafficNotice> tn = (List<TrafficNotice>) arr[3];
                trafficNotices = tn;
            }
            if (arr.length >= 5 && arr[4] instanceof List<?>) {
                @SuppressWarnings("unchecked")
                List<NewsItem> ni = (List<NewsItem>) arr[4];
                newsItems = ni;
            }
            if (arr.length >= 6 && arr[5] instanceof Long) newsFetchedAt = (Long) arr[5];
        }
        // Jos retained-instanssia ei ollut (esim. "älä säilytä aktiviteetteja" tai
        // taustalla tapahtunut recreate), näytä silti viimeisin haettu sää heti
        // ilman "Säätietoja haetaan"-välitilaa.
        if (weather == null) {
            weather = sLastWeather;
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        clockFormat.setTimeZone(HELSINKI);
        dateFormat.setTimeZone(HELSINKI);
        statusTimeFormat.setTimeZone(HELSINKI);
        forecastDayFormat.setTimeZone(HELSINKI);
        setContentView(R.layout.activity_mobile_main);
        bindViews();
        applySystemBarInsets();
        bindActions();
        renderInitial();
        // Esilataa kelikamera-asemat taustalla (24 h levycache), jotta kartan avaus on
        // nopea ilman verkkoviivettä.
        WeathercamRepository.get().load(getApplicationContext(), false, (s, e) -> { });
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        return new Object[]{weather, openMeteo, electricity, trafficNotices,
                newsItems, newsFetchedAt};
    }

    @Override
    protected void onStart() {
        super.onStart();
        destroyed = false;
        main.post(clockTick);
        WarningsRepository.get().addListener(warningsListener);
        RuuviRepository.get(this).addListener(ruuviListener);
        RuuviRepository.get(this).start();
        WarningsRepository.get().refreshIfStale();
        refreshHolidayInfo();
        if (!maybeRequestInitialLocationPermission()) {
            maybeUpdatePlaceFromLocation();
        }
        if (shouldAutoRefreshNow()) {
            refreshAll(false);
        } else {
            // Lykkää render seuraavaan looper-sykliin, jotta AppCompatin yötila ehtii
            // vakiintua (vaalea sovellus + tumma järjestelmä) ennen kuin widgetit
            // renderöidään — muuten ne voivat hetkellisesti vuotaa järjestelmän tummaa.
            main.post(() -> {
                if (destroyed || isFinishing() || isDestroyed()) return;
                renderWeather();
                renderElectricity(electricity != null
                        ? electricity
                        : ElectricityRepository.get(this).peek());
                // Päivitä widgettien näkyvyys + järjestys asetuksista palatessa, jotta
                // juuri päälle laitettu (tai uudelleenjärjestetty) widget näkyy heti.
                renderHomeWidgetVisibility();
                refreshNewsAsync(false);
            });
        }
        scheduleTomorrowPricePolling();
        scheduleAutoRefresh();
        updateGpsListenerState();
    }

    private boolean shouldAutoRefreshNow() {
        if (weather == null) return true;
        long fetched = weather.fetchedAt;
        if (fetched <= 0L) return true;
        long age = System.currentTimeMillis() - fetched;
        return age >= autoRefreshIntervalMs();
    }

    @Override
    protected void onStop() {
        stopRefreshAnimation();
        RuuviRepository.get(this).removeListener(ruuviListener);
        RuuviRepository.get(this).stop();
        WarningsRepository.get().removeListener(warningsListener);
        main.removeCallbacks(clockTick);
        main.removeCallbacks(tomorrowPricePoll);
        main.removeCallbacks(autoRefreshTick);
        main.removeCallbacks(placeSearchDebounce);
        stopGpsSpeedListener();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (placeSearchDialog != null && placeSearchDialog.isShowing()) {
            try { placeSearchDialog.dismiss(); } catch (Exception ignored) { }
        }
        placeSearchDialog = null;
        io.shutdownNow();
        compareIo.shutdownNow();
        super.onDestroy();
    }

    private void bindViews() {
        toolbar = findViewById(R.id.mobile_toolbar);
        toolbarTitle = findViewById(R.id.mobile_toolbar_title);
        refreshButton = findViewById(R.id.mobile_refresh_button);
        searchButton = findViewById(R.id.mobile_search_button);
        locationButton = findViewById(R.id.mobile_location_button);
        backToTopButton = findViewById(R.id.mobile_back_to_top);
        drawer = findViewById(R.id.mobile_drawer);
        drawerScrim = findViewById(R.id.mobile_drawer_scrim);
        scroll = findViewById(R.id.mobile_scroll);
        roadCamerasView = findViewById(R.id.mobile_road_cameras_view);
        homeView = findViewById(R.id.mobile_home_view);
        forecastView = findViewById(R.id.mobile_forecast_view);
        electricityView = findViewById(R.id.mobile_electricity_view);
        sensorsView = findViewById(R.id.mobile_sensors_view);
        trafficView = findViewById(R.id.mobile_traffic_view);
        placesView = findViewById(R.id.mobile_places_view);
        speedometerView = findViewById(R.id.mobile_speedometer_view);
        gpsSpeedometerWidget = findViewById(R.id.mobile_gps_speedometer);
        gpsSpeedometerFull = findViewById(R.id.mobile_speedometer_full);
        gpsSpeedDigital = findViewById(R.id.mobile_gps_speed_digital);
        gpsSpeedSignal = findViewById(R.id.mobile_gps_speed_signal);
        gpsSpeedFullDigital = findViewById(R.id.mobile_speedometer_full_digital);
        gpsSpeedFullSignal = findViewById(R.id.mobile_speedometer_full_signal);
        gpsSpeedFullDetails = findViewById(R.id.mobile_speedometer_full_details);
        clockText = findViewById(R.id.mobile_clock);
        dateText = findViewById(R.id.mobile_date);
        nextHolidayText = findViewById(R.id.mobile_next_holiday);
        placeText = findViewById(R.id.mobile_place);
        placeText.setSingleLine(false);
        placeText.setMaxLines(2);
        placeText.setHorizontallyScrolling(false);
        weatherUpdatedText = findViewById(R.id.mobile_weather_updated);
        weatherFavoriteButton = findViewById(R.id.mobile_weather_favorite_button);
        currentWeatherIcon = findViewById(R.id.mobile_weather_icon);
        temperatureText = findViewById(R.id.mobile_temperature);
        conditionText = findViewById(R.id.mobile_condition);
        weatherDetailsText = findViewById(R.id.mobile_weather_details);
        weatherQuickStats = findViewById(R.id.mobile_weather_quick_stats);
        hourlyForecastList = findViewById(R.id.mobile_hourly_forecast);
        weatherCard = findViewById(R.id.mobile_weather_card);
        electricityCard = findViewById(R.id.mobile_electricity_card);
        electricityText = findViewById(R.id.mobile_electricity);
        warningsCard = findViewById(R.id.mobile_warnings_card);
        warningsList = findViewById(R.id.mobile_warnings);
        sensorsCard = findViewById(R.id.mobile_sensors_card);
        sensorsContainer = findViewById(R.id.mobile_sensors_container);
        sensorsEmptyText = findViewById(R.id.mobile_sensors_empty);
        trafficCard = findViewById(R.id.mobile_traffic_card);
        trafficWidgetStatusText = findViewById(R.id.mobile_traffic_widget_status);
        trafficWidgetList = findViewById(R.id.mobile_traffic_widget_list);
        trafficTitleText = findViewById(R.id.mobile_traffic_title);
        trafficStatusText = findViewById(R.id.mobile_traffic_status);
        trafficList = findViewById(R.id.mobile_traffic_list);
        gpsSpeedCard = findViewById(R.id.mobile_gps_speed_card);
        gpsSpeedText = findViewById(R.id.mobile_gps_speed);
        newsCard = findViewById(R.id.mobile_news_card);
        newsWidgetTitle = findViewById(R.id.mobile_news_widget_title);
        if (newsWidgetTitle != null) {
            newsWidgetTitle.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                showNews();
            });
        }
        newsWidgetList = findViewById(R.id.mobile_news_widget_list);
        newsWidgetStatus = findViewById(R.id.mobile_news_widget_status);
        newsView = findViewById(R.id.mobile_news_view);
        newsViewStatus = findViewById(R.id.mobile_news_view_status);
        newsViewList = findViewById(R.id.mobile_news_view_list);
        statusText = findViewById(R.id.mobile_status);
        cheapElectricityText = findViewById(R.id.mobile_cheap_electricity);
        forecastStatusText = findViewById(R.id.mobile_forecast_status);
        forecastDays = findViewById(R.id.mobile_forecast_days);
        forecastList = findViewById(R.id.mobile_forecast_list);
        electricitySummaryText = findViewById(R.id.mobile_electricity_summary);
        electricityTodayTab = findViewById(R.id.mobile_electricity_tab_today);
        electricityTomorrowTab = findViewById(R.id.mobile_electricity_tab_tomorrow);
        electricityCompareTab = findViewById(R.id.mobile_electricity_tab_compare);
        electricityQuarterList = findViewById(R.id.mobile_electricity_quarter_list);
        electricityCompareList = findViewById(R.id.mobile_electricity_compare_list);
        sensorsList = findViewById(R.id.mobile_sensors_list);
        placesStatusText = findViewById(R.id.mobile_places_status);
        favoritePlacesList = findViewById(R.id.mobile_favorite_places);
        placeAutoButton = findViewById(R.id.mobile_place_auto_button);
        placeLockButton = findViewById(R.id.mobile_place_lock_button);
        placeFavoriteCurrentButton = findViewById(R.id.mobile_place_favorite_current_button);
        ensureWidgetContainer();
    }

    private void ensureWidgetContainer() {
        if (!(homeView instanceof LinearLayout) || widgetsContainer != null) return;
        LinearLayout home = (LinearLayout) homeView;
        widgetsContainer = new LinearLayout(this);
        widgetsContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams containerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        int insertAt = home.indexOfChild(weatherCard);
        if (insertAt < 0) insertAt = home.indexOfChild(nextHolidayText) + 1;
        if (insertAt < 0) insertAt = home.getChildCount();
        home.addView(widgetsContainer, Math.min(insertAt, home.getChildCount()), containerLp);

        String[] defaults = MobileThemeController.DEFAULT_WIDGET_ORDER.split(",");
        for (String id : defaults) {
            View widget = homeWidgetView(id.trim());
            if (widget == null) continue;
            if (widget.getParent() instanceof ViewGroup) {
                ((ViewGroup) widget.getParent()).removeView(widget);
            }
            widgetsContainer.addView(widget);
        }
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.mobile_root);
        final int toolbarBaseHeight = toolbar.getLayoutParams().height;
        final int toolbarPaddingLeft = toolbar.getPaddingLeft();
        final int toolbarPaddingRight = toolbar.getPaddingRight();
        final int toolbarPaddingBottom = toolbar.getPaddingBottom();
        final int drawerPaddingLeft = drawer.getPaddingLeft();
        final int drawerPaddingTop = drawer.getPaddingTop();
        final int drawerPaddingRight = drawer.getPaddingRight();
        final int drawerPaddingBottom = drawer.getPaddingBottom();
        final int scrollPaddingLeft = scroll.getPaddingLeft();
        final int scrollPaddingTop = scroll.getPaddingTop();
        final int scrollPaddingRight = scroll.getPaddingRight();
        final int scrollPaddingBottom = scroll.getPaddingBottom();
        final ViewGroup.MarginLayoutParams backToTopLp =
                (ViewGroup.MarginLayoutParams) backToTopButton.getLayoutParams();
        final int backToTopBottomMargin = backToTopLp.bottomMargin;

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(toolbarPaddingLeft, bars.top,
                    toolbarPaddingRight, toolbarPaddingBottom);
            ViewGroup.LayoutParams toolbarLp = toolbar.getLayoutParams();
            toolbarLp.height = toolbarBaseHeight + bars.top;
            toolbar.setLayoutParams(toolbarLp);

            drawer.setPadding(drawerPaddingLeft, drawerPaddingTop + bars.top,
                    drawerPaddingRight, drawerPaddingBottom + bars.bottom);
            scroll.setPadding(scrollPaddingLeft, scrollPaddingTop,
                    scrollPaddingRight, scrollPaddingBottom + bars.bottom);
            ViewGroup.MarginLayoutParams fabLp =
                    (ViewGroup.MarginLayoutParams) backToTopButton.getLayoutParams();
            fabLp.bottomMargin = backToTopBottomMargin + bars.bottom;
            backToTopButton.setLayoutParams(fabLp);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void bindActions() {
        findViewById(R.id.mobile_menu_button).setOnClickListener(v -> toggleDrawer());
        findViewById(R.id.mobile_drawer_close).setOnClickListener(v -> closeDrawer());
        drawerScrim.setOnClickListener(v -> closeDrawer());
        refreshButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            refreshAll(true);
        });
        toolbarTitle.setOnClickListener(v -> showHome());
        searchButton.setOnClickListener(v -> showPlaceSearchDialog());
        locationButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            locateCurrentPlaceFromToolbar();
        });
        weatherFavoriteButton.setOnClickListener(v -> toggleCurrentFavorite());
        electricityCard.setOnClickListener(v -> openElectricitySection(0));
        backToTopButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            smoothScrollToTop();
        });
        drawer.setOnClickListener(v -> {
            // Eat taps inside the menu panel.
        });

        findViewById(R.id.mobile_nav_forecast).setOnClickListener(v -> {
            closeDrawer();
            showSection(forecastView, getString(R.string.mobile_menu_forecast));
            renderForecastView();
        });
        findViewById(R.id.mobile_nav_electricity).setOnClickListener(v -> {
            closeDrawer();
            openElectricitySection(0);
        });
        electricityTodayTab.setOnClickListener(v -> {
            selectedElectricityDayOffset = 0;
            renderElectricityView();
        });
        electricityTomorrowTab.setOnClickListener(v -> {
            selectedElectricityDayOffset = 1;
            renderElectricityView();
        });
        electricityCompareTab.setOnClickListener(v -> {
            selectedElectricityDayOffset = 2;
            renderElectricityView();
        });
        findViewById(R.id.mobile_nav_sensors).setOnClickListener(v -> {
            closeDrawer();
            showSection(sensorsView, getString(R.string.mobile_menu_sensors));
            renderSensorsView();
        });
        findViewById(R.id.mobile_nav_traffic_accidents).setOnClickListener(v -> {
            closeDrawer();
            showTrafficSection(TrafficNotice.Kind.ACCIDENT);
        });
        findViewById(R.id.mobile_nav_traffic_roadworks).setOnClickListener(v -> {
            closeDrawer();
            showTrafficSection(TrafficNotice.Kind.ROAD_WORK);
        });
        findViewById(R.id.mobile_nav_traffic_weight).setOnClickListener(v -> {
            closeDrawer();
            showTrafficSection(TrafficNotice.Kind.WEIGHT_RESTRICTION);
        });
        findViewById(R.id.mobile_nav_traffic_incidents).setOnClickListener(v -> {
            closeDrawer();
            showTrafficSection(TrafficNotice.Kind.INCIDENT);
        });
        findViewById(R.id.mobile_nav_traffic_congestion).setOnClickListener(v -> {
            closeDrawer();
            showTrafficSection(TrafficNotice.Kind.CONGESTION);
        });
        findViewById(R.id.mobile_nav_road_cameras).setOnClickListener(v -> {
            closeDrawer();
            showRoadCameras();
        });
        findViewById(R.id.mobile_nav_news).setOnClickListener(v -> {
            closeDrawer();
            showNews();
        });
        findViewById(R.id.mobile_nav_speedometer).setOnClickListener(v -> {
            closeDrawer();
            showSpeedometer();
        });
        findViewById(R.id.mobile_nav_history).setOnClickListener(v -> {
            closeDrawer();
            startActivity(new Intent(this, MobileHistoryActivity.class));
        });
        findViewById(R.id.mobile_nav_places).setOnClickListener(v -> {
            closeDrawer();
            showSection(placesView, getString(R.string.mobile_menu_places));
            renderPlacesView();
        });
        findViewById(R.id.mobile_nav_settings).setOnClickListener(v -> {
            closeDrawer();
            startActivity(new Intent(this, MobileSettingsActivity.class));
        });
        findViewById(R.id.mobile_nav_system).setOnClickListener(v -> {
            closeDrawer();
            startActivity(new Intent(this, MobileSystemActivity.class));
        });
        placeAutoButton.setOnClickListener(v -> {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, true)
                    .apply();
            maybeUpdatePlaceFromLocation();
            renderPlacesView();
        });
        placeLockButton.setOnClickListener(v -> {
            SettingsManager.get().clearHomeCoordinates();
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, false)
                    .remove(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME)
                    .apply();
            placeText.setText(currentDisplayPlace());
            renderPlacesView();
        });
        placeFavoriteCurrentButton.setOnClickListener(v -> toggleCurrentFavorite());
    }

    private void renderInitial() {
        showHome();
        renderClock();
        placeText.setText(currentDisplayPlace());
        updateFavoriteButton();
        // Jos recreate (esim. teemavaihto asetuksista) säilytti edellisen säädatan,
        // näytä se heti ilman "Säätietoja haetaan"-välitilaa.
        if (weather != null && weather.current != null) {
            renderWeather();
        } else {
            weatherUpdatedText.setText("");
            currentWeatherIcon.setCondition(null);
            temperatureText.setText("-- C");
            conditionText.setText("Säätietoja haetaan");
            weatherDetailsText.setText("Tuuli -- m/s\nKosteus -- %\nSade 1h -- mm");
            renderHomeHourlyForecast();
        }
        if (electricity != null) {
            renderElectricity(electricity);
        } else {
            electricityText.setText("Hintaa haetaan");
        }
        renderWarnings(WarningsRepository.get().getLatest());
        renderSensors();
        renderForecastView();
        renderElectricityView();
        renderSensorsView();
        renderTrafficWidget();
        renderTrafficView();
        renderPlacesView();
        renderHomeWidgetVisibility();
        renderNewsWidget();
        statusText.setText("Valmis");
    }

    private void renderClock() {
        Date now = new Date();
        clockText.setText(clockFormat.format(now));
        String date = dateFormat.format(now);
        if (!date.isEmpty()) {
            date = Character.toUpperCase(date.charAt(0)) + date.substring(1);
        }
        Calendar calendar = Calendar.getInstance(HELSINKI, FI);
        calendar.setTime(now);
        date += " · viikko " + calendar.get(Calendar.WEEK_OF_YEAR);
        dateText.setText(date);
        nextHolidayText.setText(nextHolidayLine(calendar));
    }

    private void refreshAll(boolean forcedByUser) {
        if (refreshInFlight) {
            if (forcedByUser) Toast.makeText(this, "Päivitys on jo käynnissä", Toast.LENGTH_SHORT).show();
            else scheduleAutoRefresh();
            return;
        }
        refreshInFlight = true;
        main.removeCallbacks(autoRefreshTick);
        if (forcedByUser) startRefreshAnimation();
        statusText.setText("Päivitetään");
        io.execute(() -> {
            WeatherData freshWeather = null;
            OpenMeteoData freshOpenMeteo = null;
            ElectricityData freshElectricity = null;
            MobileHolidayProvider.HolidayEvent freshHoliday = null;
            Exception failure = null;
            String place = currentDisplayPlace();
            try {
                freshWeather = WeatherRepository.get(this).fetchHome(weather, forcedByUser);
            } catch (Exception e) {
                failure = e;
            }
            try {
                freshOpenMeteo = OpenMeteoRepository.get(this).fetch(place, forcedByUser);
            } catch (Exception ignored) {
                freshOpenMeteo = OpenMeteoRepository.get(this).peek(place);
            }
            try {
                freshElectricity = forcedByUser
                        ? ElectricityRepository.get(this).fetchNow()
                        : ElectricityRepository.get(this).fetchIfStale();
            } catch (Exception e) {
                if (failure == null) failure = e;
            }
            try {
                Calendar now = Calendar.getInstance(HELSINKI, FI);
                freshHoliday = MobileHolidayProvider.next(now);
            } catch (Exception e) {
                if (failure == null) failure = e;
            }
            WeatherData finalWeather = freshWeather;
            OpenMeteoData finalOpenMeteo = freshOpenMeteo;
            ElectricityData finalElectricity = freshElectricity;
            MobileHolidayProvider.HolidayEvent finalHoliday = freshHoliday;
            Exception finalFailure = failure;
            boolean finalForcedByUser = forcedByUser;
            main.post(() -> {
                refreshInFlight = false;
                stopRefreshAnimation();
                if (destroyed || isFinishing() || isDestroyed()) return;
                if (finalWeather != null) {
                    weather = finalWeather;
                    sLastWeather = finalWeather;
                    SettingsManager.get().setLastSuccessfulFmiUpdate(
                            finalWeather.fetchedAt > 0 ? finalWeather.fetchedAt : System.currentTimeMillis());
                }
                if (finalOpenMeteo != null) openMeteo = finalOpenMeteo;
                if (finalElectricity != null) electricity = finalElectricity;
                if (finalHoliday != null) nextHoliday = finalHoliday;
                renderClock();
                renderWeather();
                renderElectricity(electricity != null
                        ? electricity
                        : ElectricityRepository.get(this).peek());
                renderSensors();
                renderForecastView();
                renderElectricityView();
                renderSensorsView();
                renderPlacesView();
                renderHomeWidgetVisibility();
                if (finalFailure != null) {
                    statusText.setText("Päivitys epäonnistui: " + safeMessage(finalFailure));
                } else {
                    statusText.setText("Päivitetty " + statusTimeFormat.format(new Date()));
                }
                scheduleAutoRefresh();
                refreshTrafficAsync(finalForcedByUser);
                refreshNewsAsync(finalForcedByUser);
            });
        });
    }

    private void refreshTrafficAsync(boolean forced) {
        io.execute(() -> {
            try {
                double[] ref = trafficReferenceCoordinates();
                if (ref == null) return;
                List<TrafficNotice> fresh = trafficRepository.fetchNearby(
                        ref[0], ref[1], TrafficNotice.Kind.ALL, forced);
                main.post(() -> {
                    if (destroyed || isFinishing() || isDestroyed()) return;
                    trafficNotices = fresh;
                    renderTrafficWidget();
                    renderTrafficView();
                });
            } catch (Exception ignored) {}
        });
    }

    private void refreshNewsAsync(boolean forced) {
        if (newsFetchInFlight) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean widgetWanted = prefs.getBoolean(MobileThemeController.KEY_SHOW_NEWS_WIDGET, true);
        boolean viewVisible = newsView != null && newsView.getVisibility() == View.VISIBLE;
        // Huomioi myös per-lähde-uutiswidgetit: muuten refresh ei hae uutisia kun
        // yhdistetty "Uutiset"-widget on pois päältä eikä uutisten kokosivu ole auki.
        boolean anyFeedWidget = anyNewsFeedWidgetEnabled(prefs);
        if (!widgetWanted && !viewVisible && !anyFeedWidget) return;
        newsFetchInFlight = true;
        io.execute(() -> {
            List<NewsItem> fresh;
            try {
                fresh = RssRepository.get().fetchEnabled(prefs, forced);
            } catch (Exception e) {
                fresh = null;
            }
            List<NewsItem> finalFresh = fresh;
            main.post(() -> {
                newsFetchInFlight = false;
                if (destroyed || isFinishing() || isDestroyed()) return;
                if (finalFresh != null) {
                    newsItems = finalFresh;
                    newsFetchedAt = System.currentTimeMillis();
                }
                renderNewsWidget();
                renderNewsFeedCards();
                renderNewsView();
            });
        });
    }

    private void renderNewsWidget() {
        if (newsCard == null || newsWidgetList == null || newsWidgetStatus == null) return;
        if (newsCard.getVisibility() != View.VISIBLE) return;
        newsWidgetList.removeAllViews();
        if (newsItems == null || newsItems.isEmpty()) {
            newsWidgetStatus.setText(newsFetchInFlight ? "Haetaan uutisia..." : "Ei uutisia");
            return;
        }
        int max = Math.min(5, newsItems.size());
        for (int i = 0; i < max; i++) {
            newsWidgetList.addView(newsRow(newsItems.get(i), false));
        }
        newsWidgetStatus.setText(newsFetchedAt > 0
                ? "Päivitetty " + ageText(newsFetchedAt)
                : "");
    }

    private void renderNewsView() {
        if (newsView == null || newsViewList == null || newsViewStatus == null) return;
        if (newsView.getVisibility() != View.VISIBLE) return;
        newsViewList.removeAllViews();

        // Per-lähde-sivu: näytä vain valitun syötteen 50 uusinta (avataan widgetin otsikosta).
        if (newsViewFeedId != null) {
            SharedPreferences feedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
            NewsFeed feed = NewsFeedStore.feedById(feedPrefs, newsViewFeedId);
            String feedName = feed != null ? feed.name : "Uutiset";
            List<NewsItem> feedItems = RssRepository.get().peekForFeed(newsViewFeedId);
            if (feedItems.isEmpty()) {
                newsViewStatus.setText(newsFetchInFlight
                        ? feedName + " · Haetaan uutisia..."
                        : feedName + " · Ei uutisia. Tarkista syötteen osoite asetuksista.");
                return;
            }
            int feedMax = Math.min(50, feedItems.size());
            String feedNote = feedMax < feedItems.size()
                    ? feedMax + " uusinta uutista"
                    : feedItems.size() + " uutista";
            newsViewStatus.setText(feedName + " · Päivitetty " + ageText(newsFetchedAt)
                    + " · " + feedNote);
            for (int i = 0; i < feedMax; i++) {
                newsViewList.addView(newsRow(feedItems.get(i), true));
            }
            return;
        }

        if (newsItems == null || newsItems.isEmpty()) {
            newsViewStatus.setText(newsFetchInFlight
                    ? "Haetaan uutisia..."
                    : "Ei uutisia. Tarkista uutislähteet asetuksista.");
            return;
        }
        int max = Math.min(50, newsItems.size());
        String shownNote = max < newsItems.size()
                ? max + " uusinta uutista"
                : newsItems.size() + " uutista";
        newsViewStatus.setText("Päivitetty " + ageText(newsFetchedAt) + " · " + shownNote);
        for (int i = 0; i < max; i++) {
            newsViewList.addView(newsRow(newsItems.get(i), true));
        }
    }

    private View newsRow(NewsItem item, boolean fullSize) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setGravity(Gravity.CENTER_VERTICAL);
        outer.setBackgroundResource(R.drawable.mobile_warning_item_bg);
        int pad = dp(10);
        outer.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        outer.setLayoutParams(lp);
        outer.setClickable(true);
        outer.setFocusable(true);
        outer.setOnClickListener(v -> openUrlInCustomTab(item.link));

        int thumbPx = dp(fullSize ? 64 : 52);
        ImageView thumb = new ImageView(this);
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(thumbPx, thumbPx);
        thumbLp.setMarginEnd(dp(10));
        thumb.setLayoutParams(thumbLp);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setImageResource(R.drawable.mobile_ic_news_placeholder);
        ImageLoader.get().load(item.imageUrl, thumb, R.drawable.mobile_ic_news_placeholder);
        outer.addView(thumb);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = rowText(item.title, fullSize ? 16 : 14, true);
        title.setMaxLines(fullSize ? 3 : 2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(title);

        TextView meta = rowText(metaLine(item), 12, false);
        meta.setTextColor(getColor(R.color.mobile_text_muted));
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(3);
        meta.setLayoutParams(metaLp);
        textCol.addView(meta);

        outer.addView(textCol);
        return outer;
    }

    private String metaLine(NewsItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append(item.feedName);
        if (item.pubTimeMs > 0) {
            sb.append(" · ").append(ageText(item.pubTimeMs));
        }
        return sb.toString();
    }

    private void openUrlInCustomTab(String url) {
        if (url == null || url.trim().isEmpty()) return;
        try {
            CustomTabsIntent intent = new CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build();
            intent.launchUrl(this, Uri.parse(url));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                Toast.makeText(this, "Linkkiä ei voitu avata", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void renderWeather() {
        if (weather == null || weather.current == null) return;
        WeatherData.Current c = weather.current;
        String place = currentDisplayPlace();
        placeText.setText(place);
        updateFavoriteButton();
        weatherUpdatedText.setText(weather.fetchedAt > 0
                ? "Päivitetty klo " + statusTimeFormat.format(new Date(weather.fetchedAt))
                : "");
        currentWeatherIcon.setCondition(c.condition);
        temperatureText.setText(formatTemp(c.temperature));
        conditionText.setText(WeatherTextFormatter.label(this, c.condition));

        renderWeatherQuickStats(c);

        List<String> lines = new ArrayList<>();
        if (!Double.isNaN(c.windGust)) lines.add("Puuska " + one(c.windGust) + " m/s");
        if (!Double.isNaN(c.humidity)) lines.add("Kosteus " + Math.round(c.humidity) + " %");
        if (!Double.isNaN(c.cloudCover)) lines.add("Pilvisyys " + Math.round(c.cloudCover) + " %");
        if (c.timestamp > 0) {
            lines.add("FMI-havainto klo " + statusTimeFormat.format(new Date(c.timestamp)));
        }
        weatherDetailsText.setText(joinLines(lines));
        weatherDetailsText.setVisibility(lines.isEmpty() ? View.GONE : View.VISIBLE);
        renderHomeHourlyForecast();
    }

    private void renderWeatherQuickStats(WeatherData.Current c) {
        if (weatherQuickStats == null) return;
        weatherQuickStats.removeAllViews();

        // Jos havaintoasema ei mittaa sadetta/tuulta (NaN), fallbackataan
        // FMI-ennustemallin nykyhetken tuntiarvoon.
        WeatherData.Hour nowForecast = nearestForecastHour(System.currentTimeMillis());
        double wind = !Double.isNaN(c.windSpeed) ? c.windSpeed
                : (nowForecast != null ? nowForecast.windSpeed : Double.NaN);
        double rain = !Double.isNaN(c.precip1h) ? c.precip1h
                : (nowForecast != null ? nowForecast.precipitation : Double.NaN);

        weatherQuickStats.addView(quickStatTile(
                R.drawable.mobile_ic_thermometer_24, "Tuntuu kuin",
                Double.isNaN(c.feelsLike) ? "--" : formatTemp(c.feelsLike)));
        weatherQuickStats.addView(quickStatTile(
                R.drawable.mobile_ic_wind_24, "Tuuli",
                Double.isNaN(wind) ? "-- m/s" : one(wind) + " m/s"));
        weatherQuickStats.addView(quickStatTile(
                R.drawable.mobile_ic_rain_24, "Sade 1h",
                Double.isNaN(rain) ? "-- mm" : one(rain) + " mm"));
        weatherQuickStats.setVisibility(View.VISIBLE);
    }

    private WeatherData.Hour nearestForecastHour(long timestampMs) {
        if (weather == null || weather.hours == null || weather.hours.isEmpty()) return null;
        WeatherData.Hour closest = null;
        long bestDelta = Long.MAX_VALUE;
        for (WeatherData.Hour hour : weather.hours) {
            long delta = Math.abs(hour.timestamp - timestampMs);
            if (delta < bestDelta) {
                bestDelta = delta;
                closest = hour;
            }
        }
        // Älä käytä tuntia joka on kauempana kuin 90 min nykyhetkestä
        return bestDelta <= 90L * 60_000L ? closest : null;
    }

    private View quickStatTile(int iconRes, String label, String value) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(dp(4));
        lp.setMarginEnd(dp(4));
        col.setLayoutParams(lp);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(getColor(R.color.mobile_accent));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(26), dp(26));
        col.addView(icon, iconLp);

        TextView v = rowText(value, 16, true);
        v.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vLp.topMargin = dp(2);
        col.addView(v, vLp);

        TextView l = rowText(label, 11, false);
        l.setGravity(Gravity.CENTER);
        l.setTextColor(getColor(R.color.mobile_text_muted));
        col.addView(l, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return col;
    }

    private void renderHomeHourlyForecast() {
        if (hourlyForecastList == null) return;
        hourlyForecastList.removeAllViews();
        if (weather == null || weather.hours == null || weather.hours.isEmpty()) {
            hourlyForecastList.addView(hourlyForecastEmptyChip("Ei ennustetta"));
            return;
        }
        long now = System.currentTimeMillis();
        Calendar end = Calendar.getInstance(HELSINKI, FI);
        end.setTimeInMillis(now);
        end.add(Calendar.DAY_OF_YEAR, 1);
        end.set(Calendar.HOUR_OF_DAY, 0);
        end.set(Calendar.MINUTE, 0);
        end.set(Calendar.SECOND, 0);
        end.set(Calendar.MILLISECOND, 0);

        int count = 0;
        for (WeatherData.Hour hour : weather.hours) {
            if (hour.timestamp < now - 30L * 60_000L || hour.timestamp >= end.getTimeInMillis()) {
                continue;
            }
            hourlyForecastList.addView(hourlyForecastChip(hour));
            count++;
        }
        if (count == 0) {
            hourlyForecastList.addView(hourlyForecastEmptyChip("Loppupäivälle ei rivejä"));
        }
    }

    private View hourlyForecastChip(WeatherData.Hour hour) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER_HORIZONTAL);
        chip.setBackgroundResource(R.drawable.mobile_warning_item_bg);
        chip.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(92), dp(176));
        lp.setMargins(0, 0, dp(8), 0);
        chip.setLayoutParams(lp);

        TextView time = rowText(statusTimeFormat.format(new Date(hour.timestamp)), 13, true);
        time.setGravity(Gravity.CENTER);
        chip.addView(time, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        WeatherIconView icon = new WeatherIconView(this);
        icon.setCondition(hour.condition);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        iconLp.setMargins(0, dp(5), 0, dp(3));
        chip.addView(icon, iconLp);

        TextView temp = rowText(formatTemp(hour.temperature), 15, true);
        temp.setGravity(Gravity.CENTER);
        chip.addView(temp, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        chip.addView(chipIconRow(R.drawable.mobile_ic_rain_24,
                Double.isNaN(hour.precipitation) ? "--" : one(hour.precipitation) + " mm"));
        chip.addView(chipIconRow(R.drawable.mobile_ic_wind_24,
                Double.isNaN(hour.windSpeed) ? "--" : one(hour.windSpeed) + " m/s"));
        return chip;
    }

    private View chipIconRow(int iconRes, String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(3);
        row.setLayoutParams(lp);

        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        ic.setColorFilter(getColor(R.color.mobile_text_secondary));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        iconLp.setMarginEnd(dp(4));
        row.addView(ic, iconLp);

        TextView tv = rowText(text, 12, false);
        tv.setTextColor(getColor(R.color.mobile_text_secondary));
        row.addView(tv);
        return row;
    }

    private View hourlyForecastEmptyChip(String text) {
        TextView tv = rowText(text, 13, false);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundResource(R.drawable.mobile_warning_item_bg);
        tv.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(180), dp(64));
        lp.setMargins(0, 0, dp(8), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void renderElectricity(ElectricityData data) {
        boolean showWidget = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(MobileThemeController.KEY_SHOW_ELECTRICITY_WIDGET, true);
        electricityCard.setVisibility(showWidget ? View.VISIBLE : View.GONE);
        if (data != null) electricity = data;
        ElectricityData.Quarter q = ElectricityRepository.get(this).currentQuarter();
        if (q == null && data != null) q = currentQuarterFrom(data);
        if (q == null) {
            electricityText.setText("Nykyistä varttihintaa ei ole vielä saatavilla");
            cheapElectricityText.setVisibility(View.GONE);
            return;
        }
        electricityText.setText(String.format(FI, "Nyt klo %02d:%02d  %.3f c/kWh",
                q.hour, q.minute, q.sntPerKwh));
        renderCheapElectricityNotice(data);
    }

    private void renderCheapElectricityNotice(ElectricityData data) {
        if (data == null) data = electricity;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (!prefs.getBoolean(MobileThemeController.KEY_CHEAP_ELECTRICITY_NOTICE, true)) {
            cheapElectricityText.setVisibility(View.GONE);
            return;
        }
        double threshold = cheapElectricityThreshold(prefs);
        String mode = prefs.getString(MobileThemeController.KEY_CHEAP_ELECTRICITY_MODE,
                MobileThemeController.CHEAP_MODE_ALL_DAY);
        Calendar c = Calendar.getInstance(HELSINKI, FI);
        List<ElectricityData.Quarter> today = ElectricityRepository.get(this).dayQuarters(
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        List<ElectricityData.Quarter> checked = cheapElectricityQuarters(today, mode);
        if (checked.isEmpty() || !allBelow(checked, threshold)) {
            cheapElectricityText.setVisibility(View.GONE);
            return;
        }
        ElectricityData.Quarter max = maxQuarter(checked);
        cheapElectricityText.setVisibility(View.VISIBLE);
        String scope = cheapElectricityScopeText(mode);
        cheapElectricityText.setText(max == null
                ? String.format(FI, "Sähkö on halpaa %s", scope)
                : String.format(FI, "Sähkö on halpaa %s: kaikki alle %.3f c/kWh, korkein %.3f c/kWh",
                scope, threshold, max.sntPerKwh));
    }

    private static double cheapElectricityThreshold(SharedPreferences prefs) {
        String raw = prefs.getString(MobileThemeController.KEY_CHEAP_ELECTRICITY_THRESHOLD,
                MobileThemeController.DEFAULT_CHEAP_ELECTRICITY_THRESHOLD);
        if (raw == null) return 5.0;
        try {
            return Math.max(0.0, Double.parseDouble(raw.trim().replace(',', '.')));
        } catch (NumberFormatException e) {
            return 5.0;
        }
    }

    private List<ElectricityData.Quarter> cheapElectricityQuarters(
            List<ElectricityData.Quarter> today, String mode) {
        List<ElectricityData.Quarter> out = new ArrayList<>();
        if (today == null) return out;
        long now = System.currentTimeMillis();
        for (ElectricityData.Quarter q : today) {
            if (MobileThemeController.CHEAP_MODE_CURRENT.equals(mode)) {
                if (q.timestamp <= now && q.timestamp + 15L * 60_000L > now) out.add(q);
            } else if (MobileThemeController.CHEAP_MODE_REMAINING_DAY.equals(mode)) {
                if (q.timestamp + 15L * 60_000L > now) out.add(q);
            } else {
                out.add(q);
            }
        }
        return out;
    }

    private static String cheapElectricityScopeText(String mode) {
        if (MobileThemeController.CHEAP_MODE_CURRENT.equals(mode)) return "nyt";
        if (MobileThemeController.CHEAP_MODE_REMAINING_DAY.equals(mode)) return "loppupäivän";
        return "koko päivän";
    }

    private void renderHomeWidgetVisibility() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        trafficCard.setVisibility(prefs.getBoolean(
                MobileThemeController.KEY_SHOW_TRAFFIC_WIDGET, true) ? View.VISIBLE : View.GONE);
        gpsSpeedCard.setVisibility(prefs.getBoolean(
                MobileThemeController.KEY_SHOW_GPS_SPEED_WIDGET, false) ? View.VISIBLE : View.GONE);
        if (newsCard != null) {
            newsCard.setVisibility(prefs.getBoolean(
                    MobileThemeController.KEY_SHOW_NEWS_WIDGET, true) ? View.VISIBLE : View.GONE);
        }
        applyNewsFeedWidgetVisibility(prefs);
        applyHomeWidgetOrder();
        renderTrafficWidget();
        renderGpsSpeed();
        renderNewsWidget();
        renderNewsFeedCards();
        updateGpsListenerState();
    }

    /** Asettaa kunkin per-lähde-uutiswidgetin näkyvyyden asetusten mukaan.
     *  Kortit luodaan vain kun ne ovat päällä, ja piilotetut merkitään GONE. */
    private void applyNewsFeedWidgetVisibility(SharedPreferences prefs) {
        for (NewsFeed feed : NewsFeedStore.allFeeds(prefs)) {
            String widgetId = feed.widgetId();
            boolean show = prefs.getBoolean(
                    MobileThemeController.newsFeedVisibilityKey(feed.id), false);
            if (show) {
                View card = newsFeedCard(widgetId); // luo tarvittaessa
                card.setVisibility(View.VISIBLE);
            } else {
                View card = newsFeedCards.get(widgetId);
                if (card != null) card.setVisibility(View.GONE);
            }
        }
    }

    private void applyHomeWidgetOrder() {
        if (widgetsContainer == null) ensureWidgetContainer();
        if (widgetsContainer == null) return;
        List<View> widgets = new ArrayList<>();
        for (String id : homeWidgetOrder()) {
            View widget = homeWidgetView(id);
            if (widget != null && !widgets.contains(widget)) {
                if (widget.getParent() instanceof ViewGroup) {
                    ((ViewGroup) widget.getParent()).removeView(widget);
                }
                widgets.add(widget);
            }
        }
        for (View widget : widgets) {
            widgetsContainer.addView(widget);
        }
    }

    private List<String> homeWidgetOrder() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String raw = prefs.getString(MobileThemeController.KEY_WIDGET_ORDER,
                MobileThemeController.DEFAULT_WIDGET_ORDER);
        List<String> order = new ArrayList<>();
        if (raw != null) {
            for (String token : raw.split(",")) {
                String id = token.trim();
                if (homeWidgetView(id) != null && !order.contains(id)) {
                    order.add(id);
                }
            }
        }
        addMissingHomeWidget(order, MobileThemeController.WIDGET_WEATHER);
        addMissingHomeWidget(order, MobileThemeController.WIDGET_ELECTRICITY);
        addMissingHomeWidget(order, MobileThemeController.WIDGET_WARNINGS);
        addMissingHomeWidget(order, MobileThemeController.WIDGET_SENSORS);
        addMissingHomeWidget(order, MobileThemeController.WIDGET_TRAFFIC);
        addMissingHomeWidget(order, MobileThemeController.WIDGET_GPS_SPEED);
        // Lisää päällä olevat per-lähde-uutiswidgetit jotka eivät vielä ole listassa.
        for (NewsFeed feed : NewsFeedStore.allFeeds(prefs)) {
            if (prefs.getBoolean(MobileThemeController.newsFeedVisibilityKey(feed.id), false)) {
                addMissingHomeWidget(order, feed.widgetId());
            }
        }
        return order;
    }

    private static void addMissingHomeWidget(List<String> order, String id) {
        if (!order.contains(id)) order.add(id);
    }

    private View homeWidgetView(String id) {
        if (MobileThemeController.WIDGET_WEATHER.equals(id)) return weatherCard;
        if (MobileThemeController.WIDGET_ELECTRICITY.equals(id)) return electricityCard;
        if (MobileThemeController.WIDGET_WARNINGS.equals(id)) return warningsCard;
        if (MobileThemeController.WIDGET_SENSORS.equals(id)) return sensorsCard;
        if (MobileThemeController.WIDGET_TRAFFIC.equals(id)) return trafficCard;
        if (MobileThemeController.WIDGET_GPS_SPEED.equals(id)) return gpsSpeedCard;
        if (MobileThemeController.WIDGET_NEWS.equals(id)) return newsCard;
        if (MobileThemeController.isNewsFeedWidget(id)) return newsFeedCard(id);
        return null;
    }

    /** Luo (tai kierrättää) per-lähde-uutiswidgetin kortin. Kortti on ohjelmallisesti
     *  rakennettu vastine XML:n yhdistetylle uutiskortille: otsikko + lista + status. */
    private View newsFeedCard(String widgetId) {
        View existing = newsFeedCards.get(widgetId);
        if (existing != null) return existing;

        String feedId = MobileThemeController.newsFeedIdFromWidget(widgetId);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        NewsFeed feed = NewsFeedStore.feedById(prefs, feedId);
        if (feed == null) return null; // poistettu syöte → ei haamukorttia
        String title = feed.name;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.mobile_card_bg);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(10);
        card.setLayoutParams(cardLp);

        final String feedName = feed.name;
        TextView titleView = new TextView(this);
        // "›" vihjaa että otsikkoa voi klikata → avaa syötteen oman sivun (50 uusinta).
        titleView.setText(title + "  ›");
        titleView.setTextColor(getColor(R.color.mobile_text_primary));
        titleView.setTextSize(18f);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        titleView.setClickable(true);
        titleView.setFocusable(true);
        int titlePad = dp(4);
        titleView.setPadding(0, titlePad, 0, titlePad);
        titleView.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            showNewsForFeed(feedId, feedName);
        });
        card.addView(titleView);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        listLp.topMargin = dp(6);
        list.setLayoutParams(listLp);
        card.addView(list);

        TextView status = new TextView(this);
        status.setTextColor(getColor(R.color.mobile_text_muted));
        status.setTextSize(12f);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(4);
        status.setLayoutParams(statusLp);
        card.addView(status);

        newsFeedCards.put(widgetId, card);
        newsFeedCardLists.put(widgetId, list);
        newsFeedCardStatuses.put(widgetId, status);
        return card;
    }

    private void renderNewsFeedCards() {
        for (Map.Entry<String, View> e : newsFeedCards.entrySet()) {
            String widgetId = e.getKey();
            View card = e.getValue();
            if (card.getVisibility() != View.VISIBLE) continue;
            String feedId = MobileThemeController.newsFeedIdFromWidget(widgetId);
            LinearLayout list = newsFeedCardLists.get(widgetId);
            TextView status = newsFeedCardStatuses.get(widgetId);
            if (list == null || status == null) continue;
            list.removeAllViews();
            List<NewsItem> items = RssRepository.get().peekForFeed(feedId);
            if (items.isEmpty()) {
                status.setText(newsFetchInFlight ? "Haetaan uutisia..." : "Ei uutisia");
                continue;
            }
            int max = Math.min(5, items.size());
            for (int i = 0; i < max; i++) {
                list.addView(newsRow(items.get(i), false));
            }
            status.setText(newsFetchedAt > 0 ? "Päivitetty " + ageText(newsFetchedAt) : "");
        }
    }

    private void renderGpsSpeed() {
        if (gpsSpeedCard == null) return;
        boolean widgetVisible = gpsSpeedCard.getVisibility() == View.VISIBLE;
        if (!widgetVisible) return;
        if (!hasLocationPermission()) {
            if (gpsSpeedDigital != null) gpsSpeedDigital.setText("-- km/h");
            if (gpsSpeedSignal != null) gpsSpeedSignal.setText("Sijaintilupa puuttuu.");
            if (gpsSpeedText != null) gpsSpeedText.setText("");
            if (gpsSpeedometerWidget != null) gpsSpeedometerWidget.setSpeedKmh(0f);
            return;
        }
        float kmh = currentSpeedKmh();
        if (gpsSpeedometerWidget != null) gpsSpeedometerWidget.setSpeedKmh(kmh);
        if (gpsSpeedDigital != null) {
            gpsSpeedDigital.setText(lastGpsLocation == null || !lastGpsLocation.hasSpeed()
                    ? "-- km/h"
                    : String.format(FI, "%d km/h", Math.round(kmh)));
        }
        if (gpsSpeedSignal != null) gpsSpeedSignal.setText(signalLabel());
        if (gpsSpeedText != null) gpsSpeedText.setText(speedDetailLabel());
    }

    private void renderSpeedometerView() {
        if (speedometerView == null || speedometerView.getVisibility() != View.VISIBLE) return;
        if (!hasLocationPermission()) {
            if (gpsSpeedFullDigital != null) gpsSpeedFullDigital.setText("-- km/h");
            if (gpsSpeedFullSignal != null) gpsSpeedFullSignal.setText("Sijaintilupa puuttuu.");
            if (gpsSpeedFullDetails != null) gpsSpeedFullDetails.setText("");
            if (gpsSpeedometerFull != null) gpsSpeedometerFull.setSpeedKmh(0f);
            return;
        }
        float kmh = currentSpeedKmh();
        if (gpsSpeedometerFull != null) gpsSpeedometerFull.setSpeedKmh(kmh);
        if (gpsSpeedFullDigital != null) {
            gpsSpeedFullDigital.setText(lastGpsLocation == null || !lastGpsLocation.hasSpeed()
                    ? "-- km/h"
                    : String.format(FI, "%d km/h", Math.round(kmh)));
        }
        if (gpsSpeedFullSignal != null) gpsSpeedFullSignal.setText(signalLabel());
        if (gpsSpeedFullDetails != null) gpsSpeedFullDetails.setText(speedDetailLabel());
    }

    private float currentSpeedKmh() {
        if (lastGpsLocation == null || !lastGpsLocation.hasSpeed()) return 0f;
        long age = System.currentTimeMillis() - lastGpsLocation.getTime();
        if (age > 8_000L) return 0f;
        float kmh = lastGpsLocation.getSpeed() * 3.6f;
        // Suodata GPS-jitter: paikallaan oltaessa satunnaisvaihtelu antaa 1–3 km/h
        // näennäisnopeuden. Alle 2 km/h näytetään 0.
        if (kmh < 2f) return 0f;
        return kmh;
    }

    private String signalLabel() {
        if (gpsSatellitesUsed > 0) {
            String quality;
            if (gpsSatellitesUsed >= 10) quality = "Erinomainen";
            else if (gpsSatellitesUsed >= 7) quality = "Hyvä";
            else if (gpsSatellitesUsed >= 4) quality = "Heikko";
            else quality = "Ei korjausta";
            return quality + " · " + gpsSatellitesUsed + "/" + gpsSatellitesVisible + " satelliittia";
        }
        if (lastGpsLocation != null && lastGpsLocation.hasAccuracy()) {
            return "Tarkkuus ±" + Math.round(lastGpsLocation.getAccuracy()) + " m";
        }
        return "Odotetaan GPS-signaalia...";
    }

    private String speedDetailLabel() {
        if (lastGpsLocation == null) return "";
        StringBuilder sb = new StringBuilder();
        if (lastGpsLocation.hasAccuracy()) {
            sb.append("Tarkkuus ±").append(Math.round(lastGpsLocation.getAccuracy())).append(" m");
        }
        long age = System.currentTimeMillis() - lastGpsLocation.getTime();
        if (age > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("Päivitetty ").append(ageText(lastGpsLocation.getTime()));
        }
        return sb.toString();
    }

    private void updateGpsListenerState() {
        boolean wantsActive = isGpsSpeedNeeded();
        if (wantsActive && !gpsListenerActive) startGpsSpeedListener();
        else if (!wantsActive && gpsListenerActive) stopGpsSpeedListener();
    }

    private boolean isGpsSpeedNeeded() {
        if (!hasLocationPermission()) return false;
        boolean widgetActive = gpsSpeedCard != null
                && gpsSpeedCard.getVisibility() == View.VISIBLE
                && homeView != null
                && homeView.getVisibility() == View.VISIBLE;
        boolean fullActive = speedometerView != null
                && speedometerView.getVisibility() == View.VISIBLE;
        return widgetActive || fullActive;
    }

    private void startGpsSpeedListener() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        if (gpsSpeedListener == null) {
            gpsSpeedListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location == null) return;
                    // NETWORK_PROVIDER-sijainnilla ei ole nopeustietoa. Älä korvaa
                    // tuoretta GPS-fixiä nopeudettomalla sijainnilla, jottei mittari
                    // hyppää hetkellisesti nollaan ~20 s välein ajon aikana.
                    if (!location.hasSpeed()
                            && lastGpsLocation != null && lastGpsLocation.hasSpeed()
                            && System.currentTimeMillis() - lastGpsLocation.getTime() < 8_000L) {
                        return;
                    }
                    lastGpsLocation = location;
                    renderGpsSpeed();
                    renderSpeedometerView();
                }
                @Override public void onProviderEnabled(String provider) { }
                @Override public void onProviderDisabled(String provider) {
                    renderGpsSpeed();
                    renderSpeedometerView();
                }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
            };
        }
        if (gnssStatusCallback == null) {
            gnssStatusCallback = new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(GnssStatus status) {
                    int used = 0;
                    int visible = status.getSatelliteCount();
                    for (int i = 0; i < visible; i++) {
                        if (status.usedInFix(i)) used++;
                    }
                    gpsSatellitesUsed = used;
                    gpsSatellitesVisible = visible;
                    renderGpsSpeed();
                    renderSpeedometerView();
                }
            };
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsSpeedListener);
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
        // NETWORK_PROVIDER toimii myös sisätiloissa kun GPS ei näe taivasta.
        // Nopeus tulee tyypillisesti vain GPS-providerilta, mutta sijainti
        // päivittyy edes karkealla tarkkuudella.
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, gpsSpeedListener);
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
        try {
            lm.registerGnssStatusCallback(gnssStatusCallback, main);
        } catch (SecurityException ignored) {
        }
        Location lastGps = null;
        Location lastNet = null;
        try {
            lastGps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        } catch (SecurityException ignored) {
        }
        try {
            lastNet = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } catch (SecurityException ignored) {
        }
        Location best = lastGps;
        if (lastNet != null && (best == null || lastNet.getTime() > best.getTime())) {
            best = lastNet;
        }
        if (best != null) lastGpsLocation = best;
        gpsListenerActive = true;
        renderGpsSpeed();
        renderSpeedometerView();
    }

    private void stopGpsSpeedListener() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            gpsListenerActive = false;
            return;
        }
        if (gpsSpeedListener != null) {
            try {
                lm.removeUpdates(gpsSpeedListener);
            } catch (Exception ignored) {
            }
        }
        if (gnssStatusCallback != null) {
            try {
                lm.unregisterGnssStatusCallback(gnssStatusCallback);
            } catch (Exception ignored) {
            }
        }
        gpsListenerActive = false;
    }

    private void renderTrafficWidget() {
        if (trafficCard == null || trafficWidgetStatusText == null || trafficWidgetList == null) return;
        boolean show = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(MobileThemeController.KEY_SHOW_TRAFFIC_WIDGET, true);
        trafficCard.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        trafficWidgetList.removeAllViews();
        double[] ref = searchReferenceCoordinates();
        List<TrafficNotice> notices = trafficForKind(TrafficNotice.Kind.ALL);
        if (ref == null) {
            trafficWidgetStatusText.setText("Salli sijainti, jotta lähialueen liikennetiedotteet voidaan hakea.");
            return;
        }
        if (notices.isEmpty()) {
            trafficWidgetStatusText.setText("Ei lähialueen liikennetiedotteita juuri nyt.");
            return;
        }
        trafficWidgetStatusText.setText("Lähialueen tärkeimmät tiedotteet");
        int count = Math.min(3, notices.size());
        for (int i = 0; i < count; i++) {
            trafficWidgetList.addView(trafficNoticeCard(notices.get(i), true));
        }
    }

    private void showTrafficSection(TrafficNotice.Kind kind) {
        selectedTrafficKind = kind == null ? TrafficNotice.Kind.ACCIDENT : kind;
        showSection(trafficView, trafficKindTitle(selectedTrafficKind));
        renderTrafficView();
        if (trafficRepository.cacheTime() <= 0L && !trafficRefreshInFlight) {
            refreshTrafficOnly(false);
        }
    }

    private void refreshTrafficOnly(boolean forced) {
        if (trafficRefreshInFlight) return;
        double[] ref = trafficReferenceCoordinates();
        if (ref == null) {
            renderTrafficView();
            return;
        }
        trafficRefreshInFlight = true;
        if (trafficStatusText != null) trafficStatusText.setText("Haetaan liikennetiedotteita...");
        io.execute(() -> {
            List<TrafficNotice> fresh = null;
            Exception failure = null;
            try {
                fresh = trafficRepository.fetchNearby(ref[0], ref[1],
                        TrafficNotice.Kind.ALL, forced);
            } catch (Exception e) {
                failure = e;
            }
            List<TrafficNotice> finalFresh = fresh;
            Exception finalFailure = failure;
            main.post(() -> {
                trafficRefreshInFlight = false;
                if (destroyed || isFinishing() || isDestroyed()) return;
                if (finalFresh != null) trafficNotices = finalFresh;
                if (finalFailure != null && trafficStatusText != null) {
                    trafficStatusText.setText("Liikennetietojen haku epäonnistui: "
                            + safeMessage(finalFailure));
                }
                renderTrafficWidget();
                renderTrafficView();
            });
        });
    }

    private void renderTrafficView() {
        if (trafficView == null || trafficList == null || trafficStatusText == null) return;
        if (trafficTitleText != null) trafficTitleText.setText(trafficKindTitle(selectedTrafficKind));
        trafficList.removeAllViews();
        double[] ref = searchReferenceCoordinates();
        if (ref == null) {
            trafficStatusText.setText("Salli sijainti, jotta lähialueen "
                    + trafficKindPartitive(selectedTrafficKind)
                    + " voidaan näyttää.");
            return;
        }
        List<TrafficNotice> notices = trafficForKind(selectedTrafficKind);
        String updated = trafficRepository.cacheTime() > 0L
                ? "Päivitetty klo " + statusTimeFormat.format(new Date(trafficRepository.cacheTime()))
                : "Ei vielä haettu";
        trafficStatusText.setText(currentDisplayPlace() + " · " + updated
                + "\nLähialue: noin 50 km säde");
        if (notices.isEmpty()) {
            trafficList.addView(textCard("Ei lähialueen "
                    + trafficKindPartitive(selectedTrafficKind)
                    + " juuri nyt."));
            return;
        }
        for (TrafficNotice notice : notices) {
            trafficList.addView(trafficNoticeCard(notice, false));
        }
    }

    private String trafficKindPartitive(TrafficNotice.Kind kind) {
        if (kind == TrafficNotice.Kind.ACCIDENT) return "onnettomuuksia";
        if (kind == TrafficNotice.Kind.ROAD_WORK) return "tietöitä";
        if (kind == TrafficNotice.Kind.WEIGHT_RESTRICTION) return "painorajoituksia";
        if (kind == TrafficNotice.Kind.INCIDENT) return "häiriöitä";
        if (kind == TrafficNotice.Kind.CONGESTION) return "ruuhkia";
        return "liikennetiedotteita";
    }

    private List<TrafficNotice> trafficForKind(TrafficNotice.Kind kind) {
        List<TrafficNotice> out = new ArrayList<>();
        TrafficNotice.Kind selected = kind == null ? TrafficNotice.Kind.ALL : kind;
        for (TrafficNotice notice : trafficNotices) {
            if (notice == null) continue;
            if (selected != TrafficNotice.Kind.ALL && notice.kind != selected) continue;
            out.add(notice);
        }
        return out;
    }

    private View trafficNoticeCard(TrafficNotice notice, boolean compact) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.mobile_warning_item_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(compact ? 6 : 8));
        card.setLayoutParams(lp);

        String title = notice.title.isEmpty() ? notice.kind.title : notice.title;
        TextView titleView = rowText(title, compact ? 14 : 16, true);
        card.addView(titleView);

        List<String> meta = new ArrayList<>();
        meta.add(notice.kind.title);
        if (!Double.isNaN(notice.distanceMeters)) meta.add(formatDistance(notice.distanceMeters));
        String valid = trafficValidityText(notice);
        if (!valid.isEmpty()) meta.add(valid);
        TextView metaView = rowText(joinInline(meta), compact ? 12 : 13, false);
        metaView.setTextColor(getColor(R.color.mobile_text_secondary));
        card.addView(metaView);

        boolean hasLocation = !notice.location.isEmpty();
        boolean hasDetails = !notice.details.isEmpty();

        if (compact && (hasLocation || hasDetails)) {
            LinearLayout extra = new LinearLayout(this);
            extra.setOrientation(LinearLayout.VERTICAL);
            extra.setVisibility(View.GONE);

            if (hasLocation) {
                TextView location = rowText(notice.location, 14, false);
                LinearLayout.LayoutParams locationLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                locationLp.setMargins(0, dp(6), 0, 0);
                extra.addView(location, locationLp);
            }
            if (hasDetails) {
                TextView details = rowText(notice.details, 14, false);
                LinearLayout.LayoutParams detailsLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                detailsLp.setMargins(0, dp(6), 0, 0);
                extra.addView(details, detailsLp);
            }
            card.addView(extra);

            TextView hint = rowText("Näytä lisätiedot ▾", 12, false);
            hint.setTextColor(getColor(R.color.mobile_accent));
            LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            hintLp.setMargins(0, dp(6), 0, 0);
            card.addView(hint, hintLp);

            card.setOnClickListener(v -> {
                if (extra.getVisibility() == View.VISIBLE) {
                    extra.setVisibility(View.GONE);
                    hint.setText("Näytä lisätiedot ▾");
                } else {
                    extra.setVisibility(View.VISIBLE);
                    hint.setText("Piilota lisätiedot ▴");
                }
            });
        } else if (!compact) {
            if (hasLocation) {
                TextView location = rowText(notice.location, 14, false);
                LinearLayout.LayoutParams locationLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                locationLp.setMargins(0, dp(6), 0, 0);
                card.addView(location, locationLp);
            }
            if (hasDetails) {
                TextView details = rowText(notice.details, 14, false);
                LinearLayout.LayoutParams detailsLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                detailsLp.setMargins(0, dp(6), 0, 0);
                card.addView(details, detailsLp);
            }
        }
        return card;
    }

    private String trafficKindTitle(TrafficNotice.Kind kind) {
        if (kind == TrafficNotice.Kind.ACCIDENT) return getString(R.string.mobile_menu_traffic_accidents);
        if (kind == TrafficNotice.Kind.ROAD_WORK) return getString(R.string.mobile_menu_traffic_roadworks);
        if (kind == TrafficNotice.Kind.WEIGHT_RESTRICTION) return getString(R.string.mobile_menu_traffic_weight);
        if (kind == TrafficNotice.Kind.INCIDENT) return getString(R.string.mobile_menu_traffic_incidents);
        if (kind == TrafficNotice.Kind.CONGESTION) return getString(R.string.mobile_menu_traffic_congestion);
        return getString(R.string.mobile_menu_traffic);
    }

    private String trafficValidityText(TrafficNotice notice) {
        if (notice.startTimeMs <= 0L && notice.endTimeMs <= 0L) return "";
        if (notice.startTimeMs <= 0L) return "voimassa asti " + formatWarningDateTime(notice.endTimeMs);
        if (notice.endTimeMs <= 0L) return "alkaen " + formatWarningDateTime(notice.startTimeMs);
        if (sameDay(notice.startTimeMs, notice.endTimeMs)) {
            return formatWarningDate(notice.startTimeMs) + " "
                    + statusTimeFormat.format(new Date(notice.startTimeMs))
                    + "-" + statusTimeFormat.format(new Date(notice.endTimeMs));
        }
        return formatWarningDateTime(notice.startTimeMs) + " - "
                + formatWarningDateTime(notice.endTimeMs);
    }

    private static String formatDistance(double meters) {
        if (Double.isNaN(meters)) return "";
        if (meters < 1000.0) return Math.round(meters) + " m";
        return String.format(FI, "%.1f km", meters / 1000.0);
    }

    private void renderWarnings(List<WeatherWarning> warnings) {
        if (!PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(MobileThemeController.KEY_SHOW_WARNINGS_WIDGET, true)) {
            warningsCard.setVisibility(View.GONE);
            warningsList.removeAllViews();
            return;
        }
        if (warnings == null || warnings.isEmpty()) {
            warningsCard.setVisibility(View.GONE);
            warningsList.removeAllViews();
            return;
        }
        warningsCard.setVisibility(View.VISIBLE);
        warningsList.removeAllViews();
        for (WeatherWarning warning : warnings) {
            warningsList.addView(warningCard(warning));
        }
    }

    private View warningCard(WeatherWarning warning) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.mobile_warning_item_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        card.setLayoutParams(lp);

        String level = warning.level.fiName == null || warning.level.fiName.isEmpty()
                ? "Varoitus"
                : warning.level.fiName;
        TextView title = rowText(level + ": " + warning.event, 16, true);
        title.setTextColor(warningTitleColor(warning.level));
        card.addView(title);

        String valid = warningValidityText(warning);
        if (!valid.isEmpty()) {
            TextView meta = rowText(valid + (warning.marine ? " · merialue" : ""), 13, false);
            meta.setTextColor(getColor(R.color.mobile_text_secondary));
            card.addView(meta);
        }
        if (!warning.description.isEmpty()) {
            TextView desc = rowText(warning.description, 14, false);
            LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            descLp.setMargins(0, dp(6), 0, 0);
            card.addView(desc, descLp);
        }
        if (!warning.areaDesc.isEmpty()) {
            TextView area = rowText("Alue: " + warning.areaDesc, 13, false);
            area.setTextColor(getColor(R.color.mobile_text_secondary));
            LinearLayout.LayoutParams areaLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            areaLp.setMargins(0, dp(6), 0, 0);
            card.addView(area, areaLp);
        }
        return card;
    }

    private String warningValidityText(WeatherWarning warning) {
        if (warning.onsetMs <= 0L && warning.expiresMs <= 0L) return "";
        if (warning.onsetMs <= 0L) {
            return "Voimassa asti " + formatWarningDateTime(warning.expiresMs);
        }
        if (warning.expiresMs <= 0L) {
            return "Voimassa alkaen " + formatWarningDateTime(warning.onsetMs);
        }
        if (sameDay(warning.onsetMs, warning.expiresMs)) {
            return "Voimassa " + formatWarningDate(warning.onsetMs)
                    + " " + statusTimeFormat.format(new Date(warning.onsetMs))
                    + "-" + statusTimeFormat.format(new Date(warning.expiresMs));
        }
        return "Voimassa " + formatWarningDateTime(warning.onsetMs)
                + " - " + formatWarningDateTime(warning.expiresMs);
    }

    private void renderSensors() {
        if (!PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(MobileThemeController.KEY_SHOW_SENSORS_WIDGET, true)) {
            sensorsCard.setVisibility(View.GONE);
            return;
        }
        sensorsCard.setVisibility(View.VISIBLE);
        sensorsContainer.removeAllViews();
        SettingsManager sm = SettingsManager.get();
        RuuviRepository repo = RuuviRepository.get(this);
        List<SensorEntry> sensors = new ArrayList<>();
        List<String> assignedMacs = new ArrayList<>();
        addAssignedMac(assignedMacs, sm.getRuuviMac(SettingsManager.RUUVI_SLOT_BEDROOM));
        addAssignedMac(assignedMacs, sm.getRuuviMac(SettingsManager.RUUVI_SLOT_LIVINGROOM));
        addAssignedMac(assignedMacs, sm.getRuuviMac(SettingsManager.RUUVI_SLOT_BALCONY));
        addSensorEntry(sensors, sensorName(SettingsManager.RUUVI_SLOT_BEDROOM),
                sm.getRuuviMac(SettingsManager.RUUVI_SLOT_BEDROOM), repo);
        addSensorEntry(sensors, sensorName(SettingsManager.RUUVI_SLOT_LIVINGROOM),
                sm.getRuuviMac(SettingsManager.RUUVI_SLOT_LIVINGROOM), repo);
        addSensorEntry(sensors, sensorName(SettingsManager.RUUVI_SLOT_BALCONY),
                sm.getRuuviMac(SettingsManager.RUUVI_SLOT_BALCONY), repo);
        // Auto-numerointi jatkaa korkeimmasta MÄÄRITETYSTÄ slotista (1/2/3), jottei
        // "Anturi 1-3" jää väliin kun slotteja ei ole määritetty (silloin alkaa 1:stä).
        int nextSensor = 1;
        if (hasMac(sm.getRuuviMac(SettingsManager.RUUVI_SLOT_BEDROOM))) nextSensor = 2;
        if (hasMac(sm.getRuuviMac(SettingsManager.RUUVI_SLOT_LIVINGROOM))) nextSensor = 3;
        if (hasMac(sm.getRuuviMac(SettingsManager.RUUVI_SLOT_BALCONY))) nextSensor = 4;
        for (Map.Entry<String, RuuviSample> entry : sortedRuuviSnapshot(repo.snapshot())) {
            if (assignedMacs.contains(entry.getKey().toUpperCase(Locale.ROOT))) continue;
            sensors.add(new SensorEntry("Anturi " + nextSensor, entry.getValue()));
            nextSensor++;
        }
        if (sensors.isEmpty()) {
            sensorsEmptyText.setVisibility(View.VISIBLE);
            sensorsEmptyText.setText("Ei määritettyjä Ruuvi-antureita");
            return;
        }
        sensorsEmptyText.setVisibility(View.GONE);
        fillSensorGrid(sensorsContainer, sensors);
    }

    private void fillSensorGrid(LinearLayout container, List<SensorEntry> sensors) {
        container.removeAllViews();
        for (int i = 0; i < sensors.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) rowLp.setMargins(0, dp(8), 0, 0);
            row.setLayoutParams(rowLp);

            View first = sensorTile(sensors.get(i));
            LinearLayout.LayoutParams firstLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i + 1 < sensors.size()) firstLp.setMarginEnd(dp(8));
            row.addView(first, firstLp);

            if (i + 1 < sensors.size()) {
                View second = sensorTile(sensors.get(i + 1));
                row.addView(second, new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            } else {
                View spacer = new View(this);
                row.addView(spacer, new LinearLayout.LayoutParams(
                        0, 0, 1f));
            }
            container.addView(row);
        }
    }

    private View sensorTile(SensorEntry entry) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackgroundResource(R.drawable.mobile_warning_item_bg);
        tile.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView name = rowText(entry.label, 15, true);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tile.addView(name, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        RuuviSample sample = entry.sample;
        if (sample == null) {
            TextView wait = rowText("odottaa", 14, false);
            wait.setGravity(Gravity.CENTER_HORIZONTAL);
            wait.setTextColor(getColor(R.color.mobile_text_muted));
            LinearLayout.LayoutParams waitLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            waitLp.setMargins(0, dp(8), 0, 0);
            tile.addView(wait, waitLp);
            return tile;
        }

        Double t = sample.temperatureC();
        Double h = sample.humidityPct();
        TextView temp = rowText(t == null ? "--" : one(t) + " °C", 26, true);
        temp.setTextColor(getColor(R.color.mobile_accent));
        temp.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams tempLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tempLp.setMargins(0, dp(6), 0, 0);
        tile.addView(temp, tempLp);

        TextView humidity = rowText(h == null ? "kosteus --" : "kosteus " + Math.round(h) + " %", 14, false);
        humidity.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.addView(humidity, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView age = rowText(ageText(sample.timestamp), 12, false);
        age.setGravity(Gravity.CENTER_HORIZONTAL);
        age.setTextColor(getColor(R.color.mobile_text_muted));
        tile.addView(age, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        return tile;
    }

    private static void addSensorEntry(List<SensorEntry> out, String label, String mac, RuuviRepository repo) {
        if (mac == null || mac.trim().isEmpty()) return;
        RuuviSample sample = repo.getLatest(mac);
        out.add(new SensorEntry(label, sample));
    }

    private static void addAssignedMac(List<String> assignedMacs, String mac) {
        if (mac != null && !mac.trim().isEmpty()) {
            assignedMacs.add(mac.trim().toUpperCase(Locale.ROOT));
        }
    }

    /** Onko slotille määritetty (ei-tyhjä) MAC. */
    private static boolean hasMac(String mac) {
        return mac != null && !mac.trim().isEmpty();
    }

    private static final class SensorEntry {
        final String label;
        final RuuviSample sample;
        SensorEntry(String label, RuuviSample sample) {
            this.label = label;
            this.sample = sample;
        }
    }

    private void renderForecastView() {
        if (forecastList == null) return;
        forecastList.removeAllViews();
        forecastDays.removeAllViews();
        if (weather == null || weather.hours.isEmpty()) {
            forecastStatusText.setText("Ennustetta ei ole vielä ladattu.");
            forecastList.addView(textCard("Päivitä sää, niin tuntiennuste tulee tähän."));
            return;
        }
        List<Integer> days = forecastDayKeys();
        if (days.isEmpty()) return;
        if (!days.contains(selectedForecastDayKey)) selectedForecastDayKey = days.get(0);
        renderForecastDayTabs(days);

        int rows = 0;
        for (WeatherData.Hour h : weather.hours) {
            if (dayKey(h.timestamp) != selectedForecastDayKey) continue;
            forecastList.addView(forecastRow(h));
            rows++;
        }
        List<String> status = new ArrayList<>();
        status.add(currentDisplayPlace() + " · " + dayLabel(selectedForecastDayKey));
        status.add(dayRemainingText());
        if (weather.fetchedAt > 0) {
            status.add("Päivitetty klo " + statusTimeFormat.format(new Date(weather.fetchedAt)));
        }
        forecastStatusText.setText(joinLines(status));
        if (rows == 0) {
            forecastList.addView(textCard("Tälle päivälle ei löytynyt tuntirivejä."));
        }
    }

    private View forecastRow(WeatherData.Hour h) {
        LinearLayout card = cardContainer();

        String time = statusTimeFormat.format(new Date(h.timestamp));
        card.addView(rowText(time, 16, true));
        card.addView(providerRow("FMI",
                h.condition,
                formatTemp(h.temperature),
                WeatherTextFormatter.shortLabel(this, h.condition),
                fmiForecastDetails(h)));

        OpenMeteoData.Hour om = bestOpenMeteoHourAt(h.timestamp);
        if (om != null) {
            card.addView(providerRow("Open-Meteo",
                    om.condition,
                    formatNullableTemp(om.temperature),
                    WeatherTextFormatter.shortLabel(this, om.condition),
                    openMeteoForecastDetails(om)));
        } else {
            TextView missing = rowText("Open-Meteo ei saatavilla tälle tunnille", 14, false);
            missing.setPadding(0, dp(4), 0, 0);
            card.addView(missing);
        }
        return card;
    }

    private OpenMeteoData.Hour bestOpenMeteoHourAt(long timestamp) {
        if (openMeteo == null || openMeteo.hours == null) return null;
        long bestDiff = Long.MAX_VALUE;
        OpenMeteoData.Hour best = null;
        for (OpenMeteoData.Hour h : openMeteo.hours) {
            long diff = Math.abs(h.timestamp - timestamp);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = h;
            }
        }
        if (best == null || bestDiff > 31L * 60_000L) return null;
        return best;
    }

    private View providerRow(String source, org.jrs82.fsclock.WeatherCondition condition,
                             String temperature, String label, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, 0);

        WeatherIconView icon = new WeatherIconView(this);
        icon.setCondition(condition);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        iconLp.setMarginEnd(dp(10));
        row.addView(icon, iconLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        texts.addView(rowText(source + "  " + temperature + "  " + label, 15, true));
        if (detail != null && !detail.isEmpty()) {
            texts.addView(rowText(detail, 13, false));
        }
        return row;
    }

    private String fmiForecastDetails(WeatherData.Hour h) {
        List<String> detail = new ArrayList<>();
        if (!Double.isNaN(h.precipitation)) detail.add("Sade " + one(h.precipitation) + " mm");
        if (!Double.isNaN(h.windSpeed)) detail.add("Tuuli " + one(h.windSpeed) + " m/s");
        if (!Double.isNaN(h.windGust)) detail.add("Puuska " + one(h.windGust) + " m/s");
        return joinInline(detail);
    }

    private String openMeteoForecastDetails(OpenMeteoData.Hour h) {
        List<String> detail = new ArrayList<>();
        if (h.precipitation != null) detail.add("Sade " + one(h.precipitation) + " mm");
        if (h.windSpeed != null) detail.add("Tuuli " + one(h.windSpeed) + " m/s");
        if (h.windGust != null) detail.add("Puuska " + one(h.windGust) + " m/s");
        if (h.humidity != null) detail.add("Kosteus " + Math.round(h.humidity) + " %");
        return joinInline(detail);
    }

    private void renderForecastDayTabs(List<Integer> days) {
        for (Integer key : days) {
            TextView tab = new TextView(this);
            tab.setText(dayLabel(key));
            tab.setTextSize(15);
            tab.setGravity(Gravity.CENTER);
            tab.setSingleLine(true);
            tab.setBackgroundResource(R.drawable.mobile_menu_item_bg);
            boolean selected = key == selectedForecastDayKey;
            tab.setTextColor(getColor(selected ? R.color.mobile_accent : R.color.mobile_text_primary));
            tab.setTypeface(tab.getTypeface(), selected
                    ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
            tab.setPadding(dp(12), dp(9), dp(12), dp(9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(8), dp(4));
            forecastDays.addView(tab, lp);
            tab.setOnClickListener(v -> {
                selectedForecastDayKey = key;
                renderForecastView();
            });
        }
    }

    private List<Integer> forecastDayKeys() {
        List<Integer> out = new ArrayList<>();
        for (WeatherData.Hour h : weather.hours) {
            int key = dayKey(h.timestamp);
            if (!out.contains(key)) out.add(key);
            if (out.size() >= 7) break;
        }
        return out;
    }

    private int dayKey(long timestamp) {
        Calendar c = Calendar.getInstance(HELSINKI, FI);
        c.setTimeInMillis(timestamp);
        return c.get(Calendar.YEAR) * 10000
                + (c.get(Calendar.MONTH) + 1) * 100
                + c.get(Calendar.DAY_OF_MONTH);
    }

    private String dayLabel(int key) {
        int year = key / 10000;
        int month = (key / 100) % 100;
        int day = key % 100;
        Calendar c = Calendar.getInstance(HELSINKI, FI);
        c.clear();
        c.set(year, month - 1, day);
        String label = forecastDayFormat.format(c.getTime());
        if (label.length() > 0) {
            label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
        }
        return label;
    }

    private void renderElectricityView() {
        if (electricityQuarterList == null) return;
        electricityQuarterList.removeAllViews();
        renderElectricityTabs();

        // Vertailu-välilehti: kuukausi- ja vuosikeskiarvot, oma säiliönsä.
        if (selectedElectricityDayOffset == 2) {
            electricityQuarterList.setVisibility(View.GONE);
            if (electricityCompareList != null) electricityCompareList.setVisibility(View.VISIBLE);
            renderElectricityCompare();
            return;
        }
        electricityQuarterList.setVisibility(View.VISIBLE);
        if (electricityCompareList != null) electricityCompareList.setVisibility(View.GONE);

        ElectricityData data = electricity != null ? electricity : ElectricityRepository.get(this).peek();
        if (data == null || data.quarters.isEmpty()) {
            electricitySummaryText.setText("Sähkön hintaa ei ole vielä ladattu.");
            electricityQuarterList.addView(textCard("Päivitä tiedot, niin varttihinnat tulevat tähän."));
            return;
        }
        Calendar c = Calendar.getInstance(HELSINKI, FI);
        c.add(Calendar.DAY_OF_YEAR, selectedElectricityDayOffset);
        int year = c.get(Calendar.YEAR);
        int month = c.get(Calendar.MONTH) + 1;
        int day = c.get(Calendar.DAY_OF_MONTH);
        List<ElectricityData.Quarter> quarters = ElectricityRepository.get(this).dayQuarters(year, month, day);

        if (selectedElectricityDayOffset == 1 && (quarters == null || quarters.size() < 96)) {
            electricitySummaryText.setText(tomorrowPriceStatusText());
            scheduleTomorrowPricePolling();
            return;
        }

        if (quarters == null || quarters.isEmpty()) {
            if (selectedElectricityDayOffset == 1) {
                electricitySummaryText.setText(tomorrowPriceStatusText());
                scheduleTomorrowPricePolling();
            } else {
                electricitySummaryText.setText("Tämän päivän varttihintoja ei ole saatavilla.");
                electricityQuarterList.addView(textCard("Päivitä tiedot uudelleen."));
            }
            return;
        }

        ElectricityData.Quarter current = selectedElectricityDayOffset == 0
                ? ElectricityRepository.get(this).currentQuarter()
                : null;
        if (selectedElectricityDayOffset == 0 && current == null) current = currentQuarterFrom(data);
        electricitySummaryText.setText(electricitySummary(data, quarters, current));
        fillElectricityList(electricityQuarterList, quarters, current);
    }

    private String tomorrowPriceStatusText() {
        return "Huomisen hinnat päivittyvät noin klo 14:30 joka päivä.";
    }

    private static final String[] MONTH_NAMES_FI = {
            "Tammikuu", "Helmikuu", "Maaliskuu", "Huhtikuu", "Toukokuu", "Kesäkuu",
            "Heinäkuu", "Elokuu", "Syyskuu", "Lokakuu", "Marraskuu", "Joulukuu"
    };

    /** Vertailu-välilehti: edellisvuoden keskihinta + kuluvan vuoden kuukausikeskiarvot.
     *  Data haetaan taustalla (Elering) ja välimuistitetaan; UI näyttää ensin
     *  välimuistin ja täydentää verkosta. */
    private void renderElectricityCompare() {
        if (electricityCompareList == null) return;
        electricitySummaryText.setText("Pörssisähkön keskihinnat (ALV 0 %). Lähde: Elering/Nord Pool.");
        boolean startFetch = !compareFetchInFlight;
        if (startFetch) compareFetchInFlight = true;
        // Näytä heti välimuistista; tyhjä-tila kertoo jos haku on käynnissä taustalla.
        populateCompareList(false);
        if (!startFetch) return;
        compareIo.execute(() -> {
            // Lämmitä välimuisti verkosta (edellisvuosi + kuluvan vuoden kuukaudet).
            ElectricityAverages.previousYearAverage(this, true);
            Calendar now = Calendar.getInstance(HELSINKI, FI);
            int year = now.get(Calendar.YEAR);
            int curMonth = now.get(Calendar.MONTH) + 1;
            for (int m = 1; m <= curMonth; m++) {
                ElectricityAverages.monthAverage(this, year, m, true);
            }
            main.post(() -> {
                compareFetchInFlight = false;
                if (destroyed || isFinishing() || isDestroyed()) return;
                if (selectedElectricityDayOffset == 2) populateCompareList(false);
            });
        });
    }

    private void populateCompareList(boolean allowNetwork) {
        if (electricityCompareList == null) return;
        electricityCompareList.removeAllViews();
        Calendar now = Calendar.getInstance(HELSINKI, FI);
        int year = now.get(Calendar.YEAR);
        int curMonth = now.get(Calendar.MONTH) + 1;

        ElectricityAverages.MonthAverage prevYear =
                ElectricityAverages.previousYearAverage(this, allowNetwork);
        if (prevYear != null) {
            electricityCompareList.addView(electricityCompareRow(
                    (year - 1) + " keskihinta", prevYear.avgSntPerKwh, true));
        }

        electricityCompareList.addView(sectionLabel(year + " kuukausikeskiarvot"));
        boolean any = false;
        for (int m = 1; m <= curMonth; m++) {
            ElectricityAverages.MonthAverage ma =
                    ElectricityAverages.monthAverage(this, year, m, allowNetwork);
            if (ma == null) continue;
            any = true;
            String label = MONTH_NAMES_FI[m - 1];
            if (m == curMonth) label += " (kesken)";
            electricityCompareList.addView(electricityCompareRow(label, ma.avgSntPerKwh, false));
        }
        if (!any && prevYear == null) {
            electricityCompareList.addView(textCard(compareFetchInFlight
                    ? "Haetaan keskiarvoja…"
                    : "Keskiarvoja ei ole vielä saatavilla. Päivitä uudelleen verkkoyhteydellä."));
        }
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getColor(R.color.mobile_text_secondary));
        tv.setTextSize(13f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(2);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View electricityCompareRow(String label, double sntPerKwh, boolean highlight) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.mobile_card_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(getColor(R.color.mobile_text_primary));
        name.setTextSize(highlight ? 17f : 16f);
        if (highlight) name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        TextView value = new TextView(this);
        value.setText(String.format(FI, "%.3f c/kWh", sntPerKwh));
        value.setTextColor(getColor(highlight ? R.color.mobile_accent : R.color.mobile_text_primary));
        value.setTextSize(highlight ? 17f : 16f);
        value.setTypeface(value.getTypeface(), android.graphics.Typeface.BOLD);
        row.addView(value);

        return row;
    }

    private void renderElectricityTabs() {
        styleElectricityTab(electricityTodayTab, selectedElectricityDayOffset == 0);
        styleElectricityTab(electricityTomorrowTab, selectedElectricityDayOffset == 1);
        styleElectricityTab(electricityCompareTab, selectedElectricityDayOffset == 2);
    }

    private void styleElectricityTab(TextView tab, boolean selected) {
        if (tab == null) return;
        tab.setBackgroundResource(selected
                ? R.drawable.mobile_electricity_tab_selected_bg
                : R.drawable.mobile_electricity_tab_bg);
        tab.setTextColor(getColor(selected ? R.color.mobile_accent : R.color.mobile_text_primary));
        tab.setTypeface(tab.getTypeface(),
                selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private String electricitySummary(ElectricityData data, List<ElectricityData.Quarter> quarters,
                                      ElectricityData.Quarter current) {
        ElectricityData.Quarter min = minQuarter(quarters);
        ElectricityData.Quarter max = maxQuarter(quarters);
        List<String> lines = new ArrayList<>();
        if (selectedElectricityDayOffset == 0 && current != null) {
            lines.add(String.format(FI, "Sähkön hinta nyt klo %02d:%02d  %.3f c/kWh",
                    current.hour, current.minute, current.sntPerKwh));
        } else {
            lines.add(selectedElectricityDayOffset == 0 ? "Tänään" : "Huomenna");
        }
        if (min != null) {
            lines.add(String.format(FI, "Sähkön hinta on halvinta klo %02d:%02d  %.3f c/kWh",
                    min.hour, min.minute, min.sntPerKwh));
        }
        if (max != null) {
            lines.add(String.format(FI, "Sähkön hinta on kalleinta klo %02d:%02d  %.3f c/kWh",
                    max.hour, max.minute, max.sntPerKwh));
        }
        if (data != null && data.fetchedAt > 0) {
            lines.add("Päivitetty klo " + statusTimeFormat.format(new Date(data.fetchedAt)));
        }
        lines.add("Lähde: Elering/Nord Pool. Hinnat ALV 0%.");
        return joinLines(lines);
    }

    private void fillElectricityList(LinearLayout target, List<ElectricityData.Quarter> quarters,
                                     ElectricityData.Quarter current) {
        if (quarters == null || quarters.isEmpty()) {
            target.addView(textCard("Ei varttihintoja."));
            return;
        }
        int max = Math.min(quarters.size(), 96);
        for (int i = 0; i < max; i++) {
            ElectricityData.Quarter q = quarters.get(i);
            target.addView(electricityQuarterRow(q, sameQuarter(q, current)));
        }
    }

    private View electricityQuarterRow(ElectricityData.Quarter q, boolean current) {
        TextView tv = rowText(String.format(FI, "%s%02d:%02d   %.3f c/kWh",
                current ? "Nyt  " : "", q.hour, q.minute, q.sntPerKwh),
                current ? 19 : 17, current);
        tv.setBackgroundResource(R.drawable.mobile_card_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        tv.setLayoutParams(lp);
        return tv;
    }

    private static ElectricityData.Quarter minQuarter(List<ElectricityData.Quarter> quarters) {
        ElectricityData.Quarter best = null;
        for (ElectricityData.Quarter q : quarters) {
            if (best == null || q.sntPerKwh < best.sntPerKwh) best = q;
        }
        return best;
    }

    private static ElectricityData.Quarter maxQuarter(List<ElectricityData.Quarter> quarters) {
        ElectricityData.Quarter best = null;
        for (ElectricityData.Quarter q : quarters) {
            if (best == null || q.sntPerKwh > best.sntPerKwh) best = q;
        }
        return best;
    }

    private static boolean allBelow(List<ElectricityData.Quarter> quarters, double threshold) {
        if (quarters == null || quarters.isEmpty()) return false;
        for (ElectricityData.Quarter q : quarters) {
            if (q.sntPerKwh >= threshold) return false;
        }
        return true;
    }

    private static boolean sameQuarter(ElectricityData.Quarter a, ElectricityData.Quarter b) {
        return a != null && b != null && a.timestamp == b.timestamp;
    }

    private void renderSensorsView() {
        if (sensorsList == null) return;
        sensorsList.removeAllViews();
        Map<String, RuuviSample> snapshot = RuuviRepository.get(this).snapshot();
        if (snapshot.isEmpty() && !hasBluetoothScanPermission()) {
            sensorsList.addView(textCard("Bluetooth-skannaus vaatii luvan asetuksista."));
            return;
        }
        if (snapshot.isEmpty()) {
            sensorsList.addView(textCard("Aktiivisia Ruuvi-havaintoja ei ole vielä muistissa."));
            return;
        }
        int unassignedIndex = 4;
        for (Map.Entry<String, RuuviSample> e : sortedRuuviSnapshot(snapshot)) {
            RuuviSample s = e.getValue();
            String slot = SettingsManager.get().slotForMac(e.getKey());
            String name = slot == null ? "Anturi " + unassignedIndex++ : sensorName(slot);
            Double t = s.temperatureC();
            Double h = s.humidityPct();
            sensorsList.addView(textCard(name + "  " + e.getKey() + "\n"
                    + (t == null ? "-- C" : one(t) + " C")
                    + " · " + (h == null ? "-- %" : Math.round(h) + " %")
                    + " · " + ageText(s.timestamp)));
        }
    }

    private static List<Map.Entry<String, RuuviSample>> sortedRuuviSnapshot(
            Map<String, RuuviSample> snapshot) {
        List<Map.Entry<String, RuuviSample>> entries = new ArrayList<>(snapshot.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        return entries;
    }

    private void renderPlacesView() {
        if (favoritePlacesList == null) return;
        favoritePlacesList.removeAllViews();
        List<String> favorites = SettingsManager.get().getFavoritePlaces();
        updateFavoriteButton();

        if (favorites.isEmpty()) {
            favoritePlacesList.addView(textCard("Ei suosikkipaikkoja. Lisää nykyinen sääpaikka etusivun sydämestä."));
            return;
        }
        for (String favorite : favorites) {
            favoritePlacesList.addView(favoritePlaceRow(favorite));
        }
    }

    private String currentDisplayPlace() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String display = prefs.getString(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME, "");
        if (display != null && !display.trim().isEmpty()) return display.trim();
        return SettingsManager.get().getHomePlace();
    }

    private void updateFavoriteButton() {
        if (weatherFavoriteButton == null) return;
        String place = currentDisplayPlace();
        boolean favorite = SettingsManager.get().isFavoritePlace(place);
        weatherFavoriteButton.setImageResource(favorite
                ? R.drawable.mobile_ic_favorite_24
                : R.drawable.mobile_ic_favorite_add_24);
        weatherFavoriteButton.setContentDescription(getString(favorite
                ? R.string.mobile_favorite_remove
                : R.string.mobile_favorite_add));
        if (favorite) {
            weatherFavoriteButton.setColorFilter(0xFFE0526B);
        } else {
            weatherFavoriteButton.clearColorFilter();
        }
    }

    private void showPlaceSearchDialog() {
        if (placeSearchDialog != null && placeSearchDialog.isShowing()) {
            placeSearchDialog.show();
            return;
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        content.setPadding(pad, dp(10), pad, 0);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Hae kaupunkia tai kaupunginosaa");
        input.setTextColor(getColor(R.color.mobile_text_primary));
        input.setHintTextColor(getColor(R.color.mobile_text_muted));
        input.setTextSize(16);
        input.setSelectAllOnFocus(false);
        content.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        TextView status = rowText("Kirjoita vähintään kolme kirjainta.", 13, false);
        status.setTextColor(getColor(R.color.mobile_text_muted));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0, dp(8), 0, dp(8));
        content.addView(status, statusLp);

        ScrollView resultScroll = new ScrollView(this);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        resultScroll.addView(results);
        content.addView(resultScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(260)));

        placeSearchInput = input;
        placeSearchStatusText = status;
        placeSuggestionsList = results;

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                main.removeCallbacks(placeSearchDebounce);
                main.postDelayed(placeSearchDebounce, 120L);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        placeSearchDialog = new AlertDialog.Builder(this)
                .setTitle("Hae sääpaikka")
                .setView(content)
                .setNegativeButton("Sulje", null)
                .create();
        placeSearchDialog.setOnDismissListener(dialog -> {
            main.removeCallbacks(placeSearchDebounce);
            hideKeyboardAndClearFocus();
            placeSearchInput = null;
            placeSearchStatusText = null;
            placeSuggestionsList = null;
            placeSearchDialog = null;
        });
        placeSearchDialog.setOnShowListener(dialog -> {
            if (placeSearchDialog.getWindow() != null) {
                placeSearchDialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
            input.requestFocus();
            main.postDelayed(() -> {
                if (placeSearchInput != input || destroyed || isFinishing() || isDestroyed()) return;
                input.requestFocus();
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }, 180L);
        });
        placeSearchDialog.show();
    }

    private void dismissPlaceSearchDialog() {
        if (placeSearchDialog != null && placeSearchDialog.isShowing()) {
            placeSearchDialog.dismiss();
        } else {
            hideKeyboardAndClearFocus();
        }
    }

    private void hideKeyboardAndClearFocus() {
        View focus = getCurrentFocus();
        if (focus == null) focus = scroll;
        if (focus != null) {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        }
    }

    private View favoritePlaceRow(String place) {
        LinearLayout row = cardContainer();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOnClickListener(v -> selectFavoritePlace(place));

        TextView name = rowText(place, 16, true);
        row.addView(name, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton remove = favoriteRemoveButton();
        remove.setOnClickListener(v -> {
            SettingsManager.get().removeFavoritePlace(place);
            updateFavoriteButton();
            renderPlacesView();
        });
        row.addView(remove);
        return row;
    }

    private void selectFavoritePlace(String favorite) {
        if (favorite == null || favorite.trim().isEmpty()) return;
        LocationPlace parsed = parseStoredFavorite(favorite);
        showHome();
        selectHomePlace(parsed.dataPlace, false, parsed.displayPlace,
                parsed.latitude, parsed.longitude);
        resolvePlaceCoordinatesInBackground(parsed);
    }

    private LocationPlace parseStoredFavorite(String favorite) {
        String display = favorite.trim();
        String data = dataPlaceFromDisplay(display);
        return new LocationPlace(data, display, Double.NaN, Double.NaN);
    }

    private View placeSuggestionRow(LocationPlace place) {
        LinearLayout row = cardContainer();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOnClickListener(v -> selectSuggestedPlace(place));

        TextView name = rowText(place.displayPlace, 16, true);
        row.addView(name, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void selectSuggestedPlace(LocationPlace place) {
        if (place == null) return;
        showHome();
        selectHomePlace(place.dataPlace, false, place.displayPlace,
                place.latitude, place.longitude);
        if (Double.isNaN(place.latitude) || Double.isNaN(place.longitude)) {
            resolvePlaceCoordinatesInBackground(place);
        }
    }

    private void resolvePlaceCoordinatesInBackground(LocationPlace place) {
        if (place == null || place.displayPlace == null || place.displayPlace.trim().isEmpty()) return;
        io.execute(() -> {
            LocationPlace resolved = place;
            try {
                List<LocationPlace> matches = geocodeSearchPlaces(place.displayPlace);
                if (!matches.isEmpty()) resolved = matches.get(0);
            } catch (Exception ignored) {
            }
            LocationPlace finalResolved = resolved;
            main.post(() -> {
                if (destroyed || isFinishing() || isDestroyed()) return;
                if (Double.isNaN(finalResolved.latitude) || Double.isNaN(finalResolved.longitude)) return;
                String currentDataPlace = SettingsManager.get().getHomePlace();
                if (!finalResolved.dataPlace.equalsIgnoreCase(currentDataPlace)
                        && !dataPlaceFromDisplay(finalResolved.displayPlace).equalsIgnoreCase(currentDataPlace)) {
                    return;
                }
                SettingsManager.get().setHomeCoordinates(finalResolved.latitude, finalResolved.longitude);
                GeoPlace.register(finalResolved.dataPlace, finalResolved.latitude, finalResolved.longitude);
                GeoPlace.register(finalResolved.displayPlace, finalResolved.latitude, finalResolved.longitude);
                refreshAll(false);
            });
        });
    }

    private ImageButton favoriteRemoveButton() {
        ImageButton button = new ImageButton(this);
        button.setBackgroundResource(R.drawable.mobile_menu_item_bg);
        button.setImageResource(R.drawable.mobile_ic_favorite_24);
        button.setColorFilter(0xFFE0526B);
        button.setContentDescription(getString(R.string.mobile_favorite_remove));
        button.setPadding(dp(11), dp(11), dp(11), dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
        lp.setMarginStart(dp(8));
        button.setLayoutParams(lp);
        return button;
    }

    private TextView smallAction(String label) {
        TextView tv = rowText(label, 14, true);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundResource(R.drawable.mobile_menu_item_bg);
        tv.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(8));
        tv.setLayoutParams(lp);
        return tv;
    }

    private void searchPlaces() {
        if (placeSearchInput == null || placeSearchStatusText == null || placeSuggestionsList == null) {
            return;
        }
        String query = placeSearchInput.getText() == null
                ? ""
                : placeSearchInput.getText().toString().trim();
        if (query.length() < 3) {
            placeSuggestionsList.removeAllViews();
            placeSearchStatusText.setText(query.isEmpty()
                    ? "Hae kaupunkia tai kaupunginosaa."
                    : "Kirjoita vähintään kolme kirjainta.");
            return;
        }
        final String searchQuery = query;
        String needle = normalizeSearch(searchQuery);
        double[] searchReference = searchReferenceCoordinates();
        placeSuggestionsList.removeAllViews();
        List<LocationPlace> localMatches = new ArrayList<>();
        addSeededPlaceMatches(localMatches, needle);
        if (!localMatches.isEmpty()) {
            sortPlaceMatches(localMatches, searchReference);
            if (localMatches.size() > 5) {
                localMatches = new ArrayList<>(localMatches.subList(0, 5));
            }
            placeSearchStatusText.setText("Tarkennetaan hakua...");
            for (LocationPlace match : localMatches) {
                placeSuggestionsList.addView(placeSuggestionRow(match));
            }
        } else {
            placeSearchStatusText.setText("Haetaan...");
        }
        io.execute(() -> {
            List<LocationPlace> matches = new ArrayList<>();
            Exception failure = null;
            try {
                for (MmlGeocodingClient.MmlPlace mmlPlace
                        : MmlGeocodingClient.searchPlaces(searchQuery, 20)) {
                    addPlaceMatch(matches, locationPlaceFromMml(mmlPlace));
                }
                addSeededPlaceMatches(matches, needle);
                if (matches.isEmpty()) {
                    try {
                        String[] names = FmiPlaceSearch.fetchCityNames();
                        for (String name : names) {
                            if (normalizeSearch(name).contains(needle)) {
                                addPlaceMatch(matches, new LocationPlace(name, name, Double.NaN, Double.NaN));
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    if (matches.isEmpty()) {
                        for (LocationPlace geocoded : geocodeSearchPlaces(searchQuery)) {
                            addPlaceMatch(matches, geocoded);
                        }
                    }
                }
                sortPlaceMatches(matches, searchReference);
                if (matches.size() > 5) {
                    matches = new ArrayList<>(matches.subList(0, 5));
                }
            } catch (Exception e) {
                failure = e;
            }
            List<LocationPlace> finalMatches = matches;
            Exception finalFailure = failure;
            main.post(() -> {
                if (destroyed || isFinishing() || isDestroyed()) return;
                if (placeSearchInput == null || placeSuggestionsList == null
                        || placeSearchStatusText == null) return;
                String currentQuery = placeSearchInput.getText() == null
                        ? ""
                        : placeSearchInput.getText().toString().trim();
                if (!searchQuery.equals(currentQuery)) return;
                placeSuggestionsList.removeAllViews();
                if (finalFailure != null) {
                    placeSearchStatusText.setText("Haku epäonnistui: " + safeMessage(finalFailure));
                    return;
                }
                if (finalMatches.isEmpty()) {
                    placeSearchStatusText.setText("Kaupunkeja ei löytynyt.");
                    return;
                }
                placeSearchStatusText.setText("Valitse paikka listalta.");
                for (LocationPlace match : finalMatches) {
                    placeSuggestionsList.addView(placeSuggestionRow(match));
                }
            });
        });
    }

    private double[] searchReferenceCoordinates() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        long savedAt = prefs.getLong(MobileThemeController.KEY_LAST_DEVICE_LOCATION_TIME, 0L);
        if (savedAt > 0L && (System.currentTimeMillis() - savedAt) <= SEARCH_REFERENCE_MAX_AGE_MS) {
            float lat = prefs.getFloat(MobileThemeController.KEY_LAST_DEVICE_LATITUDE, Float.NaN);
            float lon = prefs.getFloat(MobileThemeController.KEY_LAST_DEVICE_LONGITUDE, Float.NaN);
            if (!Float.isNaN(lat) && !Float.isNaN(lon)) {
                return new double[]{lat, lon};
            }
        }
        SettingsManager sm = SettingsManager.get();
        if (sm.hasHomeCoordinates()
                && prefs.getBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, false)) {
            return new double[]{sm.getHomeLatitude(), sm.getHomeLongitude()};
        }
        return null;
    }

    private double[] trafficReferenceCoordinates() {
        double[] saved = searchReferenceCoordinates();
        if (saved != null) return saved;
        if (hasLocationPermission()) {
            Location location = readBestLocation();
            if (location != null) {
                rememberDeviceLocation(location);
                return new double[]{location.getLatitude(), location.getLongitude()};
            }
        }
        SettingsManager sm = SettingsManager.get();
        if (sm.hasHomeCoordinates()) {
            return new double[]{sm.getHomeLatitude(), sm.getHomeLongitude()};
        }
        return null;
    }

    private static void sortPlaceMatches(List<LocationPlace> matches, double[] reference) {
        Collator collator = Collator.getInstance(FI);
        collator.setStrength(Collator.PRIMARY);
        matches.sort((a, b) -> {
            double da = placeDistanceMeters(a, reference);
            double db = placeDistanceMeters(b, reference);
            boolean aKnown = !Double.isNaN(da);
            boolean bKnown = !Double.isNaN(db);
            if (aKnown && bKnown) {
                int byDistance = Double.compare(da, db);
                if (byDistance != 0) return byDistance;
            } else if (aKnown) {
                return -1;
            } else if (bKnown) {
                return 1;
            }
            return collator.compare(a.displayPlace, b.displayPlace);
        });
    }

    private static double placeDistanceMeters(LocationPlace place, double[] reference) {
        if (place == null || reference == null) return Double.NaN;
        double lat = place.latitude;
        double lon = place.longitude;
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            GeoPlace geo = GeoPlace.tryForPlace(place.displayPlace);
            if (geo == null) geo = GeoPlace.tryForPlace(place.dataPlace);
            if (geo != null) {
                lat = geo.latitude;
                lon = geo.longitude;
            }
        }
        if (Double.isNaN(lat) || Double.isNaN(lon)) return Double.NaN;
        float[] result = new float[1];
        Location.distanceBetween(reference[0], reference[1], lat, lon, result);
        return result[0];
    }

    private static void addSeededPlaceMatches(List<LocationPlace> matches, String needle) {
        if (needle == null || needle.trim().isEmpty()) return;
        for (String[] seed : FINNISH_DISTRICT_SEEDS) {
            String city = seed[0];
            String district = seed[1];
            String display = city + ", " + district;
            if (normalizeSearch(display).contains(needle)
                    || normalizeSearch(district).contains(needle)) {
                addPlaceMatch(matches,
                        new LocationPlace(city, display, Double.NaN, Double.NaN));
            }
        }
    }

    private List<LocationPlace> geocodeSearchPlaces(String query) throws Exception {
        List<LocationPlace> out = new ArrayList<>();
        for (MmlGeocodingClient.MmlPlace mmlPlace : MmlGeocodingClient.searchPlaces(query, 5)) {
            addPlaceMatch(out, locationPlaceFromMml(mmlPlace));
        }
        if (!out.isEmpty()) return out;
        if (!Geocoder.isPresent()) return out;
        Geocoder geocoder = new Geocoder(this, FI);
        List<Address> addresses = geocoder.getFromLocationName(query + ", Suomi", 5);
        if (addresses == null) return out;
        for (Address address : addresses) {
            LocationPlace place = locationPlaceFromAddress(address);
            if (place != null) addPlaceMatch(out, place);
        }
        return out;
    }

    private static LocationPlace locationPlaceFromMml(MmlGeocodingClient.MmlPlace place) {
        if (place == null) return null;
        return new LocationPlace(place.dataPlace, place.displayPlace,
                place.latitude, place.longitude);
    }

    private static void addPlaceMatch(List<LocationPlace> matches, LocationPlace place) {
        if (place == null || place.dataPlace == null || place.dataPlace.trim().isEmpty()) return;
        String key = normalizeSearch(place.displayPlace);
        for (LocationPlace existing : matches) {
            if (normalizeSearch(existing.displayPlace).equals(key)) return;
        }
        matches.add(place);
    }

    private void toggleCurrentFavorite() {
        String place = currentDisplayPlace();
        if (SettingsManager.get().isFavoritePlace(place)) {
            SettingsManager.get().removeFavoritePlace(place);
            Toast.makeText(this, "Poistettu suosikeista", Toast.LENGTH_SHORT).show();
        } else {
            SettingsManager.get().addFavoritePlace(place);
            Toast.makeText(this, "Lisätty suosikiksi", Toast.LENGTH_SHORT).show();
        }
        updateFavoriteButton();
        renderPlacesView();
    }

    private void selectHomePlace(String place, boolean fromLocation) {
        selectHomePlace(place, fromLocation, null, Double.NaN, Double.NaN);
    }

    private void selectHomePlace(String place, boolean fromLocation, String displayPlace,
                                 double latitude, double longitude) {
        if (place == null || place.trim().isEmpty()) return;
        dismissPlaceSearchDialog();
        String dataPlace = place.trim();
        SettingsManager.get().setHomePlace(dataPlace);
        if (!Double.isNaN(latitude) && !Double.isNaN(longitude)) {
            SettingsManager.get().setHomeCoordinates(latitude, longitude);
            GeoPlace.register(dataPlace, latitude, longitude);
            if (displayPlace != null && !displayPlace.trim().isEmpty()) {
                GeoPlace.register(displayPlace.trim(), latitude, longitude);
            }
        } else {
            SettingsManager.get().clearHomeCoordinates();
        }
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
        if (!fromLocation) {
            editor.putBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, false);
            if (displayPlace != null
                    && !displayPlace.trim().isEmpty()
                    && !displayPlace.trim().equalsIgnoreCase(dataPlace)) {
                editor.putString(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME,
                        displayPlace.trim());
            } else {
                editor.remove(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME);
            }
        } else if (displayPlace != null && !displayPlace.trim().isEmpty()) {
            editor.putString(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME,
                    displayPlace.trim());
        } else {
            editor.remove(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME);
        }
        editor.apply();
        weather = null;
        openMeteo = null;
        trafficNotices = new ArrayList<>();
        selectedForecastDayKey = 0;
        placeText.setText(currentDisplayPlace());
        updateFavoriteButton();
        renderPlacesView();
        refreshAll(false);
    }

    private void toggleDrawer() {
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
        else openDrawer();
    }

    private void openDrawer() {
        drawerScrim.setVisibility(View.VISIBLE);
        drawer.setVisibility(View.VISIBLE);
    }

    private void closeDrawer() {
        drawerScrim.setVisibility(View.GONE);
        drawer.setVisibility(View.GONE);
    }

    private void showSection(View section, String title) {
        // Kelikamerat on MapView-näkymä ScrollView:n ulkopuolella — vaihda koko scroll-alue
        // ja karttanäkymä keskenään, ja luo karttafragment lazysti ensiavauksella.
        boolean cameras = (roadCamerasView != null && section == roadCamerasView);
        scroll.setVisibility(cameras ? View.GONE : View.VISIBLE);
        if (roadCamerasView != null) {
            roadCamerasView.setVisibility(cameras ? View.VISIBLE : View.GONE);
        }
        if (cameras) ensureRoadCamerasFragment();
        if (placesView != null
                && placesView.getVisibility() == View.VISIBLE
                && section != placesView) {
            hideKeyboardAndClearFocus();
        }
        homeView.setVisibility(section == homeView ? View.VISIBLE : View.GONE);
        forecastView.setVisibility(section == forecastView ? View.VISIBLE : View.GONE);
        electricityView.setVisibility(section == electricityView ? View.VISIBLE : View.GONE);
        sensorsView.setVisibility(section == sensorsView ? View.VISIBLE : View.GONE);
        trafficView.setVisibility(section == trafficView ? View.VISIBLE : View.GONE);
        placesView.setVisibility(section == placesView ? View.VISIBLE : View.GONE);
        if (speedometerView != null) {
            speedometerView.setVisibility(section == speedometerView ? View.VISIBLE : View.GONE);
        }
        if (newsView != null) {
            newsView.setVisibility(section == newsView ? View.VISIBLE : View.GONE);
        }
        toolbarTitle.setText(getString(R.string.app_mobile_name));
        scroll.scrollTo(0, 0);
        updateGpsListenerState();
    }

    private void openElectricitySection(int dayOffset) {
        selectedElectricityDayOffset = dayOffset == 1 ? 1 : 0;
        showSection(electricityView, getString(R.string.mobile_menu_electricity));
        renderElectricityView();
    }

    private void showHome() {
        showSection(homeView, getString(R.string.app_mobile_name));
    }

    private void showRoadCameras() {
        showSection(roadCamerasView, getString(R.string.mobile_widget_road_cameras));
    }

    private void ensureRoadCamerasFragment() {
        if (getSupportFragmentManager().findFragmentById(R.id.mobile_road_cameras_view) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.mobile_road_cameras_view, new RoadCamerasFragment())
                    .commit();
        }
    }

    private void showSpeedometer() {
        showSection(speedometerView, "GPS-nopeus");
        renderSpeedometerView();
    }

    private void showNews() {
        newsViewFeedId = null;
        showSection(newsView, "Uutiset");
        renderNewsView();
        refreshNewsAsync(false);
    }

    /** Avaa yhden uutislähteen oman sivun (kuten Uutiset-kokosivu, mutta vain yksi
     *  syöte, 50 uusinta). Toimii automaattisesti myös käyttäjän omille RSS-syötteille. */
    private void showNewsForFeed(String feedId, String feedName) {
        if (feedId == null) return;
        newsViewFeedId = feedId;
        showSection(newsView, feedName != null ? feedName : "Uutiset");
        renderNewsView();
        refreshNewsAsync(false);
    }

    /** Hidas, animoitu liuku sivun ylälaitaan (back-to-top -napille). */
    private void smoothScrollToTop() {
        if (scroll == null) return;
        int from = scroll.getScrollY();
        if (from <= 0) return;
        ObjectAnimator anim = ObjectAnimator.ofInt(scroll, "scrollY", from, 0);
        anim.setDuration(450L);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        anim.start();
    }

    /** Onko vähintään yksi per-lähde-uutiswidget asetettu näkyväksi etusivulle. */
    private boolean anyNewsFeedWidgetEnabled(SharedPreferences prefs) {
        for (NewsFeed f : NewsFeedStore.allFeeds(prefs)) {
            if (prefs.getBoolean(MobileThemeController.newsFeedVisibilityKey(f.id), false)) {
                return true;
            }
        }
        return false;
    }

    private void startRefreshAnimation() {
        if (refreshAnimator != null) {
            refreshAnimator.cancel();
        }
        refreshAnimationStartedAt = System.currentTimeMillis();
        refreshAnimator = ObjectAnimator.ofFloat(refreshButton, View.ROTATION, 0f, 360f);
        refreshAnimator.setDuration(700L);
        refreshAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        refreshAnimator.setInterpolator(new LinearInterpolator());
        refreshAnimator.start();
    }

    private void stopRefreshAnimation() {
        if (refreshAnimator != null) {
            long elapsed = System.currentTimeMillis() - refreshAnimationStartedAt;
            if (elapsed < 700L && !destroyed) {
                main.postDelayed(this::stopRefreshAnimation, 700L - elapsed);
                return;
            }
        }
        if (refreshAnimator != null) {
            refreshAnimator.cancel();
            refreshAnimator = null;
        }
        if (refreshButton != null) refreshButton.setRotation(0f);
    }

    private void refreshHolidayInfo() {
        io.execute(() -> {
            MobileHolidayProvider.HolidayEvent event =
                    MobileHolidayProvider.next(Calendar.getInstance(HELSINKI, FI));
            main.post(() -> {
                if (destroyed || isFinishing() || isDestroyed()) return;
                nextHoliday = event;
                renderClock();
            });
        });
    }

    private void scheduleTomorrowPricePolling() {
        main.removeCallbacks(tomorrowPricePoll);
        if (destroyed) return;
        if (ElectricityRepository.get(this).hasTomorrow()) return;
        long delay = nextTomorrowPricePollDelayMs();
        if (delay >= 0L) main.postDelayed(tomorrowPricePoll, delay);
    }

    private void scheduleAutoRefresh() {
        main.removeCallbacks(autoRefreshTick);
        if (destroyed || isFinishing() || isDestroyed()) return;
        main.postDelayed(autoRefreshTick, autoRefreshIntervalMs());
    }

    private long autoRefreshIntervalMs() {
        String value = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(MobileThemeController.KEY_UPDATE_INTERVAL_MINUTES,
                        MobileThemeController.DEFAULT_UPDATE_INTERVAL_MINUTES);
        long minutes;
        try {
            minutes = Long.parseLong(value);
        } catch (NumberFormatException e) {
            minutes = Long.parseLong(MobileThemeController.DEFAULT_UPDATE_INTERVAL_MINUTES);
        }
        long ms = minutes * 60_000L;
        return Math.max(MIN_AUTO_REFRESH_MS, Math.min(MAX_AUTO_REFRESH_MS, ms));
    }

    private long nextTomorrowPricePollDelayMs() {
        Calendar now = Calendar.getInstance(HELSINKI, FI);
        Calendar publish = Calendar.getInstance(HELSINKI, FI);
        publish.set(Calendar.HOUR_OF_DAY, TOMORROW_PRICE_PUBLISH_HOUR);
        publish.set(Calendar.MINUTE, TOMORROW_PRICE_PUBLISH_MINUTE);
        publish.set(Calendar.SECOND, 0);
        publish.set(Calendar.MILLISECOND, 0);
        if (now.before(publish)) {
            return Math.max(1_000L, publish.getTimeInMillis() - now.getTimeInMillis());
        }
        if (!isTomorrowPollWindowNow()) return -1L;
        if (lastTomorrowPriceFetchAttemptMs <= 0L) return 0L;
        long elapsed = now.getTimeInMillis() - lastTomorrowPriceFetchAttemptMs;
        return Math.max(0L, TOMORROW_PRICE_POLL_MS - elapsed);
    }

    private boolean isBeforeTomorrowPublishTime() {
        Calendar now = Calendar.getInstance(HELSINKI, FI);
        return now.get(Calendar.HOUR_OF_DAY) < TOMORROW_PRICE_PUBLISH_HOUR
                || (now.get(Calendar.HOUR_OF_DAY) == TOMORROW_PRICE_PUBLISH_HOUR
                && now.get(Calendar.MINUTE) < TOMORROW_PRICE_PUBLISH_MINUTE);
    }

    private boolean isTomorrowPollWindowNow() {
        Calendar now = Calendar.getInstance(HELSINKI, FI);
        int hour = now.get(Calendar.HOUR_OF_DAY);
        int minute = now.get(Calendar.MINUTE);
        if (hour < TOMORROW_PRICE_PUBLISH_HOUR) return false;
        if (hour == TOMORROW_PRICE_PUBLISH_HOUR
                && minute < TOMORROW_PRICE_PUBLISH_MINUTE) return false;
        return hour < TOMORROW_PRICE_POLL_END_HOUR;
    }

    private void fetchTomorrowPricesNow() {
        if (tomorrowPriceFetchInFlight) return;
        tomorrowPriceFetchInFlight = true;
        lastTomorrowPriceFetchAttemptMs = System.currentTimeMillis();
        if (selectedElectricityDayOffset == 1) renderElectricityView();
        io.execute(() -> {
            ElectricityData fresh = null;
            try {
                fresh = ElectricityRepository.get(this).fetchNow();
            } catch (Exception ignored) {
            }
            ElectricityData finalFresh = fresh;
            main.post(() -> {
                tomorrowPriceFetchInFlight = false;
                if (destroyed || isFinishing() || isDestroyed()) return;
                if (finalFresh != null) electricity = finalFresh;
                renderElectricity(electricity != null
                        ? electricity
                        : ElectricityRepository.get(this).peek());
                renderElectricityView();
                scheduleTomorrowPricePolling();
            });
        });
    }

    private boolean maybeRequestInitialLocationPermission() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean(MobileThemeController.KEY_INITIAL_LOCATION_PERMISSION_ASKED, false)) {
            return false;
        }
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(MobileThemeController.KEY_INITIAL_LOCATION_PERMISSION_ASKED, true)
                .putBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, true);
        editor.apply();
        if (hasPreciseLocationPermission()) {
            maybeUpdatePlaceFromLocation();
            return true;
        }
        requestLocationPermissionFromMain();
        return true;
    }

    private void maybeUpdatePlaceFromLocation() {
        maybeUpdatePlaceFromLocation(false);
    }

    private void maybeUpdatePlaceFromLocation(boolean refreshWhenUnchanged) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (!prefs.getBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, false)) return;
        if (!hasPreciseLocationPermission()) {
            requestLocationPermissionFromMain();
            if (placeSearchStatusText != null) {
                placeSearchStatusText.setText("Salli tarkka sijainti, jotta kaupunginosa voidaan hakea oikein.");
            }
            if (statusText != null) {
                statusText.setText("Salli tarkka sijainti, jotta nykyinen paikka voidaan hakea.");
            }
            return;
        }
        io.execute(() -> {
            LocationPlace place = null;
            try {
                Location location = readBestLocation();
                if (location != null) {
                    rememberDeviceLocation(location);
                    place = reverseGeocodePlace(location);
                }
            } catch (Exception ignored) {
            }
            LocationPlace finalPlace = place;
            main.post(() -> {
                if (destroyed || isFinishing() || isDestroyed()) return;
                if (finalPlace == null || finalPlace.dataPlace.trim().isEmpty()) {
                    String message = "Sijainnista ei saatu paikkakuntaa. Manuaalinen haku toimii normaalisti.";
                    if (placeSearchStatusText != null) {
                        placeSearchStatusText.setText(message);
                    }
                    if (statusText != null) statusText.setText(message);
                    return;
                }
                SharedPreferences currentPrefs = PreferenceManager.getDefaultSharedPreferences(this);
                String currentDisplay = currentPrefs.getString(
                        MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME, "");
                boolean dataChanged = !finalPlace.dataPlace.equalsIgnoreCase(
                        SettingsManager.get().getHomePlace());
                boolean displayChanged = !finalPlace.displayPlace.equalsIgnoreCase(currentDisplay);
                if (dataChanged || displayChanged) {
                    selectHomePlace(finalPlace.dataPlace, true, finalPlace.displayPlace,
                            finalPlace.latitude, finalPlace.longitude);
                } else {
                    placeText.setText(currentDisplayPlace());
                    renderPlacesView();
                    if (refreshWhenUnchanged) {
                        refreshAll(false);
                    }
                }
            });
        });
    }

    private void locateCurrentPlaceFromToolbar() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, true)
                .apply();
        if (statusText != null) statusText.setText("Paikannetaan nykyistä sijaintia...");
        maybeUpdatePlaceFromLocation(true);
    }

    private void rememberDeviceLocation(Location location) {
        if (location == null) return;
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putFloat(MobileThemeController.KEY_LAST_DEVICE_LATITUDE,
                        (float) location.getLatitude())
                .putFloat(MobileThemeController.KEY_LAST_DEVICE_LONGITUDE,
                        (float) location.getLongitude())
                .putLong(MobileThemeController.KEY_LAST_DEVICE_LOCATION_TIME,
                        location.getTime() > 0 ? location.getTime() : System.currentTimeMillis())
                .apply();
    }

    private boolean hasLocationPermission() {
        return hasPreciseLocationPermission();
    }

    private boolean hasPreciseLocationPermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasApproximateLocationPermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermissionFromMain() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean(MobileThemeController.KEY_INITIAL_LOCATION_PERMISSION_ASKED, true)
                .apply();
        ActivityCompat.requestPermissions(this,
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                },
                LOCATION_PERMISSION_REQUEST);
    }

    private Location readBestLocation() {
        Location fused = readFusedCurrentLocation();
        if (isUsableAutoLocation(fused)) return fused;

        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        try {
            best = betterLocation(best, lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER));
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
        try {
            best = betterLocation(best, lm.getLastKnownLocation(LocationManager.GPS_PROVIDER));
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
        Location current = readCurrentLocation(lm);
        Location resolved = betterLocation(best, current);
        return isUsableAutoLocation(resolved) ? resolved : null;
    }

    private Location readFusedCurrentLocation() {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Location> ref = new AtomicReference<>();
        CancellationTokenSource tokenSource = new CancellationTokenSource();
        try {
            FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(this);
            CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setGranularity(Granularity.GRANULARITY_FINE)
                    .setMaxUpdateAgeMillis(FUSED_LOCATION_FAST_MAX_AGE_MS)
                    .setDurationMillis(CURRENT_LOCATION_TIMEOUT_MS)
                    .build();
            client.getCurrentLocation(request, tokenSource.getToken())
                    .addOnSuccessListener(location -> {
                        ref.set(location);
                        latch.countDown();
                    })
                    .addOnFailureListener(e -> latch.countDown())
                    .addOnCanceledListener(latch::countDown);
            if (!latch.await(CURRENT_LOCATION_TIMEOUT_MS + 1000L, TimeUnit.MILLISECONDS)) {
                tokenSource.cancel();
            }
        } catch (InterruptedException ignored) {
            tokenSource.cancel();
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            tokenSource.cancel();
        }
        return ref.get();
    }

    private Location readCurrentLocation(LocationManager lm) {
        List<String> providers = enabledLocationProviders(lm);
        if (providers.isEmpty()) return null;
        CountDownLatch latch = new CountDownLatch(providers.size());
        AtomicReference<Location> ref = new AtomicReference<>();
        List<CancellationSignal> signals = new ArrayList<>();
        try {
            for (String provider : providers) {
                CancellationSignal signal = new CancellationSignal();
                signals.add(signal);
                lm.getCurrentLocation(provider, signal, getMainExecutor(), location -> {
                    if (location != null) {
                        ref.set(betterLocation(ref.get(), location));
                    }
                    latch.countDown();
                });
            }
            if (!latch.await(CURRENT_LOCATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                for (CancellationSignal signal : signals) signal.cancel();
            }
        } catch (SecurityException | IllegalArgumentException | InterruptedException ignored) {
            for (CancellationSignal signal : signals) signal.cancel();
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return ref.get();
    }

    private static List<String> enabledLocationProviders(LocationManager lm) {
        List<String> providers = new ArrayList<>();
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                providers.add(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Exception ignored) {
        }
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                providers.add(LocationManager.GPS_PROVIDER);
            }
        } catch (Exception ignored) {
        }
        return providers;
    }

    private static Location betterLocation(Location current, Location candidate) {
        if (!isUsableAutoLocation(candidate)) return current;
        if (candidate == null) return current;
        if (!isUsableAutoLocation(current)) return candidate;
        if (current == null) return candidate;

        float candidateAccuracy = accuracyOrMax(candidate);
        float currentAccuracy = accuracyOrMax(current);
        if (candidateAccuracy + LOCATION_ACCURACY_MARGIN_M < currentAccuracy) {
            return candidate;
        }
        if (candidate.getTime() > current.getTime() + LOCATION_TIME_MARGIN_MS) {
            return candidate;
        }
        if (candidateAccuracy <= currentAccuracy
                && candidate.getTime() >= current.getTime() - LOCATION_TIME_MARGIN_MS) {
            return candidate;
        }
        return current;
    }

    private static boolean isUsableAutoLocation(Location location) {
        if (location == null) return false;
        long age = System.currentTimeMillis() - location.getTime();
        if (age < 0 || age > MAX_AUTO_LOCATION_AGE_MS) return false;
        return !location.hasAccuracy() || location.getAccuracy() <= MAX_AUTO_LOCATION_ACCURACY_M;
    }

    private static float accuracyOrMax(Location location) {
        return location != null && location.hasAccuracy()
                ? location.getAccuracy()
                : MAX_AUTO_LOCATION_ACCURACY_M;
    }

    private LocationPlace reverseGeocodePlace(Location location) throws IOException {
        try {
            MmlGeocodingClient.MmlPlace mmlPlace = MmlGeocodingClient.reversePlace(
                    location.getLatitude(), location.getLongitude());
            LocationPlace place = locationPlaceFromMml(mmlPlace);
            if (place != null) return place;
        } catch (Exception ignored) {
        }
        if (!Geocoder.isPresent()) return null;
        Geocoder geocoder = new Geocoder(this, FI);
        List<Address> addresses = geocoder.getFromLocation(
                location.getLatitude(), location.getLongitude(), 1);
        if (addresses == null || addresses.isEmpty()) return null;
        return locationPlaceFromAddress(addresses.get(0));
    }

    private static LocationPlace locationPlaceFromAddress(Address a) {
        if (a == null) return null;
        String locality = a.getLocality();
        if (locality == null || locality.trim().isEmpty()) locality = a.getSubAdminArea();
        if (locality == null || locality.trim().isEmpty()) locality = a.getAdminArea();
        if (locality == null || locality.trim().isEmpty()) return null;
        String city = locality.trim();
        String area = a.getSubLocality();
        if (area == null || area.trim().isEmpty()) area = a.getFeatureName();
        String display = city;
        if (area != null) {
            String cleanArea = area.trim();
            if (!cleanArea.isEmpty()
                    && !cleanArea.equalsIgnoreCase(city)
                    && !cleanArea.matches("\\d+")) {
                display = city + ", " + cleanArea;
            }
        }
        return new LocationPlace(city, display, a.getLatitude(), a.getLongitude());
    }

    private static String dataPlaceFromDisplay(String display) {
        if (display == null) return SettingsManager.DEFAULT_HOME_PLACE;
        String trimmed = display.trim();
        int comma = trimmed.indexOf(',');
        if (comma > 0) trimmed = trimmed.substring(0, comma).trim();
        return trimmed.isEmpty() ? SettingsManager.DEFAULT_HOME_PLACE : trimmed;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_PERMISSION_REQUEST) return;
        boolean precise = permissionGranted(permissions, grantResults,
                Manifest.permission.ACCESS_FINE_LOCATION);
        boolean coarse = permissionGranted(permissions, grantResults,
                Manifest.permission.ACCESS_COARSE_LOCATION);
        if (precise) {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putBoolean(MobileThemeController.KEY_INITIAL_LOCATION_PERMISSION_ASKED, true)
                    .putBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, true)
                    .apply();
            maybeUpdatePlaceFromLocation();
        } else {
            SettingsManager.get().clearHomeCoordinates();
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putBoolean(MobileThemeController.KEY_INITIAL_LOCATION_PERMISSION_ASKED, true)
                    .putBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, false)
                    .remove(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME)
                    .apply();
            if (placeSearchStatusText != null) {
                placeSearchStatusText.setText(coarse
                        ? "Tarkka sijainti ei ole käytössä. Voit vaihtaa luvan Tarkaksi Androidin asetuksista."
                        : "Sijaintilupaa ei annettu. Voit valita kaupungin haulla.");
            }
            renderPlacesView();
        }
    }

    private static boolean permissionGranted(String[] permissions, int[] grantResults,
                                             String permission) {
        if (permissions == null || grantResults == null || permission == null) return false;
        int count = Math.min(permissions.length, grantResults.length);
        for (int i = 0; i < count; i++) {
            if (permission.equals(permissions[i])) {
                return grantResults[i] == PackageManager.PERMISSION_GRANTED;
            }
        }
        return false;
    }

    private boolean hasBluetoothScanPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private static ElectricityData.Quarter currentQuarterFrom(ElectricityData data) {
        long now = System.currentTimeMillis();
        for (ElectricityData.Quarter q : data.quarters) {
            if (q.timestamp <= now && q.timestamp + 15L * 60_000L > now) return q;
        }
        return null;
    }

    private String nextHolidayLine(Calendar now) {
        MobileHolidayProvider.HolidayEvent event = nextHoliday;
        String holidayLine;
        if (event == null) {
            List<FinnishHolidays.Holiday> fallback = FinnishHolidays.upcoming(now, 1);
            if (fallback.isEmpty()) {
                holidayLine = "";
            } else {
                FinnishHolidays.Holiday h = fallback.get(0);
                holidayLine = "Seuraava pyhä: " + h.name + " " + h.day + "." + h.month + ".";
            }
        } else {
            holidayLine = "Seuraava pyhä: " + event.name + " " + formatIsoDate(event.date);
        }
        OfficialFlagDay flagDay = nextOfficialFlagDay(now);
        String flagLine = flagDay == null
                ? ""
                : "Seuraava virallinen liputuspäivä: " + flagDay.name + " "
                + formatCalendarDate(flagDay.date);
        if (holidayLine.isEmpty()) return flagLine;
        if (flagLine.isEmpty()) return holidayLine;
        return holidayLine + "\n" + flagLine;
    }

    private static OfficialFlagDay nextOfficialFlagDay(Calendar from) {
        int fromKey = dateKey(from);
        OfficialFlagDay best = null;
        for (int year = from.get(Calendar.YEAR); year <= from.get(Calendar.YEAR) + 1; year++) {
            for (OfficialFlagDay day : officialFlagDays(year)) {
                if (dateKey(day.date) < fromKey) continue;
                if (best == null || dateKey(day.date) < dateKey(best.date)) best = day;
            }
        }
        return best;
    }

    private static List<OfficialFlagDay> officialFlagDays(int year) {
        List<OfficialFlagDay> days = new ArrayList<>();
        days.add(new OfficialFlagDay("Kalevalan päivä, suomalaisen kulttuurin päivä",
                calendarDate(year, Calendar.FEBRUARY, 28)));
        days.add(new OfficialFlagDay("Vappu, suomalaisen työn päivä",
                calendarDate(year, Calendar.MAY, 1)));
        days.add(new OfficialFlagDay("Äitienpäivä",
                nthWeekday(year, Calendar.MAY, Calendar.SUNDAY, 2)));
        days.add(new OfficialFlagDay("Puolustusvoimain lippujuhlan päivä",
                calendarDate(year, Calendar.JUNE, 4)));
        days.add(new OfficialFlagDay("Juhannuspäivä, Suomen lipun päivä",
                firstWeekdayOnOrAfter(year, Calendar.JUNE, 20, Calendar.SATURDAY)));
        days.add(new OfficialFlagDay("Isänpäivä",
                nthWeekday(year, Calendar.NOVEMBER, Calendar.SUNDAY, 2)));
        days.add(new OfficialFlagDay("Itsenäisyyspäivä",
                calendarDate(year, Calendar.DECEMBER, 6)));
        return days;
    }

    private static Calendar calendarDate(int year, int month, int day) {
        Calendar c = Calendar.getInstance(HELSINKI, FI);
        c.clear();
        c.set(year, month, day);
        return c;
    }

    private static Calendar nthWeekday(int year, int month, int weekday, int nth) {
        Calendar c = calendarDate(year, month, 1);
        int seen = 0;
        while (c.get(Calendar.MONTH) == month) {
            if (c.get(Calendar.DAY_OF_WEEK) == weekday && ++seen == nth) return c;
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return calendarDate(year, month, 1);
    }

    private static Calendar firstWeekdayOnOrAfter(int year, int month, int day, int weekday) {
        Calendar c = calendarDate(year, month, day);
        while (c.get(Calendar.DAY_OF_WEEK) != weekday) {
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return c;
    }

    private static int dateKey(Calendar c) {
        return c.get(Calendar.YEAR) * 10000
                + (c.get(Calendar.MONTH) + 1) * 100
                + c.get(Calendar.DAY_OF_MONTH);
    }

    private static String formatCalendarDate(Calendar c) {
        return c.get(Calendar.DAY_OF_MONTH) + "." + (c.get(Calendar.MONTH) + 1) + ".";
    }

    private static final class OfficialFlagDay {
        final String name;
        final Calendar date;

        OfficialFlagDay(String name, Calendar date) {
            this.name = name;
            this.date = date;
        }
    }

    private static final class LocationPlace {
        final String dataPlace;
        final String displayPlace;
        final double latitude;
        final double longitude;

        LocationPlace(String dataPlace, String displayPlace, double latitude, double longitude) {
            this.dataPlace = dataPlace;
            this.displayPlace = displayPlace;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private static String formatIsoDate(String date) {
        if (date == null || date.length() != 10) return "";
        try {
            int day = Integer.parseInt(date.substring(8, 10));
            int month = Integer.parseInt(date.substring(5, 7));
            return day + "." + month + ".";
        } catch (NumberFormatException e) {
            return date;
        }
    }

    private static String dayRemainingText() {
        Calendar now = Calendar.getInstance(HELSINKI, FI);
        Calendar end = Calendar.getInstance(HELSINKI, FI);
        end.add(Calendar.DAY_OF_YEAR, 1);
        end.set(Calendar.HOUR_OF_DAY, 0);
        end.set(Calendar.MINUTE, 0);
        end.set(Calendar.SECOND, 0);
        end.set(Calendar.MILLISECOND, 0);
        long minutes = Math.max(0L, (end.getTimeInMillis() - now.getTimeInMillis()) / 60_000L);
        long hours = minutes / 60L;
        long restMinutes = minutes % 60L;
        if (hours <= 0L) return "Päivää jäljellä " + restMinutes + " minuuttia";
        return "Päivää jäljellä " + hours + " tuntia";
    }

    private String formatWarningDateTime(long ms) {
        return formatWarningDate(ms) + " " + statusTimeFormat.format(new Date(ms));
    }

    private String formatWarningDate(long ms) {
        Calendar c = Calendar.getInstance(HELSINKI, FI);
        c.setTimeInMillis(ms);
        return c.get(Calendar.DAY_OF_MONTH) + "." + (c.get(Calendar.MONTH) + 1) + ".";
    }

    private static boolean sameDay(long aMs, long bMs) {
        Calendar a = Calendar.getInstance(HELSINKI, FI);
        Calendar b = Calendar.getInstance(HELSINKI, FI);
        a.setTimeInMillis(aMs);
        b.setTimeInMillis(bMs);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static String normalizeSearch(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String safeMessage(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.isEmpty() ? e.getClass().getSimpleName() : msg;
    }

    private static String formatTemp(double v) {
        if (Double.isNaN(v)) return "-- C";
        double clean = WeatherData.cleanZero(v);
        return String.format(FI, "%.1f C", clean);
    }

    private static String formatNullableTemp(Double v) {
        return v == null ? "-- C" : formatTemp(v);
    }

    private static String one(double v) {
        return String.format(FI, "%.1f", v);
    }

    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    private static String joinInline(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(part);
        }
        return sb.toString();
    }

    private static String ageText(long timestamp) {
        long ageMin = Math.max(0L, (System.currentTimeMillis() - timestamp) / 60_000L);
        if (ageMin < 1L) return "nyt";
        if (ageMin < 60L) return ageMin + " min sitten";
        return (ageMin / 60L) + " h sitten";
    }

    private static String slotName(String slot) {
        if (SettingsManager.RUUVI_SLOT_BEDROOM.equals(slot)) return "Anturi 1";
        if (SettingsManager.RUUVI_SLOT_LIVINGROOM.equals(slot)) return "Anturi 2";
        if (SettingsManager.RUUVI_SLOT_BALCONY.equals(slot)) return "Anturi 3";
        return "Tuntematon";
    }

    private String sensorName(String slot) {
        String fallback = slotName(slot);
        String key = sensorNameKey(slot);
        if (key == null) return fallback;
        String name = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(key, fallback);
        return name == null || name.trim().isEmpty() ? fallback : name.trim();
    }

    private static String sensorNameKey(String slot) {
        if (SettingsManager.RUUVI_SLOT_BEDROOM.equals(slot)) {
            return MobileThemeController.KEY_SENSOR_NAME_BEDROOM;
        }
        if (SettingsManager.RUUVI_SLOT_LIVINGROOM.equals(slot)) {
            return MobileThemeController.KEY_SENSOR_NAME_LIVINGROOM;
        }
        if (SettingsManager.RUUVI_SLOT_BALCONY.equals(slot)) {
            return MobileThemeController.KEY_SENSOR_NAME_BALCONY;
        }
        return null;
    }

    private static String holidayText(Date date) {
        Calendar from = Calendar.getInstance(HELSINKI, FI);
        from.setTime(date);
        List<FinnishHolidays.Holiday> upcoming = FinnishHolidays.upcoming(from, 1);
        if (upcoming.isEmpty()) return "";
        FinnishHolidays.Holiday h = upcoming.get(0);
        int todayKey = from.get(Calendar.YEAR) * 10000
                + (from.get(Calendar.MONTH) + 1) * 100
                + from.get(Calendar.DAY_OF_MONTH);
        if (h.sortKey() != todayKey) return "";
        return h.name;
    }

    private LinearLayout cardContainer() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.mobile_card_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        card.setLayoutParams(lp);
        return card;
    }

    private TextView textCard(String text) {
        TextView tv = rowText(text, 16, false);
        tv.setBackgroundResource(R.drawable.mobile_card_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        tv.setLayoutParams(lp);
        return tv;
    }

    private int warningTitleColor(WeatherWarning.Level level) {
        if (level == null) return getColor(R.color.mobile_text_primary);
        switch (level) {
            case YELLOW: return getColor(R.color.mobile_warning_yellow);
            case ORANGE: return getColor(R.color.mobile_warning_orange);
            case RED: return getColor(R.color.mobile_warning_red);
            default: return getColor(R.color.mobile_text_primary);
        }
    }

    private TextView rowText(String text, int sp, boolean strong) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(getColor(strong ? R.color.mobile_text_primary : R.color.mobile_text_body));
        tv.setLineSpacing(dp(2), 1f);
        if (strong) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
