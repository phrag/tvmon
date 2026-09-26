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
- **No heavy UI deps for v1.** Settings is plain Android Views (excellent D-pad
  behaviour out of the box, zero extra Gradle surface). Compose is a later swap
  if we want to mirror RvSystem's look.

## What that constraint means for metrics
Because there's no root and no Shizuku, tiles are limited to what's readable
unprivileged. In vs. out:

| Tile | Source | Status |
|------|--------|--------|
| Output resolution / refresh | `Display.getMode` (Kotlin) | ✅ in |
| Live HDR state | `Display.isHdr()`, API 33+ (Kotlin) | ✅ in — pre-33 falls back to `getHdrCapabilities` (panel capability, not live state; labeled accordingly) |
| RAM used/total | `ActivityManager.MemoryInfo` (Kotlin) | ✅ in |
| Battery %/mV/temp | `BatteryManager` / sticky intent (Kotlin) | ✅ in |
| Wi-Fi link speed / freq | `WifiManager` (Kotlin) | ✅ in |
| Network traffic | `TrafficStats` (Kotlin) | ✅ in |
| System load (1/5/15) | `/proc/loadavg` (Rust) | ✅ in — the unprivileged stand-in for "CPU activity" |
| Temperatures | `/sys/class/thermal` (Rust, best-effort) | ✅ in (may be empty on some boxes) |
| Uptime | `/proc/uptime` (Rust) | ✅ in |
| AI Upscaling filter (Shield only) | `persist.vendor.tegra.hwc.upscale.filter` sysprop, read natively via `__system_property_read_callback` (Rust) | ✅ in — absent on non-Shield boxes |
| **Per-core CPU %** | `/proc/stat` | ❌ out — SELinux-blocked without privilege |
| **GPU freq / load** | Tegra sysfs | ❌ out — blocked, and Tegra paths differ from the usual Qualcomm ones |

If the no-Shizuku decision is ever revisited, the blocked rows become an optional
`ShizukuProvider` behind a feature flag — the `MetricSource` interface already
allows it. Nothing else changes.

## Architecture
- `syscore/` — Rust crate. Pure parsers + fake-root readers, unit-tested.
  Exposes `snapshot_json()` over JNI as
  `com.phrag.tvmon.nativebridge.SysCore.nativeSnapshotJson()`.
  Returns JSON so the JNI ABI stays a single string (no struct marshalling).
- `metric/` — `MetricSource` interface; each tile is a source that returns lines
  of text (or null when unavailable). Framework sources ignore the native
  snapshot; native sources read it. One snapshot is taken per refresh tick.
- `OverlayService` — a `TYPE_APPLICATION_OVERLAY` window that stacks one TextView
  tile per enabled+available source, restyled from prefs each tick.
- `SettingsActivity` — the leanback launcher entry: master overlay toggle,
  per-metric switches, transparency. D-pad friendly.
- `prefs/` — SharedPreferences: enabled metric ids, alpha, colors, start-on-boot.

## Verified so far
- `syscore` compiles on Rust 1.75 and passes 6 unit tests; against a live
  `/proc` it returns valid JSON for mem/load/uptime. The Android/Gradle side is
  written but **not yet compiled** (no Android SDK/NDK in the authoring env) —
  first Android Studio build will surface any stray import.

## TODO / open edges
- ~~Build `syscore` for Android ABIs with cargo-ndk into `app/src/main/jniLibs/`~~
  — done in CI (`.github/workflows/build-apk.yml`), which also runs
  `:app:assembleDebug` and uploads the APK as a workflow artifact.
- Leanback launcher tile needs a 320x180 `banner` drawable, or launch via adb.
- Start-on-boot: included as a pref + BootReceiver, but a robust boot start on
  Android 8+ likely needs a foreground service — flagged in code.
- Colour pickers for text/background (prefs exist; UI is a TODO).
- Optional: swap Views settings for Compose; add a mini load/traffic graph tile.
