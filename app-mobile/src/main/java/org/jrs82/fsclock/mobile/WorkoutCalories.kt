package org.jrs82.fsclock.mobile

import android.content.Context
import androidx.preference.PreferenceManager
import org.jrs82.fsclock.db.WorkoutEntity

/**
 * Lenkin kaloriarvio profiilitiedoista (samat prefs-avaimet kuin askelsivulla).
 * Kävely: StepCalorieEstimator askelista (fallback 0,5 × kg × km jos askeleita ei ole).
 * Pyöräily: MET-taulukko keskinopeudesta × paino × liikkeelläoloaika.
 * Palauttaa 0 jos painoa ei ole annettu (UI piilottaa silloin kalorit).
 */
internal object WorkoutCalories {

    // Samat avaimet kuin ComposePlacesSteps.kt / profiilidialogi.
    private const val KEY_WEIGHT = "mobile_profile_weight_kg"
    private const val KEY_HEIGHT = "mobile_profile_height_cm"
    private const val KEY_STEP = "mobile_profile_step_length_cm"

    fun estimateKcal(context: Context, w: WorkoutEntity): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val weightKg = prefs.getFloat(KEY_WEIGHT, 0f).toDouble()
        if (weightKg <= 0) return 0
        return if (w.type == WorkoutEntity.TYPE_BIKE) {
            val hours = w.movingTimeMs / 3_600_000.0
            if (hours <= 0) return 0
            val avgKmh = if (w.movingTimeMs > 0) w.distanceM / 1000.0 / hours else 0.0
            val met = when {
                avgKmh < 16 -> 4.0
                avgKmh < 19 -> 6.8
                avgKmh < 22 -> 8.0
                avgKmh < 26 -> 10.0
                else -> 12.0
            }
            Math.round(met * weightKg * hours).toInt()
        } else {
            if (w.steps > 0) {
                val heightCm = prefs.getFloat(KEY_HEIGHT, 0f).toDouble()
                val stepCm = prefs.getFloat(KEY_STEP, 0f).toDouble()
                StepCalorieEstimator.activeKcal(w.steps, heightCm, weightKg, stepCm)
            } else {
                Math.round(0.5 * weightKg * (w.distanceM / 1000.0)).toInt()
            }
        }
    }
}
