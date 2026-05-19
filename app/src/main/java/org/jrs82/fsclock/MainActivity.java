package org.jrs82.fsclock;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.core.view.WindowCompat;

public class MainActivity extends Activity {

    private ClockController controller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SettingsManager.get().init(getApplicationContext());
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.dream);
        View root = findViewById(R.id.shift_container);
        controller = new ClockController(this, getWindow(), root);
        controller.setLongPressCallback(new Runnable() {
            @Override public void run() { openSettings(); }
        });
        controller.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // SettingsActivitysta paluun jalkeen pakota kirkkaus uudelleen, jotta
        // mahdolliset muutokset astuvat voimaan heti
        if (controller != null) controller.reapplyBrightness();
    }

    @Override
    protected void onDestroy() {
        if (controller != null) controller.stop();
        super.onDestroy();
    }

    private void openSettings() {
        try {
            startActivity(new Intent(this, SettingsActivity.class));
        } catch (Exception ignored) { }
    }
}
