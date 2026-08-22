package com.monitorcheck.network

import com.monitorcheck.core.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale
import kotlin.system.measureTimeMillis

/** Result of a diagnostic tool run. [success] false means the operation genuinely failed. */
data class ToolResult(
    val success: Boolean,
    val title: String,
    val output: String,
    val durationMs: Long = 0
)

/**
 * User-initiated network diagnostics.
 *
 * Every tool here runs only when the user presses a button and targets only a host
 * the user typed. There is no automatic scanning, no background probing, and no
 * telemetry. All measurements are real; failures are reported as failures.
 */
object NetworkTools {

    private const val UA = "MonitoredCheck/1.0 (Android; on-device diagnostics)"

    /**
     * ICMP-style reachability using InetAddress.isReachable, which uses ICMP when the
     * OS allows it and otherwise falls back to a TCP echo attempt. We report which.
     */
    suspend fun ping(host: String, count: Int = 4, timeoutMs: Int = 3000): ToolResult =
        withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            var ok = 0
            val times = ArrayList<Long>()
            try {
                val addr = InetAddress.getByName(host)
                sb.appendLine("PING $host (${addr.hostAddress})")
                repeat(count) { i ->
                    var reachable = false
                    val ms = measureTimeMillis {
                        reachable = try { addr.isReachable(timeoutMs) } catch (_: IOException) { false }
                    }
                    if (reachable) {
                        ok++
                        times.add(ms)
                        sb.appendLine("seq=${i + 1}  time=${ms} ms")
                    } else {
                        sb.appendLine("seq=${i + 1}  timeout after ${timeoutMs} ms")
                    }
                }
                val loss = (count - ok) * 100 / count
                sb.appendLine()
                sb.appendLine("--- $host statistics ---")
                sb.appendLine("$count sent, $ok received, ${loss}% packet loss")
                if (times.isNotEmpty()) {
                    sb.appendLine(String.format(Locale.US,
                        "rtt min/avg/max = %d/%.1f/%d ms",
                        times.min(), times.average(), times.max()))
                }
                if (ok == 0) {
                    sb.appendLine()
                    sb.appendLine("Note: Android may block raw ICMP for non-system apps. " +
                        "A timeout here does not always mean the host is down — " +
                        "try the TCP port check or HTTP test.")
                }
                ToolResult(ok > 0, "Ping $host", sb.toString().trim())
            } catch (t: Throwable) {
                ToolResult(false, "Ping $host", "Failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

    /** Forward DNS lookup returning every address the resolver provides. */
    suspend fun dnsLookup(host: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            var addrs: Array<InetAddress>
            val ms = measureTimeMillis { addrs = InetAddress.getAllByName(host) }
            val sb = StringBuilder("DNS lookup: $host\nResolved in $ms ms\n\n")
            addrs.forEach { a ->
                val kind = if (a is java.net.Inet6Address) "AAAA" else "A"
                sb.appendLine("$kind  ${a.hostAddress}")
            }
            ToolResult(true, "DNS lookup", sb.toString().trim(), ms)
        } catch (t: Throwable) {
            ToolResult(false, "DNS lookup", "Resolution failed: ${t.message}")
        }
    }

    /** Reverse lookup. Returns the PTR name only if it actually differs from the IP. */
    suspend fun reverseDns(ip: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            val addr = InetAddress.getByName(ip)
            val name = addr.canonicalHostName
            if (name == addr.hostAddress) ToolResult(false, "Reverse DNS", "No PTR record for $ip")
            else ToolResult(true, "Reverse DNS", "$ip -> $name")
        } catch (t: Throwable) {
            ToolResult(false, "Reverse DNS", "Failed: ${t.message}")
        }
    }

