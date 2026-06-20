# Kotinäytön widgetit (Jetpack Glance) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add four independent Android home-screen (launcher) widgets — Weather, Electricity, Steps, Next departure — built with Jetpack Glance, refreshed by a shared WorkManager worker, so users can glance at the data without opening the app.

**Architecture:** New package `org.jrs82.fsclock.mobile.widget`. Each widget = `GlanceAppWidget` + `GlanceAppWidgetReceiver` + `appwidget-provider` XML (`updatePeriodMillis=0`). A shared `WidgetCache` (SharedPreferences) holds display values; a shared `WidgetUpdateWorker` (WorkManager periodic 15 min) fetches data via existing repositories, writes the cache, and calls `updateAll()`. Widgets are stateless — they only read `WidgetCache`. The departure widget has a config Activity to pick a favorite stop or "nearest".

**Tech Stack:** Kotlin, Jetpack Glance 1.1.1 (+ glance-material3), WorkManager 2.9.1, existing repositories (`WeatherRepository`, `ElectricityRepository`, Room `DailyStepsDao`, `DigitransitApi`, `TransitFavorites`), JUnit 4 for pure-function tests.

## Global Constraints

- minSdk **30**; target/compile per project (no change).
- Kotlin **1.9.24**, Compose compiler **1.5.14**, Compose BOM **2024.06.00** — **pinned**. Use **Glance 1.1.1** only (1.2.0+ may require newer Compose/Kotlin).
- Widgets must be **stateless/passive**: read only from `WidgetCache` (SharedPreferences) inside `provideGlance`; never hold in-memory state or do network there.
- Background refresh only via **WorkManager** (`updatePeriodMillis=0`); periodic interval **15 min** (floor). Fetch weather + electricity only when their cached value is older than ~25 min (age-based throttle ≈ 30 min); steps + departures every cycle.
- Tap opens `MobileComposeMainActivity` with extra `"open_section"` (`WorkoutTrackingService.EXTRA_OPEN_SECTION`) = HomeSection enum name; `PendingIntent` flags `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`, unique requestCode per section.
- Steps read from the **existing app pipeline** (Room `DailyStepsDao.stepsForDay` + `StepCounter`), NOT a new Health Connect background read.
- Config Activity (departure): `enableEdgeToEdge()` + insets; return `RESULT_OK` + appWidgetId.
- No Material You in v1 (light/dark brand colors only).
- Commit style: ASCII Finnish (ä→a, ö→o), **no Co-Authored-By / no Claude mentions**, `git commit -F - <<'EOF' … EOF`.
- Verify on emulator (`emulator-5554`) AND Pixel 8a (debug build installs side-by-side as `Arkikeskus DEBUG`). Build commands: `cd C:/Android/projects/FsClock-main && ./gradlew.bat :app-mobile:assembleDebug` / `:app-mobile:testReleaseUnitTest`.

---

## File Structure

New files (all under `app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/` unless noted):
- `WidgetCache.kt` — SharedPreferences wrapper: typed getters/setters for each widget's display values + per-appWidgetId departure config. One responsibility: widget data persistence.
- `WidgetFormat.kt` — pure formatting/derivation functions (price level, steps %, minutes-until, clock, temp). Unit-tested.
- `WidgetDeepLink.kt` — builds the tap `PendingIntent` for a section.
- `WidgetTheme.kt` — Glance `ColorProviders` (light/dark brand).
- `WidgetUpdateWorker.kt` — `CoroutineWorker`: fetch → write cache → `updateAll`; `schedule()`/`refreshNow()` helpers.
- `WeatherWidget.kt`, `ElectricityWidget.kt`, `StepsWidget.kt`, `DepartureWidget.kt` — each: `GlanceAppWidget` + `GlanceAppWidgetReceiver`.
- `DepartureWidgetConfigActivity.kt` — config Activity (favorite stop / nearest).
- `app-mobile/src/main/res/xml/widget_weather_info.xml`, `widget_electricity_info.xml`, `widget_steps_info.xml`, `widget_departure_info.xml` — `appwidget-provider` metadata.
- `app-mobile/src/test/java/org/jrs82/fsclock/mobile/widget/WidgetFormatTest.kt` — unit tests.

Modified files:
- `app-mobile/build.gradle` — add Glance deps.
- `app-mobile/src/main/AndroidManifest.xml` — 4 receivers + 1 config Activity.
- `app-mobile/core/java/org/jrs82/fsclock/FsClockApp.java` — schedule the widget worker on startup.

---

## Phase 0 — Shared infrastructure

### Task 1: Add Glance dependencies

**Files:**
- Modify: `app-mobile/build.gradle` (dependencies block, after the Compose deps ~line 148)

- [ ] **Step 1: Add dependencies**

Insert after the `debugImplementation 'androidx.compose.ui:ui-tooling'` line:

```gradle
    // Jetpack Glance (kotinaytton widgetit, Compose-tyyliset). 1.1.1 = vakaa, yhteensopiva
    // Kotlin 1.9.24 + Compose BOM 2024.06.00 kanssa (1.2.0+ vaatisi uudemman runtimen).
    implementation 'androidx.glance:glance-appwidget:1.1.1'
    implementation 'androidx.glance:glance-material3:1.1.1'
```

- [ ] **Step 2: Verify it resolves and compiles**

Run: `cd C:/Android/projects/FsClock-main && ./gradlew.bat :app-mobile:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL` (dependency downloads, no resolution error).

- [ ] **Step 3: Commit**

```bash
git -C C:/Android/projects/FsClock-main add app-mobile/build.gradle
git -C C:/Android/projects/FsClock-main commit -F - <<'EOF'
Widgetit: lisaa Jetpack Glance 1.1.1 -riippuvuudet
EOF
```

---

### Task 2: WidgetFormat pure functions (TDD)

