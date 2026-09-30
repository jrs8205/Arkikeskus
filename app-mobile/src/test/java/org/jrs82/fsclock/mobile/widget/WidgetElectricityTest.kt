package org.jrs82.fsclock.mobile.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetElectricityTest {
    private val quarterMs = 15L * 60_000L
    private val q0 = 1_700_000_100_000L - (1_700_000_100_000L % quarterMs)
    private val list = listOf(
        WidgetElectricity.QuarterPrice(q0, 1.5),
        WidgetElectricity.QuarterPrice(q0 + quarterMs, 2.25),
        WidgetElectricity.QuarterPrice(q0 + 2 * quarterMs, -0.5),
    )

    @Test fun encodeDecode_roundTrip() {
        val back = WidgetElectricity.decode(WidgetElectricity.encode(list))
        assertEquals(3, back.size)
        assertEquals(q0, back[0].timestamp)
        assertEquals(1.5, back[0].snt, 0.0)
        assertEquals(q0 + 2 * quarterMs, back[2].timestamp)
        assertEquals(-0.5, back[2].snt, 0.0)
    }

    @Test fun decode_invalidOrEmpty_isEmptyList() {
        assertTrue(WidgetElectricity.decode("").isEmpty())
        assertTrue(WidgetElectricity.decode("garbage").isEmpty())
        assertTrue(WidgetElectricity.decode("[]").isEmpty())
    }

    @Test fun currentPrice_picksQuarterCoveringNow() {
        assertEquals(2.25, WidgetElectricity.currentPrice(list, q0 + quarterMs + 10 * 60_000L), 0.0)
    }

    @Test fun currentPrice_atExactQuarterStart_usesNewQuarter() {
        assertEquals(2.25, WidgetElectricity.currentPrice(list, q0 + quarterMs), 0.0)
        assertEquals(-0.5, WidgetElectricity.currentPrice(list, q0 + 2 * quarterMs), 0.0)
    }

    @Test fun currentPrice_lastMillisecondOfQuarter_stillOldQuarter() {
        assertEquals(1.5, WidgetElectricity.currentPrice(list, q0 + quarterMs - 1), 0.0)
    }

    @Test fun currentPrice_outsideList_isNaN() {
        assertTrue(WidgetElectricity.currentPrice(list, q0 - 1).isNaN())
        assertTrue(WidgetElectricity.currentPrice(list, q0 + 3 * quarterMs).isNaN())
        assertTrue(WidgetElectricity.currentPrice(emptyList(), q0).isNaN())
    }

    @Test fun nextQuarterBoundary_roundsUpToNextQuarter() {
        assertEquals(q0 + quarterMs, WidgetElectricity.nextQuarterBoundary(q0 + 1))
        assertEquals(q0 + quarterMs, WidgetElectricity.nextQuarterBoundary(q0 + quarterMs - 1))
        assertEquals(q0 + quarterMs, WidgetElectricity.nextQuarterBoundary(q0 + 7 * 60_000L))
    }

    @Test fun nextQuarterBoundary_onBoundary_isFollowingQuarter() {
        assertEquals(q0 + quarterMs, WidgetElectricity.nextQuarterBoundary(q0))
    }
}