    /** HTTP(S) connectivity test: real request, real status code, real timings. */
    suspend fun httpTest(url: String): ToolResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val normalised = if (url.startsWith("http")) url else "https://$url"
            val sb = StringBuilder("HTTP test: $normalised\n\n")
            var code = -1
            val ms = measureTimeMillis {
                conn = (URL(normalised).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                    requestMethod = "GET"
                }
                code = conn!!.responseCode
            }
            val c = conn!!
            sb.appendLine("Status: $code ${c.responseMessage ?: ""}")
            sb.appendLine("Time: $ms ms")
            sb.appendLine("Protocol: ${URL(normalised).protocol.uppercase()}")
            c.contentType?.let { sb.appendLine("Content-Type: $it") }
            if (c.contentLength >= 0) sb.appendLine("Content-Length: ${Fmt.bytes(c.contentLength.toLong())}")
            sb.appendLine()
            sb.appendLine("Response headers:")
            c.headerFields.forEach { (k, v) ->
                if (k != null) sb.appendLine("  $k: ${v.joinToString(", ")}")
            }
            ToolResult(code in 200..399, "HTTP test", sb.toString().trim(), ms)
        } catch (t: Throwable) {
            ToolResult(false, "HTTP test", "Failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * TCP port check for a host the user explicitly entered.
     * Single connect attempt — deliberately not a scanner.
     */
    suspend fun portCheck(host: String, port: Int, timeoutMs: Int = 4000): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                var ms: Long
                Socket().use { socket ->
                    ms = measureTimeMillis {
                        socket.connect(InetSocketAddress(host, port), timeoutMs)
                    }
                }
                ToolResult(true, "Port check", "$host:$port is OPEN (connected in $ms ms)", ms)
            } catch (t: Throwable) {
                ToolResult(false, "Port check",
                    "$host:$port is CLOSED or filtered\n${t.javaClass.simpleName}: ${t.message}")
            }
        }

    /**
     * Public IP lookup. Explicit user action only — this is the one feature that
     * intentionally contacts a third party, and the UI says so before running.
     */
    suspend fun publicIp(): ToolResult = withContext(Dispatchers.IO) {
        val endpoints = listOf(
            "https://api.ipify.org" to "ipify.org",
            "https://icanhazip.com" to "icanhazip.com",
            "https://ifconfig.me/ip" to "ifconfig.me"
        )
        for ((url, name) in endpoints) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000; readTimeout = 6000
                    setRequestProperty("User-Agent", UA)
                }
                val ip = conn.inputStream.bufferedReader().use { it.readText().trim() }
                conn.disconnect()
                if (ip.isNotBlank() && ip.length < 60) {
                    return@withContext ToolResult(true, "Public IP", "$ip\n\nResolved via $name")
                }
            } catch (_: Throwable) { /* try next endpoint */ }
        }
        ToolResult(false, "Public IP", "Could not reach any public IP endpoint.")
    }

    /**
     * Download throughput test. Measures real bytes over real time against a
     * user-chosen size. Returns the measured rate, never a synthetic figure.
     * [customUrl] lets the user test against their own server; when blank the
     * default Cloudflare speed endpoint is used.
     */
    suspend fun downloadTest(
        sizeBytes: Long = 5_000_000,
        customUrl: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): ToolResult = withContext(Dispatchers.IO) {
        val userUrl = customUrl?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val url = userUrl ?: "https://speed.cloudflare.com/__down?bytes=$sizeBytes"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000; readTimeout = 20000
                setRequestProperty("User-Agent", UA)
            }
            val start = System.nanoTime()
            var total = 0L
            val buf = ByteArray(32 * 1024)
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    onProgress(total, sizeBytes)
                }
            }
            val seconds = (System.nanoTime() - start) / 1_000_000_000.0
            if (seconds <= 0 || total <= 0) {
                return@withContext ToolResult(false, "Download test", "No data transferred")
            }
            val bps = total / seconds
            ToolResult(true, "Download test", buildString {
                appendLine("Downloaded: ${Fmt.bytes(total)}")
                appendLine(String.format(Locale.US, "Duration: %.2f s", seconds))
                appendLine("Throughput: ${Fmt.bytesPerSecond(bps)} (${Fmt.bitsPerSecond(bps * 8)})")
                appendLine()
                if (userUrl != null) {
                    appendLine("Measured against the URL you provided: $userUrl")
                    appendLine("Result reflects real transferred bytes over elapsed time.")
                } else {
                    appendLine("Measured against speed.cloudflare.com. Result reflects real")
                    appendLine("transferred bytes over elapsed time on the active connection.")
                }
            }.trim(), (seconds * 1000).toLong())
        } catch (t: Throwable) {
            ToolResult(false, "Download test", "Failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Upload throughput test, posting generated bytes and timing the transfer.
     * [customUrl] lets the user POST to their own endpoint; blank uses Cloudflare.
     */
    suspend fun uploadTest(sizeBytes: Int = 2_000_000, customUrl: String? = null): ToolResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        val userUrl = customUrl?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val target = userUrl ?: "https://speed.cloudflare.com/__up"
        try {
            conn = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000; readTimeout = 30000
                requestMethod = "POST"
                doOutput = true
                setFixedLengthStreamingMode(sizeBytes)
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("User-Agent", UA)
            }
            val payload = ByteArray(32 * 1024)
            val start = System.nanoTime()
            conn.outputStream.use { out ->
                var written = 0
                while (written < sizeBytes) {
                    val n = minOf(payload.size, sizeBytes - written)
                    out.write(payload, 0, n)
                    written += n
                }
                out.flush()
            }
            val code = conn.responseCode
            val seconds = (System.nanoTime() - start) / 1_000_000_000.0
            if (seconds <= 0) return@withContext ToolResult(false, "Upload test", "Timing failed")
            val bps = sizeBytes / seconds
            ToolResult(code in 200..399, "Upload test", buildString {
                appendLine("Uploaded: ${Fmt.bytes(sizeBytes.toLong())}")
                appendLine(String.format(Locale.US, "Duration: %.2f s", seconds))
                appendLine("Throughput: ${Fmt.bytesPerSecond(bps)} (${Fmt.bitsPerSecond(bps * 8)})")
                appendLine("Server status: $code")
                appendLine("Target: $target")
            }.trim(), (seconds * 1000).toLong())
        } catch (t: Throwable) {
            ToolResult(false, "Upload test", "Failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Latency test: repeated TCP handshakes to a host:port, reporting min/avg/max
     * and jitter. More reliable than ICMP on Android, which often blocks raw sockets.
     */
    suspend fun latencyTest(host: String, port: Int = 443, samples: Int = 5): ToolResult =
        withContext(Dispatchers.IO) {
            val times = ArrayList<Long>()
            val sb = StringBuilder("TCP latency to $host:$port\n\n")
            repeat(samples) { i ->
                try {
                    var ms: Long
                    Socket().use { s ->
                        ms = measureTimeMillis { s.connect(InetSocketAddress(host, port), 4000) }
                    }
                    times.add(ms)
                    sb.appendLine("probe ${i + 1}: ${ms} ms")
                } catch (t: Throwable) {
                    sb.appendLine("probe ${i + 1}: failed (${t.javaClass.simpleName})")
                }
            }
            if (times.isEmpty()) {
                return@withContext ToolResult(false, "Latency test", sb.append("\nAll probes failed.").toString())
            }
            val avg = times.average()
            val jitter = if (times.size > 1)
                times.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average() else 0.0
            sb.appendLine()
            sb.appendLine(String.format(Locale.US,
                "min %d ms / avg %.1f ms / max %d ms", times.min(), avg, times.max()))
            sb.appendLine(String.format(Locale.US, "jitter %.1f ms", jitter))
            ToolResult(true, "Latency test", sb.toString().trim())
        }

    /**
     * Traceroute-style path discovery.
     *
     * Android does not give apps raw sockets or IP_TTL control on all versions, so a
     * true hop-by-hop trace is not possible for a normal app. We attempt increasing
     * TTLs via the ping binary when it is present, and clearly report when it isn't.
     */
    suspend fun traceroute(host: String, maxHops: Int = 15): ToolResult = withContext(Dispatchers.IO) {
        val sb = StringBuilder("Traceroute to $host (max $maxHops hops)\n\n")
        val pingBin = listOf("/system/bin/ping", "/system/xbin/ping").firstOrNull {
            java.io.File(it).canExecute()
        }
        if (pingBin == null) {
            return@withContext ToolResult(false, "Traceroute", buildString {
                appendLine("Traceroute is Unsupported on this device.")
                appendLine()
                appendLine("Android does not grant apps raw socket access, and no usable")
                appendLine("ping binary with TTL control was found. Use the latency test")
                appendLine("or port check instead — both work without elevated privileges.")
            })
        }
        var reached = false
        for (ttl in 1..maxHops) {
            val result = withTimeoutOrNull(5000L) {
                runCatching {
                    val p = ProcessBuilder(pingBin, "-c", "1", "-W", "2", "-t", "$ttl", host)
                        .redirectErrorStream(true).start()
                    val out = p.inputStream.bufferedReader().use { it.readText() }
                    p.waitFor()
                    out
                }.getOrNull()
            }
            if (result == null) { sb.appendLine("$ttl.  * (timeout)"); continue }
            val hop = Regex("From ([\\w.:\\-]+)").find(result)?.groupValues?.get(1)
                ?: Regex("from ([\\w.:\\-]+)").find(result)?.groupValues?.get(1)
            val rtt = Regex("time=([\\d.]+) ms").find(result)?.groupValues?.get(1)
            when {
                result.contains("bytes from") && rtt != null && hop == null -> {
                    sb.appendLine("$ttl.  $host  ${rtt} ms  (destination reached)"); reached = true
                }
                hop != null -> sb.appendLine("$ttl.  $hop${rtt?.let { "  $it ms" } ?: ""}")
                else -> sb.appendLine("$ttl.  *")
            }
            if (reached) break
        }
        if (!reached) sb.appendLine("\nDestination not reached within $maxHops hops.")
        sb.appendLine("\nNote: hop discovery relies on the system ping binary and may be")
        sb.appendLine("incomplete because Android restricts ICMP for unprivileged apps.")
        ToolResult(true, "Traceroute", sb.toString().trim())
    }

    /**
     * Routing table, from the public ConnectivityManager LinkProperties of every
     * connected network plus a best-effort read of /proc/net/route. Runs only on
     * user request and reports honestly when the kernel file is restricted.
     */
    suspend fun routingTable(context: android.content.Context): ToolResult = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        try {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
            if (cm != null) {
                sb.appendLine("== Routes from ConnectivityManager (public API) ==")
                var any = false
                @Suppress("DEPRECATION")
                val networks = cm.allNetworks
                networks.forEach { network ->
                    val lp = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: return@forEach
                    val iface = lp.interfaceName ?: "unknown"
                    lp.routes.forEach { route ->
                        any = true
                        val dest = route.destination?.toString() ?: "default"
                        val gw = route.gateway?.hostAddress?.takeIf { it.isNotBlank() } ?: "-"
                        val def = if (route.isDefaultRoute) "  [default]" else ""
                        sb.appendLine("$iface  $dest  via $gw$def")
                    }
                }
                if (!any) sb.appendLine("No routes reported for the currently connected networks.")
            } else {
                sb.appendLine("ConnectivityManager unavailable on this device.")
            }
        } catch (t: Throwable) {
            sb.appendLine("ConnectivityManager routes failed: ${t.javaClass.simpleName}: ${t.message}")
        }

        sb.appendLine()
        sb.appendLine("== Kernel table /proc/net/route ==")
        val kernel = runCatching { java.io.File("/proc/net/route").readText() }.getOrNull()
        if (kernel.isNullOrBlank()) {
            sb.appendLine("Restricted by Android: /proc/net/route is not readable by apps")
            sb.appendLine("on this device (SELinux / hidepid). The public-API routes above")
            sb.appendLine("are the real routing information available without root.")
        } else {
            val lines = kernel.lines().filter { it.isNotBlank() }
            lines.take(1).forEach { sb.appendLine(it.trim()) }
            lines.drop(1).take(32).forEach { line ->
                val f = line.trim().split(Regex("\\s+"))
                if (f.size >= 3) {
                    sb.appendLine("${f[0]}  dst ${hexIpToDotted(f[1])}  gw ${hexIpToDotted(f[2])}")
                }
            }
        }
        ToolResult(true, "Routing table", sb.toString().trim())
    }

    /** /proc/net/route stores little-endian hex IPv4 — decode it honestly or echo the raw field. */
    private fun hexIpToDotted(hex: String): String = runCatching {
        if (hex.length != 8) return hex
        val bytes = (0 until 4).map { hex.substring(it * 2, it * 2 + 2).toInt(16) }
        "${bytes[3]}.${bytes[2]}.${bytes[1]}.${bytes[0]}"
    }.getOrDefault(hex)
}