**Files:**
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/WidgetFormat.kt`
- Test: `app-mobile/src/test/java/org/jrs82/fsclock/mobile/widget/WidgetFormatTest.kt`

**Interfaces:**
- Produces:
  - `enum class PriceLevel { CHEAP, NORMAL, EXPENSIVE }`
  - `WidgetFormat.priceLevel(snt: Double, cheapThreshold: Double): PriceLevel`
  - `WidgetFormat.priceLabel(level: PriceLevel): String`  ("Halpaa"/"Normaali"/"Kallista")
  - `WidgetFormat.stepsPercent(steps: Int, goal: Int): Int`  (0..100)
  - `WidgetFormat.minutesUntil(departureEpochSec: Long, nowEpochSec: Long): Int`  (>=0)
  - `WidgetFormat.minutesLabel(minutes: Int): String`  ("nyt" / "N min")
  - `WidgetFormat.tempLabel(celsius: Double): String`  ("17 °C" / "–")
  - `WidgetFormat.clockLabel(epochMs: Long, zone: java.time.ZoneId): String`  ("HH.mm")

- [ ] **Step 1: Write the failing test**

```kotlin
package org.jrs82.fsclock.mobile.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class WidgetFormatTest {
    @Test fun priceLevel_thresholds() {
        assertEquals(PriceLevel.CHEAP, WidgetFormat.priceLevel(2.0, 5.0))
        assertEquals(PriceLevel.NORMAL, WidgetFormat.priceLevel(5.0, 5.0))   // not < threshold
        assertEquals(PriceLevel.NORMAL, WidgetFormat.priceLevel(10.0, 5.0))
        assertEquals(PriceLevel.EXPENSIVE, WidgetFormat.priceLevel(15.01, 5.0))
        assertEquals(PriceLevel.NORMAL, WidgetFormat.priceLevel(Double.NaN, 5.0))
    }
    @Test fun priceLabel_text() {
        assertEquals("Halpaa", WidgetFormat.priceLabel(PriceLevel.CHEAP))
        assertEquals("Normaali", WidgetFormat.priceLabel(PriceLevel.NORMAL))
        assertEquals("Kallista", WidgetFormat.priceLabel(PriceLevel.EXPENSIVE))
    }
    @Test fun stepsPercent_capsAt100_andZeroGoal() {
        assertEquals(50, WidgetFormat.stepsPercent(5000, 10000))
        assertEquals(100, WidgetFormat.stepsPercent(12000, 10000))
        assertEquals(0, WidgetFormat.stepsPercent(0, 10000))
        assertEquals(0, WidgetFormat.stepsPercent(5000, 0))   // guard div-by-zero
    }
    @Test fun minutesUntil_andLabel() {
        assertEquals(0, WidgetFormat.minutesUntil(1000, 1000))
        assertEquals(0, WidgetFormat.minutesUntil(1000, 2000))     // past -> 0, never negative
        assertEquals(5, WidgetFormat.minutesUntil(1000 + 5 * 60, 1000))
        assertEquals("nyt", WidgetFormat.minutesLabel(0))
        assertEquals("7 min", WidgetFormat.minutesLabel(7))
    }
    @Test fun tempLabel_roundsAndHandlesNaN() {
        assertEquals("17 °C", WidgetFormat.tempLabel(17.4))
        assertEquals("18 °C", WidgetFormat.tempLabel(17.6))
        assertEquals("–", WidgetFormat.tempLabel(Double.NaN))
    }
    @Test fun clockLabel_formatsHHmm() {
        // 2026-06-20 10:11 Europe/Helsinki = 1750403460000 ms
        assertEquals("10.11", WidgetFormat.clockLabel(1750403460000L, ZoneId.of("Europe/Helsinki")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd C:/Android/projects/FsClock-main && ./gradlew.bat :app-mobile:testReleaseUnitTest --tests "org.jrs82.fsclock.mobile.widget.WidgetFormatTest" --console=plain`
Expected: FAIL — `WidgetFormat` unresolved / compilation error.

- [ ] **Step 3: Write the implementation**

```kotlin
package org.jrs82.fsclock.mobile.widget

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class PriceLevel { CHEAP, NORMAL, EXPENSIVE }

/** Puhtaat muotoilu-/johdantafunktiot widgeteille (yksikkötestattavia, ei Androidia). */
object WidgetFormat {
    private const val EXPENSIVE_THRESHOLD = 15.0 // c/kWh, sama kuin etusivun kortti
    private val HHMM = DateTimeFormatter.ofPattern("HH.mm", Locale("fi", "FI"))

    fun priceLevel(snt: Double, cheapThreshold: Double): PriceLevel = when {
        snt.isNaN() -> PriceLevel.NORMAL
        snt < cheapThreshold -> PriceLevel.CHEAP
        snt > EXPENSIVE_THRESHOLD -> PriceLevel.EXPENSIVE
        else -> PriceLevel.NORMAL
    }

    fun priceLabel(level: PriceLevel): String = when (level) {
        PriceLevel.CHEAP -> "Halpaa"
        PriceLevel.NORMAL -> "Normaali"
        PriceLevel.EXPENSIVE -> "Kallista"
    }

    fun stepsPercent(steps: Int, goal: Int): Int {
        if (goal <= 0) return 0
        return ((steps.toLong() * 100L) / goal).toInt().coerceIn(0, 100)
    }

    fun minutesUntil(departureEpochSec: Long, nowEpochSec: Long): Int {
        val diff = departureEpochSec - nowEpochSec
        if (diff <= 0) return 0
        return (diff / 60L).toInt()
    }

    fun minutesLabel(minutes: Int): String = if (minutes <= 0) "nyt" else "$minutes min"

    fun tempLabel(celsius: Double): String =
        if (celsius.isNaN()) "–" else "${Math.round(celsius)} °C"

    fun clockLabel(epochMs: Long, zone: ZoneId): String =
        HHMM.format(Instant.ofEpochMilli(epochMs).atZone(zone))
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd C:/Android/projects/FsClock-main && ./gradlew.bat :app-mobile:testReleaseUnitTest --tests "org.jrs82.fsclock.mobile.widget.WidgetFormatTest" --console=plain`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git -C C:/Android/projects/FsClock-main add app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/WidgetFormat.kt app-mobile/src/test/java/org/jrs82/fsclock/mobile/widget/WidgetFormatTest.kt
git -C C:/Android/projects/FsClock-main commit -F - <<'EOF'
Widgetit: WidgetFormat-muotoilufunktiot + yksikkotestit
EOF
```

---

### Task 3: WidgetCache + WidgetDeepLink

**Files:**
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/WidgetCache.kt`
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/WidgetDeepLink.kt`

**Interfaces:**
- Produces (WidgetCache — object, all read/write SharedPreferences `arkikeskus_widgets`):
  - Weather: `setWeather(ctx, place: String, tempC: Double, condition: String, atMs: Long)`, `weatherPlace(ctx): String`, `weatherTempC(ctx): Double`, `weatherCondition(ctx): String`, `weatherUpdatedAt(ctx): Long`
  - Electricity: `setElectricity(ctx, snt: Double, atMs: Long)`, `electricitySnt(ctx): Double`, `electricityUpdatedAt(ctx): Long`
  - Steps: `setSteps(ctx, steps: Int, goal: Int, atMs: Long)`, `steps(ctx): Int`, `stepsGoal(ctx): Int`, `stepsUpdatedAt(ctx): Long`
  - Departure config (per appWidgetId): `setDepartureConfig(ctx, id: Int, mode: String, stopId: String, stopName: String)` where mode ∈ {"FAVORITE","NEAREST"}; `departureMode(ctx, id): String`, `departureStopId(ctx, id): String`, `departureStopName(ctx, id): String`, `clearDeparture(ctx, id: Int)`
  - Departure data (per appWidgetId): `setDepartureData(ctx, id: Int, stopName: String, json: String, atMs: Long)`, `departureStopLabel(ctx, id): String`, `departureJson(ctx, id): String`, `departureUpdatedAt(ctx, id): Long`
- Produces (WidgetDeepLink): `WidgetDeepLink.section(ctx, section: String): android.app.PendingIntent`

- [ ] **Step 1: Create WidgetCache.kt**

```kotlin
package org.jrs82.fsclock.mobile.widget

import android.content.Context

/** Widgettien näyttöarvojen + lähtö-widgetin konfiguroinnin pysyvä varasto (oma SharedPreferences-
 *  tiedosto, erillään muista asetuksista). Worker kirjoittaa, widgetit lukevat. */
object WidgetCache {
    private const val FILE = "arkikeskus_widgets"
    private fun p(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- Sää ---
    fun setWeather(ctx: Context, place: String, tempC: Double, condition: String, atMs: Long) {
        p(ctx).edit().putString("w_place", place).putString("w_temp", tempC.toString())
            .putString("w_cond", condition).putLong("w_at", atMs).apply()
    }
    fun weatherPlace(ctx: Context) = p(ctx).getString("w_place", "") ?: ""
    fun weatherTempC(ctx: Context) = p(ctx).getString("w_temp", "NaN")?.toDoubleOrNull() ?: Double.NaN
    fun weatherCondition(ctx: Context) = p(ctx).getString("w_cond", "") ?: ""
    fun weatherUpdatedAt(ctx: Context) = p(ctx).getLong("w_at", 0L)

    // --- Pörssisähkö ---
    fun setElectricity(ctx: Context, snt: Double, atMs: Long) {
        p(ctx).edit().putString("e_snt", snt.toString()).putLong("e_at", atMs).apply()
    }
    fun electricitySnt(ctx: Context) = p(ctx).getString("e_snt", "NaN")?.toDoubleOrNull() ?: Double.NaN
    fun electricityUpdatedAt(ctx: Context) = p(ctx).getLong("e_at", 0L)

    // --- Askeleet ---
    fun setSteps(ctx: Context, steps: Int, goal: Int, atMs: Long) {
        p(ctx).edit().putInt("s_steps", steps).putInt("s_goal", goal).putLong("s_at", atMs).apply()
    }
    fun steps(ctx: Context) = p(ctx).getInt("s_steps", 0)
    fun stepsGoal(ctx: Context) = p(ctx).getInt("s_goal", 10000)
    fun stepsUpdatedAt(ctx: Context) = p(ctx).getLong("s_at", 0L)

    // --- Lähtö-widgetin konfigurointi (per appWidgetId) ---
    fun setDepartureConfig(ctx: Context, id: Int, mode: String, stopId: String, stopName: String) {
        p(ctx).edit().putString("d_mode_$id", mode).putString("d_stopid_$id", stopId)
            .putString("d_stopname_$id", stopName).apply()
    }
    fun departureMode(ctx: Context, id: Int) = p(ctx).getString("d_mode_$id", "") ?: ""
    fun departureStopId(ctx: Context, id: Int) = p(ctx).getString("d_stopid_$id", "") ?: ""
    fun departureStopName(ctx: Context, id: Int) = p(ctx).getString("d_stopname_$id", "") ?: ""
    fun clearDeparture(ctx: Context, id: Int) {
        p(ctx).edit().remove("d_mode_$id").remove("d_stopid_$id").remove("d_stopname_$id")
            .remove("d_label_$id").remove("d_json_$id").remove("d_at_$id").apply()
    }

    // --- Lähtö-widgetin data (per appWidgetId) ---
    fun setDepartureData(ctx: Context, id: Int, stopName: String, json: String, atMs: Long) {
        p(ctx).edit().putString("d_label_$id", stopName).putString("d_json_$id", json)
            .putLong("d_at_$id", atMs).apply()
    }
    fun departureStopLabel(ctx: Context, id: Int) = p(ctx).getString("d_label_$id", "") ?: ""
    fun departureJson(ctx: Context, id: Int) = p(ctx).getString("d_json_$id", "[]") ?: "[]"
    fun departureUpdatedAt(ctx: Context, id: Int) = p(ctx).getLong("d_at_$id", 0L)
}
```

- [ ] **Step 2: Create WidgetDeepLink.kt**

```kotlin
package org.jrs82.fsclock.mobile.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.jrs82.fsclock.mobile.MobileComposeMainActivity
import org.jrs82.fsclock.mobile.WorkoutTrackingService

/** Widgetin tap → avaa sovellus oikeaan sektioon (olemassa oleva open_section-deep-link). */
object WidgetDeepLink {
    fun section(ctx: Context, section: String): PendingIntent {
        val intent = Intent(ctx, MobileComposeMainActivity::class.java).apply {
            putExtra(WorkoutTrackingService.EXTRA_OPEN_SECTION, section)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            ctx, section.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
```

- [ ] **Step 3: Verify compile**

Run: `cd C:/Android/projects/FsClock-main && ./gradlew.bat :app-mobile:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git -C C:/Android/projects/FsClock-main add app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/WidgetCache.kt app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/WidgetDeepLink.kt
git -C C:/Android/projects/FsClock-main commit -F - <<'EOF'
Widgetit: WidgetCache (SharedPreferences) + WidgetDeepLink (tap -> sektio)
EOF
```

---

### Task 4: WidgetTheme (Glance light/dark colors)

**Files:**
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/WidgetTheme.kt`

**Interfaces:**
- Produces: `WidgetColors.providers: androidx.glance.color.ColorProviders` (light/dark brand) and convenience color constants used by widgets: `WidgetColors.cheap`, `WidgetColors.normal`, `WidgetColors.expensive` as `androidx.glance.unit.ColorProvider`.

- [ ] **Step 1: Create WidgetTheme.kt**

```kotlin
package org.jrs82.fsclock.mobile.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.material3.ColorProviders
import androidx.glance.unit.ColorProvider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme

/** Brändivärit Glancelle (vaalea/tumma). Arvot ArkikeskusTheme-paletista; ei Material You v1:ssa. */
object WidgetColors {
    private val Light = lightColorScheme(
        primary = Color(0xFF1B53C0),
        onPrimary = Color(0xFFFFFFFF),
        background = Color(0xFFFBFCFF),
        onBackground = Color(0xFF1A1B20),
        surface = Color(0xFFEDF0F9),
        onSurface = Color(0xFF1A1B20),
        onSurfaceVariant = Color(0xFF43474E),
    )
    private val Dark = darkColorScheme(
        primary = Color(0xFFB0C6FF),
        onPrimary = Color(0xFF002A78),
        background = Color(0xFF1D2026),
        onBackground = Color(0xFFE3E6ED),
        surface = Color(0xFF272A31),
        onSurface = Color(0xFFE3E6ED),
        onSurfaceVariant = Color(0xFFC4C6D0),
    )
    val providers = ColorProviders(light = Light, dark = Dark)

    // Sähkön liikennevalovärit (vaalea/tumma) — ColorProvider valitsee teeman mukaan.
    val cheap = ColorProvider(day = Color(0xFF1E7D32), night = Color(0xFF7FD894))
    val normal = ColorProvider(day = Color(0xFF43474E), night = Color(0xFFC4C6D0))
    val expensive = ColorProvider(day = Color(0xFFC12018), night = Color(0xFFFFB4AB))
}
```

- [ ] **Step 2: Verify compile**

Run: `cd C:/Android/projects/FsClock-main && ./gradlew.bat :app-mobile:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git -C C:/Android/projects/FsClock-main add app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/WidgetTheme.kt
git -C C:/Android/projects/FsClock-main commit -F - <<'EOF'
Widgetit: WidgetTheme (Glance-brandivarit vaalea/tumma)
EOF
```

---

### Task 5: WidgetUpdateWorker + scheduling

**Files:**
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/WidgetUpdateWorker.kt`
- Modify: `app-mobile/core/java/org/jrs82/fsclock/FsClockApp.java` (onCreate, after `Notifications.schedule(this);`)

**Interfaces:**
- Consumes: `WidgetCache.*`, `WeatherRepository.get(ctx).fetchHome(cached, force)`, `ElectricityRepository.get(ctx).fetchIfStale()` + `.currentQuarter()`, `org.jrs82.fsclock.db.FsClockDb.get(ctx).dailyStepsDao().stepsForDay(StepCounter.todayKey())`, `StepGoalNotifier.goal(prefs)`, `DigitransitApi.stopDepartures(id)` / `.nearbyDepartures(lat, lon)`.
- Produces: `WidgetUpdateWorker.schedule(context)`, `WidgetUpdateWorker.refreshNow(context)`. Each widget's `updateAll(context)` is called here. (Departure fetch is added in Task 13; here departures are skipped gracefully.)

NOTE: Widget classes (`WeatherWidget`, etc.) are created in later tasks. This task references them — implement the worker's per-widget update blocks in the task that creates each widget. For Task 5, the worker fetches weather/electricity/steps into the cache and calls `WeatherWidget().updateAll`, `ElectricityWidget().updateAll`, `StepsWidget().updateAll` only after those classes exist. To keep Task 5 self-contained and compiling, the worker is created WITHOUT the per-widget `updateAll` calls and WITHOUT the data-fetch bodies first being wired to widgets; the fetch-to-cache logic is included now (it depends only on existing repos + WidgetCache), and the `updateAll` calls are added in each widget task.

- [ ] **Step 1: Create WidgetUpdateWorker.kt (fetch-to-cache only; updateAll added per widget task)**

```kotlin
package org.jrs82.fsclock.mobile.widget

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jrs82.fsclock.ElectricityRepository
import org.jrs82.fsclock.SettingsManager
import org.jrs82.fsclock.WeatherRepository
import org.jrs82.fsclock.db.FsClockDb
import org.jrs82.fsclock.mobile.MobileThemeController
import org.jrs82.fsclock.mobile.StepCounter
import org.jrs82.fsclock.mobile.StepGoalNotifier
import java.util.concurrent.TimeUnit

class WidgetUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        SettingsManager.get().init(ctx) // idempotentti varmistus
        val now = System.currentTimeMillis()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

        // Sää + sähkö vain jos vanhentunut (>25 min) -> ~30 min 15 min workerilla, akkua säästäen.
        val stale = 25L * 60_000L
        if (now - WidgetCache.weatherUpdatedAt(ctx) > stale) {
            try {
                val wd = WeatherRepository.get(ctx).fetchHome(org.jrs82.fsclock.mobile.WeatherCache.last, true)
                org.jrs82.fsclock.mobile.WeatherCache.last = wd
                val place = (prefs.getString(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME, "")
                    ?: "").ifBlank { SettingsManager.get().homePlace }
                WidgetCache.setWeather(ctx, place, wd.current.temperature,
                    wd.current.condition?.toString() ?: "", now)
            } catch (e: Exception) { /* säilytä vanha cache */ }
        }
        if (now - WidgetCache.electricityUpdatedAt(ctx) > stale) {
            try {
                val repo = ElectricityRepository.get(ctx)
                repo.fetchIfStale()
                val q = repo.currentQuarter()
                if (q != null) WidgetCache.setElectricity(ctx, q.sntPerKwh, now)
            } catch (e: Exception) { /* säilytä vanha */ }
        }
        // Askeleet joka kierros (paikallinen, halpa).
        try {
            val today = StepCounter.todayKey()
            val room = FsClockDb.get(ctx).dailyStepsDao().stepsForDay(today)
            val sensor = StepCounter.currentTodaySteps()
            val steps = maxOf(room ?: 0, if (sensor >= 0) sensor else 0)
            WidgetCache.setSteps(ctx, steps, StepGoalNotifier.goal(prefs), now)
        } catch (e: Exception) { /* säilytä vanha */ }

        // (Lähtödata haetaan Task 13:ssa.) Widgettien updateAll lisätaan kunkin widgetin taskissa.
        Result.success()
    }

    companion object {
        private const val WORK = "arkikeskus_widgets"
        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                ).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, work)
        }
        fun refreshNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK}_once", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build(),
            )
        }
    }
}
```

NOTE for implementer: verify `StepCounter.currentTodaySteps()` exists and is static `int` (from exploration it is used by `StepsSection`). If its name differs, read `StepCounter.java` and use the actual method; if no cheap sensor read exists, use only the Room value (`room ?: 0`).

- [ ] **Step 2: Schedule the worker on app startup**

In `app-mobile/core/java/org/jrs82/fsclock/FsClockApp.java`, in `onCreate()` immediately after the line `Notifications.schedule(this);`, add:

```java
        // Kotinaytton widgettien taustapaivitys (15 min).
        org.jrs82.fsclock.mobile.widget.WidgetUpdateWorker.schedule(this);
```

- [ ] **Step 3: Verify compile**

Run: `cd C:/Android/projects/FsClock-main && ./gradlew.bat :app-mobile:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`. (If `dailyStepsDao()`, `FsClockDb`, or `StepCounter.currentTodaySteps()` names differ, fix per the actual source before continuing.)

- [ ] **Step 4: Commit**

```bash
git -C C:/Android/projects/FsClock-main add app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/WidgetUpdateWorker.kt app-mobile/core/java/org/jrs82/fsclock/FsClockApp.java
git -C C:/Android/projects/FsClock-main commit -F - <<'EOF'
Widgetit: WidgetUpdateWorker (15 min) + ajastus FsClockAppissa
EOF
```

---

## Phase 1 — Weather widget

### Task 6: WeatherWidget (Glance) + receiver + provider XML + manifest + worker updateAll

**Files:**
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/WeatherWidget.kt`
- Create: `app-mobile/src/main/res/xml/widget_weather_info.xml`
- Modify: `app-mobile/src/main/AndroidManifest.xml` (add receiver inside `<application>`)
- Modify: `WidgetUpdateWorker.kt` (add `WeatherWidget().updateAll(ctx)` at end of doWork)

**Interfaces:**
- Consumes: `WidgetCache.weather*`, `WidgetDeepLink.section`, `WidgetColors`, `WidgetFormat.tempLabel`, `WidgetFormat.clockLabel`.
- Produces: `class WeatherWidget : GlanceAppWidget` and `class WeatherWidgetReceiver : GlanceAppWidgetReceiver`.

- [ ] **Step 1: Create the provider XML**

`app-mobile/src/main/res/xml/widget_weather_info.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="110dp"
    android:minHeight="110dp"
    android:targetCellWidth="2"
    android:targetCellHeight="2"
    android:updatePeriodMillis="0"
    android:resizeMode="horizontal|vertical"
    android:widgetCategory="home_screen"
    android:description="@string/widget_weather_desc" />
```

Add to `app-mobile/src/main/res/values/mobile_strings.xml` (inside `<resources>`):
```xml
    <string name="widget_weather_desc">Sää: paikkakunta ja lämpötila</string>
```

- [ ] **Step 2: Create WeatherWidget.kt**

```kotlin
package org.jrs82.fsclock.mobile.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.time.ZoneId

class WeatherWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WeatherContent(context) }
    }
}

@Composable
private fun WeatherContent(context: Context) {
    val place = WidgetCache.weatherPlace(context).ifBlank { "Sää" }
    val temp = WidgetFormat.tempLabel(WidgetCache.weatherTempC(context))
    val updated = WidgetCache.weatherUpdatedAt(context)
    GlanceTheme(colors = WidgetColors.providers) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.surface).cornerRadius(20.dp())
                .padding(14.dp())
                .clickable(actionStartActivity(WidgetDeepLink.deepLinkIntent(context, "HOME"))),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text(place, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 13.sp(), fontWeight = FontWeight.Medium), maxLines = 1)
            Text(temp, style = TextStyle(color = GlanceTheme.colors.onSurface,
                fontSize = 30.sp(), fontWeight = FontWeight.Bold))
            val cond = WidgetCache.weatherCondition(context)
            if (cond.isNotBlank()) Text(cond, style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp()), maxLines = 1)
            if (updated > 0) Text("päiv. ${WidgetFormat.clockLabel(updated, ZoneId.of("Europe/Helsinki"))}",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp()))
        }
    }
}

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherWidget()
}
```

NOTE for implementer (applies to all widgets): Glance unit helpers — use `androidx.glance.unit.dp`/`sp` via `import androidx.compose.ui.unit.dp` and `import androidx.compose.ui.unit.sp` (Glance reuses Compose `Dp`/`TextUnit`). Replace the placeholder `.dp()`/`.sp()` calls with Compose `dp`/`sp` extension values (e.g., `20.dp`, `30.sp`). `actionStartActivity` takes an `Intent`; add a helper `WidgetDeepLink.deepLinkIntent(ctx, section): Intent` returning the same Intent used in `WidgetDeepLink.section`, and use `actionStartActivity(intent)`. Verify exact Glance 1.1.1 API names against the imported library (the build will flag mismatches).

- [ ] **Step 3: Add `deepLinkIntent` to WidgetDeepLink.kt**

Add to `WidgetDeepLink`:
```kotlin
    fun deepLinkIntent(ctx: Context, section: String): Intent =
        Intent(ctx, MobileComposeMainActivity::class.java).apply {
            putExtra(WorkoutTrackingService.EXTRA_OPEN_SECTION, section)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
```

- [ ] **Step 4: Register the receiver in the manifest**

In `app-mobile/src/main/AndroidManifest.xml`, inside `<application>`, add:
```xml
        <receiver
            android:name="org.jrs82.fsclock.mobile.widget.WeatherWidgetReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/widget_weather_info" />
        </receiver>
```

- [ ] **Step 5: Call updateAll from the worker**

In `WidgetUpdateWorker.doWork()`, just before `Result.success()`, add:
```kotlin
        try { WeatherWidget().updateAll(ctx) } catch (e: Exception) { }
```

- [ ] **Step 6: Build the debug APK**

Run: `cd C:/Android/projects/FsClock-main && ./gradlew.bat :app-mobile:assembleDebug --console=plain 2>&1 | tail -n 8`
Expected: `BUILD SUCCESSFUL`. Fix any Glance API/import mismatches reported.

- [ ] **Step 7: Manual verification on emulator**

```bash
ADB="/c/Users/jrs82/AppData/Local/Android/Sdk/platform-tools/adb.exe"
APK="C:/Android/projects/FsClock-main/app-mobile/build/outputs/apk/debug/Arkikeskus-2.15.0-mobile-debug.apk"
"$ADB" -s emulator-5554 install -r "$APK"
# Open the app once so the worker runs and fills the cache:
"$ADB" -s emulator-5554 shell monkey -p org.jrs82.arkikeskus.debug -c android.intent.category.LAUNCHER 1
"$ADB" -s emulator-5554 shell am broadcast -a android.appwidget.action.APPWIDGET_UPDATE
```
Then add the "Sää" widget from the launcher widget picker, screenshot, and confirm: place + temperature render, tap opens the app. (If cache is empty initially it shows "Sää" / "–"; trigger `WidgetUpdateWorker.refreshNow` by reopening the app.)
Expected: widget shows place + temp; tap opens HOME.

- [ ] **Step 8: Commit**

```bash
git -C C:/Android/projects/FsClock-main add app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/WeatherWidget.kt app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/WidgetUpdateWorker.kt app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/WidgetDeepLink.kt app-mobile/src/main/res/xml/widget_weather_info.xml app-mobile/src/main/res/values/mobile_strings.xml app-mobile/src/main/AndroidManifest.xml
git -C C:/Android/projects/FsClock-main commit -F - <<'EOF'
Widgetit: Saa-widget (Glance) + receiver + worker-paivitys
EOF
```

---

## Phase 2 — Electricity widget

### Task 7: ElectricityWidget

**Files:**
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/ElectricityWidget.kt`
- Create: `app-mobile/src/main/res/xml/widget_electricity_info.xml`
- Modify: `AndroidManifest.xml`, `mobile_strings.xml`, `WidgetUpdateWorker.kt` (add `ElectricityWidget().updateAll(ctx)`)

**Interfaces:**
- Consumes: `WidgetCache.electricity*`, `WidgetFormat.priceLevel/priceLabel`, `WidgetColors.cheap/normal/expensive`, `cheapThreshold` (read `MobileThemeController.KEY_CHEAP_ELECTRICITY_THRESHOLD` directly), `WidgetDeepLink`.
- Produces: `ElectricityWidget`, `ElectricityWidgetReceiver`.

- [ ] **Step 1: Provider XML + string**

`app-mobile/src/main/res/xml/widget_electricity_info.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="110dp"
    android:minHeight="110dp"
    android:targetCellWidth="2"
    android:targetCellHeight="2"
    android:updatePeriodMillis="0"
    android:resizeMode="horizontal|vertical"
    android:widgetCategory="home_screen"
    android:description="@string/widget_electricity_desc" />
```
Add to `mobile_strings.xml`: `<string name="widget_electricity_desc">Pörssisähkö: nykyhinta ja taso</string>`

- [ ] **Step 2: Create ElectricityWidget.kt**

```kotlin
package org.jrs82.fsclock.mobile.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.mobile.MobileThemeController
import java.time.ZoneId
import java.util.Locale

class ElectricityWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { ElectricityContent(context) }
    }
}

@Composable
private fun ElectricityContent(context: Context) {
    val snt = WidgetCache.electricitySnt(context)
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val threshold = (prefs.getString(MobileThemeController.KEY_CHEAP_ELECTRICITY_THRESHOLD,
        MobileThemeController.DEFAULT_CHEAP_ELECTRICITY_THRESHOLD) ?: "5.0")
        .trim().replace(',', '.').toDoubleOrNull() ?: 5.0
    val level = WidgetFormat.priceLevel(snt, threshold)
    val color = when (level) {
        PriceLevel.CHEAP -> WidgetColors.cheap
        PriceLevel.NORMAL -> WidgetColors.normal
        PriceLevel.EXPENSIVE -> WidgetColors.expensive
    }
    val priceText = if (snt.isNaN()) "–" else String.format(Locale("fi", "FI"), "%.2f c/kWh", snt)
    val updated = WidgetCache.electricityUpdatedAt(context)
    GlanceTheme(colors = WidgetColors.providers) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.surface).cornerRadius(20.dp)
                .padding(14.dp)
                .clickable(actionStartActivity(WidgetDeepLink.deepLinkIntent(context, "ELECTRICITY"))),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text("Pörssisähkö", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            Text(priceText, style = TextStyle(color = color, fontSize = 22.sp,
                fontWeight = FontWeight.Bold), maxLines = 1)
            Text(WidgetFormat.priceLabel(level), style = TextStyle(color = color, fontSize = 13.sp,
                fontWeight = FontWeight.Medium))
            if (updated > 0) Text("päiv. ${WidgetFormat.clockLabel(updated, ZoneId.of("Europe/Helsinki"))}",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp))
        }
    }
}

class ElectricityWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ElectricityWidget()
}
```

- [ ] **Step 3: Manifest receiver** — in `<application>` add:
```xml
        <receiver
            android:name="org.jrs82.fsclock.mobile.widget.ElectricityWidgetReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/widget_electricity_info" />
        </receiver>
```

- [ ] **Step 4: Worker updateAll** — in `WidgetUpdateWorker.doWork()` before `Result.success()` add `try { ElectricityWidget().updateAll(ctx) } catch (e: Exception) { }`.

- [ ] **Step 5: Build**

Run: `cd C:/Android/projects/FsClock-main && ./gradlew.bat :app-mobile:assembleDebug --console=plain 2>&1 | tail -n 8`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Manual verify** — install, add "Pörssisähkö" widget, confirm price + status color + tap → ELECTRICITY. (Same install/screenshot flow as Task 6 Step 7.)

- [ ] **Step 7: Commit**

```bash
git -C C:/Android/projects/FsClock-main add app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/ElectricityWidget.kt app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/WidgetUpdateWorker.kt app-mobile/src/main/res/xml/widget_electricity_info.xml app-mobile/src/main/res/values/mobile_strings.xml app-mobile/src/main/AndroidManifest.xml
git -C C:/Android/projects/FsClock-main commit -F - <<'EOF'
Widgetit: Porssisahko-widget (Glance) + tasovari + worker-paivitys
EOF
```

---

## Phase 3 — Steps widget

### Task 8: StepsWidget

**Files:**
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/StepsWidget.kt`
- Create: `app-mobile/src/main/res/xml/widget_steps_info.xml`
- Modify: `AndroidManifest.xml`, `mobile_strings.xml`, `WidgetUpdateWorker.kt`

**Interfaces:**
- Consumes: `WidgetCache.steps/stepsGoal/stepsUpdatedAt`, `WidgetFormat.stepsPercent`, `WidgetDeepLink`.
- Produces: `StepsWidget`, `StepsWidgetReceiver`.

- [ ] **Step 1: Provider XML + string**

`app-mobile/src/main/res/xml/widget_steps_info.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="110dp"
    android:minHeight="110dp"
    android:targetCellWidth="2"
    android:targetCellHeight="2"
    android:updatePeriodMillis="0"
    android:resizeMode="horizontal|vertical"
    android:widgetCategory="home_screen"
    android:description="@string/widget_steps_desc" />
```
Add to `mobile_strings.xml`: `<string name="widget_steps_desc">Askeleet: tämän päivän määrä ja tavoite</string>`

- [ ] **Step 2: Create StepsWidget.kt**

```kotlin
package org.jrs82.fsclock.mobile.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import java.text.NumberFormat
import java.util.Locale

class StepsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { StepsContent(context) }
    }
}

