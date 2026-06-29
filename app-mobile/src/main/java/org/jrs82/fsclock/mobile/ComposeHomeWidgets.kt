package org.jrs82.fsclock.mobile

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jrs82.fsclock.R
import org.jrs82.fsclock.SettingsManager
import org.jrs82.fsclock.WarningsRepository
import org.jrs82.fsclock.WeatherWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Säädettävän etusivun widget-malli + muokkausnäkymä + kevyet Uutiset- ja Lähilähdöt-kortit
 * (käyttäjän toive 1 + 2). Etusivun kortit luetaan järjestyksessä ja näkyvyyden mukaan: jokaisen
 * kortin paikkaa voi muuttaa ja jokaisen voi laittaa pois päältä — myös sää (aiemmin pakollinen).
 *
 * Järjestys + näkyvyys talletetaan omiin SharedPreferences-avaimiinsa, jotta ne eivät sekoitu
 * vanhan View-pohjaisen widget-järjestelmän kanssa (joka oli sidottu vanhaan etusivuun).
 */

private val FI_W = Locale("fi", "FI")
private val HELSINKI_W: TimeZone = TimeZone.getTimeZone("Europe/Helsinki")

// Etusivun UUTISKORTTIEN PROSESSITASON välimuisti: uutiset säilyvät kun siirrytään sivulta toiselle
// (ei "katoa ja lataudu uudelleen" joka kerta), ja haetaan uudelleen vain sovelluksen avauksessa
// (uusi prosessi → nämä nollautuvat) tai Päivitä-napista (LocalRefreshTick kasvaa). Säilytetään myös
// viime onnistuneen haun tick, jotta tiedetään onko Päivitä-nappia painettu haun jälkeen.
// HUOM: Lähilähtöjä EI välimuisteta — niiden reaaliaikaisuus on tärkeä, ne haetaan tuoreina aina.
// Kotimaa ja Ulkomaa pidetään ERILLÄÄN — eri datalähde (domestic=true/false) → ei ristikontaminaatiota.
private var sHomeDomesticNewsCache: List<ForeignArticle>? = null
private var sHomeDomesticNewsTick = -1
private var sHomeForeignNewsCache: List<ForeignArticle>? = null
private var sHomeForeignNewsTick = -1
private val sHomeFeedCache = HashMap<String, List<NewsItem>>()
private val sHomeFeedTick = HashMap<String, Int>()

/** Tyhjentää etusivun uutisvälimuistin → seuraava haku noutaa nykyisillä lähteillä (esim. kun lähde
 *  laitetaan pois/päälle tai oma syöte muuttuu). Kutsutaan asetusmuutoksen yhteydessä. */
internal fun invalidateHomeNewsCache() {
    sHomeDomesticNewsCache = null
    sHomeDomesticNewsTick = -1
    sHomeForeignNewsCache = null
    sHomeForeignNewsTick = -1
    sHomeFeedCache.clear()
    sHomeFeedTick.clear()
}

// ===================== Widget-malli + asetukset =====================

/** Kiinteät etusivun kortit. id = talletusavain, title = muokkausnäkymän nimi, defaultVisible = oletusnäkyvyys.
 *  Näiden lisäksi etusivulla on dynaamisia per-lähde-uutiskortteja ("news:<feedId>", oletuksena piilossa). */
enum class HomeWidget(val id: String, val title: String, val defaultVisible: Boolean) {
    CLOCK("clock", "Kello ja päivämäärä", true),
    HOLIDAY("holiday", "Pyhä- ja liputuspäivät", true),
    WEATHER("weather", "Sää", true),
    ELECTRICITY("electricity", "Pörssisähkö", true),
    WARNINGS("warnings", "Säävaroitukset", true),
    SENSORS("sensors", "Anturit", true),
    TRAFFIC("traffic", "Liikennetiedot", true),
    NEWS("news", "Kotimaan uutiset", true),
    NEWS_FOREIGN("news_foreign", "Ulkomaan uutiset", true),
    TRANSIT("transit", "Lähilähdöt", true),
    FLIGHTS("flights", "Lennot", true),
}

// Julkisia, jotta View-pohjainen MobileWidgetOrderActivity (raahausjärjestely) lukee/kirjoittaa samat avaimet.
const val KEY_HOME_ORDER = "mobile_home_order"
const val KEY_HOME_SHOW_PREFIX = "mobile_home_show_"

