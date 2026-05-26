package org.jrs82.fsclock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.jrs82.fsclock.system.SystemActivity;

public class MainActivity extends Activity {

    private ClockController controller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SettingsManager.get().init(getApplicationContext());
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        WindowCompat.setDecorFitsSystemWindows(getWindow(),
                UiMetrics.isCompactHeight(getResources()));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.dream);
        View root = findViewById(R.id.shift_container);
        controller = new ClockController(this, getWindow(), root);
        controller.setSettingsClickCallback(new Runnable() {
            @Override public void run() { openSettings(); }
        });
        controller.setSystemClickCallback(new Runnable() {
            @Override public void run() { openSystem(); }
        });
        controller.start();
        applySystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // SettingsActivitysta paluun jalkeen pakota kirkkaus uudelleen, jotta
        // mahdolliset muutokset astuvat voimaan heti
        if (controller != null) controller.reapplyBrightness();
        applySystemBars();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Navigaatio- ja statuspalkit voivat tulla takaisin järjestelmäeleiden,
        // dialogien tai muiden Activityjen jälkeen — palauta immersive aina kun
        // tämä Activity saa fokuksen.
        if (hasFocus) applySystemBars();
    }

    private void applySystemBars() {
        View decor = getWindow().getDecorView();
        WindowInsetsControllerCompat ctl = WindowCompat.getInsetsController(getWindow(), decor);
        if (ctl == null) return;
        if (UiMetrics.isCompactHeight(getResources())) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
            ctl.show(WindowInsetsCompat.Type.navigationBars());
            ctl.hide(WindowInsetsCompat.Type.statusBars());
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ctl.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        ctl.hide(WindowInsetsCompat.Type.systemBars());
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

    private void openSystem() {
        try {
            startActivity(new Intent(this, SystemActivity.class));
        } catch (Exception ignored) { }
    }
}
