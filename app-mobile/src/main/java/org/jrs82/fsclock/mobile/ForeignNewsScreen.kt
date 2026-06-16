package org.jrs82.fsclock.mobile

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jrs82.fsclock.R

/** Kategoriat (API-tagi → suomenkielinen nappi). null = kaikki (lomitettu + personoitu). */
private val FOREIGN_CATEGORIES: List<Pair<String?, String>> = listOf(
    null to "Kaikki",
    "world" to "Maailma",
    "tech" to "Teknologia",
    "mobile" to "Mobiili",
    "sport" to "Urheilu",
    "culture" to "Kulttuuri",
    "entertainment" to "Viihde",
    "science" to "Tiede",
    "nature" to "Luonto",
    "fashion" to "Kauneus & muoti",
)

/** Kaikki oikeat kategoriatagit (ilman "Kaikki"-näkymää). Käytetään myös asetusten valikossa. */
internal val FOREIGN_CATEGORY_TAGS: List<Pair<String, String>> =
    FOREIGN_CATEGORIES.mapNotNull { (tag, label) -> tag?.let { it to label } }

private const val FOREIGN_LIMIT = 100

/** "Luetut"-välilehden tunniste (erotettava nullista = "Kaikki" ja oikeista kategoriatageista). */
private const val READ_TAG = "__read__"

/**
 * "Ulkomaat" = Uutiskeskus-uutispalvelu: kategoriasirut + otsikoiden kielivalinta + kortit.
 * "Kaikki"-näkymä järjestetään maltillisesti profiilin mukaan ([NewsProfile]); kategorianäkymät
 * pysyvät tuoreusjärjestyksessä. Napautus kirjaa klikin + avaa jutun selaimeen (Custom Tabs).
 */
