package org.jrs82.fsclock.mobile

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.TypedValue

/**
 * Järjestelmän fonttikoko rajataan 150 %:iin, jotta tekstit mahtuvat näkymiin ja widgetteihin
 * (Android 14+ sallii 200 %). Activityt rajataan konfiguraatiolla ([wrap]); widgetin tekstit piirtää
 * launcher järjestelmän fonttikoolla, joten niiden sp-arvo pienennetään ([widgetSp]).
 */
object FontScaleCap {
    const val MAX = 1.5f

    fun effective(fontScale: Float): Float = fontScale.coerceAtMost(MAX)

    /** Activityn attachBaseContextiin. Fonttikoon vaihto luo activityn uudelleen, joten raja seuraa asetusta. */
    fun wrap(base: Context): Context {
        val config = base.resources.configuration
        if (config.fontScale <= MAX) return base
        return base.createConfigurationContext(Configuration(config).apply { fontScale = MAX })
    }

    /** sp-arvo, joka järjestelmän fonttikoossa piirtyy yhtä suureksi kuin [sp] rajatussa koossa. */
    fun widgetSp(context: Context, sp: Float): Float {
        val fontScale = context.resources.configuration.fontScale
        if (fontScale <= MAX) return sp
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ skaalaa isoja tekstejä epälineaarisesti → muunnos alustan käyrillä molempiin suuntiin.
            val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, wrap(context).resources.displayMetrics)
            return TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, px, context.resources.displayMetrics)
        }
        return widgetSpLinear(sp, fontScale)
    }

    internal fun widgetSpLinear(sp: Float, fontScale: Float): Float =
        if (fontScale <= MAX) sp else sp * MAX / fontScale
}
