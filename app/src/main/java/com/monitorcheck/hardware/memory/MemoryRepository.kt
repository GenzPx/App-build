package com.monitorcheck.hardware.memory

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import com.monitorcheck.core.Fmt
import com.monitorcheck.core.InfoItem
import com.monitorcheck.core.InfoSection
import com.monitorcheck.core.Reading
import com.monitorcheck.core.SysFs

data class MemorySnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long?,
    val cachedBytes: Long?,
    val buffersBytes: Long?,
    val swapTotalBytes: Long?,
    val swapFreeBytes: Long?,
    val zramTotalBytes: Long?,
    val lowMemory: Boolean,
    val thresholdBytes: Long,
    val source: String
) {
    val usedPercent: Double get() = if (totalBytes > 0) usedBytes * 100.0 / totalBytes else 0.0
    val swapUsedBytes: Long? get() =
        if (swapTotalBytes != null && swapFreeBytes != null) swapTotalBytes - swapFreeBytes else null
}

/**
 * RAM / swap / zRAM information.
 *
 * Primary source is ActivityManager.MemoryInfo (always available). /proc/meminfo is
 * used to enrich with cached/buffers/swap when readable — it is world-readable on
 * essentially all Android versions, but we never assume that.
 */
class MemoryRepository(private val context: Context) {

