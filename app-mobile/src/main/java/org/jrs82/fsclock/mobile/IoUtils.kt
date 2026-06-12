package org.jrs82.fsclock.mobile

import java.io.InputStream

/** Ulkoinen tiedosto ylittää sallitun koon (tuonti/palautus) — viesti näytetään käyttäjälle. */
internal class FileTooLargeException(maxBytes: Int) :
    Exception("Tiedosto on liian suuri (yli ${maxBytes / (1024 * 1024)} Mt)")

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
