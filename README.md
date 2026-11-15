# Portal Ok Google

**Universal Offline Wake-Word Trigger for Meta Portal / Portal+ (Android 9 / API 28)**

An open-source, background hotword service enabling hands-free **"Ok Google"** and **"Hey Google"** activation on rooted Meta Portal devices running Android 9 (e.g. via [OpenPortal](https://github.com/CoderPlusPlus/OpenPortal)).

Powered by [Vosk](https://alphacephei.com/vosk/) offline speech recognition with dynamic audio session arbitration and seamless microphone handover.

---

## The Problem: Why Native "Hey Google" Fails on Meta Portal

1. **Missing DSP Sound Trigger HAL:**  
   Meta stripped Qualcomm's low-power `sound_trigger` DSP HAL libraries and `HotwordEnrollment*` packages from the stock vendor image (`/vendor/lib64/hw/sound_trigger.primary.*.so`). Hardware-level DSP wake-word detection is physically impossible without proprietary firmware blobs.

2. **API 31 Crash Loop in Modern Google App:**  
   If "Hey Google" is enabled in Google App (GSA 17.x+) settings on Android 9, GSA attempts to bind `android.service.voice.HotwordDetectionService` (which only exists in Android 12 / API 31+). This triggers a continuous `ClassNotFoundException` crash loop inside `GsaVoiceInteractionService.onCreate()`, spamming Qualcomm audio input 30 times per second (`dead IAudioRecord`) and exhausting audio tracks.

---

## How This Solution Works

- **Offline On-Device Recognition:** Uses a lightweight Vosk acoustic model (`vosk-model-small-en-us-0.15` or `vosk-model-small-es-0.42`) running entirely on-device with zero internet latency or cloud telemetry.
- **Microphone Collision Prevention:** By keeping Google App's internal "Hey Google" toggle **OFF**, the API 31 crash loop is completely eliminated.
- **Universal Capture & 24/7 Reliability:** Listens across the entire system — home screen, third-party launchers, SmartTube, web browsers, etc. Backed by `PARTIAL_WAKE_LOCK` to ensure uninterrupted capture when the display turns off.
- **Audio Chime (Earcon):** Plays an instant Google Assistant-style rising chime via `SoundPool` the millisecond "Ok Google" is detected.
- **Dynamic Mic Handover (Zero Conflicts):**
  - **Window Overlay Tracking (`FgTracker`):** An Accessibility Service detects when any app (like SmartTube in-app voice search) opens a transcription dialog and immediately yields the microphone.
  - **Hardware Recording Monitor:** Uses `AudioManager.registerAudioRecordingCallback` to yield whenever any external app opens an audio record track, and automatically reclaims the mic once the recording session ends.
  - **AudioPolicy Silence Recovery:** If the system AudioPolicy silences background audio (`amp == 0`), the capture loop automatically resets cleanly once foreground audio finishes.
- **Instant Assistant Invocation & Auto-Dismiss:** Once a wake word is detected, `WakeService` releases the microphone, allows Qualcomm HAL input to reach standby, and launches `android.intent.action.VOICE_COMMAND`. After 12 seconds of idle time without user touch, `FgTracker` automatically dismisses the Assistant overlay (Nest Hub behavior).

---

## Supported Wake Words

### English Acoustic Model (Default)
- `"ok google"`
- `"hey google"`
- `"okay google"`
- Phonetic variants: `"ok gugel"`, `"hey gugel"`, `"ok gogol"`
- Accented English matches: `"okay with"`, `"okay well"`, `"hey we will"`, `"okay let me"`

### Spanish Acoustic Model (`es`)
- `"ok google"`, `"hey google"`
- Phonetic matches: `"ok web"`, `"ook web"`, `"oke google"`, `"okey gugel"`
- Spanish phrases: `"oye google"`, `"oye gugel"`, `"hola google"`, `"google"`

---

## Prerequisites

1. **Meta Portal / Portal+** rooted and running Android 9 (Aloha/OpenPortal) with Google Play Services installed.
2. **Google App (GSA):**
   - **Package:** `com.google.android.googlequicksearchbox`
   - **Tested / Verified Version:** **`17.6.59.ve.arm64`** (Version code: `301698282`, minSdk 28, targetSdk 36, `arm64-v8a`).
   - *(Note: Regarding [OpenPortal](https://github.com/CoderPlusPlus/OpenPortal), we are using a different and newer version of Google App than older setups. Older builds such as GSA 14.14 prematurely terminate voice intents in `HandsFreeActivity`. GSA `17.6.59.ve.arm64` or modern Play Store builds are required for stable assistant activation).*
   - You can verify your installed version with:
     ```bash
     adb shell dumpsys package com.google.android.googlequicksearchbox | grep versionName
     ```
3. **Computer with ADB:** Connected to your Portal over USB or Wi-Fi (`adb connect <portal-ip>:5555`).

---

## Complete Setup Guide (Step-by-Step)

Follow these steps to configure your Portal and start hands-free listening. You can perform them via the device GUI or using the copy-paste ADB commands.

### Step 1: Install the Voice Model on Device
Run the included setup script from your computer to download and push the acoustic model to `/sdcard/Android/data/dev.nuc.okgoogle/files/model/`:

```bash
# English US (default):
./scripts/setup_model.sh

# Or Spanish:
./scripts/setup_model.sh es
```

### Step 2: Install the APK
Download the pre-compiled [okgoogle.apk](https://github.com/knives69/portal-okgoogle/releases/latest) from Releases, or build it yourself (see [Building from Source](#building-from-source)):

```bash
adb install -r okgoogle.apk
```

### Step 3: Set Google as the Default Assistant App
Google must be registered as the default voice assistant.

- **Via GUI:**  
  Go to **Settings → Apps & Notifications → Default apps → Assist & voice input → Assist app** and select **Google**.
- **Via ADB:**  
  ```bash
  adb shell settings put secure assistant com.google.android.googlequicksearchbox/.GoogleAppImplicitActionAssistGatewayInternal
  adb shell settings put secure voice_interaction_service com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService
  ```

### Step 4: Configure Hotword Settings in Google App
To avoid the Android 9 / API 31 `ClassNotFoundException` audio crash loop while keeping the voice assistant gateway active:

> [!WARNING]
> - **Incompatible Mic Grabbers (`Alexa` / `com.millenium`):** Neither **Amazon Alexa** (`com.amazon.alexa.multimodal.falcon`) nor **`com.millenium`** can remain installed or active. Both capture and hold persistent audio recording sessions in the background, stealing the microphone from our service. Disable or uninstall them with:
>   ```bash
>   adb shell pm disable-user --user 0 com.amazon.alexa.multimodal.falcon
>   adb shell pm disable-user --user 0 com.millenium
>   ```
> - **Google App Version vs OpenPortal:** Regarding [CoderPlusPlus/OpenPortal](https://github.com/CoderPlusPlus/OpenPortal), we are using a different and newer verified Google App build (**`17.6.59.ve.arm64`**). Older builds bundle voice interaction stubs that close prematurely.
> - **"Hey Google"** MUST be turned **OFF** in Google App settings (prevents the background `dead IAudioRecord` crash loop).
> - **"While driving"** (or **"Durante la conducción"** in Spanish) MUST be turned **ON** (maintains the voice command gateway ready for assistant invocation).

- **Via GUI / Direct Intent:**  
  Run this command to open the Voice Match settings page directly:
  ```bash
  adb shell am start -a com.google.android.googlequicksearchbox.action.HOTWORD_DETECTION_SETTINGS
  ```
  In the screen that opens:
  1. Toggle **"Hey Google"** to **OFF / DISABLED**.
  2. Under *Other devices / While driving* (or *Otros dispositivos / Durante la conducción*), enter and toggle **"While driving"** to **ON / ENABLED**.

### Step 5: Grant Microphone Permission
- **Via GUI:**  
  Open the **Portal Ok Google** app and tap **Allow** when prompted for microphone access.
- **Via ADB:**  
  ```bash
  adb shell pm grant dev.nuc.okgoogle android.permission.RECORD_AUDIO
  ```

### Step 6: Enable Accessibility Service (Microphone Handover)
This allows the app to detect when another app (like SmartTube voice search) opens a voice input overlay and yield the mic.

- **Via GUI:**  
  Go to **Settings → Accessibility → Portal Ok Google** and toggle it **ON**.
- **Via ADB:**  
  ```bash
  adb shell settings put secure enabled_accessibility_services dev.nuc.okgoogle/.FgTracker
  adb shell settings put secure accessibility_enabled 1
  ```

### Step 7: Whitelist from Battery Optimization (Doze)
Ensures Android does not kill the background service when the screen is idle:

```bash
adb shell dumpsys deviceidle whitelist +dev.nuc.okgoogle
```

### Step 8: Start the Wake Service
Start the service via the app UI (tap **START LISTENING**) or directly via ADB:

```bash
adb shell am start-foreground-service -n dev.nuc.okgoogle/.WakeService
```

*(The service also starts automatically on device boot via `BOOT_COMPLETED`).*

---

## In-App Controls & Customization

When you open the **Portal Ok Google** app on your device:
- **Acoustic Model:** Shows `READY (English US)` or `READY (Spanish)` once model files are detected.
- **Service State:** Displays `LISTENING` (active) or `STOPPED`.
- **Toggle Button:** Toggles between `START LISTENING` and `STOP LISTENING`.
- **TEST ASSISTANT TRIGGER:** Tap this button to immediately dispatch a `VOICE_COMMAND` intent to verify that Google Assistant / Gemini pops up and responds without needing to speak.
- **TEST CHIME:** Plays the Google Assistant-style confirmation chime on the Portal speakers.
- **Play chime on wake word:** Toggle audio feedback on/off when "Ok Google" is recognized.
- **Auto-dismiss Assistant (12s idle):** Toggle automatic dismissal of the Assistant card after 12s of inactivity.
- **Mic Voice Sensitivity Floor:** Real-time slider (200 – 1400, default 450) to calibrate the baseline quiet-room sensitivity floor. In noisy environments (TV, air conditioning, refrigerator), an **asymmetric noise-floor follower** dynamically raises the trigger threshold (+7 dB above ambient background noise), ensuring the Kaldi neural net sleeps even with background sounds.

---

## Resource & CPU Footprint

Designed specifically for embedded smart displays like the Meta Portal+:
- **RAM Usage:** **~128 MB total PSS** (reduced from 181 MB). Memory remains perfectly flat over days of continuous uptime thanks to static JNI `Recognizer` reuse and pre-allocated circular audio buffers.
- **CPU Idle (Quiet Room):** **~1.7% of a single core** (~0.2% total SoC load). During silence, the Kaldi neural network is completely bypassed.
- **CPU Idle (Noisy Room with TV/AC):** **~2–5% of a single core**. The 15th-percentile rolling noise floor tracker prevents background television or fan noise from waking the acoustic model.
- **Decoding Speed & Accuracy:** Constrained grammar lattice (`GRAMMAR` with `[unk]`) restricts Kaldi search space to wake words, cutting active decoding CPU by 50% and eliminating false-positive word gibberish.
- **Log Noise:** All debug logging is disabled by default for silent, zero-overhead operation.

---

## Controlling the Service via ADB

- **Start Listening:**
  ```bash
  adb shell am start-foreground-service -n dev.nuc.okgoogle/.WakeService
  ```
- **Stop Listening:**
  ```bash
  adb shell am stopservice -n dev.nuc.okgoogle/.WakeService
  ```
- **View Live Logs:**
  ```bash
  adb logcat -s okgoogle:V
  ```

---

## Building from Source

### Requirements
- **JDK 8** or newer (`javac`)
- **Android Build Tools:** `aapt2`, `d8` (or R8), `zipalign`, `apksigner`
- **Android SDK Platform:** `android.jar` (API 28+)

### Build Command
```bash
./scripts/build.sh
```

The script automatically detects build tools in your `PATH` or `$ANDROID_HOME` and generates a signed `okgoogle.apk`.

---

## Troubleshooting

| Symptom | Cause | Solution |
|---|---|---|
| `dead IAudioRecord` spam in logcat | "Hey Google" is enabled in Google App settings | Run `adb shell am start -a com.google.android.googlequicksearchbox.action.HOTWORD_DETECTION_SETTINGS` and turn **OFF** "Hey Google" (keep "While driving" / "Durante la conducción" ON). |
| Service yields mic immediately / `otherAppRecording=true` | Alexa or `com.millenium` holding the mic | Disable them via `adb shell pm disable-user --user 0 com.amazon.alexa.multimodal.falcon` and `adb shell pm disable-user --user 0 com.millenium`. |
| Status shows `Model: MISSING` | Acoustic model files not pushed to SD card | Run `./scripts/setup_model.sh` (or `./scripts/setup_model.sh es`). |
| "Ok Google" not recognized | Accent mismatch or wrong language model | If you speak Spanish, run `./scripts/setup_model.sh es` to install the Spanish phonetic model. |
| Mic blocked in SmartTube voice search | Accessibility service disabled | Enable Accessibility in Settings or run `adb shell settings put secure enabled_accessibility_services dev.nuc.okgoogle/.FgTracker`. |
| Assistant triggers once, then stops | Background execution limit | Ensure the service is started with `am start-foreground-service -n dev.nuc.okgoogle/.WakeService`. |

---

## Repository Structure

```
├── AndroidManifest.xml          # App manifest, permissions & receivers
├── res/
│   ├── raw/chime.wav            # Google Assistant-style confirmation chime
│   ├── values/strings.xml       # UI string resources
│   └── xml/accessibility_service_config.xml
├── lib/arm64-v8a/               # Precompiled Vosk & JNA shared libraries (arm64)
├── libs/                        # vosk-android.jar & jna.jar
├── src/dev/nuc/okgoogle/
│   ├── MainActivity.java        # UI to inspect model status, test trigger, and toggle service
│   ├── WakeService.java         # Background Vosk engine, silence recovery & voice intent
│   ├── FgTracker.java           # Accessibility service for active window arbitration
│   └── StartReceiver.java       # Boot receiver (START/STOP)
├── scripts/
│   ├── build.sh                 # Standalone APK compilation & signing script
│   └── setup_model.sh           # Automated Vosk acoustic model installer (en/es)
├── LICENSE                      # MIT License
└── README.md
```

---

## License

This project is licensed under the [MIT License](LICENSE).

### Acknowledgements
- [Alpha Cephei](https://alphacephei.com/vosk/) for the Vosk offline speech recognition engine.
- [OpenPortal](https://github.com/CoderPlusPlus/OpenPortal) community for keeping Meta Portal hardware useful and open.
