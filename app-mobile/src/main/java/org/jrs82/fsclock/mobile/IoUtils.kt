package org.jrs82.fsclock.mobile

import android.content.Context
import org.jrs82.fsclock.R
import java.io.InputStream

/** Ulkoinen tiedosto ylittää sallitun koon (tuonti/palautus). [message] on lokeja varten;
 *  käyttäjälle näytetään [userMessage], koska poikkeuksella ei ole Contextia kielen valintaan. */
internal class FileTooLargeException(maxBytes: Int) :
    Exception("File too large (over ${maxBytes / (1024 * 1024)} MB)") {

    val maxMb: Int = maxBytes / (1024 * 1024)

    fun userMessage(context: Context): String =
        context.getString(R.string.workout_file_too_large, maxMb)
}

/** Käyttäjälle näytettävä syy: kokorajan ylitys käännettynä, muut poikkeukset viestillään. */
internal fun Throwable.displayMessage(context: Context): String =
    (this as? FileTooLargeException)?.userMessage(context) ?: message ?: javaClass.simpleName

/**
 * Lukee streamin kokonaan muistiin, mutta keskeyttää [FileTooLargeException]-poikkeukseen jos
 * koko ylittää rajan. Ulkoisia tiedostoja (GPX/TCX-tuonti, varmuuskopion palautus) ei saa lukea
 * rajatta: ACTION_VIEW/SEND hyväksyy minkä tahansa tiedoston ja rajaton readBytes() voi kaataa
 * sovelluksen OOM:iin.
 */
internal fun readAllLimited(input: InputStream, maxBytes: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) throw FileTooLargeException(maxBytes)
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
