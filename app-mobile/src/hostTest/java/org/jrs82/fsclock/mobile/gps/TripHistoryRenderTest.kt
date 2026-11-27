@file:OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)

package org.jrs82.fsclock.mobile.gps

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.allViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jrs82.fsclock.R
import org.jrs82.fsclock.db.FsClockDb
import org.jrs82.fsclock.db.TripEntity
import org.jrs82.fsclock.mobile.AppLocale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class TripHistoryRenderFixture {
    protected val zone = ZoneId.systemDefault()
    protected val day = LocalDate.of(2020, 11, 24)
    protected fun at(date: LocalDate, hour: Int, minute: Int) = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    protected val early = TripEntity("early", at(day, 7, 40), at(day.plusDays(1), 0, 25), true, 800.0, 60_000, 50_000, 20f)
    protected val late = TripEntity("late", at(day, 16, 10), at(day, 16, 52), false, 1600.0, 120_000, 100_000, 15f)
    protected val single = TripEntity("single", at(day.plusDays(1), 7, 42), at(day.plusDays(1), 8, 15), false, 900.0, 30_000, 20_000, 18f, true, true)

    protected fun seed(activity: ComponentActivity, trips: List<TripEntity> = listOf(late, single, early)) {
        Settings.System.putString(activity.contentResolver, Settings.System.TIME_12_24, "24")
        TripTracker.init(activity)
        runBlocking(Dispatchers.IO) {
            val dao = FsClockDb.get(activity).tripDao()
            trips.forEach(dao::save)
        }
    }

    protected fun ComponentActivity.showHistory() = setContent {
        MaterialTheme { TripHistoryScreen(visible = true, onBack = { error("Unexpected exit from history") }) }
    }

    private fun SemanticsNode.tree(): Sequence<SemanticsNode> = sequence {
        yield(this@tree)
        children.forEach { yieldAll(it.tree()) }
    }

    protected fun nodes(view: View, merged: Boolean = true): List<SemanticsNode> {
        val root = view.allViews.filterIsInstance<RootForTest>().firstOrNull() ?: return emptyList()
        // Roomin muutos voi jo olla kompositiossa, vaikka vierityslistan mittaus vielä odottaa.
        root.measureAndLayoutForTest()
        val owner = root.semanticsOwner
        return (if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode).tree().toList()
    }

    protected fun awaitNode(view: View, label: String, merged: Boolean = true, matches: (SemanticsNode) -> Boolean): SemanticsNode {
        val main = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            main.idleFor(Duration.ofMillis(16))
            nodes(view, merged).firstOrNull { matches(it) && !it.boundsInRoot.isEmpty }?.let { return it }
        } while (System.nanoTime() < deadline)
        throw AssertionError("Missing visible node '$label': " + nodes(view, merged).map {
            it.config.getOrNull(SemanticsProperties.Text) ?: it.description()
        })
    }

    protected fun text(view: View, label: String, merged: Boolean = true) = awaitNode(view, label, merged) {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }

    protected fun SemanticsNode.description() =
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()

    protected fun described(view: View, part: String) = awaitNode(view, part) { it.description().contains(part) }

    protected fun click(node: SemanticsNode) {
        assertTrue(node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
    }

    protected fun scrollToBottom(view: View) {
        val scroller = awaitNode(view, "scroll") { it.config.contains(SemanticsActions.ScrollBy) }
        assertTrue(scroller.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 10000f))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    }

    protected fun assertTextFits(view: View, label: String, card: SemanticsNode? = null): TextLayoutResult {
        val node = if (card == null) text(view, label, merged = false) else {
            val unmerged = nodes(view, merged = false).single { it.id == card.id }
            unmerged.tree().single { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == label }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
        val layout = layouts.single()
        assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) {
            assertFalse(label, layout.isLineEllipsized(line))
            assertTrue(label, layout.getLineLeft(line) >= -1f)
            assertTrue(label, layout.getLineRight(line) <= node.boundsInRoot.width + 1)
            assertTrue(label, layout.getLineBottom(line) <= node.boundsInRoot.height + 1)
        }
        return layout
    }

}

