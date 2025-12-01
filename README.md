# Greek Voice Assistant Widget

A simple Android home screen widget that interacts with the user via Greek speech.  
Currently includes a button that triggers Text-to-Speech (TTS) prompts.

---

## Project Structure

GreekVoiceAssistant/
├─ app/
│ ├─ src/main/java/com/example/greekvoiceassistant/
│ │ ├─ VoiceAssistantWidget.kt # Main widget code (TTS + button)
│ │ ├─ RecordActivity.kt # Activity to request microphone permission
│ │ └─ RecordingService.kt # Foreground service to record audio
│ ├─ src/main/res/
│ │ ├─ layout/
│ │ │ └─ voice_assistant_widget.xml
│ │ ├─ drawable/
│ │ │ └─ ic_mic.xml
│ │ └─ values/
│ │ ├─ colors.xml
│ │ ├─ styles.xml
│ │ └─ themes.xml
│ └─ AndroidManifest.xml
├─ whisper_native/ # Imported native module for Whisper TFLite
├─ build.gradle
├─ settings.gradle
└─ README.md


---

## Current Functionality

* **Widget button:** triggers Greek TTS prompt "πείτε όνομα".
* **RecordingService:** skeleton service to record audio in foreground.
* **RecordActivity:** requests microphone permission when needed.
* **Translucent theme:** used for the recording permission activity.
* **whisper_native module:** placeholder for TFLite Whisper integration (not yet hooked).

---

## Next Steps

1. Integrate the `whisper_native` module with `RecordingService` to transcribe audio.  
2. Add fuzzy matching (SoundexGR) for Greek names.  
3. Automatically place a phone call to the matched contact.  
4. Optionally: switch between different TFLite Whisper models (small, turbo, etc.).  

---

## Notes

* Large model files (`.tflite`) are **not tracked** in this repo for now.  
* Tested performance with Samsung A56 (8GB RAM). Native TFLite implementation is ~4× faster than Java version.
* Make sure microphone and call permissions are granted for full functionality.


