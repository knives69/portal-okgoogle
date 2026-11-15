package dev.nuc.okgoogle;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

public class MainActivity extends Activity {
    private TextView status;
    private Button toggle;
    private boolean running;

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
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Universal Wake-Word Trigger for Meta Portal");
        subtitle.setTextSize(14);
        subtitle.setPadding(0, 8, 0, 24);
        root.addView(subtitle);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, 16, 0, 24);
        root.addView(status);

        toggle = new Button(this);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, WakeService.class);
                if (running) {
                    stopService(i);
                    running = false;
                } else {
                    if (Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(i);
                    } else {
                        startService(i);
                    }
                    running = true;
                }
                refresh();
            }
        });
        root.addView(toggle);

        TextView instructions = new TextView(this);
        instructions.setText("\nEnsure the Vosk model is located at:\n" + WakeService.MODEL_PATH);
        instructions.setTextSize(12);
        instructions.setGravity(Gravity.CENTER);
        root.addView(instructions);

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
        refresh();
    }

    private void refresh() {
        boolean modelReady = new File(WakeService.MODEL_PATH + "/conf").isDirectory()
                          || new File(WakeService.MODEL_PATH + "/am").isDirectory();
        status.setText("Acoustic Model: " + (modelReady ? "READY" : "MISSING") + "\n" +
                       "Service State:  " + (running ? "LISTENING" : "STOPPED"));
        toggle.setText(running ? "STOP" : "START LISTENING");
    }
}
