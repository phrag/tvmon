//! syscore — the native half of tvmon.
//!
//! Reads only *world-readable* kernel files, so it works with **no root and no
//! Shizuku** on a stock Shield / Android TV box. Anything that needs privilege
//! (per-core CPU %, GPU freq/load, most per-process stats) is deliberately NOT
//! here — see PROJECT.md. The framework-API metrics (display mode, RAM total,
//! battery, Wi-Fi, traffic) live on the Kotlin side; this core covers the few
//! useful things the Android SDK does not hand you directly:
//!
//!   * /proc/meminfo   -> MemTotal / MemAvailable (kB)
//!   * /proc/loadavg   -> 1/5/15 load averages  (the unprivileged stand-in for
//!                        a "CPU activity" readout)
//!   * /proc/uptime    -> uptime seconds
//!   * /sys/class/thermal/thermal_zone*/temp -> best-effort temperatures
//!
//! All parsing is pure and unit-tested against fixtures; file reads are split
//! out so tests can point at a fake root.

use serde::Serialize;
use std::fs;
use std::path::Path;

#[derive(Serialize, Debug, Clone, PartialEq)]
pub struct MemInfo {
    pub total_kb: u64,
    pub available_kb: u64,
}

#[derive(Serialize, Debug, Clone, PartialEq)]
pub struct LoadAvg {
    pub one: f64,
    pub five: f64,
    pub fifteen: f64,
}

#[derive(Serialize, Debug, Clone, PartialEq)]
pub struct ThermalZone {
    pub kind: String,
    pub celsius: f32,
}

#[derive(Serialize, Debug, Clone, Default)]
pub struct Snapshot {
    pub mem: Option<MemInfo>,
    pub load: Option<LoadAvg>,
    pub uptime_secs: Option<f64>,
    pub thermal: Vec<ThermalZone>,
}

// ---- pure parsers (unit-tested) ----------------------------------------------

pub fn parse_meminfo(content: &str) -> Option<MemInfo> {
    let mut total = None;
    let mut avail = None;
    for line in content.lines() {
        let mut it = line.split_whitespace();
        let key = match it.next() {
            Some(k) => k,
            None => continue,
        };
        let val: Option<u64> = it.next().and_then(|v| v.parse().ok());
        match key {
            "MemTotal:" => total = val,
            "MemAvailable:" => avail = val,
            _ => {}
        }
    }
    match (total, avail) {
        (Some(t), Some(a)) => Some(MemInfo { total_kb: t, available_kb: a }),
        _ => None,
    }
}

pub fn parse_loadavg(content: &str) -> Option<LoadAvg> {
    let mut it = content.split_whitespace();
    let one = it.next()?.parse().ok()?;
    let five = it.next()?.parse().ok()?;
    let fifteen = it.next()?.parse().ok()?;
    Some(LoadAvg { one, five, fifteen })
}

pub fn parse_uptime(content: &str) -> Option<f64> {
    content.split_whitespace().next()?.parse().ok()
}

// ---- file readers under a base dir (so tests can fake "/") -------------------

fn read(base: &Path, rel: &str) -> Option<String> {
    fs::read_to_string(base.join(rel)).ok()
}

fn read_thermal(base: &Path) -> Vec<ThermalZone> {
    let mut out = Vec::new();
    let dir = base.join("sys/class/thermal");
    let entries = match fs::read_dir(&dir) {
        Ok(e) => e,
        Err(_) => return out,
    };
    for entry in entries.flatten() {
        let p = entry.path();
        let name = p.file_name().and_then(|n| n.to_str()).unwrap_or("");
        if !name.starts_with("thermal_zone") {
            continue;
        }
        let milli = fs::read_to_string(p.join("temp"))
            .ok()
            .and_then(|s| s.trim().parse::<i64>().ok());
        let kind = fs::read_to_string(p.join("type"))
            .ok()
            .map(|s| s.trim().to_string())
            .unwrap_or_else(|| name.to_string());
        if let Some(m) = milli {
            out.push(ThermalZone { kind, celsius: m as f32 / 1000.0 });
        }
    }
    out.sort_by(|a, b| a.kind.cmp(&b.kind));
    out
}

