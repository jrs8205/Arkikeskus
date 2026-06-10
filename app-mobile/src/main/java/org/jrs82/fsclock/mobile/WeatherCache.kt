package org.jrs82.fsclock.mobile

import org.jrs82.fsclock.WeatherData

/**
 * Viimeisin haettu sää — prosessin sisäinen välimuisti lämmintä starttia ja sektiovaihtoja
 * varten. Korvaa MobileMainActivity.sLastWeather-staattikentän, jotta Compose-UI ei ole
 * kytköksissä legacy-Activityyn.
 */
object WeatherCache {
    @Volatile
    @JvmStatic
    var last: WeatherData? = null
}