/** Per-lähde-uutiskortin id-etuliite (sama kuin [NewsFeed.widgetId]: "news:<feedId>"). */
const val HOME_NEWS_FEED_PREFIX = "news:"

/** Onko avain etusivun widget-järjestykseen/näkyvyyteen liittyvä (etusivun uudelleenluentaa varten). */
internal fun isHomeWidgetKey(key: String?): Boolean =
    key != null && (key == KEY_HOME_ORDER || key.startsWith(KEY_HOME_SHOW_PREFIX))

/** Uutislähteisiin liittyvä avain (lista lisätään/poistetaan) → etusivun korttilista + uutisvälimuisti
 *  pitää päivittää (esim. poistettu oma syöte ei jää näkyviin). */
internal fun isHomeNewsListKey(key: String?): Boolean =
    key != null && (key == NewsFeedStore.KEY_CUSTOM_FEEDS || key.startsWith("mobile_news_src_"))

/** Sijaintiasetukset, joiden muutos pitää välittää Compose-näkymille heti. */
internal fun isHomeLocationPrefKey(key: String?): Boolean =
    key == MobileThemeController.KEY_USE_AUTOMATIC_LOCATION ||
        key == MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME ||
        isHomeWeatherIdentityPrefKey(key)

/** Avaimet, joiden muutos tarkoittaa varsinaisen sääpaikan tai sen koordinaattien vaihtumista. */
internal fun isHomeWeatherIdentityPrefKey(key: String?): Boolean =
    key == SettingsManager.KEY_HOME_PLACE ||
        key == SettingsManager.KEY_HOME_LATITUDE ||
        key == SettingsManager.KEY_HOME_LONGITUDE

/** Asetukset jotka vaikuttavat etusivun DATAAN (ei pelkkä widget-järjestys) → vaativat virkistyksen,
 *  jotta muutos näkyy heti asetuksista palatessa (uutislähteet, sähköraja/-tila/-huomio, anturinimet). */
internal fun isHomeDataPrefKey(key: String?): Boolean {
    if (key == null) return false
    return isHomeNewsListKey(key) ||
        isHomeLocationPrefKey(key) ||
        key == MobileThemeController.KEY_CHEAP_ELECTRICITY_THRESHOLD ||
        key == MobileThemeController.KEY_CHEAP_ELECTRICITY_MODE ||
        key == MobileThemeController.KEY_CHEAP_ELECTRICITY_NOTICE ||
        key == SettingsManager.KEY_RUUVI_MAC_BEDROOM ||
        key == SettingsManager.KEY_RUUVI_MAC_LIVINGROOM ||
        key == SettingsManager.KEY_RUUVI_MAC_BALCONY ||
        key == SettingsManager.KEY_RUUVI_SENSOR_NAMES ||
        key.startsWith("mobile_sensor_name_")
}

/** Kaikki tunnetut etusivun kortti-id:t: kiinteät widgetit + jokainen uutislähde per-lähde-korttina.
 *  Public (ei internal) jotta View-pohjainen [MobileWidgetOrderActivity] (Java) voi kutsua. */
fun allHomeWidgetIds(prefs: SharedPreferences): List<String> {
    val ids = ArrayList<String>()
    for (w in HomeWidget.entries) ids.add(w.id)
    for (f in NewsFeedStore.allFeeds(prefs)) ids.add(HOME_NEWS_FEED_PREFIX + f.id)
    return ids
}

/** Tallennettu järjestys; puuttuvat/uudet kortit lisätään loppuun oletusjärjestyksessä. */
internal fun homeWidgetIds(prefs: SharedPreferences): List<String> {
    val known = allHomeWidgetIds(prefs)
    val knownSet = known.toHashSet()
    val out = ArrayList<String>()
    val raw = prefs.getString(KEY_HOME_ORDER, null)
    if (raw != null) {
        for (token in raw.split(",")) {
            val id = token.trim()
            if (id in knownSet && id !in out) out.add(id)
        }
    }
    for (id in known) if (id !in out) out.add(id)
    return out
}

/** Oletusnäkyvyys: kiinteät enum-oletuksensa mukaan, per-lähde-uutiskortit oletuksena piilossa.
 *  Public jotta [MobileWidgetOrderActivity] (Java) voi kutsua. */
