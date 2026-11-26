package org.jrs82.fsclock.mobile.gps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandGateTest {

    @Test fun ensimmainenKomentoHyvaksytaan() {
        assertTrue(CommandGate(700).open(0))
    }

    @Test fun tilanMuutoksenJalkeenKomentojaEiHyvaksytaHetkeen() {
        val gate = CommandGate(700)
        gate.changed(1_000)
        assertFalse(gate.open(1_000))
        assertFalse(gate.open(1_020))
        assertFalse(gate.open(1_699))
        assertTrue(gate.open(1_700))
    }

    @Test fun hylattyKomentoEiPidennaOdotusta() {
        val gate = CommandGate(700)
        gate.changed(1_000)
        assertFalse(gate.open(1_500))
        assertFalse(gate.open(1_650))
        assertTrue(gate.open(1_700))
    }

    @Test fun kellonHyppyTaaksepainEiLukitseKomentoja() {
        val gate = CommandGate(700)
        gate.changed(1_000)
        assertTrue(gate.open(500))
    }
}
