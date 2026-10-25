plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Build identity for the self-updater (Updater.kt): compared against the
// version.json that CI publishes next to the rolling release APK.
fun git(vararg args: String): String = try {
    ProcessBuilder("git", *args).directory(rootDir).redirectErrorStream(true).start()
        .inputStream.bufferedReader().readText().trim()
} catch (e: Exception) {
    ""
}
val gitSha = git("rev-parse", "HEAD").takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: "unknown"
val gitTime = git("log", "-1", "--format=%ct").toLongOrNull() ?: 0L

android {
    namespace = "com.phrag.tvmon"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phrag.tvmon"
        minSdk = 26          // covers every Shield; APPLICATION_OVERLAY is 26+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("long", "GIT_TIME", "${gitTime}L")
        buildConfigField("String", "UPDATE_URL", "\"https://github.com/phrag/tvmon/releases/download/debug-latest/\"")
    }

    buildFeatures {
        buildConfig = true   // GIT_SHA etc. for the updater
    }

    // Committed debug key so every CI build has the same signature and the
    // self-updater (and adb install -r) can install a new build over the old
    // one. Debug-only: not a secret, don't use it for a store release.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    testOptions { unitTests.isReturnDefaultValues = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // The Rust .so files are built out-of-band by cargo-ndk into
    // src/main/jniLibs/<abi>/libsyscore.so. Gradle just packages whatever's there.
    // Build them with (from repo root):
    //   cargo install cargo-ndk        # once
    //   rustup target add aarch64-linux-android armv7-linux-androideabi
    //   cargo ndk -t arm64-v8a -t armeabi-v7a \
    //       -o app/src/main/jniLibs build --release --manifest-path syscore/Cargo.toml
    // (Shield 2019 is arm64-v8a; older Shields armeabi-v7a.)
}

dependencies {
    // Intentionally none at runtime. Uses only the Android framework + org.json (bundled).
    // Tests only: android.jar's org.json is stubbed on the JVM, so use the real one.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