class TripHistoryRenderTest : TripHistoryRenderFixture() {
    private fun undo(view: View) {
        text(view, "Trip deleted")
        // Ilmoitusta vaihdettaessa myös poistuva Kumoa on hetken semantiikkapuussa.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        click(text(view, "Undo"))
    }

    private fun date(activity: ComponentActivity, date: LocalDate, spoken: Boolean = false) =
        HistoryFormat(activity, AppLocale.current(activity), zone, LocalDate.now(zone), true).date(date, spoken)

    private fun multiTitle(activity: ComponentActivity) =
        "${date(activity, day)} · approx.\u00a007:40–${date(activity, day.plusDays(1))} 00:25"

    private fun draw(view: View): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    // Sama sovellusprosessi pitää Roomin ja matka-arkiston elossa aktiviteettien vaihtuessa.
    @Test fun populatedHistoryNavigationRestorationDeletionAndLargeText() {
        savedNotificationOpensItsTrip()
        populatedListAndDayShowTheirOwnRowsAndOpenEachTrip()
        deletingFromDayKeepsRemainingTripAndUndoRestoresTheLastTrip()
        restoredTripsReturnToTheirOriginalPageAfterRotation()
        for (landscape in listOf(false, true)) checkCarSettings(landscape)
        for (language in listOf("fi-rFI", "sv-rFI", "en-rUS")) {
            checkLargeText(language, landscape = false)
            checkLargeText(language, landscape = true)
        }
    }

