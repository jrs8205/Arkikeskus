package org.jrs82.fsclock.mobile

import android.content.SharedPreferences
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jrs82.fsclock.R
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

// ===================== Widget-malli + asetukset =====================

/** Etusivun kortit. id = talletusavain, title = muokkausnäkymän nimi, defaultVisible = oletusnäkyvyys. */
enum class HomeWidget(val id: String, val title: String, val defaultVisible: Boolean) {
    CLOCK("clock", "Kello ja päivämäärä", true),
    HOLIDAY("holiday", "Pyhä- ja liputuspäivät", true),
    WEATHER("weather", "Sää", true),
    ELECTRICITY("electricity", "Pörssisähkö", true),
    SENSORS("sensors", "Anturit", true),
    NEWS("news", "Uutiset", true),
    TRANSIT("transit", "Lähilähdöt", true),
}

// Julkisia, jotta View-pohjainen MobileWidgetOrderActivity (raahausjärjestely) lukee/kirjoittaa samat avaimet.
const val KEY_HOME_ORDER = "mobile_home_order"
const val KEY_HOME_SHOW_PREFIX = "mobile_home_show_"

/** Onko avain etusivun widget-järjestykseen/näkyvyyteen liittyvä (etusivun uudelleenluentaa varten). */
internal fun isHomeWidgetKey(key: String?): Boolean =
    key != null && (key == KEY_HOME_ORDER || key.startsWith(KEY_HOME_SHOW_PREFIX))

/** Tallennettu järjestys; puuttuvat/uudet widgetit lisätään loppuun oletusjärjestyksessä. */
internal fun homeWidgetOrder(prefs: SharedPreferences): List<HomeWidget> {
    val byId = HomeWidget.entries.associateBy { it.id }
    val out = ArrayList<HomeWidget>()
    val raw = prefs.getString(KEY_HOME_ORDER, null)
    if (raw != null) {
        for (token in raw.split(",")) {
            val w = byId[token.trim()]
            if (w != null && !out.contains(w)) out.add(w)
        }
    }
    for (w in HomeWidget.entries) if (!out.contains(w)) out.add(w)
    return out
}

internal fun isHomeWidgetVisible(prefs: SharedPreferences, w: HomeWidget): Boolean =
    prefs.getBoolean(KEY_HOME_SHOW_PREFIX + w.id, w.defaultVisible)

internal fun visibleHomeWidgets(prefs: SharedPreferences): List<HomeWidget> =
    homeWidgetOrder(prefs).filter { isHomeWidgetVisible(prefs, it) }

// Etusivun korttien näkyvyys + järjestys muokataan View-pohjaisessa [MobileWidgetOrderActivity]ssa
// (raahaus, kuten 1.15.x). Se kirjoittaa samat avaimet (KEY_HOME_ORDER / KEY_HOME_SHOW_PREFIX).

// ===================== Etusivun muokkausnäkymä (POISTETTU) =====================

// ===================== Uutiset-widget (kevyt, etusivulle) =====================

@Composable
internal fun HomeNewsCard(onOpenNews: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val refresh = LocalRefreshTick.current
    var items by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(refresh) {
        loading = true
        val fresh = withContext(Dispatchers.IO) {
            try {
                RssRepository.get().fetchEnabled(prefs, refresh > 0)
            } catch (e: Exception) {
                null
            }
        }
        items = fresh ?: emptyList()
        loading = false
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Uutiset", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (items.isNotEmpty()) {
                    TextButton(onClick = onOpenNews) { Text("Kaikki") }
                }
            }
            when {
                loading -> Text(
                    "Haetaan uutisia…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                items.isEmpty() -> Text(
                    "Ei uutisia. Tarkista uutislähteet asetuksista.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> items.take(5).forEach { CompactNewsRow(it) }
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
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
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

// ===================== Lähilähdöt-widget (kevyt, etusivulle) =====================

@Composable
internal fun HomeTransitCard(onOpenTransit: () -> Unit) {
    val context = LocalContext.current
    val refresh = LocalRefreshTick.current
    var deps by remember { mutableStateOf<List<Departure>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) {
        val ref = referenceCoordinates(context)
        if (ref == null) {
            note = "Salli sijainti tai aseta kotipaikka, niin lähilähdöt näkyvät."
            deps = emptyList()
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            try {
                val stops = TransitRepository.get().fetch(ref[0], ref[1])
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Lähilähdöt", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenTransit) { Text("Kaikki") }
            }
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
                    list.take(5).forEach { DepartureRow(it, onOpenTransit) }
                    if (list.size > 5) {
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
private fun DepartureRow(d: Departure, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (d.routeShortName.isNullOrEmpty()) "?" else d.routeShortName,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colorResource(transitModeColorRes(d.mode)))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(10.dp))
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
