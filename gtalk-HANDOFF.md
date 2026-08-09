# gtalk.md — Implementation Handoff

**Date:** 2026-07-22
**Feature:** Optional "Online recognition" mode (Google `SpeechRecognizer`, `el-GR`) with automatic Whisper fallback.
**Spec executed:** `AVA/gtalk.md` (locked design; implemented as written).
**Build status:** ⚠️ **Not compiled or device-tested** — the implementation environment had no JDK/Android SDK. Code is written against the exact signatures in the surrounding source, but it has never been through `javac`/Gradle. Treat everything below "What's left" as required, not optional.

---

## What was done

### 1. Manifest — `app/src/main/AndroidManifest.xml`
- Added `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />` (for the connectivity precheck).
- Added a `<queries>` entry so the recognizer is visible on API 30+:
  ```xml
  <intent>
      <action android:name="android.speech.RecognitionService" />
  </intent>
  ```
  **Why it matters:** without this, `SpeechRecognizer.isRecognitionAvailable()` returns `false` on API 30+ and the mode silently never activates. Existing viber/whatsapp query entries were left intact.
- `RECORD_AUDIO` and `INTERNET` were already present — untouched.

### 2. Setting + menu toggle
- **`res/menu/menu_main.xml`** — new item `action_toggle_online` (orderInCategory 98, `showAsAction=never`).
- **`MainActivity.kt`**
  - `onPrepareOptionsMenu`: sets the title to `Online recognition: ON/OFF` from the pref.
  - `onOptionsItemSelected`: new `R.id.action_toggle_online` branch — flips `online_recognition_enabled` in the `ava_settings` SharedPreferences, `invalidateOptionsMenu()`, Snackbar. Mirrors the existing Fast-mode / Auto-call blocks.
- **Pref:** `online_recognition_enabled` in `ava_settings`, **default `false`** (opt-in — this mode sends audio off-device and needs a network).

### 3. Online capture path — `RecordingService.kt`
New imports: `ConnectivityManager`, `NetworkCapabilities`, `Bundle`, `RecognitionListener`, `RecognizerIntent`, `SpeechRecognizer`.

New fields: `speechRecognizer`, `onlineStartMs`, `onlineBusyRetried`.

New methods (all after `vibrateAndStartRecording`):
- **`chooseEngineAndStart()`** — the strategy switch. Called from the TTS prompt `onDone` / `onError` / not-ready branches in `playPrompt()` (these three call sites previously called `vibrateAndStartRecording()` directly). Resets `onlineBusyRetried`, then picks ONE engine for the session.
- **`shouldUseOnline()`** — `pref enabled && isNetworkAvailable() && SpeechRecognizer.isRecognitionAvailable(this)`.
- **`isNetworkAvailable()`** — `ConnectivityManager` active network with `NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_VALIDATED`.
- **`startOnlineRecognition()`** — main-thread. Haptic cue only (**no manual beep** — the recognizer plays its own start/stop tones, avoids a double-beep). Creates a fresh recognizer per session, `ACTION_RECOGNIZE_SPEECH` / `LANGUAGE_MODEL_FREE_FORM` / `EXTRA_LANGUAGE="el-GR"` / `EXTRA_PREFER_OFFLINE=false` / `EXTRA_MAX_RESULTS=1`. Records `onlineStartMs` and `startListening`.
- **`onlineListener`** (`RecognitionListener`):
  - `onResults` → take first hypothesis. Non-empty → `handleTranscriptionComplete(text, elapsed)` (the **same** downstream path as Whisper) then `sessionInProgress = false`. Empty → `(empty)` log + toast + dismiss.
  - `onError` → mapped per the spec table (see below).
- **`recueAndFallbackToWhisper()`** — destroys the recognizer and calls `vibrateAndStartRecording()` (re-cue beep + haptic, then the unchanged Whisper capture path). Used for mid-session fallback, since the recognizer gives us **no raw audio** to reuse.
- **`logOnlineTerminal(label, elapsed)`** — writes a `TranscriptionLog` for the empty / no-speech terminal cases and resets `sessionInProgress`.
- **`destroyRecognizer()`** — `cancel()` + `destroy()` + null out, main-thread.

Error mapping in `onError`:

| Error code | Action |
|---|---|
| `ERROR_NO_MATCH`, `ERROR_SPEECH_TIMEOUT` | No speech — log `(no speech detected)`, toast, dismiss. No re-record. |
| `ERROR_NETWORK`, `ERROR_NETWORK_TIMEOUT`, `ERROR_SERVER`, `ERROR_SERVER_DISCONNECTED`, `ERROR_CLIENT`, `ERROR_AUDIO`, `ERROR_LANGUAGE_UNAVAILABLE`, `ERROR_LANGUAGE_NOT_SUPPORTED` (the `else` branch) | Fall back to Whisper for this session (re-cue + `vibrateAndStartRecording()`). |
| `ERROR_RECOGNIZER_BUSY` | `destroy()` + recreate once; if it recurs, fall back to Whisper. |
| `ERROR_INSUFFICIENT_PERMISSIONS` | Mic-permission handling (toast + dismiss). |

