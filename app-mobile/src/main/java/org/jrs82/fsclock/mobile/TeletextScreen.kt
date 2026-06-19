package org.jrs82.fsclock.mobile

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jrs82.fsclock.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Yle Teksti-TV: sivu näytetään virallisena PNG-kuvana (tarkka toisto, myös grafiikka) ja
 * klikattavat sivulinkit lasketaan structured-JSONin link-runeista — teksti-tv:n 40×24
 * merkkiruudukko mäppäytyy suoraan kuvan 720×456 pikseleihin. Toimii siis klikkaamalla.
 *
 * Ylen ehdot: attribuutio "Lähde: Yle Teksti-TV" pakollinen, sisältöä EI saa tallentaa levylle
 * (vain lyhyt muistivälimuisti), rate limit 300 kutsua/h → 60 s TTL-cache.
 */

private const val TT_COLS = 40f
private const val TT_ROWS = 24f

internal data class TeletextLink(val row: Int, val col: Int, val len: Int, val target: String)

internal data class TeletextMeta(
    val number: Int,
    val name: String,
    val subpageCount: Int,
    val nextPage: Int?,
    val prevPage: Int?,
    val time: String,
    val linksBySubpage: List<List<TeletextLink>>,
)

private object TeletextClient {
    private const val BASE = "https://external.api.yle.fi/v1/teletext/"
    private const val TTL_MS = 60_000L