    private fun savedNotificationOpensItsTrip() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            seed(activity)
            val request = androidx.compose.runtime.mutableStateOf<String?>(single.id)
            activity.setContent {
                MaterialTheme {
                    org.jrs82.fsclock.mobile.SpeedometerSection({}, visible = true, bottomBarHidden = false,
                        requestedTrip = request.value, onTripOpened = { request.value = null })
                }
            }
            val view = activity.window.decorView
            text(view, "Started automatically · Ended automatically")
            request.value = late.id
            text(view, "${date(activity, day)} · 16:10–16:52")
            request.value = "removed"
            described(view, "2 trips")
        }
    }

    private fun checkCarSettings(landscape: Boolean) {
        RuntimeEnvironment.setQualifiers(if (landscape) "en-rUS-w891dp-h411dp-land" else "en-rUS-w411dp-h891dp-port")
        RuntimeEnvironment.setFontScale(1.5f)
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val view = activity.window.decorView
            var toggled = false
            var picked = ""
            var requested = false
            val populated = androidx.compose.runtime.mutableStateOf(true)
            activity.setContent {
                MaterialTheme {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                        CarSettingsContent(true, if (populated.value) mapOf("a" to "Golf") else emptyMap(),
                            if (populated.value) setOf("old") else emptySet(), true,
                            listOf(CarPermission.BACKGROUND), { toggled = true }, { address, _ -> picked = address },
                            { requested = true }, {})
                    }
                }
            }
            val label = activity.getString(R.string.car_switch)
            assertTextFits(view, label)
            val minimum = 48 * view.resources.displayMetrics.density
            fun touchTarget(labelText: String): SemanticsNode = text(view, labelText).also { node ->
                assertTrue(labelText, node.boundsInRoot.width >= minimum && node.boundsInRoot.height >= minimum)
            }
            val toggle = touchTarget(label)
            assertEquals(androidx.compose.ui.semantics.Role.Switch, toggle.config[SemanticsProperties.Role])
            click(toggle)
            assertTrue(toggled)
            scrollToBottom(view)
            val car = touchTarget("Golf")
            assertEquals(androidx.compose.ui.semantics.Role.Checkbox, car.config[SemanticsProperties.Role])
            assertEquals(androidx.compose.ui.state.ToggleableState.Off, car.config[SemanticsProperties.ToggleableState])
            click(car)
            assertEquals("a", picked)
            val unpaired = touchTarget("old (no longer paired)")
            assertEquals(androidx.compose.ui.state.ToggleableState.On, unpaired.config[SemanticsProperties.ToggleableState])
            click(unpaired)
            assertEquals("old", picked)
            click(touchTarget(activity.getString(R.string.car_permission_background)))
            assertTrue(requested)
            populated.value = false
            scrollToBottom(view)
            text(view, activity.getString(R.string.car_no_selection))
            text(view, activity.getString(R.string.car_pair))
            draw(view)
        }
    }

    private fun populatedListAndDayShowTheirOwnRowsAndOpenEachTrip() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val view = activity.window.decorView
            seed(activity)
            activity.showHistory()
            val first = described(view, "1 trip")
            val second = described(view, "2 trips")
            assertTrue(first.boundsInRoot.top < second.boundsInRoot.top)
            val buttons = nodes(view).filter { it.config.contains(SemanticsActions.OnClick) }
            assertEquals(listOf("Back", first.description(), second.description()), buttons.map { it.description() })
            assertTrue(first.description().contains("From 07:42 to 08:15"))
            assertTrue(second.description().contains("From approximately 07:40 to ${date(activity, day.plusDays(1), true)} 00:25"))
            assertTrue(second.description().contains("2 trips, 2.4 kilometres, 3 minutes"))
            val singleTitle = "${date(activity, day.plusDays(1))} · 07:42–08:15"
            assertRowFits(view, first.description(), date(activity, day.plusDays(1)), "07:42–08:15",
                "1 trip", "900 m", "00:30")
            assertRowFits(view, second.description(), date(activity, day),
                "approx.\u00a007:40–${date(activity, day.plusDays(1))} 00:25", "2 trips", "2.40 km", "03:00")
            val listImage = draw(view)

            click(first)
            text(view, singleTitle)
            text(view, "Started automatically · Ended automatically")
            text(view, "Delete trip")
            val tripImage = draw(view)
            assertFalse(listImage.sameAs(tripImage))
            click(described(view, "Back"))
            text(view, "Trip history")

            click(described(view, "2 trips"))
            val dayTitle = "Day · ${multiTitle(activity)}"
            text(view, dayTitle)
            text(view, "2 trips")
            text(view, "Distance")
            val dayImage = draw(view)
            assertFalse(dayImage.sameAs(listImage))
            assertFalse(dayImage.sameAs(tripImage))
            scrollToBottom(view)
            val tripRows = nodes(view).filter { it.description().contains("Average speed") }
            assertEquals(2, tripRows.size)
            assertTrue(tripRows.first().description().contains("From 16:10 to 16:52"))
            assertTrue(tripRows.last().description().contains("approximately 07:40"))
            for ((description, title) in listOf("From 16:10" to "${date(activity, day)} · 16:10–16:52",
                "From approximately 07:40" to multiTitle(activity))) {
                click(described(view, description))
                text(view, title)
                text(view, "Delete trip")
                if (description == "From 16:10") activity.onBackPressedDispatcher.onBackPressed()
                else click(described(view, "Back"))
                text(view, dayTitle)
                scrollToBottom(view)
            }
            click(described(view, "Back"))
            described(view, "2 trips")
            text(view, "Trip history")
        }
    }

    private fun restoredTripsReturnToTheirOriginalPageAfterRotation() {
        for (viaDay in listOf(true, false)) {
            RuntimeEnvironment.setQualifiers("en-rUS-w411dp-h891dp-port")
            val saved = Bundle()
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val activity = controller.get()
            val view = activity.window.decorView
            seed(activity)
            activity.showHistory()
            if (viaDay) {
                click(described(view, "2 trips"))
                text(view, "Day · ${multiTitle(activity)}")
                scrollToBottom(view)
                click(described(view, "From 16:10"))
            } else click(described(view, "1 trip"))
            text(view, "Delete trip")
            controller.pause().saveInstanceState(saved).stop().destroy()

            RuntimeEnvironment.setQualifiers("en-rUS-w891dp-h411dp-land")
            Robolectric.buildActivity(ComponentActivity::class.java).setup(saved).use { restored ->
                val fresh = restored.get()
                val window = fresh.window.decorView
                fresh.showHistory()
                val title = if (viaDay) "${date(fresh, day)} · 16:10–16:52"
                    else "${date(fresh, day.plusDays(1))} · 07:42–08:15"
                text(window, title)
                fresh.onBackPressedDispatcher.onBackPressed()
                if (viaDay) {
                    text(window, "Day · ${multiTitle(fresh)}")
                    click(described(window, "Back"))
                }
                text(window, "Trip history")
                described(window, "1 trip")
            }
        }
    }

    private fun deletingFromDayKeepsRemainingTripAndUndoRestoresTheLastTrip() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val view = activity.window.decorView
            seed(activity)
            activity.showHistory()
            click(described(view, "2 trips"))
            text(view, "Day · ${multiTitle(activity)}")
            scrollToBottom(view)
            click(described(view, "From 16:10"))
            click(text(view, "Delete trip"))
            text(view, "1 trip")
            text(view, "Day · ${multiTitle(activity)}")
            undo(view)
            text(view, "2 trips")
            scrollToBottom(view)
            click(described(view, "From approximately 07:40"))
            click(text(view, "Delete trip"))
            val lateTitle = "${date(activity, day)} · 16:10–16:52"
            text(view, "1 trip")
            text(view, "Day · $lateTitle")
            scrollToBottom(view)
            click(described(view, "From 16:10"))
            click(text(view, "Delete trip"))
            text(view, "Trip history")
            described(view, "From 07:42")
            undo(view)
            click(described(view, "From 16:10"))
            text(view, lateTitle)
            click(text(view, "Delete trip"))
            text(view, "Trip history")
            undo(view)
            described(view, "From 16:10")
            runBlocking(Dispatchers.IO) {
                val rows = FsClockDb.get(activity).tripDao().all()
                assertEquals(2, rows.size)
                assertTrue(rows.any(late::sameFinish))
                assertTrue(rows.any(single::sameFinish))
            }
        }
    }

    private fun assertRowFits(view: View, description: String, vararg labels: String) {
        val row = described(view, description)
        val min = 48 * view.resources.displayMetrics.density
        assertTrue(row.description(), row.boundsInRoot.width >= min && row.boundsInRoot.height >= min)
        assertTrue(row.config.contains(SemanticsActions.OnClick))
        labels.forEach { assertTextFits(view, it, row) }
    }

    private fun checkLargeText(language: String, landscape: Boolean) {
        val size = if (landscape) "w891dp-h411dp-land" else "w411dp-h891dp-port"
        RuntimeEnvironment.setQualifiers("$language-$size")
        RuntimeEnvironment.setFontScale(1.5f)
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val view = activity.window.decorView
            seed(activity)
            activity.showHistory()
            val locale = AppLocale.current(activity)
            assertEquals(language.substringBefore('-'), locale.language)
            val estimate = activity.getString(R.string.gps_history_estimated, "9.23")
            assertTrue(estimate, estimate.endsWith("\u00a09.23"))
            val format = HistoryFormat(activity, locale, zone, LocalDate.now(zone), true)
            val two = activity.resources.getQuantityString(R.plurals.gps_history_count, 2, 2)
            val one = activity.resources.getQuantityString(R.plurals.gps_history_count, 1, 1)
            described(view, two)
            assertEquals(landscape, view.width > view.height)
            assertEquals(1.5f, view.resources.configuration.fontScale, 0f)
            val title = "${format.date(day)} · ${format.interval(early)}"
            assertRowFits(view, format.interval(single, true), format.date(day.plusDays(1)), format.interval(single),
                one, "900 m", "00:30")
            assertRowFits(view, format.interval(early, true), format.date(day), format.interval(early),
                two, TripFormat.distance(2400.0, locale).toString(), "03:00")
            click(described(view, two))
            assertTextFits(view, "${activity.getString(R.string.gps_history_day)} · $title")
            scrollToBottom(view)
            for (trip in listOf(late, early)) {
                assertRowFits(view, format.interval(trip, true), format.interval(trip),
                    TripFormat.distance(trip.distanceM, locale).toString(), TripFormat.clock(trip.totalMs),
                    activity.getString(R.string.gps_history_pill_avg, TripFormat.avgKmh(48.0, locale)),
                    activity.getString(R.string.gps_history_pill_top, TripFormat.wholeKmh(trip.maxSpeedMps)))
            }
            click(described(view, format.interval(early, true)))
            assertTextFits(view, title)
        }
    }
}

