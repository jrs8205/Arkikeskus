package org.jrs82.fsclock.mobile;

/** Suuntaa-antava askelpohjainen kalorikulutusarvio. Ei tarkka (ei huomioi vauhtia, mäkiä, sykettä),
 *  mutta antaa järkevän "aktiivinen kulutus" -luvun painosta, pituudesta ja askelmäärästä. BMR
 *  Mifflin-St Jeor -kaavalla. Kaava ChatGPT:n ohjeen mukaan. */
final class StepCalorieEstimator {

    private StepCalorieEstimator() {}

    /** Askelpituus metreinä: oma arvo jos annettu (cm), muuten pituudesta johdettu. */
    static double stepLengthMeters(double heightCm, double customStepCm) {
        if (customStepCm > 0) return customStepCm / 100.0;
        if (heightCm <= 0) return 0.70;
        return heightCm * 0.00414;
    }

    static double distanceKm(long steps, double heightCm, double customStepCm) {
        if (steps <= 0) return 0.0;
        return steps * stepLengthMeters(heightCm, customStepCm) / 1000.0;
    }

    /** Aktiiviset kävelykalorit ≈ 0,5 * paino_kg * matka_km. */
    static int activeKcal(long steps, double heightCm, double weightKg, double customStepCm) {
        if (steps <= 0 || weightKg <= 0) return 0;
        return (int) Math.round(0.5 * weightKg * distanceKm(steps, heightCm, customStepCm));
    }

    /** Perusaineenvaihdunta (Mifflin-St Jeor). sex: "female" → naisen kaava, muuten miehen. */
    static int bmr(double weightKg, double heightCm, int age, String sex) {
        if (weightKg <= 0 || heightCm <= 0 || age <= 0) return 0;
        double base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * age;
        if ("female".equals(sex)) return (int) Math.round(base - 161.0);
        return (int) Math.round(base + 5.0);
    }

    /** Päivän kokonaisarvio = BMR + aktiiviset askelkalorit. 0 jos BMR:ää ei voida laskea. */
    static int totalDailyKcal(int bmr, int activeKcal) {
        if (bmr <= 0) return 0;
        return bmr + Math.max(0, activeKcal);
    }
}
