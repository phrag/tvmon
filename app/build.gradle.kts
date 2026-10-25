plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.tvmon"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.tvmon"
        minSdk = 26          // covers every Shield; APPLICATION_OVERLAY is 26+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // Committed debug key (the same one the sibling apps use) so every CI build
    // has the same signature and the "latest" release APK installs over the
    // previous one. Debug-only: not a secret, don't use it for a store release.
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
    // CI does this before assembling. Build them locally with (from repo root):
    //   cargo install cargo-ndk        # once
    //   rustup target add aarch64-linux-android armv7-linux-androideabi
    //   cargo ndk -t arm64-v8a -t armeabi-v7a \
    //       -o app/src/main/jniLibs build --release --manifest-path syscore/Cargo.toml
    // (Shield 2019 is arm64-v8a; older Shields armeabi-v7a.)
}

dependencies {
    // Intentionally none at runtime. Uses only the Android framework + org.json (bundled).
    testImplementation("junit:junit:4.13.2")
}
