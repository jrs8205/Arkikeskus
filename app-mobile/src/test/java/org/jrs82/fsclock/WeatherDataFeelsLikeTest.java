package org.jrs82.fsclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** FMI:n virallisen tuntuu kuin -kaavan ominaisuudet (smartmet-library-newbase,
 *  FmiFeelsLikeTemperature). Vanha wind chill / heat index -pari palautti välillä
 *  10–26,7 °C aina pelkän lämpötilan — nämä testit vartioivat ettei kuollut alue palaa. */
public class WeatherDataFeelsLikeTest {

    private static final double EPS = 1e-6;

    @Test
    public void calmAndReferenceHumidityReturnsTemperature() {
        // Tyyni + rh 50 % (kaavan referenssikosteus) → kaavan pitää palauttaa tasan tC.
        assertEquals(20.0, WeatherData.computeFeelsLike(20.0, 0.0, 50.0, Double.NaN), EPS);
    }

    @Test
    public void missingWindIsTreatedAsCalm() {
        assertEquals(20.0, WeatherData.computeFeelsLike(20.0, Double.NaN, 50.0, Double.NaN), EPS);
    }

    @Test
    public void windCoolsInsideOldDeadZone() {
        // 16 °C + 5 m/s: vanha kaava palautti tasan 16,0 — uuden pitää viilentää selvästi.
        double feels = WeatherData.computeFeelsLike(16.0, 5.0, 70.0, Double.NaN);
        assertTrue("odotettiin alle 15,0, oli " + feels, feels < 15.0);
    }

    @Test
    public void strongerWindFeelsColder() {
        double gentle = WeatherData.computeFeelsLike(10.0, 2.0, 60.0, Double.NaN);
        double strong = WeatherData.computeFeelsLike(10.0, 10.0, 60.0, Double.NaN);
        assertTrue(strong < gentle);
    }

    @Test
    public void humidHeatFeelsHotter() {
        double feels = WeatherData.computeFeelsLike(30.0, 0.0, 90.0, Double.NaN);
        assertTrue("odotettiin yli 30, oli " + feels, feels > 30.0);
    }

    @Test
    public void radiationTermAddsWarmthInCalm() {
        // rad=800 W/m², tyyni: 0,7 * 0,07 * 800 / 10 - 0,25 = +3,67 °C.
        assertEquals(23.67, WeatherData.computeFeelsLike(20.0, 0.0, 50.0, 800.0), EPS);
    }

    @Test
    public void missingTemperatureReturnsNan() {
        assertTrue(Double.isNaN(
                WeatherData.computeFeelsLike(Double.NaN, 5.0, 50.0, Double.NaN)));
    }
}
