package org.jrs82.fsclock.mobile

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
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

    /**
     * Konteksti, jonka tekstit ovat sovellukselle valitulla kielellä. Android 13+:n sovelluskohtainen
     * kieli ei aina päädy palvelun omaan kontekstiin, kun sovelluksella ei ole aktiviteettia (esim.
     * sovellus pyyhkäisty pois viimeisimmistä): taustalla rakennettu ilmoitus näkyisi laitteen kielellä.
     * Vanhemmissa versioissa sovelluskohtaista kieltä ei ole, joten konteksti palautetaan sellaisenaan.
     */
    fun localizedContext(context: Context): Context {
        if (Build.VERSION.SDK_INT < 33) return context
        return try {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (locales == null || locales.isEmpty) {
                context
            } else {
                val conf = Configuration(context.resources.configuration)
                conf.setLocales(locales)
                context.createConfigurationContext(conf)
            }
        } catch (e: Exception) {
            context
        }
    }

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
