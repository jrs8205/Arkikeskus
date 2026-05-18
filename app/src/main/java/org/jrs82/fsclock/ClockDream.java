package org.jrs82.fsclock;

import android.content.pm.ActivityInfo;
import android.service.dreams.DreamService;
import android.view.View;
import android.view.WindowManager;

public class ClockDream extends DreamService {

    private ClockController controller;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            getWindow().setAttributes(lp);
        } catch (Throwable ignored) { }

        setContentView(R.layout.dream);
        View root = findViewById(R.id.shift_container);
        controller = new ClockController(this, getWindow(), root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (controller != null) controller.start();
    }

    @Override
    public void onDreamingStopped() {
        if (controller != null) controller.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (controller != null) controller.stop();
        super.onDetachedFromWindow();
    }
}
