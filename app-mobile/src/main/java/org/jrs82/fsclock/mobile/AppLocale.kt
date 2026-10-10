package org.jrs82.fsclock.mobile

import android.content.Context
import java.util.Locale

/** Sovelluksen kieli: fi ja sv sellaisenaan, kaikki muut englanniksi. Sama sääntö kuin
 *  resurssiresoluutiolla (values/ = en), jotta muotoilu ja API-parametrit seuraavat näkyvää kieltä.
 *  Locale-versiot ovat puhtaita, jotta logiikka on testattavissa ilman Contextia. */
object AppLocale {
    val FINNISH: Locale = Locale("fi", "FI")
    val SWEDISH: Locale = Locale("sv", "FI")
    val ENGLISH: Locale = Locale.UK

    fun resolve(tags: List<String>): Locale {
        for (tag in tags) {
            when (Locale.forLanguageTag(tag).language) {
                "fi" -> return FINNISH
                "sv" -> return SWEDISH
                "en" -> return ENGLISH
            }
        }
        return ENGLISH
    }

    fun current(context: Context): Locale {
        val locales = context.resources.configuration.locales
        val tags = (0 until locales.size()).map { locales[it].toLanguageTag() }
        return resolve(tags)
    }

    fun language(context: Context): String = current(context).language

    fun isSwedish(context: Context): Boolean = current(context).language == "sv"

    fun fmiInfoKey(locale: Locale): String = when (locale.language) {
        "sv" -> "info_sv"
        "en" -> "info_en"
        else -> "info_fi"
    }

    fun fmiInfoKey(context: Context): String = fmiInfoKey(current(context))

    /** Digitransitilla ei ole englanninkielisiä pysäkkinimiä → en putoaa suomeen. */
    fun digitransitLanguage(locale: Locale): String = if (locale.language == "sv") "sv" else "fi"

    fun digitransitLanguage(context: Context): String = digitransitLanguage(current(context))

    /** Almanakka-API:n nimityyppi: suomenruotsalainen kalenteri vain ruotsinkieliselle UI:lle. */
    fun almanakkaType(locale: Locale): String = if (locale.language == "sv") "ruotsi" else "suomi"

    fun almanakkaType(context: Context): String = almanakkaType(current(context))
}
