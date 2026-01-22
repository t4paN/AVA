# AVA - Aggelos Voice Assistant

An Android accessibility app that enables elderly and visually impaired Greek-speaking users to make phone calls through voice commands.

## Features

- **Offline Greek Speech Recognition** - Uses Whisper ASR with TFLite, optimized for Greek (no internet required)
- **Fuzzy Contact Matching** - Handles heavily distorted transcriptions through phonetic simplification and weighted scoring
- **Multi-Channel Calling** - Supports default dialer + Viber (Signal coming soon)
- **VoIP Auto-Click** - AccessibilityService with caregiver-calibrated button positions
- **Audio-First UX** - Greek TTS announcements and vibration feedback throughout
- **Widget + Unlock Activation** - Start by tapping widget or automatically on phone unlock
- **Radio** - Stream Greek radio stations with voice commands

## How It Works

1. User taps widget (or unlocks phone)
2. AVA prompts "Πείτε όνομα" with vibration
3. User says "κλήση [name]"
4. Whisper transcribes speech (~6 seconds total)
5. Fuzzy matcher finds best contact match
6. Call is placed via dialer or VoIP app

## Requirements

- Android API 26+ (Android 8.0 Oreo)
- ~150MB storage for Whisper model
- Tested on: Samsung A05, Samsung A56, Redmi 8

## Installation

### Building the APK

1. Clone the repository
2. Download [whisper-base-topworld.tflite](https://huggingface.co/DocWolle/whisper_tflite_models/tree/main)
3. Place the model in `whisper_native/assets/`
4. Build with Android Studio, or from command line:
   ```bash
   ./gradlew assembleRelease
   ```
   The APK will be in `app/build/outputs/apk/release/`

### Install on Device

1. Transfer the APK to the device
2. Open the APK file
3. Tap "Install" (may need to allow "Unknown sources" in settings)

### Step 3: Permissions

**⚠️ Enable these master settings first:**

1. **Display over other apps:**
   - Settings → Apps → Special app access → Display over other apps → AVA → ON

2. **Show notifications:**
   - Settings → Notifications → AVA → ON

3. **Accessibility Service (required for VoIP auto-click):**
   - Settings → Accessibility → Installed services
   - You may need to enable "Allow downloaded apps" first
   - Find AVA → Toggle ON
   - ⚠️ **Note:** The accessibility service must be re-enabled after each app update

**Then grant these permissions in App Info → Permissions:**
- ✅ Microphone
- ✅ Phone
- ✅ Contacts
- ✅ Notifications
- ✅ Display over other apps
- ⚠️ Disable "Manage App" if present (must be OFF)

### Step 4: Add Widget

1. Long press home screen
2. Widgets → AVA
3. Drag widget to screen

### VoIP Setup (Optional)

To enable calling through Viber:

1. In AVA: Menu (⋮) → VoIP Setup
2. Select Viber
3. Pick a screenshot of Viber's call screen
4. Tap where the call button is located
5. Save

**Important:** For Viber contacts, use `VIBER` as the surname:
- First name: `Γιώργος Παπαδόπουλος`
- Surname: `VIBER`

*Signal support coming soon.*

## Usage

1. Tap the widget
2. Wait for "Πείτε όνομα" prompt + vibration
3. Say: "κλήση [όνομα]" (e.g., "κλήση Γιώργο")
4. AVA announces the match and places the call

## Settings

| Setting | Description |
|---------|-------------|
| Start on Unlock | Automatically start AVA when phone is unlocked |
| Fast Mode | Toggle between fast/less accurate and slow/more accurate models (requires 305MB download) |
| Autocall | Automatically place call when contact match is certain |
| VoIP Setup | Configure auto-click position for Viber |
| Σταθμοί Ραδιοφώνου | Add or remove radio stations |

## Technical Details

- **Speech Recognition:** Whisper ASR (whisper-base) with Greek language token 50281
- **Voice Activity Detection:** Silero VAD for automatic recording cutoff
- **Audio Processing:** JTransforms FFT optimization for mel spectrogram calculation
- **Contact Matching:** SuperFuzzyContactMatcher with phonetic simplification and token merging
- **Performance:** ~6 seconds from button press to call initiation

## License

MIT License - see [LICENSE](LICENSE) for details

## Links

- **Repository:** [github.com/t4paN/AVA](https://github.com/t4paN/AVA)
- **Whisper Models:** [DocWolle/whisper_tflite_models](https://huggingface.co/DocWolle/whisper_tflite_models)
- **Greek Radio Stations:** [radio-browser.info](https://www.radio-browser.info/search?page=1&order=clickcount&reverse=true&hidebroken=true&countrycode=GR)
