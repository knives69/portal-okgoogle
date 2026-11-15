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
import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.util.List;
import java.util.Locale;

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
    private static volatile WakeService instance;

    /**
     * Target wake words. Matches native English transcripts, phonetic variations,
     * and multilingual approximations (e.g. Spanish vosk-model-small-es).
     */
    private static final String[] WAKE = {
        // Native English
        "ok google", "hey google", "okay google", "ok gugel", "hey gugel",
        "ok gogol", "hey gogol",
        // English model phonetic mappings with Spanish accent
        "okay with", "okay well", "ok with", "ok well", "hey we will", "okay let me", "ok let me",
        // Multilingual & Spanish model approximations
        "ok web", "ook web", "oque web", "oke web", "oke google", "okey gugel",
        "oye google", "oye gugel", "hola google", "hola gugel", "google", "gugel"
    };

    private volatile boolean alive;
    private Model model;
    private volatile AudioRecord activeRecord;
    private volatile boolean otherAppRecording;
    private AudioManager.AudioRecordingCallback recordingCallback;
    private PowerManager.WakeLock wakeLock;
    private SoundPool soundPool;
    private int chimeSoundId = -1;

    public static boolean isOtherAppRecording() {
        return instance != null && instance.otherAppRecording;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        acquireWakeLock();
        initSoundPool();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
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
        setupRecordingMonitor();
        new Thread(new Runnable() { @Override public void run() { loop(); } }, "okgoogle-audio").start();
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
            chimeSoundId = soundPool.load(this, R.raw.chime, 1);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize SoundPool chime", t);
        }
    }

    public static void playChime(Context ctx) {
        if (instance != null && instance.soundPool != null && instance.chimeSoundId != -1) {
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
                            if (!otherAppRecording) {
                                // Recording finished — arm auto-dismiss timer on assistant card
                                FgTracker.scheduleAutoDismiss();
                            }
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

    private void loop() {
        try {
            model = new Model(MODEL_PATH);
            Log.i(TAG, "Vosk model loaded successfully from " + MODEL_PATH);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load Vosk model", t);
            isRunning = false;
            stopSelf();
            return;
        }

        byte[] buf = new byte[3200];
        while (alive) {
            if (!canCapture()) {
                sleep(400);
                continue;
            }
            AudioRecord rec = openRec();
            if (rec == null) {
                sleep(600);
                continue;
            }
            activeRecord = rec;

            String heard = listen(rec, buf);
            try { rec.stop(); rec.release(); } catch (Throwable ignored) { }
            activeRecord = null;

            if (heard == null || !alive) continue;

            Log.i(TAG, "Wake word detected: \"" + heard + "\", releasing microphone...");

            // Play earcon / chime immediately if enabled
            SharedPreferences sp = getSharedPreferences(FgTracker.PREFS_NAME, Context.MODE_PRIVATE);
            if (sp.getBoolean(PREF_CHIME_ENABLED, true) && soundPool != null && chimeSoundId != -1) {
                soundPool.play(chimeSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
            }

            // Brief pause allowing Qualcomm HAL to transition input stream to standby
            sleep(250);
            launchVoice(this);

            // Arm auto-dismiss timer on assistant window
            FgTracker.scheduleAutoDismiss();

            // Dynamic wait while Google Assistant or Gemini responds
            long start = System.currentTimeMillis();
            sleep(2500);
            while (alive && System.currentTimeMillis() - start < 15000) {
                if (!canCapture()) {
                    sleep(400);
                } else if (System.currentTimeMillis() - start >= 6000) {
                    break;
                } else {
                    sleep(200);
                }
            }
        }

        if (model != null) {
            try { model.close(); } catch (Throwable ignored) { }
            model = null;
        }
        isRunning = false;
        stopSelf();
    }

    /** Returns recognized keyword string upon wake detection, or null when stream yields. */
    private String listen(AudioRecord rec, byte[] buf) {
        try (Recognizer r = new Recognizer(model, (float) RATE)) {
            int stalls = 0;
            int silences = 0;
            int loopCount = 0;
            long lastAmpLog = 0;
            long lastVoiceTime = 0;
            String lastPartial = "";

            SharedPreferences sp = getSharedPreferences(FgTracker.PREFS_NAME, Context.MODE_PRIVATE);
            int vadThreshold = sp.getInt(PREF_VAD_THRESHOLD, DEFAULT_VAD_THRESHOLD);

            // Pre-roll ring buffer: 2 chunks of 100ms = 200ms pre-speech audio
            // Prevents clipping the first phoneme ("O...") when transitioning from silence to voice
            byte[][] preRoll = new byte[2][buf.length];
            int[] preRollLen = new int[2];
            int preRollIdx = 0;
            boolean inSpeech = false;
            float noiseFloor = 250.0f; // Initial ambient acoustic floor estimate

            while (alive) {
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

                // Adaptive noise floor tracking (asymmetric envelope follower)
                if (rms < noiseFloor) {
                    // Room got quieter: adapt downwards quickly (~1.5s tau)
                    noiseFloor = 0.92f * noiseFloor + 0.08f * rms;
                } else if (!inSpeech) {
                    // Constant background sound (TV/AC): adapt upwards slowly (~12s tau)
                    // Ignored during active speech so user voice never inflates the floor
                    noiseFloor = 0.992f * noiseFloor + 0.008f * rms;
                }
                if (noiseFloor < 100.0f) noiseFloor = 100.0f;

                // Dynamic trigger threshold: base sensitivity floor or 2.2x ambient noise (+7 dB SNR)
                int effectiveThreshold = Math.max(vadThreshold, (int) (noiseFloor * 2.2f));

                // AudioPolicy silence detection: zeroed buffers indicate background muting
                if (peak == 0) {
                    if (++silences > 12) {
                        Log.w(TAG, "AudioRecord muted by system AudioPolicy, reopening clean stream");
                        return null;
                    }
                } else {
                    silences = 0;
                }

                if (DEBUG && System.currentTimeMillis() - lastAmpLog > 2500) {
                    lastAmpLog = System.currentTimeMillis();
                    Log.i(TAG, "mic alive, peak=" + peak + " rms=" + rms + " floor=" + (int) noiseFloor + " effThresh=" + effectiveThreshold + " inSpeech=" + inSpeech);
                }

                boolean hasVoice = (rms >= effectiveThreshold);
                if (hasVoice) {
                    lastVoiceTime = System.currentTimeMillis();
                }

                boolean activeSpeech = (System.currentTimeMillis() - lastVoiceTime < 1800);

                if (activeSpeech) {
                    if (!inSpeech) {
                        // Voice onset: flush pre-roll buffer into recognizer to preserve start of wake word
                        inSpeech = true;
                        for (int k = 0; k < 2; k++) {
                            int idx = (preRollIdx + k) % 2;
                            if (preRollLen[idx] > 0) {
                                r.acceptWaveForm(preRoll[idx], preRollLen[idx]);
                                preRollLen[idx] = 0;
                            }
                        }
                    }

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
                    if (inSpeech) {
                        // Speech ended (>1.8s silence): check final buffer and reset recognizer state
                        inSpeech = false;
                        String fin = textOf(r.getResult());
                        if (fin != null && !fin.isEmpty()) {
                            if (DEBUG) Log.i(TAG, "final=\"" + fin + "\" amp=" + amp);
                            if (hasWake(fin)) return fin;
                        }
                        r.reset();
                        lastPartial = "";
                    }

                    // Keep pre-roll buffer fresh during silence (Kaldi C++ neural net completely idle)
                    System.arraycopy(buf, 0, preRoll[preRollIdx], 0, n);
                    preRollLen[preRollIdx] = n;
                    preRollIdx = (preRollIdx + 1) % 2;
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

    public static void launchVoice(Context ctx) {
        try {
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
