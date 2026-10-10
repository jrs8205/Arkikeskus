package org.jrs82.fsclock.mobile

import android.content.Context
import androidx.annotation.StringRes
import org.jrs82.fsclock.R
import java.util.Locale

/**
 * Pyhä- ja liputuspäivien näyttönimet. Sekä vapaapaivat.com että [org.jrs82.fsclock.FinnishHolidays]
 * tunnistavat päivän suomenkielisestä nimestä, joten nimi on avain käännökseen. Avaimet ovat dataa
 * (lähteiden tuottamat nimet), eivät näkyvää tekstiä: näkyvä nimi tulee aina resurssista.
 */
object HolidayNames {

    private val BY_FINNISH_NAME: Map<String, Int> = listOf(
        "Uudenvuodenpäivä" to R.string.holiday_new_years_day,
        "Loppiainen" to R.string.holiday_epiphany,
        "Pitkäperjantai" to R.string.holiday_good_friday,
        "Pääsiäispäivä" to R.string.holiday_easter_sunday,
        "2. pääsiäispäivä" to R.string.holiday_easter_monday,
        "Vappuaatto" to R.string.holiday_may_day_eve,
        "Vappu" to R.string.holiday_may_day,
        "Äitienpäivä" to R.string.holiday_mothers_day,
        "Helatorstai" to R.string.holiday_ascension,
        "Helluntai" to R.string.holiday_whit_sunday,
        "Juhannusaatto" to R.string.holiday_midsummer_eve,
        "Juhannuspäivä" to R.string.holiday_midsummer_day,
        "Pyhäinpäivä" to R.string.holiday_all_saints,
        "Isänpäivä" to R.string.holiday_fathers_day,
        "Itsenäisyyspäivä" to R.string.holiday_independence,
        "1. adventti" to R.string.holiday_first_advent,
        "Jouluaatto" to R.string.holiday_christmas_eve,
        "Joulupäivä" to R.string.holiday_christmas_day,
        "Tapaninpäivä" to R.string.holiday_boxing_day,
        "Uudenvuodenaatto" to R.string.holiday_new_years_eve,
        "Runebergin päivä" to R.string.holiday_runeberg,
        "Kalevalan päivä, suomalaisen kulttuurin päivä" to R.string.holiday_kalevala,
        "Minna Canthin päivä, tasa-arvon päivä" to R.string.holiday_minna_canth,
        "Mikael Agricolan päivä, suomen kielen päivä" to R.string.holiday_agricola,
        "Kansallinen veteraanipäivä" to R.string.holiday_veterans,
        "Eurooppa-päivä" to R.string.holiday_europe,
        "J.V. Snellmanin päivä, suomalaisuuden päivä" to R.string.holiday_snellman,
        "Kaatuneitten muistopäivä" to R.string.holiday_memorial,
        "Puolustusvoimain lippujuhlan päivä" to R.string.holiday_defence_forces,
        "Eino Leinon päivä, runon ja suven päivä" to R.string.holiday_eino_leino,
        "Suomen luonnon päivä" to R.string.holiday_nature,
        "Miina Sillanpään päivä, kansalaisvaikuttamisen päivä" to R.string.holiday_miina_sillanpaa,
        "Aleksis Kiven päivä, suomalaisen kirjallisuuden päivä" to R.string.holiday_aleksis_kivi,
        "YK:n päivä" to R.string.holiday_un,
        "Ruotsalaisuuden päivä, Kustaa Aadolfin päivä" to R.string.holiday_swedish_heritage,
        "Lapsen oikeuksien päivä" to R.string.holiday_childrens_rights,
        "Jean Sibeliuksen päivä, suomalaisen musiikin päivä" to R.string.holiday_sibelius,
    ).associate { (name, res) -> normalize(name) to res }

    /** Kirjainkoko ja välilyönnit ohitetaan ("J. V." = "J.V."), jotta lähteiden pienet erot eivät pudota käännöstä. */
    internal fun normalize(name: String): String =
        name.lowercase(Locale.ROOT).filterNot { it.isWhitespace() }

    /** Käännöksen resurssi suomenkieliselle nimelle; null = tuntematon nimi. */
    @StringRes
    fun resId(fiName: String): Int? = BY_FINNISH_NAME[normalize(fiName)]

    /**
     * Näyttönimi UI:n kielellä: tunnettu nimi resurssista, muuten lähteen oma käännös kielelle
     * ([localized], kieli → nimi; vapaapaivat.com antaa sv/en) ja viimeisenä suomenkielinen nimi.
     */
    fun display(context: Context, fiName: String, localized: Map<String, String> = emptyMap()): String =
        display(fiName, AppLocale.language(context), localized) { context.getString(it) }

    internal fun display(
        fiName: String,
        language: String,
        localized: Map<String, String>,
        resolve: (Int) -> String,
    ): String {
        resId(fiName)?.let { return resolve(it) }
        val own = localized[language]?.trim().orEmpty()
        return if (own.isNotEmpty()) own else fiName
    }
}
