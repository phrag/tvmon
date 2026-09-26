# tvmon

A FOSS, TV-first system + video-output overlay for Android TV / NVIDIA Shield.
Kotlin UI + framework metrics, Rust core for kernel-file parsing. **No root, no
Shizuku** — works on a stock box. GPL-3.0.

See `PROJECT.md` for scope and design decisions.

## Layout
```
tvmon/
├── PROJECT.md            # decisions / scope (the source of truth)
├── settings.gradle.kts   # + root build.gradle.kts, gradle.properties
├── syscore/              # Rust core (unit-tested)  ✅ compiles
│   ├── Cargo.toml
│   └── src/lib.rs
└── app/                  # Android app  (written, not yet compiled)
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/example/tvmon/
            ├── SettingsActivity.kt   # launcher: toggles + transparency
            ├── OverlayService.kt     # the floating tiles
            ├── BootReceiver.kt
            ├── metric/               # MetricSource interface + all providers
            ├── nativebridge/         # JNI bridge to syscore
            └── prefs/                # SharedPreferences
```

## 1. Build the Rust core (.so)
```bash
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi
cargo ndk -t arm64-v8a -t armeabi-v7a \
    -o app/src/main/jniLibs build --release --manifest-path syscore/Cargo.toml
```
`arm64-v8a` covers the Shield 2019; `armeabi-v7a` the older ones. If you skip this
step the app still runs — the native tiles (system load, temps, uptime) just show
as unavailable.

Run the core's tests any time with:
```bash
cargo test --manifest-path syscore/Cargo.toml
```

## 2. Build the app
Open the project in Android Studio (it'll generate the Gradle wrapper) and
**Build → Build APK(s)**, or from the command line once the wrapper exists:
```bash
./gradlew :app:assembleDebug
```

## 3. Sideload to the Shield
```bash
adb connect <shield-ip>:5555        # enable Network debugging on the Shield first
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.tvmon/.SettingsActivity   # if the tile is hidden
adb shell appops set com.example.tvmon SYSTEM_ALERT_WINDOW allow  # grant overlay
```

Toggle **Show overlay** in the app, pick which tiles you want, and the floating
readout appears over whatever you're watching.

## Status
- `syscore`: compiles on Rust 1.75, 6/6 tests pass, verified against a live
  `/proc`.
- Android side: written but not compiler-checked here (no Android SDK/NDK in the
  authoring environment). Expect to fix a stray import on first build.

## Known rough edges (also in PROJECT.md)
- Leanback launcher tile needs a 320×180 `banner` drawable; use the `adb am
  start` line until then.
- Start-on-boot works via `BootReceiver`, but a robust boot start on Android 8+
  wants a foreground service.
- Text/background colour prefs exist; the colour-picker UI is a TODO (only the
  transparency slider is wired up so far).