class TripHistoryMonthRenderTest : TripHistoryRenderFixture() {
    @Test fun monthHeadersAreHeadingsAndCardsKeepOneDescription() {
        RuntimeEnvironment.setQualifiers("fi-rFI-w411dp-h891dp-port")
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val view = activity.window.decorView
            val dates = listOf(LocalDate.of(2026, 11, 26), LocalDate.of(2025, 11, 20))
            val trips = dates.map { date ->
                TripEntity(date.toString(), at(date, 9, 0), at(date, 9, 30), false, 12000.0, 1800000, 1800000, 20f)
            }
            seed(activity, trips)
            activity.showHistory()
            val newest = text(view, "MARRASKUU 2026")
            val oldest = text(view, "MARRASKUU 2025")
            assertTrue(newest.config.contains(SemanticsProperties.Heading))
            assertTrue(oldest.config.contains(SemanticsProperties.Heading))
            assertTrue(newest.boundsInRoot.top < oldest.boundsInRoot.top)
            val format = HistoryFormat(activity, AppLocale.current(activity), zone, LocalDate.now(zone), true)
            assertEquals("Marraskuu 2026", format.month(java.time.YearMonth.from(dates.first())))
            val buttons = nodes(view).filter { it.config.contains(SemanticsActions.OnClick) }
            assertEquals(3, buttons.size) // Takaisin sekä kaksi päivää
            for ((date, trip) in dates.zip(trips)) {
                val expected = listOf(format.date(date, true), format.interval(trip, true), "1 matka",
                    format.spokenQuantity(TripFormat.distance(trip.distanceM, format.locale)),
                    format.spokenDuration(trip.totalMs)).joinToString(", ")
                val card = buttons.single { it.description() == expected }
                assertEquals(listOf(expected), card.config[SemanticsProperties.ContentDescription])
                assertEquals(androidx.compose.ui.semantics.Role.Button, card.config[SemanticsProperties.Role])
                assertTrue(card.children.isEmpty())
                assertTrue(card.boundsInRoot.height >= 48 * view.resources.displayMetrics.density)
            }
            val oldDate = format.date(dates.last())
            assertTrue(oldDate, oldDate.contains("2025"))
            text(view, oldDate, merged = false)
        }
    }
}