    private val activityManager: ActivityManager? =
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    /** Parses /proc/meminfo into a map of kB values. */
    fun readMemInfo(): Map<String, Long> {
        val lines = SysFs.readLines("/proc/meminfo") ?: return emptyMap()
        val map = HashMap<String, Long>()
        for (line in lines) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim().removeSuffix(" kB").trim().toLongOrNull()
            if (value != null) map[key] = value
        }
        return map
    }

    fun snapshot(): Reading<MemorySnapshot> {
        val am = activityManager ?: return Reading.unavailable("ActivityManager unavailable")
        val mi = ActivityManager.MemoryInfo()
        try {
            am.getMemoryInfo(mi)
        } catch (t: Throwable) {
            return Reading.error(t.message)
        }

        val meminfo = readMemInfo()
        fun kb(key: String): Long? = meminfo[key]?.times(1024)

        val total = if (mi.totalMem > 0) mi.totalMem else kb("MemTotal") ?: 0L
        if (total <= 0L) return Reading.unavailable("Total memory not reported")

        val available = if (mi.availMem > 0) mi.availMem else kb("MemAvailable") ?: 0L
        val zram = readZramTotal()

        return Reading.available(
            MemorySnapshot(
                totalBytes = total,
                availableBytes = available,
                usedBytes = (total - available).coerceAtLeast(0),
                freeBytes = kb("MemFree"),
                cachedBytes = kb("Cached"),
                buffersBytes = kb("Buffers"),
                swapTotalBytes = kb("SwapTotal"),
                swapFreeBytes = kb("SwapFree"),
                zramTotalBytes = zram,
                lowMemory = mi.lowMemory,
                thresholdBytes = mi.threshold,
                source = if (meminfo.isEmpty()) "ActivityManager.MemoryInfo"
                else "ActivityManager.MemoryInfo + /proc/meminfo"
            ),
            source = "ActivityManager"
        )
    }

    /** zRAM is exposed as a block device; disksize is its configured backing size. */
    private fun readZramTotal(): Long? {
        for (i in 0..3) {
            SysFs.readLong("/sys/block/zram$i/disksize")?.let { if (it > 0) return it }
        }
        return null
    }

    /** Memory used by the Monitored Check process itself. */
    fun ownProcessMemory(): Reading<Long> = try {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        Reading.available(info.totalPss.toLong() * 1024, "Debug.MemoryInfo (PSS)")
    } catch (t: Throwable) {
        Reading.error(t.message)
    }

    fun infoSections(): List<InfoSection> {
        val snap = snapshot()
        val meminfo = readMemInfo()
        val s = snap.value

        val main = listOf(
            InfoItem("Total RAM", snap.map { Fmt.bytes(it.totalBytes) }),
            InfoItem("Used RAM", snap.map { "${Fmt.bytes(it.usedBytes)} (${Fmt.percent(it.usedPercent)})" }),
            InfoItem("Available RAM", snap.map { Fmt.bytes(it.availableBytes) }),
            InfoItem("Free RAM", s?.freeBytes?.let { Reading.available(Fmt.bytes(it), "/proc/meminfo") }
                ?: Reading.unavailable("/proc/meminfo MemFree not readable")),
            InfoItem("Cached", s?.cachedBytes?.let { Reading.available(Fmt.bytes(it), "/proc/meminfo") }
                ?: Reading.unavailable()),
            InfoItem("Buffers", s?.buffersBytes?.let { Reading.available(Fmt.bytes(it), "/proc/meminfo") }
                ?: Reading.unavailable()),
            InfoItem("Low memory state", snap.map { Fmt.yesNo(it.lowMemory) }),
            InfoItem("Low memory threshold", snap.map { Fmt.bytes(it.thresholdBytes) }),
            InfoItem("Memory class", Reading.available("${activityManager?.memoryClass} MB", "ActivityManager")),
            InfoItem("Large memory class", Reading.available("${activityManager?.largeMemoryClass} MB", "ActivityManager")),
            InfoItem("Low RAM device", Reading.available(
                Fmt.yesNo(activityManager?.isLowRamDevice == true), "ActivityManager"))
        )

        val swap = listOf(
            InfoItem("Swap total", s?.swapTotalBytes?.let { Reading.available(Fmt.bytes(it), "/proc/meminfo") }
                ?: Reading.unavailable("Swap not reported")),
            InfoItem("Swap free", s?.swapFreeBytes?.let { Reading.available(Fmt.bytes(it), "/proc/meminfo") }
                ?: Reading.unavailable()),
            InfoItem("Swap used", s?.swapUsedBytes?.let { Reading.available(Fmt.bytes(it), "/proc/meminfo") }
                ?: Reading.unavailable()),
            InfoItem("zRAM disksize", s?.zramTotalBytes?.let { Reading.available(Fmt.bytes(it), "/sys/block/zram0") }
                ?: Reading.unavailable("No zram block device readable")),
            InfoItem("zRAM compressed", SysFs.readLong("/sys/block/zram0/mm_stat")
                ?.let { Reading.available(Fmt.bytes(it), "/sys/block/zram0/mm_stat") }
                ?: Reading.unavailable())
        )

        val kernelDetail = listOf(
            "Shmem", "SReclaimable", "SUnreclaim", "KernelStack", "PageTables",
            "Dirty", "Writeback", "Mapped", "Active", "Inactive", "CommitLimit", "Committed_AS"
        ).map { key ->
            InfoItem(key, meminfo[key]?.let { Reading.available(Fmt.bytes(it * 1024), "/proc/meminfo") }
                ?: Reading.unavailable())
        }

        val process = listOf(
            InfoItem("This app (PSS)", ownProcessMemory().map { Fmt.bytes(it) }),
            InfoItem("Java heap max", Reading.available(Fmt.bytes(Runtime.getRuntime().maxMemory()), "Runtime")),
            InfoItem("Java heap allocated", Reading.available(Fmt.bytes(Runtime.getRuntime().totalMemory()), "Runtime")),
            InfoItem("Java heap free", Reading.available(Fmt.bytes(Runtime.getRuntime().freeMemory()), "Runtime"))
        )

        return listOf(
            InfoSection("Memory", main),
            InfoSection("Swap / zRAM", swap),
            InfoSection("Kernel memory detail", kernelDetail,
                note = "Read from /proc/meminfo. Entries not exported by this kernel show as Unavailable."),
            InfoSection("Monitored Check process", process)
        )
    }
}
