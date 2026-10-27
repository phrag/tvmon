# tvmon

[![Build](https://img.shields.io/github/actions/workflow/status/phrag/tvmon/build.yml?branch=main&label=Build)](https://github.com/phrag/tvmon/actions/workflows/build.yml)
[![Licence: GPL-3.0](https://img.shields.io/github/license/phrag/tvmon)](LICENSE)
![Android TV 8.0+](https://img.shields.io/badge/Android%20TV-8.0%2B-3DDC84?logo=android&logoColor=white)
![No INTERNET permission](https://img.shields.io/badge/permissions-no%20INTERNET-2ea44f)
![No Google Play Services](https://img.shields.io/badge/Google%20Play%20Services-none-2ea44f)
![Rust core](https://img.shields.io/badge/core-Rust-dea584?logo=rust&logoColor=white)

A FOSS, TV-first system + video-output overlay for Android TV / NVIDIA Shield.
Kotlin UI + framework metrics, Rust core for kernel-file parsing. **No root, no
Shizuku** — works on a stock box. GPL-3.0.

See `PROJECT.md` for scope and design decisions.

## Privacy
tvmon has **no `INTERNET` permission**: it cannot open a network socket, so
nothing it reads (addresses, DNS, VPN state…) can leave the box. That also means
there is no self-updater — see *Updating* below. Backups and device-to-device
transfer of its settings are off too.

Permissions it does ask for: display over other apps (the overlay), boot
completed (optional start on boot), foreground service (keeps the overlay
alive), post notifications (Android 13+, only so the "overlay is on"
notification is visible; the overlay runs without it), network state and Wi-Fi
state (network tiles). No location permission, so the SSID is never read.

## Tiles
| Tile | Shows |
|------|-------|
| Video output | negotiated resolution @ refresh, HDR types |
| RAM | used / total |
| Battery | %, mV, °C (hidden on boxes without one) |
| VPN | **OFF in red** when no tunnel is up; when on: the transport under it, tunnel interface and address |
| Network | Ethernet / Wi-Fi, interface, validated / metered; on Wi-Fi: band, channel + frequency, Wi-Fi standard, tx/rx link rate, RSSI in dBm with a quality word |
| IP / DNS | LAN IPv4 / IPv6 addresses, default gateway, DNS servers (the VPN's when one is up), Private DNS state |
| Traffic | live ↓/↑ throughput (TrafficStats, device-wide) |
| System load / Temperatures / Uptime | from the Rust core (`/proc`, `/sys`) |

Network data comes from `ConnectivityManager` callbacks (`NetworkCapabilities`,
`LinkProperties`) plus `WifiInfo`. Caveats: a per-app VPN that excludes tvmon
reads as off; with a VPN up the traffic totals count the tunnel and the
interface under it, so they read about double; Ethernet link speed isn't
available to an unprivileged app.

The overlay keeps one view per tile and only updates text, and it shifts by a
few dp every three minutes so its edges don't burn into OLED panels.

## Layout
```
tvmon/
├── PROJECT.md            # decisions / scope (the source of truth)
├── settings.gradle.kts   # + root build.gradle.kts, gradle.properties, gradlew
├── .github/workflows/build.yml   # CI + rolling "latest" release
├── syscore/              # Rust core (unit-tested)
│   ├── Cargo.toml
│   └── src/lib.rs
└── app/
    ├── build.gradle.kts
    ├── debug.keystore    # shared debug key, so updates install over each other
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/      # banner, icon, strings, backup rules
        │   └── java/com/example/tvmon/
        │       ├── SettingsActivity.kt   # launcher: toggles + transparency
        │       ├── OverlayService.kt     # foreground service + floating tiles
        │       ├── BootReceiver.kt
        │       ├── metric/               # MetricSource, providers, NetMonitor, NetFormat
        │       ├── nativebridge/         # JNI bridge to syscore
        │       └── prefs/                # SharedPreferences
        └── test/                         # JVM unit tests (NetFormat)
```

## 1. Build the Rust core (.so)
Needs the Android NDK (`sdkmanager "ndk;27.2.12479018"`) and cargo-ndk:
```bash
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
cd syscore
cargo ndk -t arm64-v8a -t armeabi-v7a -o ../app/src/main/jniLibs build --release
```
(Run it from `syscore/`: cargo-ndk 4 ignores `--manifest-path`.) `arm64-v8a`
covers the Shield 2019; `armeabi-v7a` the older ones. If you skip this step the
app still runs — the native tiles (system load, temps, uptime) just stay hidden.

Run the core's tests any time with:
```bash
cargo test --manifest-path syscore/Cargo.toml
```

## 2. Build the app
Point Gradle at your SDK (`echo sdk.dir=$ANDROID_HOME > local.properties`; the
file is gitignored), then:
```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```
The APK is `app/build/outputs/apk/debug/app-debug.apk`.

CI (`.github/workflows/build.yml`) does all of the above on every push — cargo
test, cargo-ndk for both ABIs, unit tests, lint, a check that the APK carries
`libsyscore.so` — and on pushes to `main` replaces the rolling **latest**
release with `tvmon.apk` and a `version.json` (commit, time, sha256, size).

## 3. Sideload to the Shield
```bash
adb connect <shield-ip>:5555        # enable Network debugging on the Shield first
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.example.tvmon SYSTEM_ALERT_WINDOW allow  # grant overlay
```

Open **tvmon** from the home screen, toggle **Show overlay**, pick which tiles
you want, and the floating readout appears over whatever you're watching.

## Updating
There is no in-app updater (no `INTERNET` permission, on purpose). Grab
`tvmon.apk` from the repo's *latest* release page — on a computer, or with a
browser / downloader app on the TV — and install it over the old one:
```bash
adb install -r tvmon.apk
```
Every build is signed with the same committed debug key, so it installs over the
previous one and keeps your settings. Compare its sha256 with `version.json` on
the same release if you like.

## Status
- `syscore`: 6/6 tests pass; cross-compiles for arm64-v8a and armeabi-v7a with
  NDK r27c; the `Java_…_nativeSnapshotJson` JNI symbol is exported.
- Android app: builds, 16/16 JVM unit tests pass, lint reports no issues, and
  the debug APK contains `libsyscore.so` for both ABIs. **Not yet run on a
  device** — the overlay, foreground service and network callbacks are
  compiler- and lint-checked only.

## Known rough edges (also in PROJECT.md)
- Text/background colour prefs exist; the colour-picker UI is a TODO (only the
  transparency slider is wired up so far).
