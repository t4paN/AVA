# AVA - Aggelos Voice Assistant

An Android accessibility app that lets **partially-sighted and elderly Greek-speaking users** operate their phone by voice. AVA is a **UX replacement button**: instead of the many small, precise taps a smartphone normally demands (find the contact, pick the right number, open a VoIP app, hit the call button), the user taps one large widget and speaks a name. Everything is audio-first, with spoken prompts and vibration feedback throughout.

Speech recognition runs **fully on-device** by default — no internet required. An optional **Online recognition** mode (default OFF) can use Google's built-in recognizer for higher accuracy, with on-device Whisper as the automatic offline fallback.

## Features

- **Offline Greek Speech Recognition** — Whisper ASR via TFLite, optimized for Greek
- **Fuzzy Contact Matching** — copes with heavily distorted transcriptions through phonetic normalization and weighted scoring
- **Voice Calling** — places calls through the default dialer or a VoIP app
- **VoIP Auto-Click** — an AccessibilityService taps the call button for you, using caregiver-calibrated positions (Viber is fully supported end-to-end; WhatsApp and Signal can be set as routing targets)
- **Read Missed Calls** — AVA opens the dialer, reads the recent missed calls, and announces them aloud
- **Magnifier & Flashlight** — turns the camera into a magnifier with the torch on
- **Radio** — stream Greek radio stations by voice
- **Audio-First UX** — Greek TTS announcements and vibration feedback at every step
- **Widget + Unlock Activation** — start by tapping the widget, or automatically on phone unlock

## Voice Commands

After the "Πείτε όνομα" prompt, say:

| Say (Greek) | Action |
|---|---|
| `κλήση [όνομα]` | Call the matching contact |
| `αναπάντητες` | Read out recent missed calls |
| `ραδιόφωνο` | Open the radio |
| `φακός` | Open the magnifier / flashlight |

The matcher is forgiving: a leading command-ish word followed by a name is treated as a call, even if the transcription is rough.

## How It Works

1. User taps the widget (or unlocks the phone, if enabled)
2. AVA prompts **"Πείτε όνομα"** with a beep and vibration
3. User says e.g. **"κλήση Γιώργο"**
4. Silero VAD detects the end of speech; Whisper transcribes on-device (~6 seconds total)
5. The fuzzy matcher finds the best contact (or asks the user to pick between two close matches)
6. AVA announces **"Καλώ …"** and places the call via the dialer or the contact's VoIP app

## Requirements

- **Android 11 (API 30) or newer** — this is the app's `minSdk`
- ~150 MB storage for the bundled Whisper base model (optional ~305 MB download for the more accurate model)
- Tested on: Samsung A05, Samsung A56, Redmi 8

## Installation

### Building the APK

The Whisper model is **not** committed to the repo (model files are git-ignored), so you must add it before building:

