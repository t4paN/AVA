# AVA - Aggelos Voice Assistant

An Android accessibility app that lets **partially-sighted and elderly Greek-speaking users** operate their phone by voice. AVA is a **UX replacement button**: instead of the many small, precise taps a smartphone normally demands (find the contact, pick the right number, open a VoIP app, hit the call button), the user taps one large widget and speaks a name. Everything is audio-first, with spoken prompts and vibration feedback throughout.

Speech recognition runs **fully on-device** by default — no internet required. An optional **Online recognition** mode (default OFF) can use Google's built-in recognizer for higher accuracy, with on-device Whisper as the automatic offline fallback.

## Features

- **Offline Greek Speech Recognition** — Whisper ASR via TFLite, optimized for Greek
- **Fuzzy Contact Matching** — copes with heavily distorted transcriptions through phonetic normalization and weighted scoring
- **Voice Calling** — places calls through the default dialer or a VoIP app
- **VoIP Calling** — places Viber, WhatsApp and Signal calls through the contact's own "Free call" entry in the address book: no on-screen tapping, no calibration, no accessibility service
- **Returns You Home** — when a call ends, AVA puts the phone back on the home screen, so the widget is one tap away and a stray tap inside the messenger cannot start a call you did not mean to make
- **Read Missed Calls** — AVA reads recent missed calls from the call log and announces them aloud
- **Magnifier & Flashlight** — turns the camera into a magnifier with the torch on
- **Radio** — stream Greek radio stations by voice; playback yields to any incoming call and resumes afterwards
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

The Whisper model is **not** committed to the repo (model files are git-ignored). You can either bundle it at build time or let the app fetch it on first run.

**Bundling it (recommended for local builds — the app then works fully offline from the first launch):**

1. Clone the repository
2. Download **`whisper-base.TOP_WORLD.tflite`** from [DocWolle/whisper_tflite_models](https://huggingface.co/DocWolle/whisper_tflite_models/tree/main)
3. Place it in **`whisper_native/src/main/assets/`** (the filename must match exactly — `ModelManager` loads `whisper-base.TOP_WORLD.tflite` from assets)
4. Build with Android Studio, or from the command line (run from the `AVA/` directory):
   ```bash
   ./gradlew assembleRelease
   ```
   The APK will be in `app/build/outputs/apk/release/`.

**Without bundling it** (CI builds, and any APK built from a clean clone): AVA does **not** nag for the model at startup, because *Recognition: Google* needs no model at all. The download is offered at the one moment it matters — switching to *Recognition: Whisper* while the model is absent — and can also be triggered from the menu (⋮ → *Λήψη μοντέλου ομιλίας*), which only appears while the model is missing. Until the model is present, offline transcription cannot run and AVA says so out loud rather than failing silently — though **Online recognition** mode works without it.

Building `:whisper_native` requires the Android **NDK and CMake** (native ABIs: `armeabi-v7a`, `arm64-v8a`).

`assembleRelease` produces an **unsigned** APK unless a signing key is supplied. For signed, distributable builds — and the tag-driven GitHub Actions workflow that publishes them — see **[docs/RELEASING.md](docs/RELEASING.md)**.

### Install on Device

1. Transfer the APK to the device
2. Open the APK file
3. Tap "Install" (you may need to allow "Unknown sources")

### Permissions & Master Settings

**⚠️ Enable these master settings first:**

1. **Display over other apps:** Settings → Apps → Special app access → Display over other apps → AVA → ON
2. **Show notifications:** Settings → Notifications → AVA → ON
3. **Accessibility Service** — **not needed for normal use.** VoIP calls are placed
   through the contact's own call entry, which requires nothing beyond the Contacts
   permission. The service is only a fallback for a contact the messenger has never
   synced into the address book. If you do want that fallback:
   - Settings → Accessibility → Installed services
   - You may need to enable "Allow downloaded apps" first
   - Find AVA → Toggle ON
   - ⚠️ Android disables it after every app update, so it would need re-enabling each time — another reason not to depend on it.

**Then grant these permissions in App Info → Permissions:**
- ✅ Microphone
- ✅ Phone
- ✅ Contacts
- ✅ Call log (for reading missed calls)
- ✅ Notifications
- ✅ Display over other apps

**Battery / background:**
- ⚠️ Turn **off** background/battery management for AVA ("Manage App" / "Remove permissions if app is unused" must be OFF), or Android will kill AVA's services.

### Add the Widget

1. Long-press the home screen
2. Widgets → AVA
3. Drag the widget to the screen

### VoIP Setup

**There is normally nothing to set up.** Viber, WhatsApp and Signal each sync a
"Free call" entry into the phone's address book, and AVA calls through that entry
directly — so there are no screen positions to calibrate, nothing to redo when the
messenger redesigns its screens, and no accessibility service involved. The channel
does have to be marked on the contact's name (below), and the messenger has to have
synced that contact, which it does by default.

Two menu items exist for the exceptions:

| Menu (⋮) item | What it is for |
|---|---|
| **Direct VoIP call** | ON by default. Turn OFF to force the old deep-link-and-tap route, for comparison on a device. |
| **VoIP διαγνωστικά** | Shows what the last call lookup found on this phone, with a copy button. Use it when a contact will not connect. |
| **VoIP Setup** | Calibrates the fallback tap position. Only relevant if you are using the accessibility fallback. |

**Mark VoIP contacts by channel:** add the channel as the **last word** of the contact's name, so AVA routes the call correctly:
- `Γιώργος Παπαδόπουλος VIBER`
- `Μαρία Κ WHATSAPP`
- `Νίκος SIGNAL`

> **Channel support, precisely:** contacts can be routed to **Viber, WhatsApp, or Signal**. Viber and Signal are verified end-to-end on real devices — the call connects with no accessibility service enabled at all. WhatsApp uses the identical mechanism and its call entry is confirmed present, but has not yet been rung in testing. If a messenger has not synced a given contact there is no entry to call, and AVA falls back to opening the app.

## Settings

| Setting | Description |
|---|---|
| Start on Unlock | Automatically start AVA when the phone is unlocked |
| Fast Mode | ON = fast/less-accurate base model (bundled); OFF = slower/more-accurate small model (~305 MB download) |
| Recognition: Google / Whisper | **Default Whisper.** Google = transcribe with the built-in speech recognizer (`el-GR`) instead of Whisper — usually more accurate on real speech, but **requires a network and sends audio to Google**. On-device Whisper stays the automatic fallback (no network, or if the recognizer errors), so the call button never dies offline. |
| Autocall | Automatically place the call when the match is confident (OFF = show a confirm button) |
| Direct VoIP call | **Default ON.** Place VoIP calls through the contact's own call entry. OFF falls back to opening the app and tapping the button, which needs the accessibility service. |
| VoIP διαγνωστικά | Shows the call entries found for the last VoIP call attempt, with a copy button — the first thing to check if a contact will not connect |
| VoIP Setup | Calibrates the tap position used by the accessibility fallback |
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
