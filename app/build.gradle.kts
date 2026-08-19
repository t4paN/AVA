plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// --- Release signing --------------------------------------------------------
// The upload keystore is never committed. CI decodes it from the
// AVA_KEYSTORE_BASE64 repository secret and points AVA_KEYSTORE_FILE at the
// decoded file; locally, export AVA_KEYSTORE_FILE at your own copy
// (~/ADMINSTUFF/ava-upload-key/ava-upload.jks on the taptop).
// With no keystore present the release build still assembles, just unsigned,
// so a fresh clone never breaks.
val avaKeystore = System.getenv("AVA_KEYSTORE_FILE")?.let(::file)?.takeIf { it.exists() }

android {
    namespace = "com.t4paN.AVA"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.t4paN.AVA"
        minSdk = 30
        targetSdk = 36
        versionCode = 9
        versionName = "1.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (avaKeystore != null) {
            create("release") {
                storeFile = avaKeystore
                storePassword = System.getenv("AVA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AVA_KEY_ALIAS") ?: "ava-upload"
                keyPassword = System.getenv("AVA_KEY_PASSWORD")
                    ?: System.getenv("AVA_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off: the Whisper engine is reached through JNI and the
            // TFLite interpreter resolves classes reflectively, so shrinking
            // needs its own keep rules and its own device test before it can
            // be turned on. Bigger APK, no surprises.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")

            // Ship ARM only. The x86/x86_64 slices of libonnxruntime and the
            // TFLite delegates exist for emulators, not phones, and cost ~35 MB
            // in the sideloadable universal APK. Debug builds keep every ABI so
            // the emulator still works.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(project(":whisper_native"))
    implementation("com.github.wendykierp:JTransforms:3.1")
    implementation("com.github.gkonovalov.android-vad:silero:2.0.10")
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-common:1.2.1")

// CameraX for magnifier
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
}