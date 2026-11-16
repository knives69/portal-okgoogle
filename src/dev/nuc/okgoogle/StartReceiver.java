package dev.nuc.okgoogle;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * BroadcastReceiver to start/stop the WakeService on boot, explicit intent, or watchdog timer.
 */
public class StartReceiver extends BroadcastReceiver {
    private static final String TAG = "okgoogle";
    public static final String ACTION_WATCHDOG = "dev.nuc.okgoogle.WATCHDOG";
    public static final String ACTION_START = "dev.nuc.okgoogle.START";
    public static final String ACTION_STOP = "dev.nuc.okgoogle.STOP";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : "";
        WakeService.ensureAssistantConfigured(context);
        Intent serviceIntent = new Intent(context, WakeService.class);
        SharedPreferences sp = context.getSharedPreferences(FgTracker.PREFS_NAME, Context.MODE_PRIVATE);

        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "Stop broadcast received, stopping WakeService and cancelling watchdog");
            sp.edit().putBoolean(FgTracker.PREF_ENABLED, false).apply();
            cancelWatchdog(context);
            context.stopService(serviceIntent);
            return;
        }

        if (ACTION_WATCHDOG.equals(action)) {
            if (!sp.getBoolean(FgTracker.PREF_ENABLED, true)) {
                Log.d(TAG, "Watchdog tick: service disabled by user preference");
                return;
            }
            if (WakeService.isHealthy()) {
                Log.d(TAG, "Watchdog tick: WakeService running healthy & capturing audio");
                return;
            }
            Log.w(TAG, "Watchdog tick: WakeService NOT healthy (down or mic deaf)! Reviving service...");
            scheduleWatchdog(context);
            WakeService.restartIfUnhealthy(context);
            return;
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action) && !sp.getBoolean(FgTracker.PREF_ENABLED, true)) {
            Log.i(TAG, "Boot completed: service explicitly disabled by user preference, not starting");
            return;
        } else {
            Log.i(TAG, "Start broadcast received (" + action + "), starting WakeService");
            sp.edit().putBoolean(FgTracker.PREF_ENABLED, true).apply();
        }

        try {
            scheduleWatchdog(context);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start WakeService from receiver", t);
        }
    }

    public static void scheduleWatchdog(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(context, StartReceiver.class);
            i.setAction(ACTION_WATCHDOG);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(context, 1001, i, flags);
            long interval = AlarmManager.INTERVAL_FIFTEEN_MINUTES;
            long triggerAt = SystemClock.elapsedRealtime() + interval;
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, interval, pi);
            Log.d(TAG, "Watchdog alarm scheduled (every 15 min)");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to schedule watchdog alarm", t);
        }
    }

    public static void cancelWatchdog(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(context, StartReceiver.class);
            i.setAction(ACTION_WATCHDOG);
            int flags = PendingIntent.FLAG_NO_CREATE;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(context, 1001, i, flags);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
                Log.i(TAG, "Watchdog alarm cancelled");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to cancel watchdog alarm", t);
        }
    }
}
