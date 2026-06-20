package org.jrs82.fsclock.mobile.widget

import android.content.Context
import android.content.Intent
import org.jrs82.fsclock.mobile.MobileComposeMainActivity
import org.jrs82.fsclock.mobile.WorkoutTrackingService

/** Widgetin tap -> avaa sovellus oikeaan sektioon (olemassa oleva open_section-deep-link). */
object WidgetDeepLink {
    fun deepLinkIntent(ctx: Context, section: String): Intent =
        Intent(ctx, MobileComposeMainActivity::class.java).apply {
            putExtra(WorkoutTrackingService.EXTRA_OPEN_SECTION, section)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
