package dev.nuc.okgoogle;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
 * 2. Dynamically yields the microphone if any app opens a speech recognition overlay
 *    or records audio (e.g. SmartTube in-app voice search).
 * 3. Automatically recovers from AudioPolicy background muting (amp == 0 detection).
 * 4. When "Ok Google" is detected: releases the microphone, waits for Qualcomm HAL standby,
 *    and launches Google Assistant (ACTION_VOICE_COMMAND).
 */
public class WakeService extends Service {
    private static final String TAG = "okgoogle";
    private static final int RATE = 16000;
    private static final String GSA = "com.google.android.googlequicksearchbox";
    public static final String MODEL_PATH = "/sdcard/Android/data/dev.nuc.okgoogle/files/model";

    /**
     * Target wake words. Matches native English transcripts as well as common
     * phonetic approximations produced by multilingual models (e.g. Spanish vosk-model-small-es).
     */
    private static final String[] WAKE = {
        // Native English
        "ok google", "hey google", "okay google", "ok gugel", "hey gugel",
        // Multilingual & phonetic approximations
        "ok web", "ook web", "oque web", "oke web", "oke google", "okey gugel",
        "oye google", "oye gugel", "hola google", "hola gugel", "google", "gugel"
    };

    private volatile boolean alive;
    private Model model;
    private volatile AudioRecord activeRecord;
    private volatile boolean otherAppRecording;
    private AudioManager.AudioRecordingCallback recordingCallback;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            channel();
            startForeground(1, notification("Listening for \"Ok Google\""));
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed", t);
        }
        if (alive) return START_STICKY;
        alive = true;
        setupRecordingMonitor();
        new Thread(new Runnable() { @Override public void run() { loop(); } }, "okgoogle-audio").start();
        return START_STICKY;
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
                            if (cfg.getClientAudioSessionId() != mySession) {
                                anotherActive = true;
                                break;
                            }
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
        try {
            r.startRecording();
        } catch (Throwable t) {
            Log.e(TAG, "AudioRecord startRecording failed", t);
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
            // Brief pause allowing Qualcomm HAL to transition input stream to standby
            sleep(250);
            launchVoice();

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
        stopSelf();
    }

    /** Returns recognized keyword string upon wake detection, or null when stream yields. */
    private String listen(AudioRecord rec, byte[] buf) {
        try (Recognizer r = new Recognizer(model, (float) RATE)) {
            int stalls = 0;
            int silences = 0;
            long lastAmpLog = 0;
            String lastPartial = "";

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

                long amp = 0;
                for (int i = 0; i + 1 < n; i += 2) {
                    int v = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
                    if (v < 0) v = -v;
                    if (v > amp) amp = v;
                }

                // AudioPolicy silence detection: zeroed buffers indicate background muting
                if (amp == 0) {
                    if (++silences > 12) {
                        Log.w(TAG, "AudioRecord muted by system AudioPolicy, reopening clean stream");
                        return null;
                    }
                } else {
                    silences = 0;
                }

                if (System.currentTimeMillis() - lastAmpLog > 2500) {
                    lastAmpLog = System.currentTimeMillis();
                    Log.i(TAG, "mic alive, amp=" + amp);
                }

                if (!r.acceptWaveForm(buf, n)) {
                    String partial = textOf(r.getPartialResult());
                    if (partial != null && !partial.equals(lastPartial)) {
                        lastPartial = partial;
                        Log.i(TAG, "partial=\"" + partial + "\" amp=" + amp);
                        if (hasWake(partial)) return partial;
                    }
                } else {
                    String fin = textOf(r.getResult());
                    if (fin != null && !fin.isEmpty()) {
                        Log.i(TAG, "final=\"" + fin + "\" amp=" + amp);
                        if (hasWake(fin)) return fin;
                    }
                    lastPartial = "";
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

    private void launchVoice() {
        try {
            Intent i = new Intent(Intent.ACTION_VOICE_COMMAND);
            i.setPackage(GSA);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Log.i(TAG, "Launching VOICE_COMMAND to Google Assistant");
            startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to launch VOICE_COMMAND", t);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    @Override public void onDestroy() {
        alive = false;
        if (Build.VERSION.SDK_INT >= 24 && recordingCallback != null) {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am != null) am.unregisterAudioRecordingCallback(recordingCallback);
            } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }
}
