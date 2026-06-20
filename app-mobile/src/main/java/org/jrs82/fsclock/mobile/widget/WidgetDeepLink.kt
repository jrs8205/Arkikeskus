package org.jrs82.fsclock.mobile.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.jrs82.fsclock.mobile.MobileComposeMainActivity
import org.jrs82.fsclock.mobile.WorkoutTrackingService

/** Widgetin tap -> avaa sovellus oikeaan sektioon (olemassa oleva open_section-deep-link). */
object WidgetDeepLink {
    fun section(ctx: Context, section: String): PendingIntent {
        val intent = Intent(ctx, MobileComposeMainActivity::class.java).apply {
            putExtra(WorkoutTrackingService.EXTRA_OPEN_SECTION, section)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            ctx, section.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun deepLinkIntent(ctx: Context, section: String): Intent =
        Intent(ctx, MobileComposeMainActivity::class.java).apply {
            putExtra(WorkoutTrackingService.EXTRA_OPEN_SECTION, section)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
