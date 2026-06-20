package org.jrs82.fsclock.mobile.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.jrs82.fsclock.mobile.MobileComposeMainActivity
import org.jrs82.fsclock.mobile.WorkoutTrackingService

/** Widgetin tap -> avaa sovellus oikeaan sektioon (olemassa oleva open_section-deep-link). */
object WidgetDeepLink {
    fun deepLinkIntent(ctx: Context, section: String): Intent =
        Intent(ctx, MobileComposeMainActivity::class.java).apply {
            putExtra(WorkoutTrackingService.EXTRA_OPEN_SECTION, section)
            // Yksilöllinen data-Uri per sektio. Intent.filterEquals EI vertaile extroja, joten
            // ilman tätä kaikkien widgettien intentit olisivat "samat" -> Androidin PendingIntent-
            // cache palauttaisi yhden ja saman intentin kaikille -> jokainen widget veisi väärään
            // (samaan) sektioon. Uri tekee jokaisesta uniikin. Toiminta-actionia EI aseteta, jotta
            // MobileComposeMainActivityn ACTION_VIEW-tiedostotuonti ei laukea.
            data = Uri.parse("arkikeskus://widget/$section")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
}
