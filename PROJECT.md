# tvmon — project decisions

A modular on-demand system + video-output overlay for Android TV / NVIDIA Shield.
Think DevInfoOverlay, but FOSS and TV-first. This file is the source of truth for
scope and design choices (it stands in for cross-chat memory).

## Fixed decisions
- **Target device:** NVIDIA Shield / Android TV boxes. TV-first: D-pad focus,
  leanback launcher entry, 10-foot layout. Not a phone app.
- **Stack:** Kotlin (UI + framework metrics) + Rust (`syscore`, kernel-file
  parsing) via JNI. Matches the maintainer's other projects.
- **License:** GPL-3.0-only. (Lets us reuse GPL-3.0 code/techniques from
  RvSystem Monitor if we ever want to.)
- **No privileged features.** No root, **no Shizuku.** The app must work with
  zero special setup on a stock box. This is a hard constraint, not a default.
- **minSdk 26**, targetSdk/compileSdk 34. 26 covers every Shield and lets the
  overlay use `TYPE_APPLICATION_OVERLAY` without a legacy branch.
- **No `INTERNET` permission.** A privacy feature: the app can't send anything
  anywhere. Consequently **no self-updater** (unlike the sibling apps); updates
  are installed by hand from the rolling `latest` GitHub release or via adb.
  No location permission either (SSID isn't needed). `allowBackup="false"` plus
  data-extraction rules that block device-to-device transfer.
- **Signing:** the committed `app/debug.keystore` (shared with the sibling apps)
  so every CI build installs over the previous one.
- **No heavy UI deps for v1.** Settings is plain Android Views (excellent D-pad
  behaviour out of the box, zero extra Gradle surface). Compose is a later swap
  if we want to mirror RvSystem's look.

## What that constraint means for metrics
Because there's no root and no Shizuku, tiles are limited to what's readable
unprivileged. In vs. out:

| Tile | Source | Status |
|------|--------|--------|
| Output resolution / refresh / HDR | `Display.getMode` + `getHdrCapabilities` (Kotlin) | ✅ in |
| RAM used/total | `ActivityManager.MemoryInfo` (Kotlin) | ✅ in |
| Battery %/mV/temp | `BatteryManager` / sticky intent (Kotlin) | ✅ in |
| VPN on/off + underlying transport | `ConnectivityManager` callbacks, `NetworkCapabilities` (Kotlin) | ✅ in — red when off |
| Transport, interface, validated/metered | `NetworkCapabilities` + `LinkProperties` (Kotlin) | ✅ in |
| Wi-Fi band / channel / standard / tx+rx link / RSSI | `WifiInfo` (transportInfo on 31+, `connectionInfo` before) | ✅ in — no location permission, so no SSID |
| IPs, gateway, DNS, Private DNS | `LinkProperties` (Kotlin) | ✅ in |
| Network traffic | `TrafficStats` (Kotlin) | ✅ in (device-wide; double-counts through a VPN) |
| Ethernet link speed | `/sys/class/net/*/speed` | ❌ out — not readable unprivileged on recent Android |
| System load (1/5/15) | `/proc/loadavg` (Rust) | ✅ in — the unprivileged stand-in for "CPU activity" |
| Temperatures | `/sys/class/thermal` (Rust, best-effort) | ✅ in (may be empty on some boxes) |
| Uptime | `/proc/uptime` (Rust) | ✅ in |
| **Per-core CPU %** | `/proc/stat` | ❌ out — SELinux-blocked without privilege |
| **GPU freq / load** | Tegra sysfs | ❌ out — blocked, and Tegra paths differ from the usual Qualcomm ones |

If the no-Shizuku decision is ever revisited, the blocked rows become an optional
`ShizukuProvider` behind a feature flag — the `MetricSource` interface already
allows it. Nothing else changes.

## Architecture
- `syscore/` — Rust crate. Pure parsers + fake-root readers, unit-tested.
  Exposes `snapshot_json()` over JNI as
  `com.example.tvmon.nativebridge.SysCore.nativeSnapshotJson()`.
  Returns JSON so the JNI ABI stays a single string (no struct marshalling).
- `metric/` — `MetricSource` interface; each tile is a source that returns lines
  of text (or null when unavailable). Framework sources ignore the native
  snapshot; native sources read it. One snapshot is taken per refresh tick.
- `metric/NetMonitor` — one shared `ConnectivityManager` callback cache (all
  networks incl. VPNs + the default network); the VPN / Network / IP-DNS tiles
  read it instead of polling. `metric/NetFormat` holds the pure formatting
  (band, channel, RSSI words, rates, addresses), JVM unit-tested.
- `MetricSource.warn` — a tile can ask to be drawn in the warning colour (used
  by VPN when the tunnel is down).
- `OverlayService` — a foreground service (`specialUse` type on API 34,
  low-importance notification) owning a `TYPE_APPLICATION_OVERLAY` window. One
  TextView per tile is created once; each tick only updates text, colour and
  visibility. Every 3 min the window is nudged a few dp via
  `updateViewLayout` (OLED burn-in protection). Started with
  `startForegroundService` from SettingsActivity and BootReceiver.
- `SettingsActivity` — the leanback launcher entry: master overlay toggle,
  per-metric switches, transparency. D-pad friendly.
- `prefs/` — SharedPreferences: enabled metric ids, alpha, colors, start-on-boot.

## Build
Gradle 8.7 wrapper, AGP 8.5.2, Kotlin 1.9.24 (same as the sibling apps), JDK 17.
`syscore` is cross-compiled by cargo-ndk (NDK r27c, `27.2.12479018`) into
`app/src/main/jniLibs/` (gitignored); Gradle just packages it. CI
(`.github/workflows/build.yml`) runs cargo test → cargo-ndk → unit tests,
assemble, lint → checks the APK has `libsyscore.so` → on `main`, publishes the
rolling `latest` release (`tvmon.apk` + `version.json`). Steps in README.

## Verified so far
- `syscore`: 6/6 tests pass (Rust 1.97 here; originally 1.75); against a live
  `/proc` it returns valid JSON for mem/load/uptime. Cross-compiles for
  arm64-v8a + armeabi-v7a and exports the JNI symbol.
- App: compiles, 16/16 JVM unit tests (NetFormat) pass, `lintDebug` reports no
  issues, debug APK contains `libsyscore.so` for both ABIs, has no INTERNET
  permission, and is signed with the shared debug key.
- **Not yet run on a device or emulator**: overlay, FGS, boot start and the
  network callbacks are unverified at runtime.

## TODO / open edges
- First on-device run on a Shield (Android 11) and an Android 14 box.
- Colour pickers for text/background (prefs exist; UI is a TODO).
- Optional: swap Views settings for Compose; add a mini load/traffic graph tile.
