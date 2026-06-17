package org.jrs82.fsclock.mobile

/**
 * Suomen kunta → maakunta -haku. FMI:n säävaroitukset (MeteoAlarm `areaDesc`) annetaan
 * MAAKUNNITTAIN (esim. "Uusimaa", "Pirkanmaa", "Pohjanmaa"), ei kunnittain → [WeatherWarningNotifier]
 * päättelee kotipaikan ([org.jrs82.fsclock.SettingsManager.getHomePlace]) maakunnan tästä ja matchaa
 * sen varoituksen alueeseen. Maakuntien nimet vastaavat MeteoAlarmin käyttämiä nominatiivimuotoja.
 *
 * Kunnat: Manner-Suomi + Ahvenanmaa (tilanne 2024). Jos kotipaikka ei ole listassa (esim. kylä),
 * palautetaan null → notifier nojaa kuntanimi-matchiin (pohjoisen kuntalistaukset).
 */
object FinnishRegions {

    // region -> ";"-erotettu kuntalista (välilyönti sallittu kunnan nimessä, esim. "Koski Tl").
    private val BY_REGION: Map<String, String> = mapOf(
        "Uusimaa" to "Askola;Espoo;Hanko;Helsinki;Hyvinkää;Inkoo;Järvenpää;Karkkila;Kauniainen;Kerava;Kirkkonummi;Lapinjärvi;Lohja;Loviisa;Myrskylä;Mäntsälä;Nurmijärvi;Pornainen;Porvoo;Pukkila;Raasepori;Sipoo;Siuntio;Tuusula;Vantaa;Vihti",
        "Varsinais-Suomi" to "Aura;Kaarina;Kemiönsaari;Koski Tl;Kustavi;Laitila;Lieto;Loimaa;Marttila;Masku;Mynämäki;Naantali;Nousiainen;Oripää;Paimio;Parainen;Pyhäranta;Pöytyä;Raisio;Rusko;Salo;Sauvo;Somero;Taivassalo;Turku;Uusikaupunki;Vehmaa",
        "Satakunta" to "Eura;Eurajoki;Harjavalta;Huittinen;Jämijärvi;Kankaanpää;Karvia;Kokemäki;Merikarvia;Nakkila;Pomarkku;Pori;Rauma;Säkylä;Ulvila",
        "Kanta-Häme" to "Forssa;Hattula;Hausjärvi;Humppila;Hämeenlinna;Janakkala;Jokioinen;Loppi;Riihimäki;Tammela;Ypäjä",
        "Pirkanmaa" to "Akaa;Hämeenkyrö;Ikaalinen;Juupajoki;Kangasala;Kihniö;Kuhmoinen;Lempäälä;Mänttä-Vilppula;Nokia;Orivesi;Parkano;Pirkkala;Punkalaidun;Pälkäne;Ruovesi;Sastamala;Tampere;Urjala;Valkeakoski;Vesilahti;Virrat;Ylöjärvi",
        "Päijät-Häme" to "Asikkala;Hartola;Heinola;Hollola;Iitti;Kärkölä;Lahti;Orimattila;Padasjoki;Sysmä",
        "Kymenlaakso" to "Hamina;Kotka;Kouvola;Miehikkälä;Pyhtää;Virolahti",
        "Etelä-Karjala" to "Imatra;Lappeenranta;Lemi;Luumäki;Parikkala;Rautjärvi;Ruokolahti;Savitaipale;Taipalsaari",
        "Etelä-Savo" to "Enonkoski;Hirvensalmi;Juva;Kangasniemi;Mikkeli;Mäntyharju;Pertunmaa;Pieksämäki;Puumala;Rantasalmi;Savonlinna;Sulkava",
        "Pohjois-Savo" to "Heinävesi;Iisalmi;Kaavi;Keitele;Kiuruvesi;Kuopio;Lapinlahti;Leppävirta;Pielavesi;Rautalampi;Rautavaara;Siilinjärvi;Sonkajärvi;Suonenjoki;Tervo;Tuusniemi;Varkaus;Vesanto;Vieremä",
        "Pohjois-Karjala" to "Ilomantsi;Joensuu;Juuka;Kitee;Kontiolahti;Lieksa;Liperi;Nurmes;Outokumpu;Polvijärvi;Rääkkylä;Tohmajärvi",
        "Keski-Suomi" to "Hankasalmi;Joutsa;Jyväskylä;Jämsä;Kannonkoski;Karstula;Keuruu;Kinnula;Kivijärvi;Konnevesi;Kyyjärvi;Laukaa;Luhanka;Multia;Muurame;Petäjävesi;Pihtipudas;Saarijärvi;Toivakka;Uurainen;Viitasaari;Äänekoski",
        "Etelä-Pohjanmaa" to "Alajärvi;Alavus;Evijärvi;Ilmajoki;Isojoki;Karijoki;Kauhajoki;Kauhava;Kuortane;Kurikka;Lappajärvi;Lapua;Seinäjoki;Soini;Teuva;Vimpeli;Ähtäri",
        "Pohjanmaa" to "Isokyrö;Kaskinen;Korsnäs;Kristiinankaupunki;Kruunupyy;Laihia;Luoto;Maalahti;Mustasaari;Närpiö;Pedersöre;Pietarsaari;Uusikaarlepyy;Vaasa;Vöyri",
        "Keski-Pohjanmaa" to "Halsua;Kannus;Kaustinen;Kokkola;Lestijärvi;Perho;Toholampi;Veteli",
        "Pohjois-Pohjanmaa" to "Alavieska;Haapajärvi;Haapavesi;Hailuoto;Ii;Kalajoki;Kempele;Kuusamo;Kärsämäki;Liminka;Lumijoki;Merijärvi;Muhos;Nivala;Oulainen;Oulu;Pudasjärvi;Pyhäjoki;Pyhäjärvi;Pyhäntä;Raahe;Reisjärvi;Sievi;Siikajoki;Siikalatva;Taivalkoski;Tyrnävä;Utajärvi;Vaala;Ylivieska",
        "Kainuu" to "Hyrynsalmi;Kajaani;Kuhmo;Paltamo;Puolanka;Ristijärvi;Sotkamo;Suomussalmi",
        "Lappi" to "Enontekiö;Inari;Kemi;Kemijärvi;Keminmaa;Kittilä;Kolari;Muonio;Pelkosenniemi;Pello;Posio;Ranua;Rovaniemi;Salla;Savukoski;Simo;Sodankylä;Tervola;Tornio;Utsjoki;Ylitornio",
        "Ahvenanmaa" to "Brändö;Eckerö;Finström;Föglö;Geta;Hammarland;Jomala;Kumlinge;Kökar;Lemland;Lumparland;Maarianhamina;Saltvik;Sottunga;Sund;Vårdö",
    )

    private val MUNI_TO_REGION: Map<String, String> = buildMap {
        for ((region, munis) in BY_REGION) {
            for (m in munis.split(";")) put(m.trim().lowercase(), region)
        }
    }

    /** Kotipaikan maakunta, tai null jos kuntaa ei tunnisteta. */
    fun regionForPlace(place: String?): String? {
        if (place == null) return null
        val p = place.trim().lowercase()
        if (p.isEmpty()) return null
        MUNI_TO_REGION[p]?.let { return it }
        // Kotipaikka voi olla muodossa "Vantaa, Tikkurila" / "Helsinki (Kallio)" → kokeile ensimmäistä osaa.
        val first = p.split(",", "(", "/")[0].trim()
        return MUNI_TO_REGION[first]
    }
}
