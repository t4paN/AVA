# Third-Party Notices

AVA (Aggelos Voice Assistant) is licensed under the MIT License (see [LICENSE](LICENSE)).
That license covers **the project's own source code only**. The application also
includes, bundles, or depends on the third-party components listed below, each of
which is distributed under its own license and remains the property of its
respective copyright holders.

When redistributing AVA (source or APK), retain this file and the license texts of
the components it references.

> Note on accuracy: the components and licenses below reflect the dependencies
> declared in `app/build.gradle.kts`, `whisper_native/build.gradle`, and the source
> vendored under `whisper_native/src/main/cpp/tf-lite-api/`. Library licenses were
> verified against upstream on 2026-07-22 (JTransforms → BSD-2-Clause and
> android-vad → MIT confirmed from their `LICENSE` files; Apache-2.0 components
> confirmed in-tree or as standard Google/JetBrains terms). The one item still worth
> confirming before a public release is the **Whisper TFLite model conversion**
> terms (see the models table) if you redistribute the `.tflite` weights.

## Vendored source (included in this repository)

Located under `whisper_native/src/main/cpp/tf-lite-api/include/`:

| Component | License | Bundled license text |
|---|---|---|
| Abseil (abseil-cpp) | Apache-2.0 | `tf-lite-api/include/abseil-cpp/LICENSE` |
| FlatBuffers | Apache-2.0 | `tf-lite-api/include/flatbuffers/LICENSE.txt` |

## Native binaries (bundled `.so`)

Located under `whisper_native/src/main/cpp/tf-lite-api/generated-libs/`:

| Component | License |
|---|---|
| TensorFlow Lite (`libtensorflowlite.so`, arm64-v8a / armeabi-v7a / x86_64) | Apache-2.0 |

## Bundled models & assets

| Component | License | Notes |
|---|---|---|
| Whisper (OpenAI) — `whisper-base.TOP_WORLD.tflite` weights | MIT | Model architecture/weights from OpenAI Whisper; TFLite conversion via [DocWolle/whisper_tflite_models](https://huggingface.co/DocWolle/whisper_tflite_models). Confirm the conversion repo's terms. |
| Silero VAD model | MIT | Ships inside the `android-vad:silero` dependency. |

## Runtime dependencies (fetched at build, shipped in the APK)

Declared in `app/build.gradle.kts` and `whisper_native/build.gradle`:

| Component | License |
|---|---|
| TensorFlow Lite / TFLite Support / TFLite GPU (`org.tensorflow:*`) | Apache-2.0 |
| AndroidX (core-ktx, appcompat, constraintlayout, navigation, media3/ExoPlayer, CameraX) | Apache-2.0 |
| Material Components for Android (`com.google.android.material`) | Apache-2.0 |
| Kotlin stdlib & coroutines (JetBrains) | Apache-2.0 |
| JTransforms (`com.github.wendykierp:JTransforms:3.1`) | BSD-2-Clause |
| Android VAD — Silero (`com.github.gkonovalov.android-vad:silero`) | MIT |

## Full license texts

- Apache-2.0: https://www.apache.org/licenses/LICENSE-2.0
- BSD-2-Clause: https://opensource.org/license/bsd-2-clause
- MIT: https://opensource.org/license/mit

The Apache-2.0 texts for the vendored Abseil and FlatBuffers sources are included in
this repository at the paths listed above.