1. Clone the repository
2. Download **`whisper-base.TOP_WORLD.tflite`** from [DocWolle/whisper_tflite_models](https://huggingface.co/DocWolle/whisper_tflite_models/tree/main)
3. Place it in **`whisper_native/src/main/assets/`** (the filename must match exactly — `ModelManager` loads `whisper-base.TOP_WORLD.tflite` from assets)
4. Build with Android Studio, or from the command line (run from the `AVA/` directory):
   ```bash
   ./gradlew assembleRelease
   ```
   The APK will be in `app/build/outputs/apk/release/`.

Building `:whisper_native` requires the Android **NDK and CMake** (native ABIs: `armeabi-v7a`, `arm64-v8a`).

### Install on Device

1. Transfer the APK to the device
2. Open the APK file
3. Tap "Install" (you may need to allow "Unknown sources")

### Permissions & Master Settings

**⚠️ Enable these master settings first:**

1. **Display over other apps:** Settings → Apps → Special app access → Display over other apps → AVA → ON
2. **Show notifications:** Settings → Notifications → AVA → ON
3. **Accessibility Service** (required for VoIP auto-click and reading missed calls):
   - Settings → Accessibility → Installed services
   - You may need to enable "Allow downloaded apps" first
   - Find AVA → Toggle ON
   - ⚠️ **The accessibility service is disabled by Android after every app update — you must re-enable it each time.**

**Then grant these permissions in App Info → Permissions:**
- ✅ Microphone
- ✅ Phone
- ✅ Contacts
- ✅ Notifications
- ✅ Display over other apps

**Battery / background:**
- ⚠️ Turn **off** background/battery management for AVA ("Manage App" / "Remove permissions if app is unused" must be OFF), or Android will kill AVA's services.

### Add the Widget

1. Long-press the home screen
2. Widgets → AVA
3. Drag the widget to the screen

### VoIP Setup (Optional)

To place calls through a VoIP app:

1. In AVA: Menu (⋮) → VoIP Setup
2. Select the app (Viber is the fully-supported target)
3. Pick a screenshot of the app's call screen
4. Tap where the call button is located
5. Save

**Mark VoIP contacts by channel:** add the channel as the **last word** of the contact's name, so AVA routes the call correctly:
- `Γιώργος Παπαδόπουλος VIBER`
- `Μαρία Κ WHATSAPP`
- `Νίκος SIGNAL`

The VoIP Setup screen also lets you calibrate the dialer for the **"αναπάντητες" (missed calls)** command.

> **Channel support, precisely:** contacts can be routed to **Viber, WhatsApp, or Signal**. Auto-click (tapping the call button for the user) is currently wired end-to-end for **Viber** only; other channels deep-link into the app but do not yet auto-click.

## Settings

| Setting | Description |
|---|---|
| Start on Unlock | Automatically start AVA when the phone is unlocked |
| Fast Mode | ON = fast/less-accurate base model (bundled); OFF = slower/more-accurate small model (~305 MB download) |
| Online recognition | **Default OFF.** ON = transcribe with Google's built-in speech recognizer (`el-GR`) instead of Whisper — usually more accurate on real speech, but **requires a network and sends audio to Google**. On-device Whisper stays the automatic fallback (no network, or if the recognizer errors), so the call button never dies offline. |
| Autocall | Automatically place the call when the match is confident (OFF = show a confirm button) |
| VoIP Setup | Configure auto-click positions and dialer calibration |
| Σταθμοί Ραδιοφώνου | Add or remove radio stations |

The **reset button** (floating button on the main screen) fully restarts AVA and reloads contacts from the device — use it after adding or renaming contacts, since AVA caches them.

## Technical Details

- **Modules:** `:app` (Kotlin UI + services) and `:whisper_native` (Whisper TFLite engine, JNI/C++)
- **Speech Recognition:** Whisper ASR (whisper-base) with the Greek language token
- **Voice Activity Detection:** Silero VAD for automatic recording cutoff
- **Audio Processing:** JTransforms FFT for mel-spectrogram computation
- **Contact Matching:** `SuperFuzzyContactMatcher` — phonetic normalization, token merging, Levenshtein + substring scoring with ambiguity detection. Its normalization is kept identical to `ContactRepository.normalizeName()` so spoken names and stored names compare apples-to-apples.
- **Performance:** ~6 seconds from button press to call initiation

## License

MIT License - see [LICENSE](LICENSE) for details.

AVA bundles and depends on third-party components (TensorFlow Lite, Abseil, FlatBuffers, the Whisper and Silero VAD models, JTransforms, AndroidX/Material, and others) under their own licenses. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## Links

- **Repository:** [github.com/t4paN/AVA](https://github.com/t4paN/AVA)
- **Whisper Models:** [DocWolle/whisper_tflite_models](https://huggingface.co/DocWolle/whisper_tflite_models)
- **Greek Radio Stations:** [radio-browser.info](https://www.radio-browser.info/search?page=1&order=clickcount&reverse=true&hidebroken=true&countrycode=GR)