pub fn snapshot_from(base: &Path) -> Snapshot {
    Snapshot {
        mem: read(base, "proc/meminfo").as_deref().and_then(parse_meminfo),
        load: read(base, "proc/loadavg").as_deref().and_then(parse_loadavg),
        uptime_secs: read(base, "proc/uptime").as_deref().and_then(parse_uptime),
        thermal: read_thermal(base),
    }
}

pub fn snapshot() -> Snapshot {
    snapshot_from(Path::new("/"))
}

pub fn snapshot_json() -> String {
    serde_json::to_string(&snapshot()).unwrap_or_else(|_| "{}".to_string())
}

// ---- JNI surface (Android only) ---------------------------------------------
// Symbol maps to: com.phrag.tvmon.nativebridge.SysCore.nativeSnapshotJson()
#[cfg(target_os = "android")]
mod android {
    use super::snapshot_json;
    use jni::objects::JClass;
    use jni::sys::jstring;
    use jni::JNIEnv;

    #[no_mangle]
    pub extern "system" fn Java_com_phrag_tvmon_nativebridge_SysCore_nativeSnapshotJson(
        mut env: JNIEnv,
        _class: JClass,
    ) -> jstring {
        match env.new_string(snapshot_json()) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }
}

// ---- tests -------------------------------------------------------------------
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn meminfo_parses_total_and_available() {
        let s = "MemTotal:        3000000 kB\nMemFree:  100000 kB\nMemAvailable:    1500000 kB\n";
        let m = parse_meminfo(s).unwrap();
        assert_eq!(m.total_kb, 3_000_000);
        assert_eq!(m.available_kb, 1_500_000);
    }

    #[test]
    fn meminfo_missing_available_is_none() {
        assert!(parse_meminfo("MemTotal: 100 kB\n").is_none());
    }

    #[test]
    fn loadavg_parses_three_values() {
        let l = parse_loadavg("0.52 0.58 0.59 1/523 12345\n").unwrap();
        assert!((l.one - 0.52).abs() < 1e-9);
        assert!((l.five - 0.58).abs() < 1e-9);
        assert!((l.fifteen - 0.59).abs() < 1e-9);
    }

    #[test]
    fn uptime_takes_first_field() {
        assert_eq!(parse_uptime("12345.67 9999.00\n"), Some(12345.67));
    }

    #[test]
    fn snapshot_reads_from_fake_root() {
        let base = std::env::temp_dir().join(format!("syscore_it_{}", std::process::id()));
        let proc = base.join("proc");
        fs::create_dir_all(&proc).unwrap();
        fs::write(proc.join("meminfo"), "MemTotal: 100 kB\nMemAvailable: 40 kB\n").unwrap();
        fs::write(proc.join("loadavg"), "1.0 2.0 3.0 1/1 1\n").unwrap();
        fs::write(proc.join("uptime"), "50.0 25.0\n").unwrap();

        let tz = base.join("sys/class/thermal/thermal_zone0");
        fs::create_dir_all(&tz).unwrap();
        fs::write(tz.join("type"), "cpu-thermal\n").unwrap();
        fs::write(tz.join("temp"), "42500\n").unwrap();

        let snap = snapshot_from(&base);
        assert_eq!(snap.mem.unwrap(), MemInfo { total_kb: 100, available_kb: 40 });
        assert_eq!(snap.load.unwrap().five, 2.0);
        assert_eq!(snap.uptime_secs, Some(50.0));
        assert_eq!(snap.thermal.len(), 1);
        assert!((snap.thermal[0].celsius - 42.5).abs() < 1e-4);
        assert_eq!(snap.thermal[0].kind, "cpu-thermal");

        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn snapshot_json_is_valid_json() {
        let base = std::env::temp_dir().join(format!("syscore_json_{}", std::process::id()));
        fs::create_dir_all(base.join("proc")).unwrap();
        fs::write(base.join("proc/loadavg"), "0.1 0.2 0.3 1/1 1\n").unwrap();
        let json = serde_json::to_string(&snapshot_from(&base)).unwrap();
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert!(v.get("load").is_some());
        let _ = fs::remove_dir_all(&base);
    }
}
