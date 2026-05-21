package org.jrs82.fsclock;

import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private BrightnessController brightness;
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
            if (key == null || brightness == null) return;
            switch (key) {
                case SettingsManager.KEY_DAY_BRIGHTNESS:
                case SettingsManager.KEY_NIGHT_BRIGHTNESS:
                case SettingsManager.KEY_DAY_MORNING_HOUR:
                case SettingsManager.KEY_NIGHT_EVENING_HOUR:
                case SettingsManager.KEY_TEST_MODE_TYPE:
                case SettingsManager.KEY_TEST_MODE_UNTIL:
                    brightness.applyNow();
                    break;
                default:
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.SettingsTheme);
        super.onCreate(savedInstanceState);
        SettingsManager.get().init(getApplicationContext());

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new SettingsFragment())
                    .commit();
        }
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.settings_title);
        }

        brightness = new BrightnessController(getWindow(), SettingsManager.get());
        brightness.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Pakota uudelleenasetus paluukohdissa: järjestelmä on voinut palauttaa
        // Windowin oletuskirkkauteen kun Activity oli taustalla.
        if (brightness != null) brightness.reapply();
        SettingsManager.get().registerListener(prefsListener);
    }

    @Override
    protected void onPause() {
        SettingsManager.get().unregisterListener(prefsListener);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (brightness != null) brightness.stop();
        super.onDestroy();
    }
}
