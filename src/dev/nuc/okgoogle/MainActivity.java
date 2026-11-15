package dev.nuc.okgoogle;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class MainActivity extends Activity {
    private TextView status;
    private Button toggle;
    private Button testBtn;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("Portal Ok Google");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Universal Wake-Word Trigger for Meta Portal");
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 8, 0, 24);
        root.addView(subtitle);

        status = new TextView(this);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 16, 0, 24);
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

        testBtn = new Button(this);
        testBtn.setText("TEST ASSISTANT TRIGGER");
        testBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WakeService.launchVoice(MainActivity.this);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 24;
        testBtn.setLayoutParams(lp);
        root.addView(testBtn);

        TextView instructions = new TextView(this);
        instructions.setText("\nModel directory:\n" + WakeService.MODEL_PATH);
        instructions.setTextSize(12);
        instructions.setGravity(Gravity.CENTER);
        root.addView(instructions);

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
