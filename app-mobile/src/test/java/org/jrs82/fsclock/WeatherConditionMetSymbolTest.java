package org.jrs82.fsclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** MET Norwayn symbol_code → WeatherCondition -parsinta (Locationforecast 2.0). */
public class WeatherConditionMetSymbolTest {

    @Test
    public void clearskyDay() {
        WeatherCondition c = WeatherCondition.fromMetSymbol("clearsky_day");
        assertEquals(WeatherCondition.Type.CLEAR, c.type);
        assertFalse(c.isNight);
    }

    @Test
    public void heavyRainShowersNight() {
        WeatherCondition c = WeatherCondition.fromMetSymbol("heavyrainshowers_night");
        assertEquals(WeatherCondition.Type.RAIN, c.type);
        assertEquals(WeatherCondition.Intensity.HEAVY, c.intensity);
        assertTrue(c.isShower);
        assertTrue(c.isNight);
    }

    @Test
    public void partlyCloudyIsNotCloudy() {
        WeatherCondition c = WeatherCondition.fromMetSymbol("partlycloudy_day");
        assertEquals(WeatherCondition.Type.PARTLY_CLOUDY, c.type);
    }

    @Test
    public void lightSleetIsLight() {
        WeatherCondition c = WeatherCondition.fromMetSymbol("lightsleet");
        assertEquals(WeatherCondition.Type.SLEET, c.type);
        assertEquals(WeatherCondition.Intensity.LIGHT, c.intensity);
    }

    @Test
    public void nullOrEmptyIsUnknown() {
        assertEquals(WeatherCondition.Type.UNKNOWN, WeatherCondition.fromMetSymbol(null).type);
        assertEquals(WeatherCondition.Type.UNKNOWN, WeatherCondition.fromMetSymbol("").type);
    }
}