fun defaultVisibleForId(id: String): Boolean {
    val w = HomeWidget.entries.firstOrNull { it.id == id }
    return w?.defaultVisible ?: false
}

internal fun isHomeWidgetVisibleId(prefs: SharedPreferences, id: String): Boolean =
    prefs.getBoolean(KEY_HOME_SHOW_PREFIX + id, defaultVisibleForId(id))

internal fun visibleHomeWidgetIds(prefs: SharedPreferences): List<String> =
    homeWidgetIds(prefs).filter { isHomeWidgetVisibleId(prefs, it) }

/** Otsikko muokkausnäkymälle (MobileWidgetOrderActivity, Java). Per-lähde: "Uutiset: <nimi>". */
fun homeWidgetTitleForId(prefs: SharedPreferences, id: String): String {
    val w = HomeWidget.entries.firstOrNull { it.id == id }
    if (w != null) return w.title
    if (id.startsWith(HOME_NEWS_FEED_PREFIX)) {
        val feedId = id.substring(HOME_NEWS_FEED_PREFIX.length)
        val feed = NewsFeedStore.feedById(prefs, feedId)
        return "Uutiset: " + (feed?.name?.ifEmpty { feedId } ?: feedId)
    }
    return id
}

// Etusivun korttien näkyvyys + järjestys muokataan View-pohjaisessa [MobileWidgetOrderActivity]ssa
// (raahaus, kuten 1.15.x). Se kirjoittaa samat avaimet (KEY_HOME_ORDER / KEY_HOME_SHOW_PREFIX).

// ===================== Etusivun muokkausnäkymä (POISTETTU) =====================

// ===================== Uutiset-widget (kevyt, etusivulle) =====================

/**
 * Etusivun uutiskorttien yhteinen kategoria-hard-filter: näytä VAIN valittujen kategorioiden jutut.
 * Tyhjä valinta (kaikki kategoriat piilossa) TAI kaikki valittu → ei suodatusta — sama sääntö kuin
 * Kotimaat/Ulkomaat "Kaikki"-näkymässä ([ForeignNewsScreen]). Pure → yksikkötestattava.
 */
internal fun hardFilterCategories(
    items: List<ForeignArticle>,
    visible: Set<String>,
    allTagCount: Int,
): List<ForeignArticle> =
    if (visible.isNotEmpty() && visible.size < allTagCount) {
        items.filter { NewsProfile.topicVisible(it.topics, visible) }
    } else {
        items
    }

/** Hakee etusivun uutiskortille näytettävän listan: piilota luetut, kategorioiden hard-filter, mute-suodatin. */
private fun filterHomeNews(prefs: SharedPreferences, fetched: List<ForeignArticle>): List<ForeignArticle> {
    val read = NewsProfile.readUrls(prefs)
    val unread = if (read.isEmpty()) fetched else fetched.filterNot { it.url in read }
    val allTags = FOREIGN_CATEGORY_TAGS.map { it.first }
    val cats = NewsProfile.visibleCats(prefs, allTags)
    val filtered = hardFilterCategories(unread, cats, allTags.size)
    // Mykistyssuodatin (käyttäjän mute-sanat + aihepaketit) — sama kuin Kotimaat/Ulkomaat-näkymissä.
    return NewsProfile.applyMute(prefs, filtered)
}

/** Hiusviivaerotin korttien rivien väliin — selvästi näkyvä molemmissa teemoissa (outlineVariant 50 %). */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        thickness = 1.dp,
    )
}