class TripHistoryPillRenderTest : TripHistoryRenderFixture() {
    @Test fun pillsStayOnOneLineAtLargeFontInEveryLanguage() {
        val trip = TripEntity("commute", at(day, 12, 0), at(day, 12, 30), false, 24700.0, 1800000, 1800000, 25f)
        for ((language, speeds) in mapOf(
            "fi-rFI" to listOf("ka 49,4 km/h", "huippu 90 km/h", "ka 48,0 km/h", "huippu 54 km/h"),
            "sv-rFI" to listOf("medel 49,4 km/h", "topp 90 km/h", "medel 48,0 km/h", "topp 54 km/h"),
            "en-rUS" to listOf("avg 49.4 km/h", "top 90 km/h", "avg 48.0 km/h", "top 54 km/h"),
        )) {
            RuntimeEnvironment.setQualifiers("$language-w320dp-h891dp-port")
            RuntimeEnvironment.setFontScale(1.5f)
            Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
                val activity = controller.get()
                val view = activity.window.decorView
                seed(activity, listOf(trip, late))
                activity.showHistory()
                val two = activity.resources.getQuantityString(R.plurals.gps_history_count, 2, 2)
                click(described(view, two))
                text(view, two)
                for (label in listOf(R.string.gps_label_distance, R.string.gps_label_top_speed,
                    R.string.gps_label_avg_total, R.string.gps_label_avg_moving, R.string.gps_label_total_time,
                    R.string.gps_label_moving, R.string.gps_label_stopped)) {
                    assertTextFits(view, activity.getString(label))
                }
                scrollToBottom(view)
                assertEquals(320f, view.width / view.resources.displayMetrics.density, 1f)
                assertEquals(1.5f, view.resources.configuration.fontScale, 0f)
                val locale = AppLocale.current(activity)
                val format = HistoryFormat(activity, locale, zone, LocalDate.now(zone), true)
                for ((row, speedPills) in listOf(trip, late).zip(speeds.chunked(2))) {
                    val card = described(view, format.interval(row, true))
                    val pills = listOf(TripFormat.distance(row.distanceM, locale).toString(), TripFormat.clock(row.totalMs)) + speedPills
                    for (pill in pills) assertEquals(pill, 1, assertTextFits(view, pill, card).lineCount)
                }
            }
        }
    }
}