@Composable
internal fun NewsCategoryScreen(domestic: Boolean) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    // Uutisten oma tick: ei hae paluulla selaimesta/taustalta, vain kylmästart/Päivitä/väli.
    val refresh = LocalNewsRefreshTick.current
    var selectedTopic by remember { mutableStateOf<String?>(null) }
    var showOriginal by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<ForeignArticle>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // Kategorianäkymä, jossa kaikki haetut on jo luettu → erota "kaikki luettu" tyhjästä hausta (B3).
    var allFilteredRead by remember { mutableStateOf(false) }
    var handledRefresh by remember { mutableIntStateOf(refresh) }
    var showOnboarding by remember { mutableStateOf(!NewsProfile.isOnboarded(prefs)) }
    val visibleCats = NewsProfile.visibleCats(prefs, FOREIGN_CATEGORY_TAGS.map { it.first })

    // Lukuajan mittaus: kun palataan selaimesta sovellukseen, kirjaa avatun jutun lukuaika.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) NewsProfile.recordPendingRead(prefs)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // showOnboarding avaimena: kun alkukysely valmistuu (true→false), feed haetaan uudelleen, jotta
    // juuri valitut kategoriat suodattuvat heti (muuten näkyisi alkukyselyä ennen haettu kaikki-lista).
    LaunchedEffect(selectedTopic, refresh, showOnboarding) {
        if (showOnboarding) return@LaunchedEffect   // odota valinta ennen hakua
        val forced = refresh != handledRefresh
        handledRefresh = refresh
        loading = true
        val topic = selectedTopic
        var filteredAllRead = false
        items = withContext(Dispatchers.IO) {
            // "Luetut"-välilehti: tallennetut luetut jutut (uusin-luettu ensin) — ei verkkoa, ei suodatusta.
            if (topic == READ_TAG) {
                return@withContext NewsProfile.readArticles(prefs)
            }
            val fetched = try {
                ForeignNewsClient.fetch(topic, FOREIGN_LIMIT, forced, domestic)
            } catch (e: Exception) {
                emptyList()
            }
            // Piilota jo luetut jutut KAIKISTA näkymistä (myös "Kaikki") → eivät tule uudestaan vastaan.
            val read = NewsProfile.readUrls(prefs)
            val unreadAll = if (read.isEmpty()) fetched else fetched.filter { it.url !in read }
            // HARD-FILTER "Kaikki"-näkymässä: näytä VAIN valitut kategoriat (alkukyselyn/asetusten valinta).
            // Kategorianäkymät on jo rajattu yhteen; tyhjä valinta tai kaikki valittu → ei suodatusta.
            val allTags = FOREIGN_CATEGORY_TAGS.map { it.first }
            val cats = NewsProfile.visibleCats(prefs, allTags)
            val unread = if (topic == null && cats.isNotEmpty() && cats.size < allTags.size) {
                unreadAll.filter { NewsProfile.topicVisible(it.topics, cats) }
            } else {
                unreadAll
            }
            // Kategoriassa (ei "Kaikki", ei "Luetut") kaikki tuoreet luettu → oma tyhjäteksti (B3).
            filteredAllRead = topic != null && fetched.isNotEmpty() && unread.isEmpty()
            // Personointi vain "Kaikki"-näkymässä; kategorianäkymät pysyvät tuoreusjärjestyksessä.
            if (topic == null && unread.isNotEmpty()) {
                val snap = NewsProfile.snapshot(prefs)
                NewsProfile.rerank(unread, { NewsProfile.scoreWith(snap, it.topics, it.source) }, { it.publishedAtMs })
            } else {
                unread
            }
        }
        allFilteredRead = filteredAllRead
        loading = false
    }

    // Merkitse "suositeltu" jutut: ne jotka näkyvät tuoreempien YLÄPUOLELLA (personointi nosti).
    // Vain "Kaikki"-näkymässä (muut ovat puhdasta aika-/lukujärjestystä → ei suosittelumerkkiä).
    val boostedUrls = remember(items, selectedTopic) {
        if (selectedTopic != null) {
            emptySet()
        } else {
            val set = HashSet<String>()
            var newestBelow = Long.MIN_VALUE
            for (i in items.indices.reversed()) {
                val ms = items[i].publishedAtMs
                if (ms in 1 until newestBelow) set.add(items[i].url)
                if (ms > newestBelow) newestBelow = ms
            }
            set
        }
    }

    if (showOnboarding) {
        NewsOnboardingDialog(
            onDone = { picked ->
                NewsProfile.seedTopics(prefs, picked)
                // Valinta = näkyvät kategoriat (hard-filter, sama Kotimaat + Ulkomaat). Tyhjä = kaikki.
                NewsProfile.applyCategorySelection(prefs, picked, FOREIGN_CATEGORY_TAGS.map { it.first })
                showOnboarding = false
            },
            onSkip = { NewsProfile.setOnboarded(prefs); showOnboarding = false },
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (domestic) "Kotimaat" else "Ulkomaat",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            // Otsikoiden kielivalinta — VAIN Ulkomailla (Kotimaa on jo suomeksi). Koskee vain listan
            // otsikoita; jutut avautuvat selaimeen alkukielellä. Nappi näyttää kohdekielen.
            if (!domestic) {
                Pill(
                    text = if (showOriginal) "Otsikot suomeksi" else "Otsikot englanniksi",
                    selected = false,
                ) { showOriginal = !showOriginal }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FOREIGN_CATEGORIES.forEach { (tag, label) ->
                if (tag == null || tag in visibleCats) {
                    Pill(text = label, selected = tag == selectedTopic) { selectedTopic = tag }
                    Spacer(Modifier.width(8.dp))
                }
            }
            // "Luetut" aina viimeisenä siruna — jo luetut jutut, ei kategorianäkyvyyden alainen.
            Pill(text = "Luetut", selected = selectedTopic == READ_TAG) { selectedTopic = READ_TAG }
            Spacer(Modifier.width(8.dp))
        }
        Spacer(Modifier.height(10.dp))
        // "Kaikki"-näkymä on personoitu (tuoreus + sinua kiinnostavat) → selitä järjestys.
        // Kategorianäkymät ovat puhdas aikajärjestys, joten niihin ei selitettä.
        if (selectedTopic == null) {
            Text(
                "Tuoreimmat ensin · järjestys mukautuu lukemiisi aiheisiin",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        } else if (selectedTopic == READ_TAG) {
            Text(
                "Viimeksi lukemasi · uusin ylimpänä (enintään 100)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        when {
            loading -> Text(
                "Haetaan uutisia…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            items.isEmpty() -> Text(
                when {
                    selectedTopic == READ_TAG -> "Et ole vielä lukenut uutisia."
                    allFilteredRead -> "Olet lukenut tämän kategorian tuoreet uutiset."
                    else -> "Ei uutisia juuri nyt. Päivitä alapalkin painikkeesta."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(items, key = { it.url }) { a ->
                    ForeignNewsRow(a, showOriginal, a.url in boostedUrls) {
                        NewsProfile.recordClick(prefs, a.topics, a.source)
                        NewsProfile.markOpened(a.topics, a.source)
                        NewsProfile.markRead(prefs, a)
                        // Poista luettu juttu heti listalta (paitsi "Luetut"-välilehdellä). #1 estää
                        // refetchin paluulla, joten ilman tätä juttu jäisi näkyviin kunnes Päivitä (B1).
                        if (selectedTopic != READ_TAG) {
                            items = items.filterNot { it.url == a.url }
                        }
                        openUrl(context, a.url)
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

/** Alkukysely: "Mitkä aiheet kiinnostavat?" → seed-painot. Näytetään kerran (ks. [NewsProfile]). */
@Composable
internal fun NewsOnboardingDialog(onDone: (List<String>) -> Unit, onSkip: () -> Unit) {
    val selected = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Mitkä aiheet kiinnostavat?") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Valitse aiheet joista haluat uutisia — näytämme vain valitsemiasi kategorioita " +
                        "(sekä koti- että ulkomaat). Jos et valitse mitään, näytetään kaikki. " +
                        "Voit muuttaa valintoja myöhemmin asetuksista.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FOREIGN_CATEGORY_TAGS.forEach { (tag, label) ->
                    val checked = tag in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (checked) selected.remove(tag) else selected.add(tag) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { if (checked) selected.remove(tag) else selected.add(tag) })
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(selected.toList()) }) { Text("Valmis") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Ohita") } },
    )
}

/** Pyöreä nappula (kategoriasiru / kielivalinta). */
@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun ForeignNewsRow(a: ForeignArticle, showOriginal: Boolean, boosted: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onOpen() }
            .padding(10.dp),
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
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (showOriginal) a.titleOrig else a.titleFi,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    a.source,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = ArkiTheme.colors.newsAccent,
                )
                val age = foreignAge(a.publishedAtMs)
                if (age.isNotEmpty()) {
                    Text(
                        " · $age",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (boosted) {
                    Text(
                        " · suositeltu",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = ArkiTheme.colors.newsAccent,
                    )
                }
            }
            if (a.relatedCount > 0) {
                Text(
                    "+ ${a.relatedCount} muuta lähdettä",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** Suhteellinen ikä ("12 min", "3 h", "2 pv"). Tyhjä jos aikaleima puuttuu. */
private fun foreignAge(ms: Long): String {
    if (ms <= 0L) return ""
    val diff = System.currentTimeMillis() - ms
    if (diff < 0L) return ""
    val min = diff / 60_000L
    return when {
        min < 1L -> "juuri nyt"
        min < 60L -> "$min min"
        min < 1440L -> "${min / 60L} h"
        else -> "${min / 1440L} pv"
    }
}