Lifecycle cleanup: `destroyRecognizer()` added to **`stopEverything()`** (cancel path) and **`onDestroy()`**. `handleCancelFromOverlay()` sets `isCancelled = true` before `stopEverything()`, so any late listener callbacks are ignored (every callback guards on `isCancelled`/`isServiceAlive`).

### 4. Left untouched (as the spec requires)
`VadAudioPipeline`, `ModelManager`, the Whisper engine, `SuperFuzzyContactMatcher`, `ContactRepository`, `CallManagerService`, overlays, `TranscriptionLog`. The Whisper path — including its phonetic-normalization parity with the matcher — is byte-for-byte the fallback.

### 5. Docs / memory
- `README.md` — updated intro line and the Settings table with the Online recognition row.
- Memory `ava-online-recognition-spec` + `MEMORY.md` index — marked IMPLEMENTED (not yet tested).

---

## Files changed
```
app/src/main/AndroidManifest.xml
app/src/main/res/menu/menu_main.xml
app/src/main/java/com/t4paN/AVA/MainActivity.kt
app/src/main/java/com/t4paN/AVA/RecordingService.kt
README.md
```

---

## What's left (testing / verification)

### Must do first: build
No JDK was available in the implementation environment, so **this has never compiled.** On a machine with the Android SDK/NDK + CMake:
```
cd AVA
./gradlew :app:assembleDebug        # or :app:compileDebugKotlin for a fast check
./gradlew :app:installDebug
```
Watch for: unresolved imports, and confirm `MODE_PRIVATE` resolves unqualified inside the Service (it should — `Service` is a `Context`; matches how `MainActivity` uses it).

### Device test matrix (from gtalk.md acceptance criteria)
Test on a real target device — **Samsung A05/A56 or Redmi 8** — with Google "Speech Services" present.

1. **Mode OFF** → behavior identical to today (Whisper path). Regression check.
2. **Mode ON + online** → say *"κλήση [όνομα]"* → Google returns Greek text → same match / announce / call flow → a `TranscriptionLog` entry appears in `FirstFragment`.
3. **Mode ON + airplane mode** → `shouldUseOnline()` is false → goes straight to Whisper → call still works, no wasted prompt.
4. **Mode ON, network drops mid-utterance** → recognizer error → single Whisper re-record (re-cue beep) → call still completes.
5. **Cancel overlay while listening** → recognizer stopped/destroyed cleanly, service ready for the next trigger (no `ERROR_RECOGNIZER_BUSY` on the next tap).
6. **Toggle** in Menu (⋮) flips and persists across app restarts; title reflects state.
7. **Greek accuracy** on real speech from the actual end user — the whole point of the feature. Compare against Whisper-base on the same phrases.

### Things to watch during testing
- **Double-beep / no-cue:** confirm the online path has only the haptic + the recognizer's own tones, and the fallback path re-cues audibly.
- **`isRecognitionAvailable()` returning false** on a device that clearly has Google voice typing → almost always the missing `<queries>` entry, or Google app / Speech Services disabled. Verify the manifest merged (`app/build/intermediates/merged_manifests/...`).
- **Main-thread assertion:** `SpeechRecognizer` throws if created/called off the main thread. All entry points here are on the main looper, but verify no crash under real timing.
- **`sessionInProgress` never stuck true:** if a session can end via a path that doesn't reset it, the next widget tap is ignored ("Session already in progress"). The terminal branches all reset it — confirm on device, especially the fallback and cancel paths.
- **Foreground-service mic:** the service is a `foregroundServiceType="microphone"` FGS; `SpeechRecognizer` should be fine within it, but confirm no `SecurityException`/mic-in-use on the A56 (stricter FGS rules).

---

## Notes / decisions carried from the spec

- **One engine per session, never overlapping** `AudioRecord`/VAD and `SpeechRecognizer` (mic contention). The switch happens once, after the TTS prompt finishes.
- **No raw audio from the recognizer** → mid-session fallback means the user must speak again. The up-front network + availability check in `shouldUseOnline()` is what keeps that rare; if there's no network at session start we go straight to Whisper and never waste a prompt.
- **Recreate the recognizer per session** (simplest; avoids `ERROR_RECOGNIZER_BUSY`) rather than reusing one instance.
- **Default OFF is deliberate** — this mode departs from AVA's fully-offline promise (network required, audio sent to Google), so it's a caregiver opt-in.

## Explicitly out of scope (deferred, per spec)
- Cloud HTTP STT (Groq / OpenAI-compatible) and API keys.
- Offline Greek via Google `SpeechRecognizer` (no reliable on-device Greek model exists) — Whisper stays the offline engine.
- Vosk.
- Feeding the recognizer's N-best alternatives into `SuperFuzzyContactMatcher` (currently `EXTRA_MAX_RESULTS=1`, top hypothesis only).

## Suggested follow-ups (not required)
- Add a unit/instrumented test around the error→action mapping if a test harness is ever set up (there's effectively none today).
- Consider surfacing which engine produced a given `TranscriptionLog` entry (Whisper vs. online) to make the debugging surface in `FirstFragment` clearer during the accuracy comparison.
