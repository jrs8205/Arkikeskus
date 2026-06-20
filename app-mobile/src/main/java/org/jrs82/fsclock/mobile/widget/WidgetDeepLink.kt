package org.jrs82.fsclock.mobile.widget

import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import org.jrs82.fsclock.mobile.MobileComposeMainActivity

/** Widgetin tap avaa sovelluksen (etusivu). Sektiokohtainen deep-link poistettu — se ei toiminut
 *  Glancen kanssa luotettavasti, ja yksinkertainen "avaa sovellus" riittaa. */
object WidgetDeepLink {
    fun openApp(): Action = actionStartActivity<MobileComposeMainActivity>()
}
