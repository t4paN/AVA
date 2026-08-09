# gtalk.md — Online (Google) Transcription Mode

**Status:** Ready to implement. This doc is written to be executed in one shot by
someone with the code but **none of the discussion that produced it** — everything
needed is here. Facts web-verified 2026-07-22.

---

## Decision (locked)

Add an **optional "Online recognition" mode** that transcribes speech with **Android's
built-in `SpeechRecognizer`** (Google's on-device *"Speech Services"*) instead of the
bundled Whisper.

- **Engine:** `android.speech.SpeechRecognizer`, online, language `el-GR`.
- **Why this one:** free, **no account, no API key, no billing** — it uses the Google
  services already present on the phone. Greek is well supported online, and it's more
  accurate on real speech than the bundled Whisper-**base**.
- **Hybrid, not a replacement:** on-device Whisper stays as the **automatic fallback**
  (offline, or if the recognizer errors), so the call button never dies without a
  network.
- **Opt-in:** new setting, **default OFF** (this mode sends audio to Google and needs a
  network — both are departures from AVA's current fully-offline behavior, so the
  caregiver opts in).

### What we are *not* doing in this shot
- No cloud HTTP APIs / Groq / API keys (a separate future option).
- No **offline** `SpeechRecognizer` for Greek — Google ships no reliable on-device
  Greek model, so offline stays Whisper's job. (See "Background".)
- No Vosk (deferred).
- Not feeding the recognizer's N-best alternatives into the matcher yet (future).

---

## How it slots into the current architecture

Today, `RecordingService` owns the whole capture→transcribe path:

```
onStartCommand → playPrompt() [TTS "Πείτε όνομα"]
  → (TTS onDone) vibrateAndStartRecording() [beep+vibrate]
    → prepareRecorder() → AudioRecord loop → VadAudioPipeline
      → stopRecordingImmediately() → transcribeAudio() [bg thread]
        → sharedWhisperEngine.transcribeBuffer(float[])
          → handleTranscriptionComplete(text, elapsedMs)  ← everything downstream
```

`SpeechRecognizer` **cannot** consume our PCM buffer — it takes over the mic and does
its own capture + endpointing. So this is **not** a new `WhisperEngine` implementation;
it's a **strategy switch at the start of the session**: pick ONE engine per session.
Never run `AudioRecord`/VAD and `SpeechRecognizer` at the same time (mic contention).

```
onStartCommand → playPrompt() [TTS "Πείτε όνομα"]
  → (TTS onDone) chooseEngine():
       online mode ON && network && recognizer available ?
         ├─ YES → startOnlineRecognition()   ← NEW path
         └─ NO  → vibrateAndStartRecording() ← existing Whisper path, unchanged
```

Both paths converge on the **existing** `handleTranscriptionComplete(text, elapsedMs)`,
so intent detection, `SuperFuzzyContactMatcher`, `CallManagerService`, logging, and the
overlay all stay **exactly as they are.**

---

## Implementation steps

### 1. Manifest (`app/src/main/AndroidManifest.xml`) — REQUIRED, easy to miss

- Add permission so we can check connectivity:
  ```xml
  <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
  ```
- Add a `<queries>` entry so `SpeechRecognizer.isRecognitionAvailable()` can see the
  recognition service. **On Android 11+ (AVA is minSdk 30) this is mandatory — without
  it `isRecognitionAvailable()` returns false and the mode silently never activates:**
  ```xml
  <queries>
      <!-- existing viber/whatsapp entries stay -->
      <intent>
          <action android:name="android.speech.RecognitionService" />
      </intent>
  </queries>
  ```
- `RECORD_AUDIO` and `INTERNET` are already present — no change.

### 2. Setting: `online_recognition_enabled` (default `false`)

- Store in the existing `ava_settings` SharedPreferences (same file as fast-mode /
  auto-call).
- Add a menu toggle mirroring the existing **Fast mode** toggle:
  - `res/menu/menu_main.xml`: new item `action_toggle_online`.
  - `MainActivity.onPrepareOptionsMenu`: set title `"Online recognition: ON/OFF"`.
  - `MainActivity.onOptionsItemSelected`: flip the pref, `invalidateOptionsMenu()`,
    Snackbar. (Copy the `action_toggle_fastmode` / `action_toggle_autocall` blocks.)

### 3. New capture path in `RecordingService`

Add these to `RecordingService` (keep it in the service — it needs `handler`, overlay,
vibrator, and `handleTranscriptionComplete`, which are all here).

**a. Engine choice (call from the TTS `onDone("prompt")` branch in `playPrompt()`):**
```
fun shouldUseOnline(): Boolean =
    prefs.getBoolean("online_recognition_enabled", false)
    && isNetworkAvailable()                       // ConnectivityManager + NET_CAPABILITY_INTERNET/VALIDATED
    && SpeechRecognizer.isRecognitionAvailable(this)
```
In `playPrompt()`'s `onDone`/`onError` for `utteranceId == "prompt"`, branch:
`if (shouldUseOnline()) startOnlineRecognition() else vibrateAndStartRecording()`.

**b. `startOnlineRecognition()` — MUST run on the main thread** (use the existing
`handler` / main looper; the service is already a foreground `microphone` service, which
is fine for `SpeechRecognizer`):
- `vibrateShort()` (keep the haptic cue). The recognizer emits its own start/stop tones,
  so **drop the manual `playBeep()`** in this path to avoid a double-beep.
- Create per session: `SpeechRecognizer.createSpeechRecognizer(this)`.
- Intent:
  ```
  ACTION_RECOGNIZE_SPEECH
  EXTRA_LANGUAGE_MODEL = LANGUAGE_MODEL_FREE_FORM
  EXTRA_LANGUAGE       = "el-GR"
  EXTRA_PREFER_OFFLINE = false          // we want online Greek
  EXTRA_MAX_RESULTS    = 1              // top hypothesis is enough for now
  ```
- Set a `RecognitionListener`; record `startMs = System.currentTimeMillis()` at
  `startListening`.
- The `CallOverlayController.showRecording { handleCancelFromOverlay() }` cancel overlay
  is already shown in `onStartCommand` — leave it; cancel must also stop the recognizer
  (see 3d).

**c. `RecognitionListener` results → converge on existing code:**
- `onResults(bundle)`: take `bundle.getStringArrayList(RESULTS_RECOGNITION)?.firstOrNull()`.
  - Non-empty → `handleTranscriptionComplete(text, elapsed)` (existing routing/logging).
  - Empty/null → same "empty" handling `transcribeAudio()` uses today (log `(empty)`,
    toast, dismiss overlay).
- `onError(code)` → map:

  | Error code | Action |
  |---|---|
  | `ERROR_NO_MATCH`, `ERROR_SPEECH_TIMEOUT` | Treat as **no speech** — reuse existing "(no speech detected)" log + feedback. Do **not** re-record. |
  | `ERROR_NETWORK`, `ERROR_NETWORK_TIMEOUT`, `ERROR_SERVER`, `ERROR_SERVER_DISCONNECTED`, `ERROR_CLIENT`, `ERROR_AUDIO`, `ERROR_LANGUAGE_UNAVAILABLE`, `ERROR_LANGUAGE_NOT_SUPPORTED` | **Fall back to Whisper for this session:** re-cue (short beep) and call `vibrateAndStartRecording()` (the existing Whisper capture path). |
  | `ERROR_RECOGNIZER_BUSY` | `destroy()` + recreate once; if it recurs, fall back to Whisper. |
  | `ERROR_INSUFFICIENT_PERMISSIONS` | RECORD_AUDIO missing — same handling as `prepareRecorder()`'s permission check. |

  **Note on mid-session fallback:** the recognizer gives us **no raw audio**, so falling
  back means the user must speak again (hence the re-cue beep). The up-front network +
  availability check in `shouldUseOnline()` is what keeps this rare — if there's no
  network at session start we go straight to Whisper and never waste an attempt or
  double-prompt.

**d. Cancel / lifecycle:**
- In `handleCancelFromOverlay()` and `stopEverything()`: also
  `speechRecognizer?.cancel()` and `?.destroy()` (on main thread), null it out.
- In `onDestroy()`: `speechRecognizer?.destroy()`.
- Recreate per session (simplest; avoids `ERROR_RECOGNIZER_BUSY`).

### 4. Leave untouched
`VadAudioPipeline`, `ModelManager`, Whisper engine, `SuperFuzzyContactMatcher`,
`ContactRepository`, `CallManagerService`, overlays, TranscriptionLog. The Whisper path
is the fallback and must keep working byte-for-byte.

---

## Gotchas / must-not-break

1. **`<queries>` for `RecognitionService` is mandatory on API 30+** or
   `isRecognitionAvailable()` is always false and the mode appears dead. (#1)
2. **`SpeechRecognizer` is main-thread only** — create and call it on the main looper.
   The current Whisper transcription runs on a background `Thread`; the online path stays
   on the main thread (its callbacks arrive there too).
3. **Never overlap `AudioRecord` and `SpeechRecognizer`** — strictly one engine per
   session.
4. **TTS prompt must finish before `startListening()`** — the mic must be free. Reuse the
   existing "wait for TTS `onDone`" sequencing; don't start listening during the prompt.
5. **No raw audio from the recognizer** → mid-session fallback = re-record (re-cue beep).
   Prefer the preflight check to avoid it.
6. **Default OFF.** Turning it on sends audio off-device and requires network — a
   deliberate caregiver choice.
7. Don't touch the phonetic-normalization parity between
   `SuperFuzzyContactMatcher.cleanTranscription()` and
   `ContactRepository.normalizeName()` — unrelated here, but easy to disturb; the matcher
   stays as-is.

## Acceptance criteria

- **Mode OFF** → behavior byte-for-byte identical to today (Whisper path).
- **Mode ON + online** → say `"κλήση [όνομα]"` → Google returns Greek text → same match /
  announce / call flow → a `TranscriptionLog` entry is written.
- **Mode ON + airplane mode** → `shouldUseOnline()` is false → straight to Whisper → call
  still works, no wasted prompt.
- **Mode ON, network drops mid-utterance** → recognizer error → single Whisper re-record,
  call still completes.
- **Cancel overlay while listening** → recognizer stopped/destroyed cleanly, service ready
  for next trigger.
- Greek recognition confirmed on a real target device (Samsung A05/A56 or Redmi 8).

---

## Background (context, not required to implement)

- **Why online, not offline, for Google:** Google splits **online** recognition (~119
  languages incl. Greek) from **on-device/offline** (a small curated subset that has
  **not** included Greek). The on-demand model APIs (`checkRecognitionSupport()`,
  `triggerModelDownload()`, API 33+) only fetch a model **if Google published one** for
  that locale — usage does not "create" a Greek offline model. So offline Greek via
  Google is unreliable; that's why Whisper remains the offline engine and Vosk is the
  future option if better *offline* Greek is ever the goal.
- **Why not Groq (the drop-in cloud alternative):** cleaner integration (fits behind the
  `WhisperEngine` interface, keeps the VAD pipeline) and more accurate, but needs an
  account + API key. Rejected for now in favor of the account-free built-in recognizer.
  If revisited: OpenAI-compatible endpoint
  `https://api.groq.com/openai/v1/audio/transcriptions`, `whisper-large-v3-turbo`,
  `language=el`, free tier ~2,000 req/day; wrap PCM→WAV with the existing
  `whisper_native` `WaveUtil` and POST.

### Sources (verified 2026-07-22)
- [SpeechRecognizer API](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [Android speech recognition guide 2026](https://picovoice.ai/blog/android-speech-recognition/)
- [Google voice typing — 119 languages](https://www.notebookcheck.net/Google-s-voice-typing-now-supports-119-languages.241576.0.html)
- [Groq STT docs](https://console.groq.com/docs/speech-to-text) · [Vosk](https://alphacephei.com/vosk/)