@Composable
private fun StepsContent(context: Context) {
    val steps = WidgetCache.steps(context)
    val goal = WidgetCache.stepsGoal(context)
    val pct = WidgetFormat.stepsPercent(steps, goal)
    val stepsText = NumberFormat.getInstance(Locale("fi", "FI")).format(steps)
    GlanceTheme(colors = WidgetColors.providers) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.surface).cornerRadius(20.dp)
                .padding(14.dp)
                .clickable(actionStartActivity(WidgetDeepLink.deepLinkIntent(context, "STEPS"))),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text("Askeleet", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            Text(stepsText, style = TextStyle(color = GlanceTheme.colors.onSurface,
                fontSize = 26.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            LinearProgressIndicator(
                progress = pct / 100f,
                modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp),
                color = GlanceTheme.colors.primary,
                backgroundColor = GlanceTheme.colors.onSurfaceVariant,
            )
            Text("$pct % · tavoite $goal", style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
        }
    }
}

class StepsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepsWidget()
}
```

NOTE: verify `LinearProgressIndicator` signature in Glance 1.1.1 (`progress: Float`, `color`, `backgroundColor`). If the params differ, adapt; if unavailable, replace with a text-only "$pct %".

- [ ] **Step 3: Manifest receiver** — in `<application>` add:
```xml
        <receiver
            android:name="org.jrs82.fsclock.mobile.widget.StepsWidgetReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/widget_steps_info" />
        </receiver>
