package org.jrs82.fsclock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

public class MainActivity extends Activity {

    private ClockController controller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.dream);
        View root = findViewById(R.id.shift_container);
        controller = new ClockController(this, getWindow(), root);
        controller.setLongPressCallback(new Runnable() {
            @Override public void run() { showBrightnessDialog(); }
        });
        controller.start();
    }

    @Override
    protected void onDestroy() {
        if (controller != null) controller.stop();
        super.onDestroy();
    }

    private void showBrightnessDialog() {
        final SharedPreferences sp = getSharedPreferences(ClockController.PREFS, Context.MODE_PRIVATE);
        final int oldDay = sp.getInt(ClockController.KEY_BRIGHTNESS_DAY, ClockController.DEFAULT_BRIGHTNESS_DAY);
        final int oldNight = sp.getInt(ClockController.KEY_BRIGHTNESS_NIGHT, ClockController.DEFAULT_BRIGHTNESS_NIGHT);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        panel.setPadding(pad, pad, pad, pad);

        final TextView dayLabel = new TextView(this);
        dayLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        dayLabel.setText(getString(R.string.brightness_day_label) + ": " + oldDay + " %");
        panel.addView(dayLabel);

        final SeekBar daySeek = new SeekBar(this);
        daySeek.setMax(100);
        daySeek.setProgress(oldDay);
        panel.addView(daySeek);

        TextView gap = new TextView(this);
        gap.setHeight((int) (16 * getResources().getDisplayMetrics().density));
        panel.addView(gap);

        final TextView nightLabel = new TextView(this);
        nightLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        nightLabel.setText(getString(R.string.brightness_night_label) + ": " + oldNight + " %");
        panel.addView(nightLabel);

        final SeekBar nightSeek = new SeekBar(this);
        nightSeek.setMax(100);
        nightSeek.setProgress(oldNight);
        panel.addView(nightSeek);

        // Live preview kun käyttäjä vetää: päivä-slideri näyttää päivän, yö-slideri yön kirkkauden
        daySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                dayLabel.setText(getString(R.string.brightness_day_label) + ": " + progress + " %");
                if (fromUser) controller.previewBrightness(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        nightSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                nightLabel.setText(getString(R.string.brightness_night_label) + ": " + progress + " %");
                if (fromUser) controller.previewBrightness(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        new AlertDialog.Builder(this)
                .setTitle(R.string.brightness_dialog_title)
                .setView(panel)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        sp.edit()
                                .putInt(ClockController.KEY_BRIGHTNESS_DAY, daySeek.getProgress())
                                .putInt(ClockController.KEY_BRIGHTNESS_NIGHT, nightSeek.getProgress())
                                .apply();
                        controller.reapplyBrightness();
                    }
                })
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        // palauta entiset arvot — reapplyBrightness lukee SP:n
                        controller.reapplyBrightness();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override public void onCancel(DialogInterface d) {
                        controller.reapplyBrightness();
                    }
                })
                .show();
    }
}
