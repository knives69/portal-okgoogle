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

- **Offline On-Device Recognition:** Uses a lightweight Vosk acoustic model (`vosk-model-small-en-us-0.15`) running entirely on-device with zero internet latency or cloud telemetry.
- **Microphone Collision Prevention:** By keeping Google App's internal "Hey Google" toggle **OFF**, the API 31 crash loop is completely eliminated.
- **Universal Capture:** Listens across the entire system — home screen, third-party launchers, SmartTube, web browsers, etc.
- **Dynamic Mic Handover (Zero Conflicts):**
  - **Window Overlay Tracking (`FgTracker`):** An Accessibility Service detects when any app (like SmartTube or Google Voice Search) opens a dictation dialog (`TranscriptionActivity`, `RecognizerIntent`, etc.) and immediately releases the microphone.
  - **Hardware Recording Monitor:** Uses `AudioManager.registerAudioRecordingCallback` to yield when any other client opens an audio stream.
  - **AudioPolicy Silence Recovery:** If the system AudioPolicy silences background audio (`amp == 0`), the capture loop automatically resets cleanly once foreground audio finishes.
- **Instant Assistant Invocation:** Once a wake word is detected, `WakeService` releases the microphone, allows Qualcomm HAL input to reach standby, and launches `android.intent.action.VOICE_COMMAND` to trigger Google Assistant or Gemini.

---

## Supported Wake Words

The default English acoustic model listens for:
- `"ok google"`
- `"hey google"`
- `"okay google"`
- Phonetic variants: `"ok gugel"`, `"hey gugel"`

*(Also supports Spanish/multilingual models with phonetic approximations like `"ok web"`).*

---

## Prerequisites

1. **Meta Portal / Portal+** rooted and running Android 9 (Aloha/OpenPortal).
2. **Google App (GSA):** Tested with version `17.6.59.ve.arm64` or later.
3. **Default Assist App:** Go to **Settings → Apps & Notifications → Default apps → Assist & voice input** and select **Google**.
4. **IMPORTANT:** Open Google App → Settings → Voice → Voice Match, and ensure **"Hey Google"** is **DISABLED / OFF**. (Voice command invocation will handle triggering).

---

## Quick Start / Installation

### 1. Install the Voice Model
Run the automated setup script to download and push the model to the device over ADB.
By default it installs English, or you can specify Spanish:

```bash
# Default (English US):
./scripts/setup_model.sh

# Or Spanish:
./scripts/setup_model.sh es
```

*(This downloads the acoustic model (~40MB) and installs it to `/sdcard/Android/data/dev.nuc.okgoogle/files/model/`).*

### 2. Build and Install the APK
Build the APK from source:

```bash
./scripts/build.sh
adb install -r okgoogle.apk
```

### 3. Grant Permissions & Enable Accessibility
Grant audio permissions and enable the foreground tracker service:

```bash
# Grant microphone permission
adb shell pm grant dev.nuc.okgoogle android.permission.RECORD_AUDIO

# Enable Accessibility Service for dynamic microphone handover
adb shell settings put secure enabled_accessibility_services dev.nuc.okgoogle/.FgTracker
adb shell settings put secure accessibility_enabled 1

# Start the wake service directly
adb shell am start-foreground-service -n dev.nuc.okgoogle/.WakeService
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

## Repository Structure

```
├── AndroidManifest.xml          # App manifest & permissions
├── res/                         # Strings & accessibility service configuration
├── lib/arm64-v8a/               # Precompiled Vosk & JNA shared libraries (arm64)
├── libs/                        # vosk-android.jar & jna.jar
├── src/dev/nuc/okgoogle/
│   ├── MainActivity.java        # UI to inspect model status and toggle service
│   ├── WakeService.java         # Background Vosk engine, silence recovery & voice intent
│   ├── FgTracker.java           # Accessibility service for active window arbitration
│   └── StartReceiver.java       # Boot & broadcast receiver (START/STOP)
├── scripts/
│   ├── build.sh                 # Standalone APK compilation & signing script
│   └── setup_model.sh           # Automated Vosk acoustic model installer
├── LICENSE                      # MIT License
└── README.md
```

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

## License

This project is licensed under the [MIT License](LICENSE).

### Acknowledgements
- [Alpha Cephei](https://alphacephei.com/vosk/) for the Vosk offline speech recognition engine.
- [OpenPortal](https://github.com/CoderPlusPlus/OpenPortal) community for keeping Meta Portal hardware useful and open.