```
- [ ] **Step 4: Worker updateAll** — add `try { StepsWidget().updateAll(ctx) } catch (e: Exception) { }`.
- [ ] **Step 5: Build** — `:app-mobile:assembleDebug`, expect `BUILD SUCCESSFUL`.
- [ ] **Step 6: Manual verify** — add "Askeleet" widget; confirm steps + progress + tap → STEPS.
- [ ] **Step 7: Commit**

```bash
git -C C:/Android/projects/FsClock-main add app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/StepsWidget.kt app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/WidgetUpdateWorker.kt app-mobile/src/main/res/xml/widget_steps_info.xml app-mobile/src/main/res/values/mobile_strings.xml app-mobile/src/main/AndroidManifest.xml
git -C C:/Android/projects/FsClock-main commit -F - <<'EOF'
Widgetit: Askeleet-widget (Glance) + edistymispalkki + worker-paivitys
EOF
```

---

## Phase 4 — Next departure widget + config

### Task 9: Departure formatting helpers (TDD)

**Files:**
- Modify: `WidgetFormat.kt` (add departure JSON encode/decode for the cache payload)
- Modify: `WidgetFormatTest.kt`

**Interfaces:**
- Produces: `data class DepartureLine(val line: String, val mode: String, val epochSec: Long)`;
  `WidgetFormat.encodeDepartures(list: List<DepartureLine>): String` (JSON array);
  `WidgetFormat.decodeDepartures(json: String): List<DepartureLine>`.

- [ ] **Step 1: Write failing test (append to WidgetFormatTest)**

```kotlin
    @Test fun departures_roundTrip() {
        val list = listOf(
            WidgetFormat.DepartureLine("550", "TRAM", 1750000000L),
            WidgetFormat.DepartureLine("H305", "RAIL", 1750000600L),
        )
        val json = WidgetFormat.encodeDepartures(list)
        val back = WidgetFormat.decodeDepartures(json)
        assertEquals(list, back)
        assertEquals(emptyList<WidgetFormat.DepartureLine>(), WidgetFormat.decodeDepartures("[]"))
        assertEquals(emptyList<WidgetFormat.DepartureLine>(), WidgetFormat.decodeDepartures("garbage"))
    }
