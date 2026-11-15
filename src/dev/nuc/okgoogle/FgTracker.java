package dev.nuc.okgoogle;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
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
    private static final long AUTO_DISMISS_DELAY_MS = 12000; // 12 seconds idle timeout

    static volatile FgTracker instance;
    static volatile String foregroundPackage = "";
    static volatile String foregroundClass = "";
    static volatile boolean voiceInputActive = false;
    private static volatile boolean autoDismissScheduled = false;

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
        int type = event.getEventType();
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (pkg != null) foregroundPackage = pkg.toString();
            if (cls != null) foregroundClass = cls.toString();
            updateState();
        } else if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // User intentionally clicked a button or card: reset the timer
            autoDismissScheduled = false;
            scheduleAutoDismiss();
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
        }

        checkAssistantOverlayState();
    }

    private static void checkAssistantOverlayState() {
        if (instance == null) return;
        boolean isAssistantOverlay = GSA.equalsIgnoreCase(foregroundPackage);

        if (isAssistantOverlay) {
            scheduleAutoDismiss();
        } else {
            cancelAutoDismiss();
        }
    }

    public static void scheduleAutoDismiss() {
        if (instance == null) return;
        SharedPreferences sp = instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean enabled = sp.getBoolean(PREF_AUTO_DISMISS, true);
        if (!enabled) return;

        if (autoDismissScheduled) {
            return; // Already armed! Do not reset countdown on subtle UI animations
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
        Log.i(TAG, "triggerAutoDismiss fired! pkg=" + foregroundPackage);
        if (instance == null) return;
        boolean stillAssistant = GSA.equalsIgnoreCase(foregroundPackage);

        if (stillAssistant && !WakeService.isOtherAppRecording()) {
            Log.i(TAG, "Assistant card idle timeout (12s) reached: auto-dismissing via BACK");
            boolean ok = instance.performGlobalAction(GLOBAL_ACTION_BACK);
            Log.i(TAG, "performGlobalAction(GLOBAL_ACTION_BACK) result=" + ok);
        } else {
            Log.i(TAG, "triggerAutoDismiss skipped: stillAssistant=" + stillAssistant + " otherAppRec=" + WakeService.isOtherAppRecording());
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
        updateState();
        return !voiceInputActive;
    }
}
