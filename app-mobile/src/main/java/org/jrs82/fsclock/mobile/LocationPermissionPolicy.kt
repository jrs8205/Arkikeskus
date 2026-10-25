package org.jrs82.fsclock.mobile

/** Sijaintiluvan kyselyn ja asetusten "Automaattinen sijainti" -kytkimen päätökset (yksikkötestattava). */
internal object LocationPermissionPolicy {

    /** Kysytäänkö lupaa käynnistyksessä: kerran per laite ([DeviceLocalPrefs]), ei jos käyttäjä on itse
     *  kytkenyt automaattisen sijainnin pois tai lupa on jo annettu. */
    fun shouldAskInitially(askedOnThisDevice: Boolean, autoLocationOn: Boolean, granted: Boolean): Boolean =
        !askedOnThisDevice && autoLocationOn && !granted

    data class SwitchState(val checked: Boolean, val permissionMissing: Boolean)

    /** Kytkin näyttää päällä vain, kun sijainti oikeasti päivittyy: pelkkä asetus ilman lupaa näytetään
     *  pois päältä ja puuttuvana lupana, jolloin kytkimen napautus avaa lupakyselyn. */
    fun switchState(autoLocationOn: Boolean, granted: Boolean): SwitchState =
        SwitchState(checked = autoLocationOn && granted, permissionMissing = autoLocationOn && !granted)
}