```

- [ ] **Step 2: Run, expect FAIL**

Run: `cd C:/Android/projects/FsClock-main && ./gradlew.bat :app-mobile:testReleaseUnitTest --tests "org.jrs82.fsclock.mobile.widget.WidgetFormatTest" --console=plain`
Expected: FAIL (unresolved `DepartureLine`/`encodeDepartures`).

- [ ] **Step 3: Implement (add to WidgetFormat object)**

```kotlin
    data class DepartureLine(val line: String, val mode: String, val epochSec: Long)

    fun encodeDepartures(list: List<DepartureLine>): String {
        val arr = org.json.JSONArray()
        for (d in list) arr.put(org.json.JSONObject()
            .put("l", d.line).put("m", d.mode).put("t", d.epochSec))
        return arr.toString()
    }

    fun decodeDepartures(json: String): List<DepartureLine> = try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            DepartureLine(o.optString("l"), o.optString("m"), o.optLong("t"))
        }
    } catch (e: Exception) { emptyList() }
```

(`org.json` is available in unit tests via `testImplementation 'org.json:json:...'` and on-device via Android.)

- [ ] **Step 4: Run, expect PASS**

Run: same as Step 2. Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git -C C:/Android/projects/FsClock-main add app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/WidgetFormat.kt app-mobile/src/test/java/org/jrs82/fsclock/mobile/widget/WidgetFormatTest.kt
git -C C:/Android/projects/FsClock-main commit -F - <<'EOF'
Widgetit: lahtolistan JSON-koodaus WidgetFormatiin + testit
EOF
```

