# Releasing AVA

How a signed build gets made. Two artifacts come out of every release:

| File | What it's for |
|---|---|
| `app-release.aab` | Upload this to the Google Play Console. Play splits it per device, so a phone downloads far less than the file's own size. |
| `app-release.apk` | Sideload copy — install straight onto a phone from the GitHub Release link, no cable. This is the update channel until Play internal testing is live. |

Both are signed with the **upload key**. Under Play App Signing this is not the
key that reaches users' devices — Google holds that one — so if the upload key
is ever lost it can be reset from the Play Console. Back it up regardless.

## Where the key lives

On the taptop: `~/ADMINSTUFF/ava-upload-key/`

```
ava-upload.jks          the keystore
ava-upload.jks.base64   the same file, base64 — this is what GitHub gets
PASSWORD.txt            the password (same for store and key), alias ava-upload
```

The directory is `chmod 700`, the files `chmod 600`, and it sits outside the
repo. `.gitignore` also blocks `*.jks` as a second line of defence. **Copy this
folder somewhere off the laptop** — a password manager, an encrypted USB stick.

## One-time GitHub setup

Add four repository secrets at
`https://github.com/t4paN/AVA/settings/secrets/actions`:

| Secret | Value |
|---|---|
| `AVA_KEYSTORE_BASE64` | the entire contents of `ava-upload.jks.base64` (one long line, no spaces or newlines) |
| `AVA_KEYSTORE_PASSWORD` | the password from `PASSWORD.txt` |
| `AVA_KEY_PASSWORD` | the same password |
| `AVA_KEY_ALIAS` | `ava-upload` |

To copy the base64 to the clipboard:

```bash
xclip -selection clipboard < ~/ADMINSTUFF/ava-upload-key/ava-upload.jks.base64
```

## Cutting a release

1. Bump both numbers in `app/build.gradle.kts`. Play refuses an upload whose
   `versionCode` it has seen before, so it must go **up** every single time:

   ```kotlin
   versionCode = 9        // +1, always
   versionName = "1.3.1"  // the human-readable one
   ```

2. Commit, then tag with `v` + the same versionName and push:

   ```bash
   git commit -am "release: 1.3.1"
   git tag v1.3.1
   git push && git push --tags
   ```

3. The **Release** workflow does the rest: builds the bundle and APK, verifies
   the signature, and publishes a GitHub Release with both files attached.

The workflow refuses to start if the tag and `versionName` disagree, and fails
if the keystore secret is missing — both before the ten-minute native build,
not after it.

`workflow_dispatch` (the "Run workflow" button on the Actions tab) builds and
uploads the artifacts without creating a GitHub Release. Useful for a dry run.

## Building a signed release locally

```bash
export JAVA_HOME=/home/eltapo/tools/jdk-17.0.20+8
export PATH="$JAVA_HOME/bin:$PATH"
export AVA_KEYSTORE_FILE=~/ADMINSTUFF/ava-upload-key/ava-upload.jks
export AVA_KEYSTORE_PASSWORD='...'   # from PASSWORD.txt
export AVA_KEY_ALIAS=ava-upload
./gradlew :app:bundleRelease :app:assembleRelease
```

Roughly seven minutes cold, two if the native build is already cached. Without `AVA_KEYSTORE_FILE` the release build still
succeeds — just unsigned — so a fresh clone with no key is never blocked.

Check the result:

```bash
"$ANDROID_HOME"/build-tools/36.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

## Notes

- **R8 stays off** (`isMinifyEnabled = false`). The Whisper engine is reached
  through JNI and TFLite resolves classes reflectively, so shrinking needs its
  own keep rules and a device test before it can be switched on.
- **The Whisper model is not in the build.** `ModelManager` downloads
  `whisper-base.TOP_WORLD.tflite` (~102 MB) from HuggingFace on first run. The
  first launch after install therefore needs a network connection.
- **Release builds are ARM-only.** `abiFilters` on the release build type drops
  the x86/x86_64 native libraries, which exist for emulators and no real phone:
  the sideload APK went from 163 MB to **89 MB** and the bundle from 80 MB to
  **45 MB**. Debug builds still carry every ABI, so the emulator keeps working.
  The sideload APK is still universal across the two ARM types; Play splits the
  `.aab` further per device, so a phone downloads less again.
- **Testers must re-enable the Accessibility service after every update.**
  Android disables it on reinstall, which silently breaks Viber auto-call. The
  release notes say so; say it again in person.
