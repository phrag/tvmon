package com.phrag.tvmon.metric

import android.content.Context
import android.net.ConnectivityManager

/**
 * Background pinger for [LatencySource]. Runs on its own daemon thread so the
 * ~1s-per-ping cost never touches the overlay's 1s render tick; sample() just
 * reads whatever these fields last settled to.
 *
 * Requires the INTERNET permission (declared in the manifest) — the only
 * metric in the app that needs it, since RTT has no passive kernel counter.
 * Lifecycle is owned by OverlayService: start() only while the Latency tile
 * is enabled, stop() otherwise and on service destroy, so a user who never
 * enables it never causes any network probing at all.
 */
object LatencyProbe {
    @Volatile var gatewayMs: Int? = null
        private set
    @Volatile var internetMs: Int? = null
        private set

    private const val INTERVAL_MS = 10_000L
    private const val PUBLIC_HOST = "1.1.1.1"
    private const val PING_BIN = "/system/bin/ping"
    private val timeRegex = Regex("""time[=<]([0-9.]+)""")

    @Volatile private var running = false
    private var thread: Thread? = null

    @Synchronized
    fun start(ctx: Context) {
        if (running) return
        running = true
        thread = Thread {
            val appCtx = ctx.applicationContext
            while (running) {
                gatewayMs = gatewayAddress(appCtx)?.let(::ping)
                internetMs = ping(PUBLIC_HOST)
                try {
                    Thread.sleep(INTERVAL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }.apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        gatewayMs = null
        internetMs = null
    }

    private fun gatewayAddress(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val net = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(net) ?: return null
        return lp.routes.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
    }

    /** One ping via the system binary (needs no special capability under this app's UID). */
    private fun ping(host: String): Int? = try {
        val proc = ProcessBuilder(PING_BIN, "-c", "1", "-W", "1", host)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        timeRegex.find(output)?.groupValues?.get(1)?.toDoubleOrNull()?.let { Math.round(it).toInt() }
    } catch (e: Exception) {
        null
    }
}
