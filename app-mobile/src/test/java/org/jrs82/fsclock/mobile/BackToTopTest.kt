package org.jrs82.fsclock.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uutislistojen kelluva Takaisin ylös näkyy vasta, kun listaa on vieritetty pitkälle. */
class BackToTopTest {

    @Test fun lazyListShowsButtonOnlyAfterScrollingPastThreshold() {
        assertFalse(backToTopVisible(0))
        assertFalse(backToTopVisible(BACK_TO_TOP_MIN_INDEX - 1))
        assertTrue(backToTopVisible(BACK_TO_TOP_MIN_INDEX))
        assertTrue(backToTopVisible(99))
    }

    @Test fun scrollColumnShowsButtonAfterOneAndHalfScreens() {
        assertFalse(backToTopVisible(scrollPx = 0, viewportPx = 2000))
        assertFalse(backToTopVisible(scrollPx = 2999, viewportPx = 2000))
        assertTrue(backToTopVisible(scrollPx = 3000, viewportPx = 2000))
    }

    @Test fun scrollColumnWithoutMeasuredViewportHidesButton() {
        // Ennen ensimmäistä mittausta näkymän korkeus on 0.
        assertFalse(backToTopVisible(scrollPx = 5000, viewportPx = 0))
    }
}