---

### Task 10: DepartureWidgetConfigActivity

**Files:**
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/DepartureWidgetConfigActivity.kt`
- Modify: `AndroidManifest.xml` (config Activity with `APPWIDGET_CONFIGURE`)

**Interfaces:**
- Consumes: `TransitFavorites.getStops(ctx): List<TransitFavorites.FavStop>` (fields `gtfsId`, `name`), `WidgetCache.setDepartureConfig`, `WidgetUpdateWorker.refreshNow`.
- Produces: Activity that writes config for `EXTRA_APPWIDGET_ID` and finishes with `RESULT_OK`.

- [ ] **Step 1: Create DepartureWidgetConfigActivity.kt**

```kotlin
package org.jrs82.fsclock.mobile.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jrs82.fsclock.mobile.ArkikeskusTheme
import org.jrs82.fsclock.mobile.MobileThemeController
import org.jrs82.fsclock.mobile.TransitFavorites

class DepartureWidgetConfigActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        MobileThemeController.apply(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Oletus: peruttu, kunnes käyttäjä valitsee.
        setResult(RESULT_CANCELED)
        val widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        setContent {
            ArkikeskusTheme(dynamicColor = MobileThemeController.dynamicColor(this)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val stops = remember { TransitFavorites.getStops(this) }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Text("Valitse pysäkki", style = MaterialTheme.typography.headlineSmall)
                        Text("Lähin pysäkki (GPS)", modifier = Modifier
                            .fillMaxSize().padding(vertical = 12.dp)
                            .clickable { confirm(widgetId, "NEAREST", "", "Lähin pysäkki") },
                            style = MaterialTheme.typography.bodyLarge)
                        stops.forEach { s ->
                            Text(s.name, modifier = Modifier.padding(vertical = 12.dp)
                                .clickable { confirm(widgetId, "FAVORITE", s.gtfsId, s.name) },
                                style = MaterialTheme.typography.bodyLarge)
                        }
                        if (stops.isEmpty()) Text(
                            "Ei suosikkipysäkkejä — lisää suosikki Lähilähdöt-näkymässä, tai valitse Lähin.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    private fun confirm(widgetId: Int, mode: String, stopId: String, stopName: String) {
        WidgetCache.setDepartureConfig(this, widgetId, mode, stopId, stopName)
        WidgetUpdateWorker.refreshNow(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
```

NOTE: `TransitFavorites.getStops` returns `FavStop` with fields `gtfsId` and `name` (verify exact field names from `TransitFavorites.java`; exploration shows `FavStop(gtfsId, name)`). The `clickable` on the "Lähin" row uses `fillMaxSize()` by mistake in a Column child — replace with `fillMaxWidth()` (import `androidx.compose.foundation.layout.fillMaxWidth`).

- [ ] **Step 2: Manifest config Activity**

In `<application>` add:
```xml
        <activity
            android:name="org.jrs82.fsclock.mobile.widget.DepartureWidgetConfigActivity"
            android:exported="true"
            android:theme="@style/MobileComposeTheme">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_CONFIGURE" />
            </intent-filter>
        </activity>
```

- [ ] **Step 3: Build** — `:app-mobile:assembleDebug`, expect `BUILD SUCCESSFUL`. Fix `fillMaxWidth` import + verify `FavStop` field names.

- [ ] **Step 4: Commit**

```bash
git -C C:/Android/projects/FsClock-main add app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/DepartureWidgetConfigActivity.kt app-mobile/src/main/AndroidManifest.xml
git -C C:/Android/projects/FsClock-main commit -F - <<'EOF'
Widgetit: lahto-widgetin konfigurointi-Activity (suosikki tai lahin)
EOF
```

---

### Task 11: DepartureWidget + worker fetch

**Files:**
- Create: `app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/DepartureWidget.kt`
- Create: `app-mobile/src/main/res/xml/widget_departure_info.xml`
- Modify: `AndroidManifest.xml`, `mobile_strings.xml`, `WidgetUpdateWorker.kt` (fetch departures per placed widget + `DepartureWidget().updateAll`)

**Interfaces:**
- Consumes: `WidgetCache.departure*`, `WidgetFormat.decodeDepartures/minutesUntil/minutesLabel/clockLabel`, `transitModeIconRes` (from `ComposeCommon.kt`), `DigitransitApi.stopDepartures(id)` / `.nearbyDepartures(lat, lon)`, `GlanceAppWidgetManager` to enumerate placed ids, device location for NEAREST.
- Produces: `DepartureWidget`, `DepartureWidgetReceiver`.

- [ ] **Step 1: Provider XML** — `widget_departure_info.xml` with bigger size:
```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="180dp"
    android:minHeight="110dp"
    android:targetCellWidth="4"
    android:targetCellHeight="2"
    android:updatePeriodMillis="0"
    android:resizeMode="horizontal|vertical"
    android:widgetCategory="home_screen"
    android:configure="org.jrs82.fsclock.mobile.widget.DepartureWidgetConfigActivity"
    android:description="@string/widget_departure_desc" />
```
Add `<string name="widget_departure_desc">Seuraava lähtö: suosikkipysäkin lähdöt</string>`.

- [ ] **Step 2: Create DepartureWidget.kt**

```kotlin
package org.jrs82.fsclock.mobile.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalGlanceId
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import java.time.ZoneId

class DepartureWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent { DepartureContent(context, appWidgetId) }
    }
}

@Composable
private fun DepartureContent(context: Context, appWidgetId: Int) {
    val stop = WidgetCache.departureStopLabel(context, appWidgetId).ifBlank { "Seuraava lähtö" }
    val deps = WidgetFormat.decodeDepartures(WidgetCache.departureJson(context, appWidgetId))
    val now = System.currentTimeMillis() / 1000L
    val updated = WidgetCache.departureUpdatedAt(context, appWidgetId)
    GlanceTheme(colors = WidgetColors.providers) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.surface).cornerRadius(20.dp).padding(14.dp)
                .clickable(actionStartActivity(WidgetDeepLink.deepLinkIntent(context, "TRANSIT"))),
        ) {
            Text(stop, style = TextStyle(color = GlanceTheme.colors.onSurface,
                fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            if (deps.isEmpty()) {
                Text("Ei lähtöjä", style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
            } else {
                deps.take(3).forEach { d ->
                    val min = WidgetFormat.minutesLabel(WidgetFormat.minutesUntil(d.epochSec, now))
                    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(d.line, style = TextStyle(color = GlanceTheme.colors.primary,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold))
                        Text("  $min", style = TextStyle(
                            color = GlanceTheme.colors.onSurface, fontSize = 14.sp))
                    }
                }
            }
            if (updated > 0) Text("päiv. ${WidgetFormat.clockLabel(updated, ZoneId.of("Europe/Helsinki"))}",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                modifier = GlanceModifier.padding(top = 4.dp))
        }
    }
}

class DepartureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DepartureWidget()
}
```

NOTE: mode icons are omitted in this MVP Glance row (line + minutes only). If desired later, add a Glance `Image(provider = ImageProvider(transitModeIconRes(d.mode)))` before the line.

- [ ] **Step 3: Worker — fetch departures for each placed widget**

In `WidgetUpdateWorker.doWork()`, after the steps block and before the `updateAll` calls, add:

```kotlin
        // Lähtö-widgetit: hae kunkin asetetun widgetin pysäkin lähdöt.
        try {
            val mgr = androidx.glance.appwidget.GlanceAppWidgetManager(ctx)
            val ids = mgr.getGlanceIds(DepartureWidget::class.java)
            for (gid in ids) {
                val awId = mgr.getAppWidgetId(gid)
                val mode = WidgetCache.departureMode(ctx, awId)
                val stop = try {
                    when (mode) {
                        "FAVORITE" -> org.jrs82.fsclock.mobile.DigitransitApi.stopDepartures(
                            WidgetCache.departureStopId(ctx, awId))
                        "NEAREST" -> {
                            val loc = org.jrs82.fsclock.mobile.deviceLocationBlocking(ctx)
                            if (loc != null)
                                org.jrs82.fsclock.mobile.DigitransitApi.nearbyDepartures(
                                    loc.latitude, loc.longitude).firstOrNull()
                            else null
                        }
                        else -> null
                    }
                } catch (e: Exception) { null }
                if (stop != null) {
                    val lines = stop.departures.take(3).map {
                        WidgetFormat.DepartureLine(it.routeShortName, it.mode, it.departureEpochSec)
                    }
                    val name = if (mode == "NEAREST") stop.name
                        else WidgetCache.departureStopName(ctx, awId).ifBlank { stop.name }
                    WidgetCache.setDepartureData(ctx, awId, name,
                        WidgetFormat.encodeDepartures(lines), now)
                }
            }
        } catch (e: Exception) { }
```

NOTE for implementer: `NearbyStop`/`Departure` field names from exploration are `departures`, `routeShortName`, `mode`, `departureEpochSec`, `name`. If a synchronous device-location helper does not exist as `deviceLocationBlocking`, read how `maybeRefreshDeviceLocation`/`deviceLocation` works in `ComposeMainScreen.kt` and use the available blocking location read inside the worker (it already runs on `Dispatchers.IO`). If no blocking location is available, gate NEAREST behind last-known location (`SettingsManager` home coords) as a fallback.

- [ ] **Step 4: Worker updateAll + manifest** — add `try { DepartureWidget().updateAll(ctx) } catch (e: Exception) { }` to the worker; and in `<application>` add the receiver:
```xml
        <receiver
            android:name="org.jrs82.fsclock.mobile.widget.DepartureWidgetReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/widget_departure_info" />
        </receiver>
```

- [ ] **Step 5: Build** — `:app-mobile:assembleDebug`, expect `BUILD SUCCESSFUL`. Resolve any field/method name mismatches against `DigitransitApi.java` / `Departure` / `NearbyStop`.

- [ ] **Step 6: Manual verify** — add "Seuraava lähtö" widget → config screen opens → pick a favorite (add one first in-app if none) or "Lähin" → widget shows stop + departures; tap → TRANSIT. Test both FAVORITE and NEAREST.

- [ ] **Step 7: Commit**

```bash
git -C C:/Android/projects/FsClock-main add app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/DepartureWidget.kt app-mobile/src/main/java/org/jrs82/fsclock/mobile/widget/WidgetUpdateWorker.kt app-mobile/src/main/res/xml/widget_departure_info.xml app-mobile/src/main/res/values/mobile_strings.xml app-mobile/src/main/AndroidManifest.xml
git -C C:/Android/projects/FsClock-main commit -F - <<'EOF'
Widgetit: Seuraava lahto -widget (Glance) + worker-haku + konfigurointikytkenta
EOF
```

---

## Phase 5 — Integration verification

### Task 12: Full build, tests, and device verification

- [ ] **Step 1: Run all unit tests + both APKs**

Run: `cd C:/Android/projects/FsClock-main && ./gradlew.bat :app-mobile:testReleaseUnitTest :app-mobile:assembleDebug :app-mobile:assembleRelease --console=plain 2>&1 | tail -n 12`
Expected: `BUILD SUCCESSFUL`; WidgetFormatTest passes.

- [ ] **Step 2: Install debug on emulator + Pixel 8a, add all four widgets**

Install on `emulator-5554` and the Pixel (`adb-43121JEKB17735-33gPwg._adb-tls-connect._tcp`). Add each widget from the picker. Verify: content renders, tap opens correct section, light/dark both readable, departure config (favorite + nearest) works, and values refresh after reopening the app (`WidgetUpdateWorker.refreshNow` runs on app start — add that call in `MobileComposeMainActivity.onResume` if not already, OR rely on the 15-min periodic).

- [ ] **Step 3: Add refreshNow on app foreground (freshness)**

In `MobileComposeMainActivity.kt` `onResume()` (or onCreate), add:
```kotlin
        org.jrs82.fsclock.mobile.widget.WidgetUpdateWorker.refreshNow(this)
```
Rebuild, reinstall, confirm widgets update shortly after opening the app.

- [ ] **Step 4: Commit**

```bash
git -C C:/Android/projects/FsClock-main add app-mobile/src/main/java/org/jrs82/fsclock/mobile/MobileComposeMainActivity.kt
git -C C:/Android/projects/FsClock-main commit -F - <<'EOF'
Widgetit: paivita widgetit kun sovellus avataan (refreshNow onResumessa)
EOF
```

- [ ] **Step 5: Manual sign-off**

Confirm with the user on-device, then proceed to version bump + release as a separate step (not part of this plan).

---

## Self-Review notes (for the executor)

- **Spec coverage:** all four widgets (Tasks 6/7/8/11), shared worker+cache (Tasks 3/5), config Activity (Task 10), theme (Task 4), deep-link (Task 3), tests (Tasks 2/9), Android-17/Glance-1.1.1 constraints (Global Constraints). Covered.
- **Known verification points (resolve against source, not placeholders):** exact Glance 1.1.1 API names (`provideContent`, `actionStartActivity(Intent)`, `LinearProgressIndicator`, `cornerRadius`, `GlanceAppWidgetManager.getGlanceIds/getAppWidgetId`); `StepCounter.currentTodaySteps()`; `FavStop` field names; a blocking device-location read for NEAREST; `WeatherCondition.toString()` for a readable condition label. Each task's NOTE flags these; the build/test step in that task catches mismatches.
- **Type consistency:** `WidgetFormat.DepartureLine(line, mode, epochSec)` used identically in worker encode and widget decode; `WidgetCache` setter/getter key names match; section strings ("HOME"/"ELECTRICITY"/"STEPS"/"TRANSIT") match `HomeSection` enum names.
