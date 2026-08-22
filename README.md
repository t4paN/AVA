# AVA - Aggelos Voice Assistant

An Android accessibility app that lets **partially-sighted and elderly Greek-speaking users** operate their phone by voice. AVA is a **UX replacement button**: instead of the many small, precise taps a smartphone normally demands (find the contact, pick the right number, open a VoIP app, hit the call button), the user taps one large widget and speaks a name. Everything is audio-first, with spoken prompts and vibration feedback throughout.

Speech recognition has **two engines**, switchable per device. The shipped default is **Google** (`Αναγνώριση: Google`), the phone's built-in recognizer: more accurate on real speech, needs no model download, and **sends the recorded audio to Google**. **Whisper** runs **fully on-device** with no network at all, and is the automatic fallback whenever Google is unavailable or errors — so the call button still works with the SIM out. Whichever engine is selected, AVA keeps working offline; only the accuracy changes.

The audio a user speaks is the only thing that ever leaves the phone, and only on the Google engine. Contacts, call log, and transcription logs stay on the device. See the [privacy policy](https://t4pan.github.io/ava/privacy.html) for the full statement.

## Features

- **Two Greek Speech Engines** — Google's recognizer (`el-GR`, the default) or fully offline Whisper ASR via TFLite; Whisper is the automatic fallback when there is no network
- **Waits for a Slow Start** — the speaking time does not begin counting until the user actually starts speaking, so thinking about who to call never eats the time to say the name
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
3. AVA listens, waiting up to **6 seconds** for the user to begin — thinking time, not talking time
4. User says e.g. **"κλήση Γιώργο"**. The speaking budget starts at the **first syllable**, and the end of speech closes the recording early (Silero VAD offline, the recognizer's own endpointing online)
5. The selected engine transcribes — Google over the network, or Whisper on-device
6. The fuzzy matcher finds the best contact (or asks the user to pick between two close matches)
7. AVA announces **"Καλώ …"** and places the call via the dialer or the contact's VoIP app

## Requirements

- **Android 11 (API 30) or newer** — this is the app's `minSdk`
- **Storage for the Whisper model is optional** — the default Google engine needs none. Switching to Whisper needs the ~102 MB base model (downloaded on demand), and Fast Mode OFF needs the larger ~305 MB one
- A network connection for the Google engine; Whisper needs none
- Tested on: Motorola G84, Samsung A05s, Samsung A05, Samsung A56, Redmi 8

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
3. **Accessibility Service** — **not needed, and not present in a release build.**
   VoIP calls are placed through the contact's own call entry, which requires nothing
   beyond the Contacts permission. As of 1.5.0 the service is declared **only in debug
   builds** (`app/src/debug/AndroidManifest.xml`), so an installed release APK or Play
   build has nothing to enable and the old auto-click fallback cannot run on it. On a
   debug build it is still there for comparison testing: Settings → Accessibility →
   Installed services → AVA (you may need "Allow downloaded apps" first). Android
   disables it after every app update — another reason not to depend on it.

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

### VoIP Calls

**There is normally nothing to set up.** Viber, WhatsApp and Signal each sync a
"Free call" entry into the phone's address book, and AVA calls through that entry
directly — so there are no screen positions to calibrate, nothing to redo when the
messenger redesigns its screens, and no accessibility service involved. The channel
does have to be marked on the contact's name (below), and the messenger has to have
synced that contact, which it does by default.

**There are no VoIP menu items any more.** The Direct-VoIP-call toggle, the
diagnostics dialog and the calibration screen were all removed in 1.5.0 — they
served the old deep-link-and-tap route, and direct calling replaced it. Direct
calling is now the only path. (`VoIPDirectCall` still records what the last lookup
found; only the on-device viewer is gone, so reading it needs `adb logcat`.)

**Mark VoIP contacts by channel:** add the channel as the **last word** of the contact's name, so AVA routes the call correctly:
- `Γιώργος Παπαδόπουλος VIBER`
- `Μαρία Κ WHATSAPP`
- `Νίκος SIGNAL`

> **Channel support, precisely:** contacts can be routed to **Viber, WhatsApp, or Signal**. Viber and Signal are verified end-to-end on real devices — the call connects with no accessibility service enabled at all. WhatsApp uses the identical mechanism and its call entry is confirmed present, but has not yet been rung in testing. If a messenger has not synced a given contact there is no entry to call, and AVA falls back to opening the app.

## Settings

Settings live in three places, and all of them are meant for the **caregiver** doing
the setup, not the person who uses the widget day to day. Every label is Greek.

**Overflow menu (⋮)** — the everyday switches. State reads as `ΝΑΙ` / `ΟΧΙ`:

| Menu item | Description |
|---|---|
| `Αναγνώριση: Google / Whisper` | **Default Google.** The recognition engine. Google (`el-GR`) is usually more accurate on real speech but **requires a network and sends audio to Google**. Whisper runs on-device and needs the model downloaded. Whisper is the automatic fallback either way, so the call button never dies offline. |
| `Λειτουργία χωρίς ίντερνετ` | **Default ΝΑΙ.** Whether Whisper may run at all. ΟΧΙ means there is no offline engine: with no network AVA says so and places no call, instead of making the user wait. Whisper is CPU-only, so on a slow phone that wait is long — see the (!) notice |
| `Γρήγορη λειτουργία` | Whisper only. ΝΑΙ = fast/less-accurate base model; ΟΧΙ = slower/more-accurate small model (~305 MB download) |
| `Εκκίνηση με ξεκλείδωμα` | Start AVA automatically when the phone is unlocked |
| `Αυτόματη κλήση` | Place the call automatically when the match is confident (ΟΧΙ = show a confirm button) |
| `Λήψη μοντέλου ομιλίας` | Downloads the Whisper model. **Only appears while the model is missing** |
| `Σταθμοί ραδιοφώνου` | Add or remove radio stations |

**The (!) button**, bottom-left of the main screen — the caregiver notice: what has to
be set up by hand and why, in one screen, before anything else. It scrolls, for reading
at high display zoom. Its model section asks the caregiver to **try offline recognition
a few times with the network off** and, if it feels too slow on that phone, to switch
`Λειτουργία χωρίς ίντερνετ` off — a judgement left to the person doing the setup rather
than to a threshold. While a transcription is running, AVA says **«Περιμένετε»** after
3 seconds so the silence is explained.

**The gear**, next to it — `Προχωρημένες ρυθμίσεις`, the timing knobs. Steppers, not
sliders, so they survive maximum display zoom and can be talked through over the
phone ("press plus twice"). **Reset to defaults comes first on the screen**, because a
caregiver who has changed something into a corner needs the way out before the way in:

| Knob | Default | Range |
|---|---|---|
| Pause before listening (`think_gap_ms`) | 0 s (none) | 0–10 s, 0.5 s steps |
| Speaking time (`max_listen_ms`) | 5 s | 2–15 s, 1 s steps |
| End-of-speech pause (`endpoint_silence_ms`) | 0.7 s | 0.2–3 s, 0.1 s steps |

Reset **clears** the settings file rather than writing defaults back — an empty file
*is* the shipped state. The speech model and contacts are untouched by it.

The **reset button** (the round button on the main screen) is a different thing: it
fully restarts AVA and reloads contacts from the device — use it after adding or
renaming contacts, since AVA caches them.

## Technical Details

- **Modules:** `:app` (Kotlin UI + services) and `:whisper_native` (Whisper TFLite engine, JNI/C++)
- **Speech Recognition:** Android `SpeechRecognizer` (`el-GR`) by default; Whisper ASR (whisper-base) with the Greek language token on-device, and as the automatic fallback
- **Voice Activity Detection:** Silero VAD for automatic recording cutoff, and to anchor the speaking budget to the first syllable rather than to the button press
- **Capture timing:** a lead-in budget (6 s of patience, waiting for speech to start) and a separate speaking budget that *restarts* when speech begins — so a slow start never shortens the sentence
- **Audio Processing:** JTransforms FFT for mel-spectrogram computation
- **Contact Matching:** `SuperFuzzyContactMatcher` — phonetic normalization, token merging, Levenshtein + substring scoring with ambiguity detection. Its normalization is kept identical to `ContactRepository.normalizeName()` so spoken names and stored names compare apples-to-apples.
- **Performance:** ~6 seconds from the end of speech to the call being placed. Wall-clock time from the button press depends on how long the speaker takes to start — deliberately, since waiting is the feature

## License

MIT License - see [LICENSE](LICENSE) for details.

AVA bundles and depends on third-party components (TensorFlow Lite, Abseil, FlatBuffers, the Whisper and Silero VAD models, JTransforms, AndroidX/Material, and others) under their own licenses. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## Links

- **Repository:** [github.com/t4paN/AVA](https://github.com/t4paN/AVA)
- **Whisper Models:** [DocWolle/whisper_tflite_models](https://huggingface.co/DocWolle/whisper_tflite_models)
- **Greek Radio Stations:** [radio-browser.info](https://www.radio-browser.info/search?page=1&order=clickcount&reverse=true&hidebroken=true&countrycode=GR)
