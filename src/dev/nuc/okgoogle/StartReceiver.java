package dev.nuc.okgoogle;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * BroadcastReceiver to start/stop the WakeService on boot or via explicit intent.
 */
public class StartReceiver extends BroadcastReceiver {
    private static final String TAG = "okgoogle";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : "";
        Intent serviceIntent = new Intent(context, WakeService.class);

        if ("dev.nuc.okgoogle.STOP".equals(action)) {
            Log.i(TAG, "Stop broadcast received, stopping WakeService");
            context.stopService(serviceIntent);
            return;
        }

        Log.i(TAG, "Start broadcast received (" + action + "), starting WakeService");
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start WakeService from receiver", t);
        }
    }
}