@Composable
internal fun HomeNewsCard(onOpenNews: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val refresh = LocalRefreshTick.current
    // Seedataan prosessivälimuistista → ei välky "Haetaan uutisia…" sivua vaihtaessa.
    var items by remember { mutableStateOf(sHomeDomesticNewsCache ?: emptyList()) }
    var loading by remember { mutableStateOf(sHomeDomesticNewsCache == null) }
    var handledRefresh by remember { mutableIntStateOf(refresh) }
    LaunchedEffect(refresh) {
        // Ohita haku vain kun välimuistin tick vastaa nykyistä refreshiä JA cachessa on dataa
        // → Päivitä-nappi (refresh kasvaa) pakottaa silti tuoreen haun.
        val cached = sHomeDomesticNewsCache
        if (cached != null && sHomeDomesticNewsTick == refresh) {
            items = cached
            loading = false
            handledRefresh = refresh
            return@LaunchedEffect
        }
        val forced = refresh != handledRefresh
        handledRefresh = refresh
        if (items.isEmpty()) loading = true
        val fresh = withContext(Dispatchers.IO) {
            val fetched = try {
                // Haetaan 40 (näytetään 5) → luettujen + kategoria-hard-filterin jälkeen jää yleensä ≥5.
                ForeignNewsClient.fetch(null, 40, forced, domestic = true)
            } catch (e: Exception) {
                emptyList()
            }
            filterHomeNews(prefs, fetched)
        }
        if (fresh.isNotEmpty()) {
            sHomeDomesticNewsCache = fresh
            sHomeDomesticNewsTick = refresh
            items = fresh
        }
        loading = false
    }
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            ArkiCardHeader(
                icon = painterResource(R.drawable.mobile_ic_rss_24),
                accent = ArkiTheme.colors.newsAccent,
                title = "Kotimaan uutiset",
                trailing = if (items.isNotEmpty()) {
                    { TextButton(onClick = onOpenNews) { Text("Kaikki") } }
                } else {
                    null
                },
            )
            when {
                loading -> Text(
                    "Haetaan uutisia…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                items.isEmpty() -> Text(
                    "Ei uutisia juuri nyt.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> items.take(5).forEach { a ->
                    RowDivider()
                    CompactForeignNewsRow(a)
                }
            }
        }
    }
}

