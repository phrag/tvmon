plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phrag.tvmon"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phrag.tvmon"
        minSdk = 26          // covers every Shield; APPLICATION_OVERLAY is 26+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

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
    // Intentionally none. Uses only the Android framework + org.json (bundled).
}
