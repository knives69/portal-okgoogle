package dev.nuc.okgoogle;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class MainActivity extends Activity {
    private TextView status;
    private Button toggle;
    private Button testBtn;
    private Button testChimeBtn;
    private CheckBox chimeCheck;
    private CheckBox autoDismissCheck;
    private TextView vadLabel;
    private SeekBar vadSeekBar;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        final SharedPreferences sp = getSharedPreferences(FgTracker.PREFS_NAME, Context.MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(36, 36, 36, 36);

        TextView title = new TextView(this);
        title.setText("Portal Ok Google v1.1.0");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Universal Hands-Free Trigger for Meta Portal");
        subtitle.setTextSize(13);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 4, 0, 16);
        root.addView(subtitle);

        status = new TextView(this);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 8, 0, 16);
        root.addView(status);

        toggle = new Button(this);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, WakeService.class);
                if (WakeService.isRunning) {
                    stopService(i);
                } else {
                    if (Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(i);
                    } else {
                        startService(i);
                    }
                }
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refresh();
                    }
                }, 350);
            }
        });
        root.addView(toggle);

        LinearLayout rowButtons = new LinearLayout(this);
        rowButtons.setOrientation(LinearLayout.HORIZONTAL);
        rowButtons.setGravity(Gravity.CENTER);
        rowButtons.setPadding(0, 12, 0, 16);

        testBtn = new Button(this);
        testBtn.setText("TEST ASSISTANT");
        testBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WakeService.launchVoice(MainActivity.this);
            }
        });
        rowButtons.addView(testBtn);

        testChimeBtn = new Button(this);
        testChimeBtn.setText("TEST CHIME");
        testChimeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WakeService.playChime(MainActivity.this);
            }
        });
        rowButtons.addView(testChimeBtn);
        root.addView(rowButtons);

        // Feature Toggles Section
        LinearLayout settingsCard = new LinearLayout(this);
        settingsCard.setOrientation(LinearLayout.VERTICAL);
        settingsCard.setPadding(24, 16, 24, 16);
        settingsCard.setBackgroundColor(Color.parseColor("#1AFFFFFF"));

        chimeCheck = new CheckBox(this);
        chimeCheck.setText("Play Google chime sound upon wake-word detection");
        chimeCheck.setChecked(sp.getBoolean(WakeService.PREF_CHIME_ENABLED, true));
        chimeCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                sp.edit().putBoolean(WakeService.PREF_CHIME_ENABLED, isChecked).apply();
            }
        });
        settingsCard.addView(chimeCheck);

        autoDismissCheck = new CheckBox(this);
        autoDismissCheck.setText("Auto-dismiss Assistant overlay after 12s idle (Nest Hub)");
        autoDismissCheck.setChecked(sp.getBoolean(FgTracker.PREF_AUTO_DISMISS, true));
        autoDismissCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                sp.edit().putBoolean(FgTracker.PREF_AUTO_DISMISS, isChecked).apply();
            }
        });
        settingsCard.addView(autoDismissCheck);

        vadLabel = new TextView(this);
        int currentVad = sp.getInt(WakeService.PREF_VAD_THRESHOLD, WakeService.DEFAULT_VAD_THRESHOLD);
        vadLabel.setText("Voice Sensitivity Floor: " + currentVad + " (Auto-Adapts)");
        vadLabel.setPadding(0, 16, 0, 4);
        settingsCard.addView(vadLabel);

        vadSeekBar = new SeekBar(this);
        vadSeekBar.setMax(1200); // 200 to 1400
        vadSeekBar.setProgress(Math.max(0, currentVad - 200));
        vadSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int val = progress + 200;
                vadLabel.setText("Voice Sensitivity Floor: " + val + " (Auto-Adapts)");
                if (fromUser) {
                    sp.edit().putInt(WakeService.PREF_VAD_THRESHOLD, val).apply();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        settingsCard.addView(vadSeekBar);

        root.addView(settingsCard);

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private String detectModelLanguage() {
        File readme = new File(WakeService.MODEL_PATH, "README");
        if (!readme.exists()) {
            boolean hasAm = new File(WakeService.MODEL_PATH, "am").isDirectory();
            return hasAm ? "READY (Unknown lang)" : "MISSING";
        }
        try (BufferedReader br = new BufferedReader(new FileReader(readme))) {
            String firstLine = br.readLine();
            if (firstLine != null) {
                String low = firstLine.toLowerCase();
                if (low.contains("spanish") || low.contains("espanol")) {
                    return "READY (Spanish)";
                } else if (low.contains("english")) {
                    return "READY (English US)";
                }
            }
        } catch (Throwable ignored) { }
        return "READY";
    }

    private void refresh() {
        String lang = detectModelLanguage();
        boolean running = WakeService.isRunning;

        status.setText("Acoustic Model: " + lang + "\n" +
                       "Service State:  " + (running ? "LISTENING" : "STOPPED"));
        toggle.setText(running ? "STOP LISTENING" : "START LISTENING");
    }
}
