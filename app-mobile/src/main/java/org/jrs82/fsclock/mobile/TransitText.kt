package org.jrs82.fsclock.mobile

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Joukkoliikenteen Context-vapaan logiikan tekstitulos (bannerit, ilmoitukset): resurssi-id +
 * argumentit, jotta logiikka pysyy JVM-testattavana ilman kielisidonnaisia merkkijonoja.
 * Argumentti voi itsekin olla [TransitText] (esim. kulkuvälineen nimi lauseen sisällä).
 */
sealed class TransitText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : TransitText()
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : TransitText()

    /** API:sta tuleva valmis teksti (esim. häiriön otsikko), jota ei käännetä. */
    data class Raw(val text: String) : TransitText()

    fun resolve(context: Context): String = when (this) {
        is Res -> if (args.isEmpty()) context.getString(id) else context.getString(id, *resolveArgs(context, args))
        is Plural -> context.resources.getQuantityString(id, count, *resolveArgs(context, args))
        is Raw -> text
    }
}

private fun resolveArgs(context: Context, args: List<Any>): Array<Any> =
    args.map { if (it is TransitText) it.resolve(context) else it }.toTypedArray()
