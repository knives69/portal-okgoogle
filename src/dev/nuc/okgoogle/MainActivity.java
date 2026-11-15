package dev.nuc.okgoogle;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * MainActivity — Modern Material 3 dashboard for Portal Ok Google (English Edition).
 * Features:
 * - Dynamic Day / Night mode tracking Android system theme.
 * - Live acoustic telemetry card (model language, active status, VAD adaptation).
 * - Instant test controls (voice assistant trigger & chime earcon).
 * - Persistent preferences for sensitivity, auto-dismiss, earcon, and theme.
 */
public class MainActivity extends Activity {
    public static final String PREF_THEME_MODE = "theme_mode"; // 0: System, 1: Dark, 2: Light

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable telemetryPoller;

    // Dynamic views for refresh
    private TextView statusBadge;
    private TextView statModelValue;
    private TextView statStreamValue;
    private TextView statHealthValue;
    private Button toggleBtn;
    private TextView themeSubText;
    private Button themeBtn;
    private TextView vadLabel;
    private SeekBar vadSeekBar;

    private int themeMode = 0; // 0: Auto, 1: Dark, 2: Light
    private boolean isDark = true;

    // Palette colors
    private int colBg;
    private int colSurface;
    private int colSurfaceBorder;
    private int colSurfaceElevated;
    private int colTextPrimary;
    private int colTextSecondary;
    private int colTextTertiary;
    private int colAccentBlue;
    private int colAccentPurple;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadThemeColors();
        buildUI();

        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (themeMode == 0) {
            loadThemeColors();
            buildUI();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startTelemetryLoop();
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopTelemetryLoop();
    }

    @Override
    protected void onDestroy() {
        stopTelemetryLoop();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        super.onDestroy();
    }

    private void loadThemeColors() {
        SharedPreferences sp = getSharedPreferences(FgTracker.PREFS_NAME, Context.MODE_PRIVATE);
        themeMode = sp.getInt(PREF_THEME_MODE, 0);

        boolean isSystemDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (themeMode == 1) {
            isDark = true;
        } else if (themeMode == 2) {
            isDark = false;
        } else {
            isDark = isSystemDark;
        }

        if (isDark) {
            // Google Deep Indigo / Dark Theme
            colBg = Color.parseColor("#0F1117");
            colSurface = Color.parseColor("#1A1D26");
            colSurfaceBorder = Color.parseColor("#2A2E3B");
            colSurfaceElevated = Color.parseColor("#222632");
            colTextPrimary = Color.parseColor("#FFFFFF");
            colTextSecondary = Color.parseColor("#9AA0A6");
            colTextTertiary = Color.parseColor("#6A7180");
            colAccentBlue = Color.parseColor("#4285F4");
            colAccentPurple = Color.parseColor("#A142F4");
        } else {
            // Google Material 3 Light Theme
            colBg = Color.parseColor("#F6F8FC");
            colSurface = Color.parseColor("#FFFFFF");
            colSurfaceBorder = Color.parseColor("#E0E3EB");
            colSurfaceElevated = Color.parseColor("#F1F3F9");
            colTextPrimary = Color.parseColor("#1B1D20");
            colTextSecondary = Color.parseColor("#5F6368");
            colTextTertiary = Color.parseColor("#80868B");
            colAccentBlue = Color.parseColor("#1A73E8");
            colAccentPurple = Color.parseColor("#7B1FA2");
        }
    }

    private void buildUI() {
        final SharedPreferences sp = getSharedPreferences(FgTracker.PREFS_NAME, Context.MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(colBg);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));
        scroll.addView(root);

