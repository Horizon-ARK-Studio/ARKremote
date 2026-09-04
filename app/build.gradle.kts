plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arklight.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.arklight.app"
        minSdk = 28 // android.bluetooth.BluetoothHidDevice was added in API 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    // Signing config (keystore path/passwords) is the project owner's
    // own concern, passed through via env vars -- ARKlight does not
    // manage keystores/credentials on anyone's behalf (see
    // docs/Backends/ANDROID-BACKEND-IMPLEMENTATION.md, Stage 7).
    // Locally, with these unset, `./gradlew assembleRelease` still
    // works, it just produces an unsigned APK you'd sign yourself.
    // `isNullOrBlank()` (not just a null check) matters here because
    // Stage 4's CI job sets this from a GitHub Actions secret via an
    // `env:` block -- when that secret isn't configured, the
    // expression evaluating it resolves to an *empty string*, not an
    // unset var, so a plain `!= null` check would still (wrongly) try
    // `file("")` and fail the build instead of falling back to
    // unsigned, same as the local no-env-vars-at-all case does.
    val releaseStorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    signingConfigs {
        if (!releaseStorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseStorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (!releaseStorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // WebViewAssetLoader -- serves app/src/main/assets/ to the WebView
    // over https://appassets.androidplatform.net/, so ARKlight's Stage 8
    // State(persist=True) -> localStorage stays reliable inside a
    // packaged app (see docs/Foundational/DESIGN-NOTES.md, "v0.0438:
    // Android backend", "Why this needs to exist at all").
    implementation("androidx.webkit:webkit:1.11.0")
}
