package org.jrs82.fsclock.mobile.widget

import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import org.jrs82.fsclock.mobile.MobileComposeMainActivity
import org.jrs82.fsclock.mobile.WorkoutTrackingService

/**
 * Widgetin tap -> avaa sovellus oikeaan sektioon.
 *
 * Glancen TUETTU tapa on ActionParameters: Glance toimittaa arvon intentin extrana avaimen nimella
 * (= EXTRA_OPEN_SECTION), jonka MobileComposeMainActivity jo lukee. Raaka Intent + putExtra EI
 * valittynyt luotettavasti widget-napautuksessa (Glance ei sailyta mielivaltaisen Intentin extroja
 * -> jokainen widget avasi sovelluksen etusivulle). Todennettu Android-dokumentaatiosta + laitteella.
 */
object WidgetDeepLink {
    private val SECTION_KEY = ActionParameters.Key<String>(WorkoutTrackingService.EXTRA_OPEN_SECTION)

    fun openSection(section: String): Action =
        actionStartActivity<MobileComposeMainActivity>(actionParametersOf(SECTION_KEY to section))
}