        // 1. HEADER SECTION (App Icon + Title + Version Chip)
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(4), 0, dp(16));

        ImageView iconView = new ImageView(this);
        iconView.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(54), dp(54));
        iconParams.rightMargin = dp(14);
        iconView.setLayoutParams(iconParams);
        header.addView(iconView);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleText = new TextView(this);
        titleText.setText("Portal Ok Google");
        titleText.setTextSize(20);
        titleText.setTypeface(Typeface.DEFAULT_BOLD);
        titleText.setTextColor(colTextPrimary);
        titleRow.addView(titleText);

        TextView verChip = new TextView(this);
        verChip.setText("v1.5.9");
        verChip.setTextSize(10);
        verChip.setTypeface(Typeface.DEFAULT_BOLD);
        verChip.setTextColor(colAccentBlue);
        verChip.setPadding(dp(8), dp(2), dp(8), dp(2));
        verChip.setBackground(makePill(isDark ? Color.parseColor("#1E2D4A") : Color.parseColor("#E8F0FE"), colAccentBlue, 10));
        LinearLayout.LayoutParams verParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        verParams.leftMargin = dp(10);
        verChip.setLayoutParams(verParams);
        titleRow.addView(verChip);
        titleCol.addView(titleRow);

        TextView subtitle = new TextView(this);
        subtitle.setText("Universal Hands-Free Wake Trigger for Meta Portal");
        subtitle.setTextSize(12);
        subtitle.setTextColor(colTextSecondary);
        subtitle.setPadding(0, dp(2), 0, 0);
        titleCol.addView(subtitle);

        header.addView(titleCol);
        root.addView(header);

        // 2. HERO STATUS CARD (Live Telemetry)
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        statusCard.setBackground(makeRoundedCard(colSurface, colSurfaceBorder, 16, 1));

        TextView cardLabel = new TextView(this);
        cardLabel.setText("RECOGNITION STATUS");
        cardLabel.setTextSize(11);
        cardLabel.setTypeface(Typeface.DEFAULT_BOLD);
        cardLabel.setTextColor(colTextTertiary);
        cardLabel.setLetterSpacing(0.08f);
        statusCard.addView(cardLabel);

        statusBadge = new TextView(this);
        statusBadge.setTextSize(13);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        statusBadge.setPadding(dp(12), dp(6), dp(12), dp(6));
        statusBadge.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeParams.topMargin = dp(10);
        badgeParams.bottomMargin = dp(14);
        statusBadge.setLayoutParams(badgeParams);
        statusCard.addView(statusBadge);

        // Divider line
        View divider = new View(this);
        divider.setBackgroundColor(colSurfaceBorder);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        divParams.bottomMargin = dp(14);
        divider.setLayoutParams(divParams);
        statusCard.addView(divider);

        // Telemetry details list
        LinearLayout statsList = new LinearLayout(this);
        statsList.setOrientation(LinearLayout.VERTICAL);

        statModelValue = addStatRow(statsList, "Acoustic Model", "Loading...");
        statStreamValue = addStatRow(statsList, "Audio Stream", "16,000 Hz · 16-bit Mono PCM");
        statHealthValue = addStatRow(statsList, "Supervision", "Watchdog Level 1 & 2 Active");

        statusCard.addView(statsList);
        root.addView(statusCard);

        // 3. MAIN ACTION BUTTON (Big Pill)
        toggleBtn = new Button(this);
        toggleBtn.setTextSize(14);
        toggleBtn.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        btnParams.topMargin = dp(16);
        toggleBtn.setLayoutParams(btnParams);
        toggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, WakeService.class);
                if (WakeService.isRunning) {
                    sp.edit().putBoolean(FgTracker.PREF_ENABLED, false).apply();
                    StartReceiver.cancelWatchdog(MainActivity.this);
                    stopService(i);
                } else {
                    sp.edit().putBoolean(FgTracker.PREF_ENABLED, true).apply();
                    StartReceiver.scheduleWatchdog(MainActivity.this);
                    if (Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(i);
                    } else {
                        startService(i);
                    }
                }
                handler.postDelayed(new Runnable() {
                    @Override public void run() { refresh(); }
                }, 300);
            }
        });
        root.addView(toggleBtn);

        // 4. QUICK TEST BUTTONS (Assistant & Earcon Test)
        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams testParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        testParams.topMargin = dp(12);
        testRow.setLayoutParams(testParams);

        Button testAssistantBtn = new Button(this);
        testAssistantBtn.setText("TEST ASSISTANT");
        testAssistantBtn.setTextSize(12);
        testAssistantBtn.setTextColor(colTextPrimary);
        testAssistantBtn.setBackground(makePill(colSurfaceElevated, colSurfaceBorder, 10));
        LinearLayout.LayoutParams b1p = new LinearLayout.LayoutParams(0, dp(42), 1.0f);
        b1p.rightMargin = dp(6);
        testAssistantBtn.setLayoutParams(b1p);
        testAssistantBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { WakeService.launchVoice(MainActivity.this); }
        });
        testRow.addView(testAssistantBtn);

        Button testChimeBtn = new Button(this);
        testChimeBtn.setText("TEST CHIME");
        testChimeBtn.setTextSize(12);
        testChimeBtn.setTextColor(colTextPrimary);
        testChimeBtn.setBackground(makePill(colSurfaceElevated, colSurfaceBorder, 10));
        LinearLayout.LayoutParams b2p = new LinearLayout.LayoutParams(0, dp(42), 1.0f);
        b2p.leftMargin = dp(6);
        testChimeBtn.setLayoutParams(b2p);
        testChimeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { WakeService.playChime(MainActivity.this); }
        });
        testRow.addView(testChimeBtn);

        root.addView(testRow);

        // 5. SETTINGS CARD
        LinearLayout settingsCard = new LinearLayout(this);
        settingsCard.setOrientation(LinearLayout.VERTICAL);
        settingsCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        settingsCard.setBackground(makeRoundedCard(colSurface, colSurfaceBorder, 16, 1));
        LinearLayout.LayoutParams setCardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        setCardParams.topMargin = dp(16);
        settingsCard.setLayoutParams(setCardParams);

        TextView setLabel = new TextView(this);
        setLabel.setText("SETTINGS & PREFERENCES");
        setLabel.setTextSize(11);
        setLabel.setTypeface(Typeface.DEFAULT_BOLD);
        setLabel.setTextColor(colTextTertiary);
        setLabel.setLetterSpacing(0.08f);
        setLabel.setPadding(0, 0, 0, dp(8));
        settingsCard.addView(setLabel);

        // Theme toggle row
        LinearLayout themeRow = new LinearLayout(this);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        themeRow.setGravity(Gravity.CENTER_VERTICAL);
        themeRow.setPadding(0, dp(10), 0, dp(10));

        LinearLayout themeTextCol = new LinearLayout(this);
        themeTextCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ttColParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        themeTextCol.setLayoutParams(ttColParams);

        TextView themeTitle = new TextView(this);
        themeTitle.setText("Appearance (Theme)");
        themeTitle.setTextSize(14);
        themeTitle.setTypeface(Typeface.DEFAULT_BOLD);
        themeTitle.setTextColor(colTextPrimary);
        themeTextCol.addView(themeTitle);

        themeSubText = new TextView(this);
        themeSubText.setTextSize(12);
        themeSubText.setTextColor(colTextSecondary);
        themeTextCol.addView(themeSubText);
        themeRow.addView(themeTextCol);

        themeBtn = new Button(this);
        themeBtn.setTextSize(11);
        themeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        themeBtn.setTextColor(colAccentBlue);
        themeBtn.setBackground(makePill(colSurfaceElevated, colSurfaceBorder, 10));
        themeBtn.setPadding(dp(12), dp(4), dp(12), dp(4));
        themeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Cycle: 0 (System) -> 1 (Dark) -> 2 (Light) -> 0
                themeMode = (themeMode + 1) % 3;
                sp.edit().putInt(PREF_THEME_MODE, themeMode).apply();
                loadThemeColors();
                buildUI();
                refresh();
            }
        });
        themeRow.addView(themeBtn);
        settingsCard.addView(themeRow);

        addDivider(settingsCard);

        // Switch: Google Nest Chime
        Switch chimeSwitch = new Switch(this);
        chimeSwitch.setText("Wake chime sound (Earcon)");
        chimeSwitch.setTextSize(14);
        chimeSwitch.setTypeface(Typeface.DEFAULT_BOLD);
        chimeSwitch.setTextColor(colTextPrimary);
        chimeSwitch.setChecked(sp.getBoolean(WakeService.PREF_CHIME_ENABLED, true));
        chimeSwitch.setPadding(0, dp(10), 0, dp(10));
        chimeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                sp.edit().putBoolean(WakeService.PREF_CHIME_ENABLED, checked).apply();
            }
        });
        settingsCard.addView(chimeSwitch);

        addDivider(settingsCard);

        // Switch: Auto-dismiss 15s
        Switch autoDismissSwitch = new Switch(this);
        autoDismissSwitch.setText("Smart auto-dismiss (15s)");
        autoDismissSwitch.setTextSize(14);
        autoDismissSwitch.setTypeface(Typeface.DEFAULT_BOLD);
        autoDismissSwitch.setTextColor(colTextPrimary);
        autoDismissSwitch.setChecked(sp.getBoolean(FgTracker.PREF_AUTO_DISMISS, true));
        autoDismissSwitch.setPadding(0, dp(10), 0, dp(10));
        autoDismissSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                sp.edit().putBoolean(FgTracker.PREF_AUTO_DISMISS, checked).apply();
            }
        });
        settingsCard.addView(autoDismissSwitch);

        addDivider(settingsCard);

        // VAD Sensitivity Slider
        vadLabel = new TextView(this);
        vadLabel.setTextSize(14);
        vadLabel.setTypeface(Typeface.DEFAULT_BOLD);
        vadLabel.setTextColor(colTextPrimary);
        vadLabel.setPadding(0, dp(10), 0, dp(2));
        settingsCard.addView(vadLabel);

        TextView vadDesc = new TextView(this);
        vadDesc.setText("Minimum acoustic energy baseline (filter auto-adapts to ambient noise)");
        vadDesc.setTextSize(12);
        vadDesc.setTextColor(colTextSecondary);
        vadDesc.setPadding(0, 0, 0, dp(8));
        settingsCard.addView(vadDesc);

        int currentVad = sp.getInt(WakeService.PREF_VAD_THRESHOLD, WakeService.DEFAULT_VAD_THRESHOLD);
        vadSeekBar = new SeekBar(this);
        vadSeekBar.setMax(1200); // 200 to 1400
        vadSeekBar.setProgress(Math.max(0, currentVad - 200));
        vadSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int val = progress + 200;
                vadLabel.setText("Voice Sensitivity Floor: " + val + " (Auto-Adapting)");
                if (fromUser) {
                    sp.edit().putInt(WakeService.PREF_VAD_THRESHOLD, val).apply();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        settingsCard.addView(vadSeekBar);

        root.addView(settingsCard);

        // 6. FOOTER
        TextView footer = new TextView(this);
        footer.setText("Meta Portal+ Edition · Offline Speech Recognition");
        footer.setTextSize(11);
        footer.setTextColor(colTextTertiary);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(20), 0, dp(10));
        root.addView(footer);

        setContentView(scroll);
    }

    private TextView addStatRow(LinearLayout parent, String label, String initialValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));

        TextView lbl = new TextView(this);
        lbl.setText(label + ": ");
        lbl.setTextSize(12);
        lbl.setTextColor(colTextSecondary);
        row.addView(lbl);

        TextView val = new TextView(this);
        val.setText(initialValue);
        val.setTextSize(12);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setTextColor(colTextPrimary);
        row.addView(val);

        parent.addView(row);
        return val;
    }

    private void addDivider(LinearLayout parent) {
        View div = new View(this);
        div.setBackgroundColor(colSurfaceBorder);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        div.setLayoutParams(lp);
        parent.addView(div);
    }

    private void refresh() {
        if (statusBadge == null) return;

        boolean running = WakeService.isRunning;
        boolean healthy = WakeService.isHealthy();
        String lang = detectModelLanguage();

        if (statModelValue != null) {
            statModelValue.setText(lang);
        }

        if (!running) {
            statusBadge.setText("■  STOPPED");
            statusBadge.setTextColor(Color.parseColor("#EA4335"));
            statusBadge.setBackground(makePill(isDark ? Color.parseColor("#331212") : Color.parseColor("#FCE8E6"),
                    Color.parseColor("#EA4335"), 12));
            toggleBtn.setText("▶  START LISTENING");
            toggleBtn.setTextColor(Color.WHITE);
            toggleBtn.setBackground(makePill(colAccentBlue, 0, 14));
            if (statHealthValue != null) statHealthValue.setText("Service inactive");
        } else if (healthy) {
            statusBadge.setText("●  ACTIVE · LISTENING 24/7");
            statusBadge.setTextColor(Color.parseColor("#34A853"));
            statusBadge.setBackground(makePill(isDark ? Color.parseColor("#122E1D") : Color.parseColor("#E6F4EA"),
                    Color.parseColor("#34A853"), 12));
            toggleBtn.setText("⏹  STOP LISTENING");
            toggleBtn.setTextColor(Color.WHITE);
            toggleBtn.setBackground(makePill(Color.parseColor("#D93025"), 0, 14));
            if (statHealthValue != null) statHealthValue.setText("Healthy · Mic streaming PCM data");
        } else {
            statusBadge.setText("▲  RECOVERING AUDIO STREAM");
            statusBadge.setTextColor(Color.parseColor("#FBBC04"));
            statusBadge.setBackground(makePill(isDark ? Color.parseColor("#332608") : Color.parseColor("#FEF7E0"),
                    Color.parseColor("#FBBC04"), 12));
            toggleBtn.setText("⏹  STOP LISTENING");
            toggleBtn.setTextColor(Color.WHITE);
            toggleBtn.setBackground(makePill(Color.parseColor("#D93025"), 0, 14));
            if (statHealthValue != null) statHealthValue.setText("Recovering frozen stream...");
        }

        if (themeSubText != null) {
            if (themeMode == 1) {
                themeSubText.setText("Forced Dark mode");
            } else if (themeMode == 2) {
                themeSubText.setText("Forced Light mode");
            } else {
                themeSubText.setText("Following system theme (" + (isDark ? "Dark" : "Light") + ")");
            }
        }

        if (themeBtn != null) {
            if (themeMode == 1) {
                themeBtn.setText("DARK");
            } else if (themeMode == 2) {
                themeBtn.setText("LIGHT");
            } else {
                themeBtn.setText("SYSTEM");
            }
        }

        SharedPreferences sp = getSharedPreferences(FgTracker.PREFS_NAME, Context.MODE_PRIVATE);
        int currentVad = sp.getInt(WakeService.PREF_VAD_THRESHOLD, WakeService.DEFAULT_VAD_THRESHOLD);
        if (vadLabel != null) {
            vadLabel.setText("Voice Sensitivity Floor: " + currentVad + " (Auto-Adapting)");
        }
    }

    private void startTelemetryLoop() {
        stopTelemetryLoop();
        telemetryPoller = new Runnable() {
            @Override
            public void run() {
                refresh();
                handler.postDelayed(this, 2000);
            }
        };
        handler.postDelayed(telemetryPoller, 2000);
    }

    private void stopTelemetryLoop() {
        if (telemetryPoller != null) {
            handler.removeCallbacks(telemetryPoller);
            telemetryPoller = null;
        }
    }

    private String detectModelLanguage() {
        File readme = new File(WakeService.MODEL_PATH, "README");
        if (!readme.exists()) {
            boolean hasAm = new File(WakeService.MODEL_PATH, "am").isDirectory();
            return hasAm ? "Ready (Language unspecified)" : "Model not found";
        }
        try (BufferedReader br = new BufferedReader(new FileReader(readme))) {
            String firstLine = br.readLine();
            if (firstLine != null) {
                String low = firstLine.toLowerCase();
                if (low.contains("spanish") || low.contains("espanol")) {
                    return "Spanish (vosk-model-small-es)";
                } else if (low.contains("english")) {
                    return "English (vosk-model-small-en-us)";
                }
            }
        } catch (Throwable ignored) { }
        return "Ready for capture";
    }

    private GradientDrawable makeRoundedCard(int bgColor, int borderColor, float radiusDp, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(bgColor);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            d.setStroke(dp(strokeDp), borderColor);
        }
        return d;
    }

    private GradientDrawable makePill(int bgColor, int borderColor, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(bgColor);
        d.setCornerRadius(dp(radiusDp));
        if (borderColor != 0) {
            d.setStroke(dp(1.2f), borderColor);
        }
        return d;
    }

    private int dp(float val) {
        return (int) (val * getResources().getDisplayMetrics().density + 0.5f);
    }
}
