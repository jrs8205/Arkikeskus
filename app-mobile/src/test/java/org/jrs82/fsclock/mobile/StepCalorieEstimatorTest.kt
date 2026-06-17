package org.jrs82.fsclock.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/** Askelmatka-/askelpituuskaavojen testit (käytetään askelsivun km-arviossa). */
class StepCalorieEstimatorTest {

    @Test fun customStepLengthUsed() {
        // Oma askelmitta 75 cm -> 0,75 m (pituus jätetään huomiotta).
        assertEquals(0.75, StepCalorieEstimator.stepLengthMeters(180.0, 75.0), 0.001)
    }

    @Test fun stepLengthDerivedFromHeight() {
        // Ei omaa askelmittaa -> pituus * 0,00414.
        assertEquals(180.0 * 0.00414, StepCalorieEstimator.stepLengthMeters(180.0, 0.0), 0.0001)
    }

    @Test fun stepLengthDefaultWhenNoData() {
        assertEquals(0.70, StepCalorieEstimator.stepLengthMeters(0.0, 0.0), 0.001)
    }

    @Test fun distanceFromStepsAndCustomLength() {
        // 10000 askelta * 0,75 m = 7500 m = 7,5 km.
        assertEquals(7.5, StepCalorieEstimator.distanceKm(10000, 180.0, 75.0), 0.001)
        assertEquals(0.0, StepCalorieEstimator.distanceKm(0, 180.0, 75.0), 0.001)
    }
}
