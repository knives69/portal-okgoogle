# Portal Ok Google

**Offline Wake-Word Trigger for Meta Portal+ 1st generation (`aloha`) · Android 9 / API 28**

*Tested on that model only; other Portal models are untested (see the compatibility note below).*

[![Release](https://img.shields.io/github/v/release/knives69/portal-okgoogle?style=flat-square)](https://github.com/knives69/portal-okgoogle/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%209%20(API%2028)-green.svg?style=flat-square)](https://android.com)

An open-source, 24/7 background hotword service enabling hands-free **"Ok Google"** and **"Hey Google"** activation on Meta Portal devices running Android 9 (such as via [OpenPortal](https://github.com/CoderPlusPlus/OpenPortal)).

Powered by [Vosk](https://alphacephei.com/vosk/) offline speech recognition with dynamic audio session arbitration, acoustic echo suppression, and seamless microphone handover.

**No root required.** The app is an ordinary sideloaded APK: it needs `RECORD_AUDIO`, a foreground service and an accessibility service (to know what is on screen). Nothing it does touches `/system`, `/vendor`, `su` or SELinux state.

> [!IMPORTANT]
> **Tested scope:** only on **Meta Portal+ 1st generation (`aloha`), Android 9 / API 28**, with Google Play Services + GSA `17.6.59.ve.arm64` installed via OpenPortal. Other Portal models (Portal 10" Gen 1, Portal Mini, Go, TV, Gen 2) are **untested** — the code assumes a single external mic, `ACTION_VOICE_COMMAND` routed to GSA, and API 28 `AudioRecordingCallback` semantics.

---

## Key Features in v1.6.0

- **100% Offline & Private:** Runs entirely on-device with Vosk Kaldi acoustic models. Verified working build: **`vosk-model-small-es-0.22`** (Spanish, 66 MB) and **`vosk-model-small-en-us-0.15`** (English). Newer models (e.g. `vosk-model-small-es-0.42`) should work but have not been validated on this device — if you switch, re-check CPU/RAM (see *Resource usage*). Zero cloud latency, zero audio telemetry.
- **Strict 4-Syllable Hotword Detection:** Constrained grammar lattice eliminating all conversational false positives.
- **Adaptive Media Playback Suppression:** Dynamically elevates acoustic thresholds and hysteresis when media (TikTok, YouTube, Spotify) is playing through the Portal's own speakers, preventing speaker spillover from triggering false activations while still allowing close-range voice commands.
- **Smart 15-Second Auto-Dismiss (Google Nest Hub Standard):** Automatically dismisses the Assistant overlay after 15 seconds of inactivity. Includes intelligent TTS reading window extension (postpones dismissal while Assistant speaks long news or recipes, then grants a full 15-second window to read suggestions).
- **Pure BACK Dismissal (No Recents Trigger):** Dismisses Assistant using a clean two-stage `GLOBAL_ACTION_BACK` sequence without injecting `HOME` events that could inadvertently open the Android task switcher.
- **Universal Voice Dictation Handover:** Yields the microphone immediately when any application (e.g., SmartTube, YouTube, browser) opens an in-app speech dictation or voice search dialog.
- **Dual-Tier Resilient Watchdog:** Instant foreground service revival via Accessibility Service lifecycle (`FgTracker`) backed by a 15-minute `AlarmManager` repeating watchdog.
- **Thread Generation Guard:** Employs atomic generation counters (`AtomicInteger generation`) preventing older audio threads from closing shared Vosk pointers or killing newly revived services.
- **Auto-Enabling Accessibility Service:** Uses `WRITE_SECURE_SETTINGS` to automatically register and maintain the `FgTracker` service across app updates.
- **Material 3 UI Dashboard:** Modern dashboard with Light, Dark, and Follow System theme modes, real-time audio telemetry, and full English localization.

---

## The Problem: Why Native "Hey Google" Fails on Meta Portal

1. **Missing DSP Sound Trigger HAL:**  
   Meta stripped Qualcomm's low-power `sound_trigger` DSP HAL libraries and `HotwordEnrollment*` packages from the stock vendor image (`/vendor/lib64/hw/sound_trigger.primary.*.so`). Hardware-level DSP wake-word detection is physically impossible without proprietary firmware blobs.

2. **API 31 Crash Loop in Modern Google App:**  
   If "Hey Google" is enabled in Google App (GSA 17.x+) settings on Android 9, GSA attempts to bind `android.service.voice.HotwordDetectionService` (which only exists in Android 12 / API 31+). This triggers a continuous `ClassNotFoundException` crash loop inside `GsaVoiceInteractionService.onCreate()`, spamming Qualcomm audio input 30 times per second (`dead IAudioRecord`) and exhausting audio tracks.

---

## How This Solution Works

- **Microphone Collision Prevention:** By keeping Google App's internal "Hey Google" toggle **OFF**, the API 31 crash loop is completely eliminated.
- **Universal Capture & 24/7 Reliability:** Listens across the entire system — home screen, third-party launchers, video apps, browsers, etc. Backed by `PARTIAL_WAKE_LOCK` to ensure uninterrupted capture when the display turns off.
- **Audio Chime (Earcon):** Plays an instant Google Assistant-style rising chime via `SoundPool` the millisecond a wake word is recognized.
- **Multi-App Arbitration:**
  - **Window Overlay Tracking (`FgTracker`):** Accessibility Service detects speech search classes (`voicesearch`, `transcription`, `speechrecognizer`) across any app and yields the mic instantly.
  - **Hardware Recording Monitor:** Uses `AudioManager.registerAudioRecordingCallback` to yield whenever an external app opens a recording track, automatically reclaiming the mic once the recording session ends.
  - **AudioPolicy Silence Recovery:** If the system AudioPolicy silences background audio (`peak == 0`), the capture loop automatically resets cleanly once foreground audio finishes.
- **Local AudioRecord Backoff:** 4-stage exponential retry backoff (2s, 4s, 8s, 16s = 30s) allows temporary hardware audio conflicts (e.g. video calls, camera capture) to recover without crashing the service.
- **60-Second Telemetry Heartbeat:** Periodically outputs diagnostic state (`peak`, `rms`, `floor`, `dynThresh`, `inSpeech`, `media`, `gen`) to logcat without log spam.

---

## Supported Wake Words

Recognition is restricted to high-confidence 4-syllable phrases to guarantee zero false positives from background TV, conversation, or social video feeds:

| Wake Word | Syllables | Phonetic Target |
|---|---|---|
| `"ok google"` | 4 | `[oʊ-keɪ-ɡuː-ɡəl]` |
| `"hey google"` | 3-4 | `[heɪ-ɡuː-ɡəl]` |
| `"okay google"` | 4 | `[oʊ-keɪ-ɡuː-ɡəl]` |
| `"ok gugel"` | 4 | Phonetic Spanish/Multilingual approximation |
| `"hey gugel"` | 3-4 | Phonetic Spanish/Multilingual approximation |

> [!NOTE]
> All conversational phrases and loose 2-syllable triggers (`"ok web"`, `"oye google"`, `"hola google"`, single-word `"google"`) have been completely purged from the grammar and wake tables to eliminate false positives in videos and movies.

---

## Prerequisites

1. **Meta Portal+ 1st generation (`aloha`)** running Android 9 (API 28) with Google Play Services installed (e.g. via OpenPortal). **Root is not required.** Only this model has been tested; other models are untested.
2. **Google App (GSA):**
   - **Package:** `com.google.android.googlequicksearchbox`
   - **Verified Working Build:** **`17.6.59.ve.arm64`** (Version code: `301698282`, minSdk 28, targetSdk 36, `arm64-v8a`).
   - *(Note: Regarding [OpenPortal](https://github.com/CoderPlusPlus/OpenPortal), newer GSA builds such as `17.6.59.ve.arm64` or modern Play Store releases are required. Older builds like GSA 14.14 prematurely terminate voice intents in `HandsFreeActivity`).*
   - Verify your installed version with:
     ```bash
     adb shell dumpsys package com.google.android.googlequicksearchbox | grep versionName
     ```
3. **Computer with ADB:** Connected to your Portal over USB or Wi-Fi (`adb connect <portal-ip>:5555`).

---

## Complete Setup Guide (Step-by-Step)

Follow these steps to configure your Portal and start hands-free listening.

### Step 1: Install the Voice Model on Device
Run the included setup script from your computer to download and push the acoustic model to `/sdcard/Android/data/dev.nuc.okgoogle/files/model/`:

```bash
# English US (default):
./scripts/setup_model.sh

# Or Spanish (validated build vosk-model-small-es-0.22):
./scripts/setup_model.sh es

# Try the newer Spanish model (untested on this device):
VOSK_ES_MODEL=vosk-model-small-es-0.42 ./scripts/setup_model.sh es
```

### Step 2: Install the APK
Download the latest [okgoogle.apk](https://github.com/knives69/portal-okgoogle/releases/latest) from Releases, or build from source (see [Building from Source](#building-from-source)):

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
> - **Incompatible Background Mic Grabbers:** Neither **Amazon Alexa** (`com.amazon.alexa.multimodal.falcon`) nor **`com.millenium`** can remain active. Both capture and hold persistent audio recording sessions in the background. Disable them with:
>   ```bash
>   adb shell pm disable-user --user 0 com.amazon.alexa.multimodal.falcon
>   adb shell pm disable-user --user 0 com.millenium
>   ```
> - **"Hey Google"** MUST be turned **OFF** in Google App settings (prevents the background `dead IAudioRecord` crash loop).
> - **"While driving"** (or **"Durante la conducción"** in Spanish) MUST be turned **ON** (maintains the voice command gateway ready for assistant invocation).

- **Via GUI / Direct Intent:**  
  Open the Voice Match settings page directly:
  ```bash
  adb shell am start -a com.google.android.googlequicksearchbox.action.HOTWORD_DETECTION_SETTINGS
  ```
  1. Toggle **"Hey Google"** to **OFF / DISABLED**.
  2. Under *Other devices / While driving* (or *Otros dispositivos / Durante la conducción*), toggle **"While driving"** to **ON / ENABLED**.

### Step 5: Grant Permissions & Enable Auto-Management
Grant audio capture and secure settings permissions via ADB so the service can auto-manage its accessibility helper:

```bash
adb shell pm grant dev.nuc.okgoogle android.permission.RECORD_AUDIO
adb shell pm grant dev.nuc.okgoogle android.permission.WRITE_SECURE_SETTINGS
```

### Step 6: Enable Accessibility Service (Microphone Handover & Auto-Dismiss)
- **Via GUI:**  
  Go to **Settings → Accessibility → Portal Ok Google** and toggle it **ON**.
- **Via ADB:**  
  ```bash
  adb shell settings put secure enabled_accessibility_services dev.nuc.okgoogle/.FgTracker
  adb shell settings put secure accessibility_enabled 1
  ```

### Step 7: Whitelist from Battery Optimization (Doze)
Ensures Android does not throttle or kill the background capture loop:

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

The dashboard (Material 3) includes:
- **Service Status Chip:** Displays live status (`LISTENING` or `STOPPED`) and app version (`v1.6.0`).
- **Acoustic Model Language:** Shows `READY (English US)` or `READY (Spanish)` once model files are validated.
- **Start / Stop Button:** Starts or stops background listening.
- **Theme Selector:** Toggle between **Light**, **Dark**, and **Follow System** (auto-adapts UI contrast).
- **TEST ASSISTANT TRIGGER:** Dispatches a `VOICE_COMMAND` intent to verify Assistant pops up without needing to speak.
- **TEST CHIME:** Plays the confirmation chime on Portal speakers.
- **Play chime on wake word:** Toggle audio feedback on/off when wake words are detected.
- **Auto-dismiss Assistant (15s idle):** Toggle automatic dismissal of the Assistant card after 15 seconds of inactivity.
- **Mic Voice Sensitivity Floor:** Real-time slider (200 – 1400, default 450) to calibrate quiet-room sensitivity. In noisy environments, the rolling noise-floor tracker automatically adapts the trigger threshold (+7 dB above background ambient sound).

---

## Resource & CPU Footprint

Tested and benchmarked on Meta Portal+ hardware (Snapdragon 660 / Aloha):
- **RAM Usage:** **~125 MB total PSS** (flat memory footprint; static JNI `Recognizer` reuse and zero buffer churn).
- **CPU Idle (Quiet Room):** **~1.5% of a single core** (~0.2% total SoC load). During silence, the Kaldi neural net sleeps.
- **CPU Idle (Noisy Room with TV/AC):** **~2–4% of a single core**. The rolling noise-floor follower prevents background television or fan noise from waking the acoustic model.
- **Speaker Playback Immunity:** When video/audio is active (`media=true`), the threshold automatically raises to 2600 and opening hysteresis requires 300ms, completely ignoring video dialogue while keeping voice commands responsive.
- **Heartbeat Telemetry:** 1 log line per minute (`Heartbeat (60s)`) ensures live health observability without logcat spam.

---

## Controlling the Service via ADB

- **Start Listening:**
  ```bash
  adb shell am start-foreground-service -n dev.nuc.okgoogle/.WakeService
  ```
- **Stop Listening:**
  ```bash
  adb shell am broadcast -a dev.nuc.okgoogle.STOP -p dev.nuc.okgoogle
  ```
- **Simulate Watchdog Tick:**
  ```bash
  adb shell am broadcast -a dev.nuc.okgoogle.WATCHDOG -p dev.nuc.okgoogle
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
| Assistant card does not close after 15s | Accessibility service not running or permission missing | Grant secure settings via `adb shell pm grant dev.nuc.okgoogle android.permission.WRITE_SECURE_SETTINGS` and restart the service. |
| Assistant triggers repeatedly while watching videos | Outdated app version with loose grammar | Upgrade to `v1.6.0+` which includes adaptive media suppression and strict 4-syllable hotwords. |

---

## Repository Structure

```
├── AndroidManifest.xml          # App manifest, permissions & receivers
├── res/
│   ├── mipmap-*/                # App launcher icons (Gemini star + studio mic)
│   ├── raw/chime.wav            # Google Assistant-style confirmation chime
│   ├── values/strings.xml       # UI string resources (English localization)
│   └── xml/a11y.xml             # Accessibility service configuration
├── lib/arm64-v8a/               # Precompiled Vosk & JNA shared libraries (arm64)
├── libs/                        # vosk-android.jar & jna.jar
├── src/dev/nuc/okgoogle/
│   ├── MainActivity.java        # Material 3 UI dashboard (Day/Night, telemetry & controls)
│   ├── WakeService.java         # Background Vosk engine, generation guards & voice intent
│   ├── FgTracker.java           # Accessibility service, window arbitration & 15s auto-dismiss
│   └── StartReceiver.java       # Boot receiver, watchdog timer & START/STOP intents
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
