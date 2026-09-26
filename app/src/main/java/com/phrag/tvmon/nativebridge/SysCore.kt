package com.phrag.tvmon.nativebridge

import org.json.JSONObject

/**
 * Bridge to the Rust core (libsyscore.so). If the .so isn't bundled (e.g. you
 * haven't run cargo-ndk yet) the app still runs — native tiles just report
 * "unavailable" instead of crashing.
 */
object SysCore {
    @Volatile private var available = false

    init {
        available = try {
            System.loadLibrary("syscore")
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun isAvailable(): Boolean = available

    /** Implemented in syscore/src/lib.rs. Returns a JSON snapshot string. */
    private external fun nativeSnapshotJson(): String

    /** One native read; null if the library is missing or the call fails. */
    fun snapshot(): NativeSnapshot? {
        if (!available) return null
        val json = runCatching { nativeSnapshotJson() }.getOrNull() ?: return null
        return runCatching { NativeSnapshot(JSONObject(json)) }.getOrNull()
    }
}

/** Thin typed view over the Rust JSON, so providers don't each parse it. */
class NativeSnapshot(private val root: JSONObject) {

    /** (1min, 5min, 15min) load averages, or null. */
    fun load(): Triple<Double, Double, Double>? {
        val o = root.optJSONObject("load") ?: return null
        return Triple(o.optDouble("one"), o.optDouble("five"), o.optDouble("fifteen"))
    }

    /** (total_kb, available_kb), or null. */
    fun mem(): Pair<Long, Long>? {
        val o = root.optJSONObject("mem") ?: return null
        return o.optLong("total_kb") to o.optLong("available_kb")
    }

    fun uptimeSecs(): Double? =
        if (root.has("uptime_secs") && !root.isNull("uptime_secs"))
            root.optDouble("uptime_secs") else null

    /** List of (zone type, °C). */
    fun thermal(): List<Pair<String, Double>> {
        val arr = root.optJSONArray("thermal") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val z = arr.optJSONObject(i) ?: continue
                add(z.optString("kind") to z.optDouble("celsius"))
            }
        }
    }
}