@Composable
private fun CompactNewsRow(item: NewsItem) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clickable { openUrl(context, item.link) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(R.drawable.mobile_ic_news_placeholder)
                }
            },
            update = { ImageLoader.get().load(item.imageUrl, it, R.drawable.mobile_ic_news_placeholder) },
            modifier = Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    item.feedName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = ArkiTheme.colors.newsAccent,
                )
                if (item.pubTimeMs > 0) {
                    Text(
                        " · " + relAge(item.pubTimeMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ===================== Ulkomaan uutiset -widget (Uutiskeskus, etusivulle) =====================

/** Etusivun kortti Ulkomaat-uutisille (Uutiskeskus-API, suomennetut otsikot). Näyttää 5 tuoreinta,
 *  "Kaikki" avaa Ulkomaat-osion, napautus avaa jutun selaimeen. */
@Composable
internal fun HomeForeignNewsCard(onOpenForeign: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val refresh = LocalRefreshTick.current
    // Seedataan prosessivälimuistista → ei välky "Haetaan uutisia…" sivua vaihtaessa.
    var items by remember { mutableStateOf(sHomeForeignNewsCache ?: emptyList()) }
    var loading by remember { mutableStateOf(sHomeForeignNewsCache == null) }
    var handledRefresh by remember { mutableIntStateOf(refresh) }
    LaunchedEffect(refresh) {
        // Ohita haku vain kun välimuistin tick vastaa nykyistä refreshiä JA cachessa on dataa
        // → Päivitä-nappi (refresh kasvaa) pakottaa silti tuoreen haun.
        val cached = sHomeForeignNewsCache
        if (cached != null && sHomeForeignNewsTick == refresh) {
            items = cached
            loading = false
            handledRefresh = refresh
            return@LaunchedEffect
        }
        val forced = refresh != handledRefresh
        handledRefresh = refresh
        if (items.isEmpty()) loading = true
        val fresh = withContext(Dispatchers.IO) {
            val fetched = try {
                // Haetaan 40 (näytetään 5) → luettujen + kategoria-hard-filterin jälkeen jää yleensä ≥5.
                ForeignNewsClient.fetch(null, 40, forced)
            } catch (e: Exception) {
                emptyList()
            }
            // Piilota luetut + näytä vain valitut kategoriat (hard-filter, sama kuin Ulkomaat-osio).
            filterHomeNews(prefs, fetched)
        }
        if (fresh.isNotEmpty()) {
            sHomeForeignNewsCache = fresh
            sHomeForeignNewsTick = refresh
            items = fresh
        }
        loading = false
    }
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            ArkiCardHeader(
                icon = painterResource(R.drawable.mobile_ic_rss_24),
                accent = ArkiTheme.colors.newsAccent,
                title = "Ulkomaan uutiset",
                trailing = if (items.isNotEmpty()) {
                    { TextButton(onClick = onOpenForeign) { Text("Kaikki") } }
                } else {
                    null
                },
            )
            when {
                loading -> Text(
                    "Haetaan uutisia…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                items.isEmpty() -> Text(
                    "Ei uutisia juuri nyt.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> items.take(5).forEach { a ->
                    RowDivider()
                    CompactForeignNewsRow(a)
                }
            }
        }
    }
}

@Composable
private fun CompactForeignNewsRow(a: ForeignArticle) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clickable { openUrl(context, a.url) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(R.drawable.mobile_ic_news_placeholder)
                }
            },
            update = { ImageLoader.get().load(a.imageUrl, it, R.drawable.mobile_ic_news_placeholder) },
            modifier = Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                a.titleFi,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    a.source,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = ArkiTheme.colors.newsAccent,
                )
                if (a.publishedAtMs > 0) {
                    Text(
                        " · " + relAge(a.publishedAtMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ===================== Per-lähde-uutiskortti (yksi uutislähde, etusivulle) =====================

/**
 * Yhden uutislähteen kortti etusivulle ("Uutiset: HS" jne.). Lähteen voi laittaa näkyviin ja
 * sen paikkaa voi siirtää etusivun muokkauksessa (id "news:<feedId>"). Toimii myös käyttäjän
 * omille RSS-syötteille. Hakee oman lähteensä riippumatta yhdistetyn virran päällä/pois-tilasta.
 */
@Composable
internal fun HomeNewsSourceCard(feedId: String) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val refresh = LocalRefreshTick.current
    val newsRevision = LocalHomeNewsRevision.current
    val feed = remember(feedId, newsRevision) { NewsFeedStore.feedById(prefs, feedId) }
    if (feed == null) return // lähde poistettu → ei korttia
    var items by remember(feedId) { mutableStateOf(sHomeFeedCache[feedId]) }
    var handledRefresh by remember(feedId) { mutableIntStateOf(refresh) }
    LaunchedEffect(feedId, refresh, newsRevision) {
        val cached = sHomeFeedCache[feedId]
        if (cached != null && sHomeFeedTick[feedId] == refresh) {
            items = cached
            return@LaunchedEffect
        }
        val forceNetwork = refresh != handledRefresh
        handledRefresh = refresh
        val fresh = withContext(Dispatchers.IO) {
            try {
                RssRepository.get().fetchForFeed(feed, forceNetwork)
            } catch (e: Exception) {
                null
            }
        }
        if (fresh != null) {
            sHomeFeedCache[feedId] = fresh
            sHomeFeedTick[feedId] = refresh
            items = fresh
        } else if (cached == null) {
            items = emptyList()
        }
    }
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            ArkiCardHeader(
                icon = painterResource(R.drawable.mobile_ic_rss_24),
                accent = ArkiTheme.colors.newsAccent,
                title = feed.name.ifEmpty { "Uutiset" },
            )
            val list = items
            when {
                list == null -> Text(
                    "Haetaan uutisia…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                list.isEmpty() -> Text(
                    "Ei uutisia tästä lähteestä juuri nyt.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> list.take(5).forEach { item ->
                    RowDivider()
                    CompactNewsRow(item)
                }
            }
        }
    }
}

// ===================== Säävaroitukset-widget (etusivulle) =====================

/** Säävaroitukset etusivulla — näkyy vain kun voimassa olevia varoituksia on (kuten 1.15.1). */
@Composable
internal fun HomeWarningsCard(onOpenWarnings: () -> Unit) {
    val repo = remember { WarningsRepository.get() }
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val l = WarningsRepository.Listener { main.post { tick++ } }
        repo.addListener(l) // kutsuu heti nykyisellä listalla
        repo.refreshIfStale()
        onDispose { repo.removeListener(l) }
    }
    val warnings = remember(tick) { repo.getLatest() }
    if (warnings.isEmpty()) return
    val arki = ArkiTheme.colors
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            ArkiCardHeader(
                icon = painterResource(R.drawable.mobile_ic_warning_24),
                accent = arki.warning,
                title = "Säävaroitukset",
                subtitle = "${warnings.size} voimassa",
                trailing = { TextButton(onClick = onOpenWarnings) { Text("Kaikki") } },
            )
            warnings.take(4).forEach { w ->
                RowDivider()
                WarningRow(w)
            }
        }
    }
}

