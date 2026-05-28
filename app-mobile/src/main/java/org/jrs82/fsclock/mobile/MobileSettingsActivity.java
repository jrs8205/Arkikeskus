package org.jrs82.fsclock.mobile;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import org.jrs82.fsclock.R;
import org.jrs82.fsclock.SettingsManager;

public class MobileSettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        MobileThemeController.apply(this);
        setTheme(R.style.MobileSettingsTheme);
        super.onCreate(savedInstanceState);
        SettingsManager.get().init(getApplicationContext());

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new MobileSettingsFragment())
                    .commit();
        }
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.mobile_settings_title);
        }
    }
}
