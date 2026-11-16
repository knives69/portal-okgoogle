package dev.nuc.okgoogle;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WakeService — Universal offline hotword trigger for Meta Portal.
 *
 * 1. Listens continuously in the background across any app using Vosk.
 * 2. 24/7 reliability via PARTIAL_WAKE_LOCK (no sleep when display turns off).
 * 3. Plays low-latency rising chime upon wake-word detection (Nest Hub earcon).
 * 4. Yields microphone dynamically during other app recordings (SmartTube, dictation).
 * 5. Launches Google Assistant / Gemini via ACTION_VOICE_COMMAND.
 */
public class WakeService extends Service {
    private static final String TAG = "okgoogle";
    private static final boolean DEBUG = false;
    private static final int RATE = 16000;
    private static final String GSA = "com.google.android.googlequicksearchbox";
    public static final String MODEL_PATH = "/sdcard/Android/data/dev.nuc.okgoogle/files/model";
    public static final String PREF_CHIME_ENABLED = "chime_enabled";
    public static final String PREF_VAD_THRESHOLD = "vad_threshold";
    public static final int DEFAULT_VAD_THRESHOLD = 450;

    public static volatile boolean isRunning = false;
    public static volatile long lastAudioChunkTime = 0;
    private static volatile WakeService instance;

    /**
     * Strict target wake words. Every trigger requires at least 3-4 syllables with "google" or "gugel".
     * Eliminated all short/conversational false-positive magnets ("ok web", "oye", single words).
     */
    private static final String[] WAKE = {
        "ok google", "hey google", "okay google", "ok gugel", "hey gugel"
    };

    /** Constrains Vosk decoder graph strictly to target keywords + unknown token, slashing false positives. */
    private static final String GRAMMAR =
        "[\"ok google\", \"hey google\", \"okay google\", \"ok gugel\", \"hey gugel\", \"[unk]\"]";

    private volatile boolean alive;
    private Model model;
    private Recognizer recognizer;
    private final byte[][] preRoll = new byte[4][3200];
    private final int[] preRollLen = new int[4];
    private int preRollIdx = 0;
    private final int[] recentPeak = new int[50]; // 5.0 seconds rolling history
    private final int[] recentRms = new int[50];
    private int statIdx = 0;
    private volatile AudioRecord activeRecord;
    private volatile boolean otherAppRecording;
    private AudioManager.AudioRecordingCallback recordingCallback;
    private PowerManager.WakeLock wakeLock;
    private SoundPool soundPool;
    private int chimeSoundId = -1;
    private volatile boolean chimeLoaded = false;
    private static final AtomicInteger generation = new AtomicInteger(0);
    private long lastHeartbeatLog = 0;

    public static boolean isOtherAppRecording() {
        return instance != null && instance.otherAppRecording;
    }

    public static boolean isHealthy() {
        if (!isRunning || instance == null) return false;
        // If mic is intentionally yielded to another app or assistant overlay, consider healthy
        if (isOtherAppRecording() || !FgTracker.canListen()) return true;
        if (lastAudioChunkTime == 0) return true; // Initializing grace window
        return (SystemClock.elapsedRealtime() - lastAudioChunkTime) < 90_000;
    }

