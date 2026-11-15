package dev.nuc.okgoogle;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * FgTracker — Tracks foreground windows, yields microphone during speech dictation,
 * and auto-dismisses Assistant overlays after an idle timeout (Nest Hub style).
 */
public class FgTracker extends AccessibilityService {
    private static final String TAG = "okgoogle";
    private static final String GSA = "com.google.android.googlequicksearchbox";
    public static final String PREFS_NAME = "portal_okgoogle_prefs";
    public static final String PREF_AUTO_DISMISS = "auto_dismiss";
    public static final String PREF_ENABLED = "service_enabled";
    private static final long AUTO_DISMISS_DELAY_MS = 15000; // 15 seconds idle timeout (Google Nest Hub standard)

    static volatile FgTracker instance;
    static volatile String foregroundPackage = "";
    static volatile String foregroundClass = "";
    static volatile boolean voiceInputActive = false;
    private static volatile boolean autoDismissScheduled = false;
    private static volatile boolean dismissedForCurrentSession = false;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Runnable autoDismissRunnable = new Runnable() {
        @Override
        public void run() {
            triggerAutoDismiss();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "FgTracker accessibility service connected");
        checkAndReviveWakeService();
    }

    @Override
    public void onDestroy() {
        cancelAutoDismiss();
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (!WakeService.isHealthy()) {
            checkAndReviveWakeService();
        }
        int type = event.getEventType();
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (pkg != null) foregroundPackage = pkg.toString();
            if (cls != null) foregroundClass = cls.toString();
            updateState();
        } else if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // User intentionally clicked a button or card: reset the timer
            resetAutoDismissTimer();
        }
    }

    public static void checkAndReviveWakeService() {
        if (instance == null) return;
        SharedPreferences sp = instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!sp.getBoolean(PREF_ENABLED, true)) return;
        if (!WakeService.isHealthy()) {
            Log.i(TAG, "Watchdog (FgTracker): WakeService down or mic deaf, reviving foreground service...");
            WakeService.restartIfUnhealthy(instance);
        }
    }

    private static void updateState() {
        if (instance != null) {
            try {
                AccessibilityNodeInfo root = instance.getRootInActiveWindow();
                if (root != null && root.getPackageName() != null) {
                    foregroundPackage = root.getPackageName().toString();
                }
            } catch (Throwable ignored) { }
        }

        boolean isVoice = checkIsVoice(foregroundPackage, foregroundClass);
        if (isVoice != voiceInputActive) {
            voiceInputActive = isVoice;
            Log.i(TAG, "voiceInputActive=" + voiceInputActive + " (pkg=" + foregroundPackage + " cls=" + foregroundClass + ")");
            if (!voiceInputActive && GSA.equalsIgnoreCase(foregroundPackage)) {
                // Speech recognition completed and Assistant result/suggestions card is displayed:
                // reset the 15s timer so the user has the full window to read and interact.
                resetAutoDismissTimer();
            }
        }

        checkAssistantOverlayState();
    }

    public static void onNewVoiceInvocation() {
        dismissedForCurrentSession = false;
        autoDismissScheduled = false;
    }

    private static void checkAssistantOverlayState() {
        if (instance == null) return;
        boolean isAssistantOverlay = GSA.equalsIgnoreCase(foregroundPackage);

        if (isAssistantOverlay) {
            scheduleAutoDismiss();
        } else {
            dismissedForCurrentSession = false;
            cancelAutoDismiss();
        }
    }

    public static void resetAutoDismissTimer() {
        if (instance == null) return;
        if (!GSA.equalsIgnoreCase(foregroundPackage)) return;
        if (dismissedForCurrentSession) return;
        autoDismissScheduled = false;
        mainHandler.removeCallbacks(autoDismissRunnable);
        scheduleAutoDismiss();
    }

    public static void scheduleAutoDismiss() {
        if (instance == null) return;
        if (dismissedForCurrentSession) {
            return; // Already fired BACK once this turn; do not re-arm and close foreground apps
        }
        SharedPreferences sp = instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean enabled = sp.getBoolean(PREF_AUTO_DISMISS, true);
        if (!enabled) return;

        if (autoDismissScheduled) {
            return; // Already armed! Do not reset countdown on background polling or subtle UI animations
        }

        autoDismissScheduled = true;
        Log.i(TAG, "scheduleAutoDismiss: armed for " + AUTO_DISMISS_DELAY_MS + "ms");
        mainHandler.removeCallbacks(autoDismissRunnable);
        mainHandler.postDelayed(autoDismissRunnable, AUTO_DISMISS_DELAY_MS);
    }

    public static void cancelAutoDismiss() {
        if (autoDismissScheduled) {
            autoDismissScheduled = false;
            mainHandler.removeCallbacks(autoDismissRunnable);
            Log.i(TAG, "cancelAutoDismiss: disarmed");
        }
    }

    private static void triggerAutoDismiss() {
        autoDismissScheduled = false;
        if (instance == null) return;

        try {
            AccessibilityNodeInfo root = instance.getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                foregroundPackage = root.getPackageName().toString();
            }
        } catch (Throwable ignored) { }

        boolean stillAssistant = GSA.equalsIgnoreCase(foregroundPackage);
        Log.i(TAG, "triggerAutoDismiss fired! pkg=" + foregroundPackage + " stillAssistant=" + stillAssistant);

        if (stillAssistant) {
            if (voiceInputActive) {
                Log.i(TAG, "triggerAutoDismiss: voiceInputActive=true, postponing 5s");
                autoDismissScheduled = true;
                mainHandler.postDelayed(autoDismissRunnable, 5000);
                return;
            }
            dismissedForCurrentSession = true;
            Log.i(TAG, "Assistant card idle timeout (15s) reached: auto-dismissing via BACK");
            instance.performGlobalAction(GLOBAL_ACTION_BACK);

            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (instance != null) {
                        try {
                            AccessibilityNodeInfo r = instance.getRootInActiveWindow();
                            if (r != null && r.getPackageName() != null) {
                                foregroundPackage = r.getPackageName().toString();
                            }
                        } catch (Throwable ignored) { }

                        if (GSA.equalsIgnoreCase(foregroundPackage)) {
                            Log.i(TAG, "Assistant still visible after 1st BACK, sending secondary BACK");
                            instance.performGlobalAction(GLOBAL_ACTION_BACK);

                            mainHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (instance != null) {
                                        try {
                                            AccessibilityNodeInfo r2 = instance.getRootInActiveWindow();
                                            if (r2 != null && r2.getPackageName() != null) {
                                                foregroundPackage = r2.getPackageName().toString();
                                            }
                                        } catch (Throwable ignored) { }

                                        if (GSA.equalsIgnoreCase(foregroundPackage)) {
                                            Log.i(TAG, "Assistant still visible after 2x BACK, sending GLOBAL_ACTION_HOME");
                                            instance.performGlobalAction(GLOBAL_ACTION_HOME);
                                        }
                                    }
                                }
                            }, 400);
                        }
                    }
                }
            }, 400);
        } else {
            dismissedForCurrentSession = true;
            Log.i(TAG, "triggerAutoDismiss skipped: foreground is not Assistant (" + foregroundPackage + ")");
        }
    }

    private static boolean checkIsVoice(String pkg, String cls) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase();
        String c = cls != null ? cls.toLowerCase() : "";

        if (!p.equals(GSA)) return false;

        // Transcription or transient voice search dialogs
        return c.contains("transcription")
                || c.contains("intentapi")
                || c.contains("voicesearch")
                || c.contains("voiceinteraction")
                || c.contains("speechrecognizer")
                || c.contains("recognizerintent")
                || c.contains("handsfreeactivity");
    }

    static boolean canListen() {
        return !voiceInputActive;
    }
}