    private val metaCache = object : LinkedHashMap<Int, Pair<Long, TeletextMeta>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Pair<Long, TeletextMeta>>) = size > 30
    }
    private val imageCache = object : LinkedHashMap<String, Pair<Long, Bitmap>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Bitmap>>) = size > 20
    }

    private fun auth() = "app_id=" + BuildConfig.YLE_APP_ID + "&app_key=" + BuildConfig.YLE_APP_KEY

    @Synchronized
    private fun cachedMeta(page: Int): TeletextMeta? =
        metaCache[page]?.takeIf { System.currentTimeMillis() - it.first < TTL_MS }?.second

    @Synchronized
    private fun putMeta(page: Int, meta: TeletextMeta) {
        metaCache[page] = System.currentTimeMillis() to meta
    }

    @Synchronized
    private fun cachedImage(key: String): Bitmap? =
        imageCache[key]?.takeIf { System.currentTimeMillis() - it.first < TTL_MS }?.second

    @Synchronized
    private fun putImage(key: String, bmp: Bitmap) {
        imageCache[key] = System.currentTimeMillis() to bmp
    }

    fun fetchMeta(page: Int): TeletextMeta? {
        cachedMeta(page)?.let { return it }
        return try {
            val body = httpBytes(BASE + "pages/" + page + ".json?" + auth()).toString(Charsets.UTF_8)
            val pg = JSONObject(body).getJSONObject("teletext").getJSONObject("page")
            // subpage voi olla objekti (1 alasivu) tai taulukko — normalisoidaan taulukoksi.
            val subs = pg.optJSONArray("subpage")
                ?: JSONArray().apply { pg.optJSONObject("subpage")?.let { put(it) } }
            val links = ArrayList<List<TeletextLink>>()
            for (i in 0 until subs.length()) links.add(parseLinks(subs.optJSONObject(i)))
            TeletextMeta(
                number = pg.optString("number", "").toIntOrNull() ?: page,
                name = pg.optString("name", ""),
                subpageCount = pg.optString("subpagecount", "").toIntOrNull()
                    ?: maxOf(1, subs.length()),
                nextPage = pg.optString("nextpg", "").toIntOrNull(),
                prevPage = pg.optString("prevpg", "").toIntOrNull(),
                time = pg.optString("time", ""),
                linksBySubpage = links,
            ).also { putMeta(page, it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseLinks(subpage: JSONObject?): List<TeletextLink> {
        val out = ArrayList<TeletextLink>()
        val content = subpage?.optJSONArray("content") ?: return out
        var structured: JSONObject? = null
        for (i in 0 until content.length()) {
            val c = content.optJSONObject(i)
            if (c != null && c.optString("type") == "structured") {
                structured = c
                break
            }
        }
        val lines = structured?.optJSONArray("line") ?: return out
        for (i in 0 until lines.length()) {
            val line = lines.optJSONObject(i) ?: continue
            val row = line.optString("number", "").toIntOrNull() ?: continue
            val runsRaw = line.opt("run") ?: continue
            val runs = if (runsRaw is JSONArray) runsRaw else JSONArray().put(runsRaw)
            var col = 0
            for (j in 0 until runs.length()) {
                val run = runs.optJSONObject(j) ?: continue
                val len = run.optInt("length", run.optString("Text", "").length).coerceAtLeast(1)
                val target = run.optString("link", "")
                if (target.isNotEmpty()) out.add(TeletextLink(row, col, len, target))
                col += len
            }
        }
        return out
    }

    fun fetchImage(page: Int, subpage: Int): Bitmap? {
        val key = "$page/$subpage"
        cachedImage(key)?.let { return it }
        return try {
            val bytes = httpBytes(BASE + "images/" + page + "/" + subpage + ".png?" + auth())
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { putImage(key, it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun httpBytes(urlStr: String): ByteArray {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Arkikeskus-Android")
        try {
            if (conn.responseCode != 200) throw Exception("HTTP " + conn.responseCode)
            conn.inputStream.use { input ->
                val baos = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    baos.write(buf, 0, n)
                }
                return baos.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }
}

@Composable
internal fun TeletextScreen(initialPage: Int, title: String) {
    val context = LocalContext.current
    val refresh = LocalRefreshTick.current
    var pageNum by rememberSaveable(initialPage) { mutableIntStateOf(initialPage) }
    var subIndex by rememberSaveable(initialPage) { mutableIntStateOf(0) }
    var meta by remember { mutableStateOf<TeletextMeta?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var pageInput by rememberSaveable(initialPage) { mutableStateOf("") }
    // Sivuhistoria takaisin-napille: linkin/numeron kautta siirtyminen työntää nykyisen sivun
    // pinoon, ja takaisin palaa siihen (esim. 100 → 133 → takaisin → 100). Kun pino on tyhjä,
    // takaisin valuu pääruudun käsittelijälle (→ etusivu).
    var history by rememberSaveable(initialPage) { mutableStateOf(intArrayOf()) }

    fun open(target: String) {
        val num = target.toIntOrNull()
        if (num != null && num in 100..899) {
            if (num != pageNum) history += pageNum
            subIndex = 0
            pageNum = num
        } else if (target.startsWith("http")) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                // selainta ei löytynyt — ei kaadeta
            }
        }
    }

    BackHandler(enabled = history.isNotEmpty()) {
        val prev = history.last()
        history = history.copyOf(history.size - 1)
        subIndex = 0
        pageNum = prev
    }

    LaunchedEffect(pageNum, refresh) {
        status = null
        val m = withContext(Dispatchers.IO) { TeletextClient.fetchMeta(pageNum) }
        if (m == null) {
            status = "Sivua $pageNum ei löytynyt tai haku epäonnistui."
        } else {
            meta = m
            if (subIndex >= m.subpageCount) subIndex = 0
        }
    }
    LaunchedEffect(meta, subIndex, refresh) {
        val m = meta ?: return@LaunchedEffect
        val bmp = withContext(Dispatchers.IO) { TeletextClient.fetchImage(m.number, subIndex + 1) }
        if (bmp != null) bitmap = bmp else if (status == null) status = "Kuvan lataus epäonnistui."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        val m = meta
        Text(
            status ?: if (m != null) {
                "Sivu " + m.number + (if (m.name.isNotEmpty()) " · " + m.name else "") +
                    (if (m.time.isNotEmpty()) " · " + m.time.substringAfter('T').take(5) else "")
            } else {
                "Ladataan…"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        // Sivunavigointi: edellinen / sivunumero / seuraava
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(
                onClick = { open(((m?.prevPage) ?: (pageNum - 1)).toString()) },
                modifier = Modifier.semantics { contentDescription = "Edellinen sivu" },
            ) { Text("◀") }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = pageInput,
                onValueChange = { v -> pageInput = v.filter { it.isDigit() }.take(3) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(pageNum.toString()) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    pageInput.toIntOrNull()?.let { open(it.toString()) }
                    pageInput = ""
                }),
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = { open(((m?.nextPage) ?: (pageNum + 1)).toString()) },
                modifier = Modifier.semantics { contentDescription = "Seuraava sivu" },
            ) { Text("▶") }
        }

        // Alasivut (esim. uutissivuilla 1/4)
        if (m != null && m.subpageCount > 1) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = { subIndex = (subIndex - 1 + m.subpageCount) % m.subpageCount },
                    modifier = Modifier.semantics { contentDescription = "Edellinen alasivu" },
                ) { Text("◀") }
                Text(
                    "Alasivu " + (subIndex + 1) + "/" + m.subpageCount,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                FilledTonalButton(
                    onClick = { subIndex = (subIndex + 1) % m.subpageCount },
                    modifier = Modifier.semantics { contentDescription = "Seuraava alasivu" },
                ) { Text("▶") }
            }
        }

        // Pikavalinnat
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(100 to "Uutiset", 200 to "Urheilu", 300 to "TV-ohjelmat", 400 to "Sää").forEach { (p, label) ->
                FilledTonalButton(onClick = { open(p.toString()) }) { Text("$p $label") }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Sivu kuvana + klikattavat linkkialueet (40×24-ruudukko → kuvan mitat)
        val bmp = bitmap
        if (bmp != null) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(720f / 432f),
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Teksti-TV:n sivu $pageNum",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                val w = maxWidth
                val h = maxHeight
                m?.linksBySubpage?.getOrNull(subIndex)?.forEach { link ->
                    Box(
                        modifier = Modifier
                            .offset(x = w * (link.col / TT_COLS), y = h * ((link.row - 1) / TT_ROWS))
                            .size(w * (link.len / TT_COLS), h * (1f / TT_ROWS))
                            .clickable { open(link.target) },
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(720f / 432f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    status ?: "Ladataan…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        // Ylen käyttöehtojen vaatima lähdemaininta — ÄLÄ poista.
        Text(
            "Lähde: Yle Teksti-TV",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
