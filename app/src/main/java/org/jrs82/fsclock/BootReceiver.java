package org.jrs82.fsclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)) {
            return;
        }
        try {
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(launch);
            Log.i(TAG, "Arkikeskus-autostart MainActivity launched after boot");
        } catch (Exception e) {
            Log.w(TAG, "Autostart epäonnistui — daily_reboot-moduuli jää varalle", e);
        }
    }
}
