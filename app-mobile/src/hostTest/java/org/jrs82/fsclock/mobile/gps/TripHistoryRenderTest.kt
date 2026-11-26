package org.jrs82.fsclock.mobile.gps

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.View
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
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class TripHistoryRenderTest {
    private val zone = ZoneId.systemDefault()
    private val day = LocalDate.of(2020, 11, 24)
    private fun at(date: LocalDate, hour: Int, minute: Int) = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    private val early = TripEntity("early", at(day, 7, 40), at(day.plusDays(1), 0, 25), true, 800.0, 60_000, 50_000, 20f)
    private val late = TripEntity("late", at(day, 16, 10), at(day, 16, 52), false, 1600.0, 120_000, 100_000, 15f)
    private val single = TripEntity("single", at(day.plusDays(1), 7, 42), at(day.plusDays(1), 8, 15), false, 900.0, 30_000, 20_000, 18f)

    private fun seed(activity: ComponentActivity) {
        Settings.System.putString(activity.contentResolver, Settings.System.TIME_12_24, "24")
        TripTracker.init(activity)
        runBlocking(Dispatchers.IO) {
            val dao = FsClockDb.get(activity).tripDao()
            listOf(late, single, early).forEach(dao::save)
        }
    }

    private fun ComponentActivity.showHistory() = setContent {
        MaterialTheme { TripHistoryScreen(visible = true, onBack = { error("Unexpected exit from history") }) }
    }

    private fun SemanticsNode.tree(): Sequence<SemanticsNode> = sequence {
        yield(this@tree)
        children.forEach { yieldAll(it.tree()) }
    }

    private fun nodes(view: View, merged: Boolean = true): List<SemanticsNode> {
        val root = view.allViews.filterIsInstance<RootForTest>().firstOrNull() ?: return emptyList()
        // Roomin muutos voi jo olla kompositiossa, vaikka vierityslistan mittaus vielä odottaa.
        root.measureAndLayoutForTest()
        val owner = root.semanticsOwner
        return (if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode).tree().toList()
    }

    private fun awaitNode(view: View, label: String, merged: Boolean = true, matches: (SemanticsNode) -> Boolean): SemanticsNode {
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

    private fun text(view: View, label: String, merged: Boolean = true) = awaitNode(view, label, merged) {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }

    private fun SemanticsNode.description() =
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()

    private fun described(view: View, part: String) = awaitNode(view, part) { it.description().contains(part) }

    private fun click(node: SemanticsNode) {
        assertTrue(node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
    }

    private fun scrollToBottom(view: View) {
        val scroller = awaitNode(view, "scroll") { it.config.contains(SemanticsActions.ScrollBy) }
        assertTrue(scroller.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 10000f))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    }

    private fun undo(view: View) {
        text(view, "Trip deleted")
        // Ilmoitusta vaihdettaessa myös poistuva Kumoa on hetken semantiikkapuussa.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        click(text(view, "Undo"))
    }

    private fun date(activity: ComponentActivity, date: LocalDate, spoken: Boolean = false) =
        HistoryFormat(activity, AppLocale.current(activity), zone, LocalDate.now(zone), true).date(date, spoken)

    private fun multiTitle(activity: ComponentActivity) =
        "${date(activity, day)} · approx. 07:40–${date(activity, day.plusDays(1))} 00:25"

    private fun draw(view: View): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    // Sama sovellusprosessi pitää Roomin ja matka-arkiston elossa aktiviteettien vaihtuessa.
    @Test fun populatedHistoryNavigationRestorationDeletionAndLargeText() {
        populatedListAndDayShowTheirOwnRowsAndOpenEachTrip()
        deletingFromDayKeepsRemainingTripAndUndoRestoresTheLastTrip()
        restoredTripsReturnToTheirOriginalPageAfterRotation()
        for (language in listOf("fi-rFI", "sv-rFI", "en-rUS")) {
            checkLargeText(language, landscape = false)
            checkLargeText(language, landscape = true)
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
            text(view, singleTitle, merged = false)
            text(view, multiTitle(activity), merged = false)
            text(view, "1 trip · 900 m · 00:30", merged = false)
            text(view, "2 trips · 2.40 km · 03:00", merged = false)
            val listImage = draw(view)

            click(first)
            text(view, singleTitle)
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

    private fun assertTextFits(view: View, label: String) {
        val node = text(view, label, merged = false)
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
    }

    private fun assertRowFits(view: View, description: String, heading: String, figures: String) {
        val row = described(view, description)
        val min = 48 * view.resources.displayMetrics.density
        assertTrue(row.description(), row.boundsInRoot.width >= min && row.boundsInRoot.height >= min)
        assertTrue(row.config.contains(SemanticsActions.OnClick))
        assertTextFits(view, heading)
        assertTextFits(view, figures)
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
            val format = HistoryFormat(activity, locale, zone, LocalDate.now(zone), true)
            val two = activity.resources.getQuantityString(R.plurals.gps_history_count, 2, 2)
            val one = activity.resources.getQuantityString(R.plurals.gps_history_count, 1, 1)
            described(view, two)
            assertEquals(landscape, view.width > view.height)
            assertEquals(1.5f, view.resources.configuration.fontScale, 0f)
            val title = "${format.date(day)} · ${format.interval(early)}"
            val singleTitle = "${format.date(day.plusDays(1))} · ${format.interval(single)}"
            assertRowFits(view, format.interval(single, true), singleTitle, "$one · 900 m · 00:30")
            assertRowFits(view, format.interval(early, true), title, "$two · ${TripFormat.distance(2400.0, locale)} · 03:00")
            click(described(view, two))
            assertTextFits(view, "${activity.getString(R.string.gps_history_day)} · $title")
            scrollToBottom(view)
            for (trip in listOf(late, early)) {
                val speeds = activity.getString(R.string.gps_history_speeds,
                    TripFormat.avgKmh(48.0, locale), TripFormat.wholeKmh(trip.maxSpeedMps))
                val figures = "${TripFormat.distance(trip.distanceM, locale)} · ${TripFormat.clock(trip.totalMs)} · $speeds"
                assertRowFits(view, format.interval(trip, true), format.interval(trip), figures)
            }
            click(described(view, format.interval(early, true)))
            assertTextFits(view, title)
        }
    }
}