    public static void restartIfUnhealthy(Context ctx) {
        generation.incrementAndGet(); // Invalidates any prior running audio thread immediately
        if (instance != null) {
            instance.alive = false;
            if (instance.activeRecord != null) {
                try {
                    Log.w(TAG, "Forcing active AudioRecord release to unstick native stream");
                    instance.activeRecord.release();
                } catch (Throwable ignored) { }
                instance.activeRecord = null;
            }
        }
        isRunning = false;
        try {
            Intent si = new Intent(ctx, WakeService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(si);
            } else {
                ctx.startService(si);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to restart WakeService", t);
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        ensureAccessibilityEnabled(this);
        ensureAssistantConfigured(this);
        acquireWakeLock();
        initSoundPool();
    }

    public static void ensureAccessibilityEnabled(Context ctx) {
        try {
            String serviceName = ctx.getPackageName() + "/" + FgTracker.class.getName();
            String enabledServices = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices == null || !enabledServices.contains(serviceName)) {
                String updated = (enabledServices == null || enabledServices.isEmpty())
                        ? serviceName : enabledServices + ":" + serviceName;
                Settings.Secure.putString(ctx.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
                Settings.Secure.putInt(ctx.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                Log.i(TAG, "Auto-enabled accessibility service: " + serviceName);
            }
        } catch (Throwable t) {
            // Requires WRITE_SECURE_SETTINGS or user setting toggle
        }
    }

    private static final String TARGET_VAS = "com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService";
    private static final String TARGET_ASSISTANT = "com.google.android.googlequicksearchbox/.GoogleAppImplicitActionAssistGatewayInternal";

    public static void ensureAssistantConfigured(Context ctx) {
        try {
            String currentVas = Settings.Secure.getString(ctx.getContentResolver(), "voice_interaction_service");
            if (currentVas == null || currentVas.isEmpty() || !TARGET_VAS.equals(currentVas)) {
                Settings.Secure.putString(ctx.getContentResolver(), "voice_interaction_service", TARGET_VAS);
                Log.i(TAG, "Auto-configured voice_interaction_service: " + TARGET_VAS);
            }
            String currentAssistant = Settings.Secure.getString(ctx.getContentResolver(), "assistant");
            if (currentAssistant == null || currentAssistant.isEmpty()) {
                Settings.Secure.putString(ctx.getContentResolver(), "assistant", TARGET_ASSISTANT);
                Log.i(TAG, "Auto-configured assistant: " + TARGET_ASSISTANT);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to auto-configure assistant settings: " + t.getMessage());
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            ensureAccessibilityEnabled(this);
            ensureAssistantConfigured(this);
            channel();
            startForeground(1, notification("Listening for \"Ok Google\""));
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed", t);
        }
        if (alive) {
            isRunning = true;
            return START_STICKY;
        }
        alive = true;
        isRunning = true;
        final int myGen = generation.incrementAndGet();
        lastAudioChunkTime = SystemClock.elapsedRealtime();
        SharedPreferences sp = getSharedPreferences(FgTracker.PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(FgTracker.PREF_ENABLED, true).apply();
        StartReceiver.scheduleWatchdog(this);
        setupRecordingMonitor();
        new Thread(new Runnable() { @Override public void run() { loop(myGen); } }, "okgoogle-audio-" + myGen).start();
        return START_STICKY;
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "portal:okgoogle_wakelock");
                    wakeLock.setReferenceCounted(false);
                    wakeLock.acquire();
                    Log.i(TAG, "PARTIAL_WAKE_LOCK acquired for 24/7 background audio capture");
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to acquire wake lock", t);
            }
        }
    }

    private void initSoundPool() {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder()
                    .setMaxStreams(2)
                    .setAudioAttributes(attrs)
                    .build();
            soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                @Override
                public void onLoadComplete(SoundPool sp, int sampleId, int status) {
                    if (status == 0) {
                        chimeLoaded = true;
                    }
                }
            });
            chimeSoundId = soundPool.load(this, R.raw.chime, 1);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize SoundPool chime", t);
        }
    }

    public static void playChime(Context ctx) {
        if (instance != null && instance.soundPool != null && instance.chimeLoaded && instance.chimeSoundId != -1) {
            instance.soundPool.play(instance.chimeSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
        }
    }

    private void setupRecordingMonitor() {
        if (Build.VERSION.SDK_INT >= 24) {
            final AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                recordingCallback = new AudioManager.AudioRecordingCallback() {
                    @Override
                    public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                        if (configs == null) return;
                        AudioRecord rec = activeRecord;
                        int mySession = (rec != null) ? rec.getAudioSessionId() : -1;
                        boolean anotherActive = false;
                        for (AudioRecordingConfiguration cfg : configs) {
                            int sid = cfg.getClientAudioSessionId();
                            if (mySession != -1 && sid == mySession) {
                                continue; // Skip our own AudioRecord session
                            }
                            anotherActive = true;
                            break;
                        }
                        if (anotherActive != otherAppRecording) {
                            otherAppRecording = anotherActive;
                            Log.i(TAG, "Hardware recording monitor: otherAppRecording=" + otherAppRecording);
                        }
                    }
                };
                am.registerAudioRecordingCallback(recordingCallback, new Handler(Looper.getMainLooper()));
            }
        }
    }

    private void channel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel("okgoogle", "okgoogle",
                NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
    }

    private Notification notification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), 0);
        return new Notification.Builder(this, "okgoogle")
                .setContentTitle("Portal Ok Google")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private AudioRecord openRec() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE * 2));
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed initialization");
            r.release();
            return null;
        }
        activeRecord = r;
        try {
            r.startRecording();
        } catch (Throwable t) {
            Log.e(TAG, "AudioRecord startRecording failed", t);
            activeRecord = null;
            r.release();
            return null;
        }
        return r;
    }

    private boolean canCapture() {
        return FgTracker.canListen() && !otherAppRecording;
    }

    private void loop(final int myGen) {
        File internal = new File(getFilesDir(), "model");
        File external = new File(MODEL_PATH);
        File modelDir = (internal.isDirectory() && new File(internal, "conf").isDirectory()) ? internal : external;

        int attempts = 0;
        while (alive && generation.get() == myGen) {
            try {
                model = new Model(modelDir.getAbsolutePath());
                recognizer = new Recognizer(model, (float) RATE, GRAMMAR);
                Log.i(TAG, "Vosk model & restricted grammar recognizer loaded successfully from " + modelDir.getAbsolutePath());
                break;
            } catch (Throwable t) {
                if (!alive || generation.get() != myGen) return;
                if (++attempts > 10) {
                    Log.e(TAG, "Failed to load Vosk model after 10 attempts from " + modelDir.getAbsolutePath(), t);
                    isRunning = false;
                    stopSelf();
                    return;
                }
                Log.w(TAG, "Model/storage not ready yet (attempt " + attempts + "/10): " + t.getMessage() + ", retrying in 1.5s...");
                sleep(1500);
            }
        }

        byte[] buf = new byte[3200];
        int consecutiveAudioFailures = 0;
        while (alive && generation.get() == myGen) {
            if (!canCapture()) {
                sleep(400);
                continue;
            }
            AudioRecord rec = openRec();
            if (rec == null) {
                consecutiveAudioFailures++;
                long waitMs = Math.min(16000, 2000L * (1 << (consecutiveAudioFailures - 1)));
                Log.w(TAG, "AudioRecord init failed (attempt " + consecutiveAudioFailures + "/4), retrying in " + waitMs + "ms");
                sleep(waitMs);
                if (consecutiveAudioFailures >= 4) {
                    Log.e(TAG, "AudioRecord failed to initialize 4 consecutive times, delegating to watchdog");
                    break;
                }
                continue;
            }
            activeRecord = rec;

            String heard = listen(rec, buf, recognizer, myGen);
            try { rec.stop(); rec.release(); } catch (Throwable ignored) { }
            activeRecord = null;

            if (heard == null || !alive || generation.get() != myGen) {
                if (generation.get() != myGen) break;
                if (SystemClock.elapsedRealtime() - lastAudioChunkTime > 90_000) {
                    consecutiveAudioFailures++;
                    long waitMs = Math.min(16000, 2000L * (1 << (consecutiveAudioFailures - 1)));
                    Log.w(TAG, "Silent audio >90s (attempt " + consecutiveAudioFailures + "/4), backoff " + waitMs + "ms");
                    sleep(waitMs);
                    if (consecutiveAudioFailures >= 4) {
                        Log.e(TAG, "Silent audio for 4 consecutive capture sessions (>90s), stopping for watchdog resurrection");
                        break;
                    }
                } else {
                    consecutiveAudioFailures = 0;
                }
                continue;
            }
            consecutiveAudioFailures = 0;

            Log.i(TAG, "Wake word detected: \"" + heard + "\", releasing microphone...");

            // Play earcon / chime immediately if enabled
            SharedPreferences sp = getSharedPreferences(FgTracker.PREFS_NAME, Context.MODE_PRIVATE);
            if (sp.getBoolean(PREF_CHIME_ENABLED, true) && soundPool != null && chimeLoaded && chimeSoundId != -1) {
                soundPool.play(chimeSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
            }

            // Brief pause allowing Qualcomm HAL to transition input stream to standby
            sleep(250);
            launchVoice(this);

            // Arm auto-dismiss timer on assistant window (reset session lock)
            FgTracker.onNewVoiceInvocation();
            FgTracker.scheduleAutoDismiss();

            // Dynamic wait while Google Assistant or Gemini responds
            long start = System.currentTimeMillis();
            sleep(2500);
            while (alive && generation.get() == myGen && System.currentTimeMillis() - start < 15000) {
                if (!canCapture()) {
                    sleep(400);
                } else if (System.currentTimeMillis() - start >= 6000) {
                    break;
                } else {
                    sleep(200);
                }
            }
        }

        if (generation.get() == myGen) {
            if (recognizer != null) {
                try { recognizer.close(); } catch (Throwable ignored) { }
                recognizer = null;
            }
            if (model != null) {
                try { model.close(); } catch (Throwable ignored) { }
                model = null;
            }
            isRunning = false;
            stopSelf();
        } else {
            Log.i(TAG, "Audio thread (gen " + myGen + ") exited cleanly; newer generation " + generation.get() + " active");
        }
    }

    /** Returns recognized keyword string upon wake detection, or null when stream yields. */
    private String listen(AudioRecord rec, byte[] buf, Recognizer r, final int myGen) {
        if (r == null) return null;
        r.reset();
        int stalls = 0;
        int silences = 0;
        int loopCount = 0;
        int aboveRun = 0;
        int belowRun = 0;
        long lastAmpLog = 0;
        String lastPartial = "";

        SharedPreferences sp = getSharedPreferences(FgTracker.PREFS_NAME, Context.MODE_PRIVATE);
        int vadThreshold = sp.getInt(PREF_VAD_THRESHOLD, DEFAULT_VAD_THRESHOLD);

        boolean inSpeech = false;
        preRollIdx = 0;
        for (int i = 0; i < 4; i++) preRollLen[i] = 0;

        try {
            while (alive && generation.get() == myGen) {
            if (SystemClock.elapsedRealtime() - lastAudioChunkTime > 90_000) {
                Log.w(TAG, "Audio stream silent watchdog: >90s without valid chunk, reopening stream");
                return null;
            }

            int n = rec.read(buf, 0, buf.length);
            if (n <= 0) {
                if (++stalls > 15) {
                    Log.w(TAG, "Audio stream stalled or interrupted, yielding");
                    return null;
                }
                sleep(10);
                continue;
            }
            stalls = 0;
            lastAudioChunkTime = SystemClock.elapsedRealtime();

            if (!canCapture()) {
                Log.i(TAG, "External voice input or recording detected, yielding mic");
                return null;
            }

            // Refresh threshold periodically from preferences
            if (++loopCount % 10 == 0) {
                vadThreshold = sp.getInt(PREF_VAD_THRESHOLD, DEFAULT_VAD_THRESHOLD);
            }

            long sumSq = 0;
            long peak = 0;
            int samples = n / 2;
            for (int i = 0; i + 1 < n; i += 2) {
                short v = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
                int abs = (v < 0) ? -v : v;
                if (abs > peak) peak = abs;
                sumSq += (long) v * v;
            }
            int rms = (samples > 0) ? (int) Math.sqrt(sumSq / samples) : 0;
            long amp = peak;

            // Update rolling 5-second acoustic history
            recentPeak[statIdx] = (int) peak;
            recentRms[statIdx] = rms;
            statIdx = (statIdx + 1) % recentPeak.length;

            int peakFloor = getPercentile(recentPeak, 15);
            int rmsFloor = getPercentile(recentRms, 15);

            boolean mediaPlaying = false;
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am != null && am.isMusicActive()) {
                    mediaPlaying = true;
                }
            } catch (Throwable ignored) { }

            // Dynamic peak trigger threshold:
            // When media/video (TikTok, YouTube, Spotify) is playing from Portal speakers, raise baseline from 1600 to 2600
            // and hysteresis from 2 to 3 blocks to prevent speaker audio spillover from opening false VAD sessions.
            int baseMin = mediaPlaying ? 2600 : 1600;
            int minPeakThreshold = Math.max(baseMin, vadThreshold * 2);
            int floorCapped = Math.min(peakFloor, 2200);
            int dynamicPeakThreshold = Math.max(minPeakThreshold, (int) (floorCapped * 1.85f));
            boolean above = (peak >= dynamicPeakThreshold);

            if (above) {
                aboveRun++;
                belowRun = 0;
            } else {
                belowRun++;
                aboveRun = 0;
            }

            // AudioPolicy silence detection: zeroed buffers indicate background muting
            if (peak == 0) {
                if (++silences > 12) {
                    Log.w(TAG, "AudioRecord muted by system AudioPolicy, reopening clean stream");
                    return null;
                }
            } else {
                silences = 0;
            }

            long nowMs = SystemClock.elapsedRealtime();
            if (nowMs - lastHeartbeatLog > 60_000) {
                lastHeartbeatLog = nowMs;
                Log.i(TAG, "Heartbeat (60s): peak=" + peak + " rms=" + rms + " floor=" + floorCapped + " dynThresh=" + dynamicPeakThreshold + " inSpeech=" + inSpeech + " media=" + mediaPlaying + " otherRec=" + otherAppRecording + " gen=" + myGen);
            } else if (DEBUG && System.currentTimeMillis() - lastAmpLog > 2500) {
                lastAmpLog = System.currentTimeMillis();
                Log.d(TAG, "mic alive, peak=" + peak + " rms=" + rms + " peakFloor=" + peakFloor + " dynThresh=" + dynamicPeakThreshold + " inSpeech=" + inSpeech);
            }

            // Hysteresis: require 3 consecutive blocks (300ms) to open when media is playing, 2 (200ms) when quiet
            int requiredOpenRuns = mediaPlaying ? 3 : 2;
            if (!inSpeech && aboveRun >= requiredOpenRuns) {
                inSpeech = true;
                // Flush 400ms pre-roll circular buffer chronologically to capture word onset ("O...")
                for (int k = 0; k < 4; k++) {
                    int idx = (preRollIdx + k) % 4;
                    if (preRollLen[idx] > 0) {
                        r.acceptWaveForm(preRoll[idx], preRollLen[idx]);
                        preRollLen[idx] = 0;
                    }
                }
            } else if (inSpeech && belowRun >= 10) {
                inSpeech = false;
                String fin = textOf(r.getResult());
                if (fin != null && !fin.isEmpty()) {
                    if (DEBUG) Log.i(TAG, "final=\"" + fin + "\" amp=" + amp);
                    if (hasWake(fin)) return fin;
                }
                r.reset();
                lastPartial = "";
            }

            if (inSpeech) {
                if (!r.acceptWaveForm(buf, n)) {
                    String partial = textOf(r.getPartialResult());
                    if (partial != null && !partial.equals(lastPartial)) {
                        lastPartial = partial;
                        if (DEBUG) Log.i(TAG, "partial=\"" + partial + "\" amp=" + amp);
                        if (hasWake(partial)) return partial;
                    }
                } else {
                    String fin = textOf(r.getResult());
                    if (fin != null && !fin.isEmpty()) {
                        if (DEBUG) Log.i(TAG, "final=\"" + fin + "\" amp=" + amp);
                        if (hasWake(fin)) return fin;
                    }
                    lastPartial = "";
                }
            } else {
                // Save silent chunk in pre-roll circular buffer
                System.arraycopy(buf, 0, preRoll[preRollIdx], 0, n);
                preRollLen[preRollIdx] = n;
                preRollIdx = (preRollIdx + 1) % 4;
            }
        }
    } catch (Throwable t) {
        Log.e(TAG, "listen loop error", t);
    }
    return null;
}

    private static boolean hasWake(String text) {
        if (text == null) return false;
        String low = text.toLowerCase(Locale.ROOT);
        for (String w : WAKE) {
            if (low.contains(w)) return true;
        }
        return false;
    }

    private static String textOf(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.has("text")) {
                String t = obj.optString("text", "").trim();
                return t.isEmpty() ? null : t;
            }
            if (obj.has("partial")) {
                String p = obj.optString("partial", "").trim();
                return p.isEmpty() ? null : p;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static int getPercentile(int[] arr, int p) {
        int[] copy = arr.clone();
        Arrays.sort(copy);
        int idx = (p * copy.length) / 100;
        return copy[Math.max(0, Math.min(copy.length - 1, idx))];
    }

    public static void launchVoice(Context ctx) {
        try {
            ensureAssistantConfigured(ctx);
            Intent i = new Intent(Intent.ACTION_VOICE_COMMAND);
            i.setPackage(GSA);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Log.i(TAG, "Launching VOICE_COMMAND to Google Assistant");
            ctx.startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to launch VOICE_COMMAND", t);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    @Override public void onDestroy() {
        alive = false;
        isRunning = false;
        if (recognizer != null) {
            try { recognizer.close(); } catch (Throwable ignored) {}
            recognizer = null;
        }
        if (model != null) {
            try { model.close(); } catch (Throwable ignored) {}
            model = null;
        }
        if (Build.VERSION.SDK_INT >= 24 && recordingCallback != null) {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am != null) am.unregisterAudioRecordingCallback(recordingCallback);
            } catch (Throwable ignored) {}
        }
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) wakeLock.release();
            } catch (Throwable ignored) {}
            wakeLock = null;
        }
        if (soundPool != null) {
            try { soundPool.release(); } catch (Throwable ignored) {}
            soundPool = null;
        }
        instance = null;
        super.onDestroy();
    }
}
