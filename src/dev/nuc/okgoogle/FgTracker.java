package dev.nuc.okgoogle;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * FgTracker — Tracks foreground windows and voice dialogs to dynamically yield the microphone.
 *
 * Enables listening for "Ok Google" across all apps (Launcher, SmartTube, browsers, etc.),
 * and automatically yields the microphone whenever a voice search or dictation overlay is active.
 */
public class FgTracker extends AccessibilityService {
    private static final String TAG = "okgoogle";
    static volatile FgTracker instance;
    static volatile String foregroundPackage = "";
    static volatile String foregroundClass = "";
    static volatile boolean voiceInputActive = false;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "FgTracker accessibility service connected");
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg != null) foregroundPackage = pkg.toString();
        if (cls != null) foregroundClass = cls.toString();

        updateState();
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
    }

    private static boolean checkIsVoice(String pkg, String cls) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase();
        String c = cls != null ? cls.toLowerCase() : "";

        // If the active window is not Google App, it is a standard application (SmartTube, launcher, etc.)
        if (!p.equals("com.google.android.googlequicksearchbox")) {
            return false;
        }

        // Voice dictation, transcription, or transient voice search dialogs
        if (c.contains("transcription")
                || c.contains("intentapi")
                || c.contains("voicesearch")
                || c.contains("voiceinteraction")
                || c.contains("speechrecognizer")
                || c.contains("recognizerintent")
                || c.contains("handsfreeactivity")) {
            return true;
        }

        return false;
    }

    /**
     * Returns true if we are clear to capture microphone audio (no active voice input window).
     */
    static boolean canListen() {
        updateState();
        return !voiceInputActive;
    }
}