@Composable
private fun WarningRow(w: WeatherWarning) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(w.level.color)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (w.event.isNotEmpty()) w.event else w.level.fiName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }
        if (w.areaDesc.isNotEmpty()) {
            Text(
                w.areaDesc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        val v = warningValidity(w)
        if (v.isNotEmpty()) {
            Text(
                v,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun warningValidity(w: WeatherWarning): String {
    if (w.onsetMs <= 0L && w.expiresMs <= 0L) return ""
    val dt = SimpleDateFormat("d.M. HH:mm", FI_W)
    dt.timeZone = HELSINKI_W
    return when {
        w.onsetMs <= 0L -> "voimassa asti " + dt.format(Date(w.expiresMs))
        w.expiresMs <= 0L -> "alkaen " + dt.format(Date(w.onsetMs))
        else -> dt.format(Date(w.onsetMs)) + " – " + dt.format(Date(w.expiresMs))
    }
}

// ===================== Liikennetiedot-widget (kevyt, etusivulle) =====================

/** Liikennetiedot etusivulla: 3 lähintä tiedotetta (kaikki tyypit) tiivistettynä. */
@Composable
internal fun HomeTrafficCard(onOpenTraffic: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { TrafficNoticesRepository() }
    val refresh = LocalRefreshTick.current
    // Myös kotipaikan/sijainnin vaihtuessa (rev kasvaa) → referenssikoordinaatit luetaan uudelleen.
    val rev = LocalHomeDataRevision.current
    var notices by remember { mutableStateOf<List<TrafficNotice>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh, rev) {
        val ref = referenceCoordinates(context)
        if (ref == null) {
            note = "Salli sijainti, niin lähialueen liikennetiedot näkyvät."
            notices = emptyList()
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            try {
                repo.fetchNearby(ref[0], ref[1], TrafficNotice.Kind.ALL, refresh > 0)
            } catch (e: Exception) {
                null
            }
        }
        if (result == null) {
            note = "Liikennetietojen haku epäonnistui."
            notices = emptyList()
        } else {
            note = null
            notices = result
        }
    }
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            ArkiCardHeader(
                icon = painterResource(R.drawable.mobile_ic_construction_24),
                accent = ArkiTheme.colors.warning,
                title = "Liikennetiedot",
                trailing = if (!notices.isNullOrEmpty()) {
                    { TextButton(onClick = onOpenTraffic) { Text("Kaikki") } }
                } else {
                    null
                },
            )
            val list = notices
            when {
                list == null -> Text(
                    "Haetaan liikennetietoja…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                list.isEmpty() -> Text(
                    note ?: "Ei tiedotteita lähistöllä juuri nyt.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    list.take(3).forEach { n ->
                        RowDivider()
                        CompactTrafficRow(n)
                    }
                    if (list.size > 3) {
                        RowDivider()
                        Text(
                            "+ ${list.size - 3} lisää",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenTraffic)
                                .padding(top = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactTrafficRow(n: TrafficNotice) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            n.title.ifEmpty { n.kind.title },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = ArkiTheme.colors.warning,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = buildList {
            add(n.kind.title)
            if (!n.distanceMeters.isNaN()) add(distanceText(n.distanceMeters))
        }.joinToString("  ·  ")
        Text(
            meta,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ===================== Lähilähdöt-widget (kevyt, etusivulle) =====================

@Composable
internal fun HomeTransitCard(onOpenTransit: () -> Unit) {
    val context = LocalContext.current
    val refresh = LocalRefreshTick.current
    // Myös kotipaikan/sijainnin vaihtuessa (rev kasvaa) → referenssikoordinaatit luetaan uudelleen.
    val rev = LocalHomeDataRevision.current
    // Lähilähtöjä EI välimuisteta — haetaan aina tuoreina, jotta reaaliaikaisuus säilyy.
    var deps by remember { mutableStateOf<List<Departure>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    // Mikä lähtörivi on laajennettuna (reitti auki). Yksi kerrallaan; napautus togglaa.
    var expandedKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh, rev) {
        val ref = referenceCoordinates(context)
        if (ref == null) {
            note = "Salli sijainti tai aseta kotipaikka, niin lähilähdöt näkyvät."
            deps = emptyList()
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            try {
                // Alue laitteen/kotipaikan sijainnista (Tampereen seutu → Nysse, muuten HSL), jottei
                // Tampereella oleva käyttäjä saa tyhjää HSL-hakua koordinaateillaan.
                val stops = TransitRepository.get()
                    .fetch(ref[0], ref[1], TransitRegion.forLocation(ref[0], ref[1]))
                stops.flatMap { it.departures }.sortedBy { it.departureEpochSec }
            } catch (e: Exception) {
                null
            }
        }
        if (result == null) {
            note = "Lähtöjen haku epäonnistui."
            deps = emptyList()
        } else {
            note = null
            deps = result
        }
    }
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            ArkiCardHeader(
                icon = painterResource(R.drawable.mobile_ic_bus_24),
                accent = MaterialTheme.colorScheme.primary,
                title = "Lähilähdöt",
                trailing = { TextButton(onClick = onOpenTransit) { Text("Kaikki") } },
            )
            val list = deps
            when {
                list == null -> Text(
                    "Haetaan lähtöjä…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                list.isEmpty() -> Text(
                    note ?: "Ei lähtöjä lähistöllä juuri nyt.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    list.take(5).forEach { d ->
                        RowDivider()
                        val key = departureKey(d)
                        DepartureRow(
                            d = d,
                            expanded = expandedKey == key,
                            onToggle = { expandedKey = if (expandedKey == key) null else key },
                        )
                    }
                    if (list.size > 5) {
                        RowDivider()
                        Text(
                            "+ ${list.size - 5} lähtöä lisää",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenTransit)
                                .padding(top = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DepartureRow(d: Departure, expanded: Boolean, onToggle: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (d.routeShortName.isNullOrEmpty()) "?" else d.routeShortName,
                modifier = Modifier
                    .widthIn(min = 52.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(colorResource(transitModeColorRes(d.mode)))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                color = transitOnModeColor(d.mode),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (d.headsign.isNullOrEmpty()) "—" else d.headsign,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    departureSub(d),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                departureTimeText(d),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (d.realtime) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
            )
        }
        // Napautus avaa vuoron reitin (pysäkit + live-sijainti) suoraan tähän; uusi napautus piilottaa.
        if (expanded) {
            TripTimelineInline(d)
        }
    }
}

/** Yhden vuoron reitti (pysäkit aikoineen + nousupysäkki + ajoneuvon sijainti) inline lähtörivin alla.
 *  Haetaan tuoreena joka avauksella (live-data); koko sivu vierii, joten pitkäkin reitti on luettavissa. */
@Composable
private fun TripTimelineInline(d: Departure) {
    var timeline by remember(d.tripGtfsId, d.stopGtfsId) { mutableStateOf<TripTimeline?>(null) }
    var loading by remember(d.tripGtfsId, d.stopGtfsId) { mutableStateOf(true) }
    LaunchedEffect(d.tripGtfsId, d.stopGtfsId) {
        loading = true
        val tl = withContext(Dispatchers.IO) {
            try {
                DigitransitApi.tripTimeline(d.tripGtfsId, d.patternCode, d.stopGtfsId,
                    TransitRegion.forGtfsId(d.tripGtfsId))
            } catch (e: Exception) {
                null
            }
        }
        timeline = tl
        loading = false
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 6.dp, bottom = 8.dp),
    ) {
        val tl = timeline
        when {
            loading -> Text(
                "Haetaan reittiä…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            tl == null || tl.stops.isEmpty() -> Text(
                "Reittiä ei saatu juuri nyt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> tl.stops.forEachIndexed { i, s ->
                TimelineStopRow(
                    s = s,
                    isBoard = i == tl.boardStopIndex,
                    hasVehicle = tl.vehicleStopIndices.contains(i),
                )
            }
        }
    }
}

@Composable
private fun TimelineStopRow(s: TimelineStop, isBoard: Boolean, hasVehicle: Boolean) {
    val dotColor = when {
        hasVehicle -> MaterialTheme.colorScheme.secondary
        isBoard -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(if (hasVehicle || isBoard) 10.dp else 7.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(dotColor),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (s.name.isNullOrEmpty()) "—" else s.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isBoard) FontWeight.Bold else FontWeight.Normal,
            color = if (isBoard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stopTimeText(s.depEpochSec),
            style = MaterialTheme.typography.bodySmall,
            color = if (s.realtime) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun departureKey(d: Departure): String =
    "${d.tripGtfsId}|${d.stopGtfsId}|${d.departureEpochSec}"

private fun stopTimeText(epochSec: Long): String {
    if (epochSec <= 0L) return ""
    val f = SimpleDateFormat("HH:mm", FI_W)
    f.timeZone = HELSINKI_W
    return f.format(Date(epochSec * 1000L))
}

// ===================== Apurit =====================

private fun transitModeColorRes(mode: String?): Int = when (mode) {
    "TRAM" -> R.color.mobile_transit_tram
    "RAIL" -> R.color.mobile_transit_rail
    "SUBWAY" -> R.color.mobile_transit_subway
    "BUS" -> R.color.mobile_transit_bus
    "FERRY" -> R.color.mobile_transit_ferry
    else -> R.color.mobile_accent
}

private fun departureSub(d: Departure): String {
    val dist = distanceText(d.distanceMeters)
    val stop = d.stopName ?: ""
    return when {
        stop.isEmpty() -> dist
        dist.isEmpty() -> stop
        else -> "$stop · $dist"
    }
}

private fun distanceText(meters: Double): String = when {
    meters.isNaN() || meters < 0 -> ""
    meters < 1000.0 -> "${Math.round(meters)} m"
    else -> String.format(FI_W, "%.1f km", meters / 1000.0)
}

private fun departureTimeText(d: Departure): String {
    val nowSec = System.currentTimeMillis() / 1000L
    val diff = d.departureEpochSec - nowSec
    if (diff <= 30) return "nyt"
    val min = diff / 60L
    if (min < 60) return "$min min"
    val f = SimpleDateFormat("HH:mm", FI_W)
    f.timeZone = HELSINKI_W
    return f.format(Date(d.departureEpochSec * 1000L))
}

private fun relAge(timestamp: Long): String {
    val ageMin = Math.max(0L, (System.currentTimeMillis() - timestamp) / 60_000L)
    return when {
        ageMin < 1L -> "nyt"
        ageMin < 60L -> "$ageMin min sitten"
        else -> "${ageMin / 60L} h sitten"
    }
}

// ===================== Lennot-kortti (etusivulle) =====================

/** Etusivun lentokortti: seuraavat lähtevät HEL:stä. Jakaa FlightsRepositoryn (ei omaa hakua). */
@Composable
internal fun HomeFlightsCard(onOpenFlights: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val l = FlightsRepository.Listener { main.post { tick++ } }
        FlightsRepository.addListener(l)
        FlightsRepository.refreshIfStale()
        onDispose { FlightsRepository.removeListener(l) }
    }
    val data = remember(tick) { FlightsRepository.getLatest() }
    val arki = ArkiTheme.colors
    val next = remember(data, tick) {
        FlightsFilter.board(data, "HEL", FlightDir.DEP)
            .filter { FlightDisplay.category(it) != FlightStatusCat.COMPLETED }
            .take(3)
    }
    if (data != null && next.isEmpty()) return // ei näytetä tyhjää korttia
    ArkiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            ArkiCardHeader(
                icon = painterResource(R.drawable.mobile_ic_flight_24),
                accent = arki.weatherAccent,
                title = "Lennot",
                subtitle = "Helsinki-Vantaa · lähtevät",
                trailing = { TextButton(onClick = onOpenFlights) { Text("Kaikki") } },
            )
            if (data == null) {
                Text("Ladataan…", modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                next.forEach { f ->
                    RowDivider()
                    HomeFlightRow(f)
                }
            }
        }
    }
}

@Composable
private fun HomeFlightRow(f: Flight) {
    val timeFmt = remember {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).apply {
            timeZone = java.util.TimeZone.getTimeZone("Europe/Helsinki")
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(timeFmt.format(java.util.Date(f.effectiveMs)), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(f.flightNo, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            val place = if (f.city.isNotBlank()) f.city else f.otherAirport
            Text(place, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (f.status.isNotBlank()) {
            Text(f.status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
