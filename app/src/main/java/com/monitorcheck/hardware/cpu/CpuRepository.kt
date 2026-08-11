package com.monitorcheck.hardware.cpu

import android.os.Build
import com.monitorcheck.core.Fmt
import com.monitorcheck.core.InfoItem
import com.monitorcheck.core.InfoSection
import com.monitorcheck.core.Reading
import com.monitorcheck.core.SysFs
import java.io.File

/** Snapshot of the aggregate + per-core jiffy counters from /proc/stat. */
data class CpuTimes(
    val user: Long, val nice: Long, val system: Long, val idle: Long,
    val iowait: Long, val irq: Long, val softirq: Long, val steal: Long
) {
    val total: Long get() = user + nice + system + idle + iowait + irq + softirq + steal
    val busy: Long get() = total - idle - iowait
}

data class CpuStatSnapshot(
    val aggregate: CpuTimes?,
    val perCore: Map<Int, CpuTimes>
)

data class CoreInfo(
    val id: Int,
    val currentKHz: Long?,
    val minKHz: Long?,
    val maxKHz: Long?,
    val governor: String?,
    val online: Boolean,
    val usagePercent: Double?
)

data class CpuUsage(
    val totalPercent: Reading<Double>,
    val perCorePercent: Map<Int, Double>,
    val cores: List<CoreInfo>,
    val loadAverage: Reading<Triple<Double, Double, Double>>
)

/**
 * CPU information and utilisation.
 *
 * Utilisation is computed by differencing /proc/stat jiffy counters between two
 * samples — the same method top/htop use. On Android 8+ /proc/stat is often
 * restricted for non-system apps under the hidepid mount option; in that case we
 * report the restriction instead of inventing numbers.
 */
class CpuRepository {

    private companion object {
        const val CPUFREQ_BASE = "/sys/devices/system/cpu"
        const val PROC_STAT = "/proc/stat"
    }

    private var lastSnapshot: CpuStatSnapshot? = null

    val coreCount: Int = Runtime.getRuntime().availableProcessors()

    /** Reads /proc/stat and parses aggregate + per-core lines. */
    fun readStat(): CpuStatSnapshot? {
        val lines = SysFs.readLines(PROC_STAT) ?: return null
        var aggregate: CpuTimes? = null
        val perCore = HashMap<Int, CpuTimes>()
        for (line in lines) {
            if (!line.startsWith("cpu")) continue
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 5) continue
            val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (nums.size < 4) continue
            val times = CpuTimes(
                user = nums.getOrElse(0) { 0 },
                nice = nums.getOrElse(1) { 0 },
                system = nums.getOrElse(2) { 0 },
                idle = nums.getOrElse(3) { 0 },
                iowait = nums.getOrElse(4) { 0 },
                irq = nums.getOrElse(5) { 0 },
                softirq = nums.getOrElse(6) { 0 },
                steal = nums.getOrElse(7) { 0 }
            )
            val label = parts[0]
            if (label == "cpu") aggregate = times
            else label.removePrefix("cpu").toIntOrNull()?.let { perCore[it] = times }
        }
        if (aggregate == null && perCore.isEmpty()) return null
        return CpuStatSnapshot(aggregate, perCore)
    }

    /**
     * Samples utilisation. The first call after construction establishes the
     * baseline and returns UNAVAILABLE for percentages (a delta needs two samples).
     */
    fun sampleUsage(): CpuUsage {
        val current = readStat()
        val previous = lastSnapshot
        if (current != null) lastSnapshot = current

        val cores = readCores()

        if (current == null) {
            return CpuUsage(
                totalPercent = Reading.restricted(
                    "/proc/stat is not readable by this app on this Android version"
                ),
                perCorePercent = emptyMap(),
                cores = cores,
                loadAverage = readLoadAverage()
            )
        }
        if (previous == null) {
            return CpuUsage(
                totalPercent = Reading.unavailable("Collecting first sample"),
                perCorePercent = emptyMap(),
                cores = cores,
                loadAverage = readLoadAverage()
            )
        }

        val total = delta(previous.aggregate, current.aggregate)
        val perCore = HashMap<Int, Double>()
        for ((id, cur) in current.perCore) {
            delta(previous.perCore[id], cur)?.let { perCore[id] = it }
        }

        val mergedCores = cores.map { it.copy(usagePercent = perCore[it.id]) }

        return CpuUsage(
            totalPercent = total?.let { Reading.available(it, PROC_STAT) }
                ?: Reading.unavailable("No counter movement between samples"),
            perCorePercent = perCore,
            cores = mergedCores,
            loadAverage = readLoadAverage()
        )
    }

    private fun delta(prev: CpuTimes?, cur: CpuTimes?): Double? {
        if (prev == null || cur == null) return null
        val totalDelta = cur.total - prev.total
        val busyDelta = cur.busy - prev.busy
        if (totalDelta <= 0) return null
        return (busyDelta.toDouble() / totalDelta.toDouble() * 100.0).coerceIn(0.0, 100.0)
    }

    fun readCores(): List<CoreInfo> = (0 until coreCount).map { id ->
        val base = "$CPUFREQ_BASE/cpu$id/cpufreq"
        val online = SysFs.readInt("$CPUFREQ_BASE/cpu$id/online") != 0 ||
            !SysFs.isReadable("$CPUFREQ_BASE/cpu$id/online")
        CoreInfo(
            id = id,
            currentKHz = SysFs.readLong("$base/scaling_cur_freq") ?: SysFs.readLong("$base/cpuinfo_cur_freq"),
            minKHz = SysFs.readLong("$base/cpuinfo_min_freq") ?: SysFs.readLong("$base/scaling_min_freq"),
            maxKHz = SysFs.readLong("$base/cpuinfo_max_freq") ?: SysFs.readLong("$base/scaling_max_freq"),
            governor = SysFs.readFirstLine("$base/scaling_governor"),
            online = online,
            usagePercent = null
        )
    }

    fun readLoadAverage(): Reading<Triple<Double, Double, Double>> {
        val text = SysFs.readText("/proc/loadavg", useFailureCache = false)
            ?: return Reading.restricted("/proc/loadavg not readable")
        val p = text.split(Regex("\\s+"))
        val a = p.getOrNull(0)?.toDoubleOrNull()
        val b = p.getOrNull(1)?.toDoubleOrNull()
        val c = p.getOrNull(2)?.toDoubleOrNull()
        return if (a != null && b != null && c != null) Reading.available(Triple(a, b, c), "/proc/loadavg")
        else Reading.unavailable("Unparseable /proc/loadavg")
    }

    /**
     * Groups cores into clusters by their max frequency — this is how big.LITTLE
     * topology is exposed to unprivileged apps.
     */
    fun clusterTopology(): List<Pair<Long, List<Int>>> {
        val cores = readCores()
        return cores.filter { it.maxKHz != null }
            .groupBy { it.maxKHz!! }
            .toSortedMap()
            .map { (freq, list) -> freq to list.map { it.id } }
            .reversed()
    }

    private fun readCpuInfo(): Map<String, String> {
        val lines = SysFs.readLines("/proc/cpuinfo") ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (line in lines) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty() && !map.containsKey(key)) map[key] = value
        }
        return map
    }

    /** Static CPU/SoC description. */
    fun staticInfo(): List<InfoSection> {
        val info = readCpuInfo()
        val clusters = clusterTopology()

        val socName = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                Build.SOC_MODEL != Build.UNKNOWN && Build.SOC_MODEL.isNotBlank() ->
                "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
            info["Hardware"] != null -> info["Hardware"]
            else -> null
        }

        val identity = listOf(
            InfoItem.of("SoC", socName),
            InfoItem.of("SoC manufacturer",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER.takeIf { it != Build.UNKNOWN } else null),
            InfoItem.of("Hardware", info["Hardware"]),
            InfoItem.of("Processor", info["Processor"] ?: info["model name"]),
            InfoItem.of("Implementer", info["CPU implementer"]?.let { decodeImplementer(it) }),
            InfoItem.of("Part", info["CPU part"]?.let { decodePart(it) }),
            InfoItem.of("Architecture", info["CPU architecture"] ?: System.getProperty("os.arch")),
            InfoItem.of("Revision", info["CPU revision"]),
            InfoItem.of("Variant", info["CPU variant"]),
            InfoItem.of("Board", Build.BOARD),
            InfoItem.of("Primary ABI", Build.SUPPORTED_ABIS.firstOrNull()),
            InfoItem.of("Supported ABIs", Build.SUPPORTED_ABIS.joinToString(", ")),
            InfoItem.of("32-bit ABIs", Build.SUPPORTED_32_BIT_ABIS.joinToString(", ").ifBlank { null }),
            InfoItem.of("64-bit ABIs", Build.SUPPORTED_64_BIT_ABIS.joinToString(", ").ifBlank { null }),
            InfoItem.of("Instruction features", info["Features"] ?: info["flags"])
        )

        val topology = buildList {
            add(InfoItem.of("Logical cores (runtime)", coreCount.toString()))
            add(InfoItem.of("Cores present", SysFs.readFirstLine("$CPUFREQ_BASE/present")))
            add(InfoItem.of("Cores possible", SysFs.readFirstLine("$CPUFREQ_BASE/possible")))
            add(InfoItem.of("Cores online", SysFs.readFirstLine("$CPUFREQ_BASE/online")))
            if (clusters.isEmpty()) {
                add(InfoItem(
                    "Cluster topology",
                    Reading.unavailable("cpufreq nodes not readable on this device")
                ))
            } else {
                clusters.forEachIndexed { index, (freq, ids) ->
                    val kind = when {
                        clusters.size == 1 -> "Uniform"
                        index == 0 -> "Big / Prime"
                        index == clusters.lastIndex -> "Little / Efficiency"
                        else -> "Mid"
                    }
                    add(InfoItem.of(
                        "Cluster ${index + 1} ($kind)",
                        "${ids.size} cores @ ${Fmt.freqKHz(freq)} — CPU ${ids.joinToString(",")}"
                    ))
                }
            }
        }

        val scaling = buildList {
            val governors = readCores().mapNotNull { it.governor }.distinct()
            add(InfoItem.of("Governors in use", governors.joinToString(", ").ifBlank { null }))
            add(InfoItem.of(
                "Available governors",
                SysFs.readFirstLine("$CPUFREQ_BASE/cpu0/cpufreq/scaling_available_governors")
            ))
            add(InfoItem.of(
                "Available frequencies (CPU0)",
                SysFs.readFirstLine("$CPUFREQ_BASE/cpu0/cpufreq/scaling_available_frequencies")
                    ?.split(" ")?.mapNotNull { it.toLongOrNull() }?.joinToString(", ") { Fmt.freqKHz(it) }
            ))
            add(InfoItem.of("Driver (CPU0)", SysFs.readFirstLine("$CPUFREQ_BASE/cpu0/cpufreq/scaling_driver")))
        }

        return listOf(
            InfoSection("Processor identity", identity),
            InfoSection("Topology", topology),
            InfoSection("Frequency scaling", scaling,
                note = "Values are read from /sys/devices/system/cpu. Some vendors restrict these nodes; unreadable entries show as Unavailable.")
        )
    }

    /** Best-effort CPU temperature from a thermal zone whose type mentions cpu/soc. */
    fun cpuTemperature(): Reading<Double> {
        val zones = SysFs.listDir("/sys/class/thermal") { it.startsWith("thermal_zone") }
        for (zone in zones) {
            val type = SysFs.readFirstLine(File(zone, "type").absolutePath)?.lowercase() ?: continue
            if (type.contains("cpu") || type.contains("soc") || type.contains("tsens") || type.contains("apc")) {
                val raw = SysFs.readLong(File(zone, "temp").absolutePath) ?: continue
                val celsius = normaliseTemp(raw)
                if (celsius in -40.0..150.0) {
                    return Reading.available(celsius, "${zone.absolutePath}/temp ($type)")
                }
            }
        }
        return Reading.unavailable("No readable CPU thermal zone")
    }

    /** Thermal nodes report milli-, deci- or plain Celsius depending on the vendor. */
    private fun normaliseTemp(raw: Long): Double = when {
        raw > 10_000 -> raw / 1000.0
        raw > 1_000 -> raw / 100.0
        raw > 200 -> raw / 10.0
        else -> raw.toDouble()
    }

    private fun decodeImplementer(hex: String): String = when (hex.lowercase()) {
        "0x41" -> "ARM ($hex)"
        "0x51" -> "Qualcomm ($hex)"
        "0x53" -> "Samsung ($hex)"
        "0x4e" -> "NVIDIA ($hex)"
        "0x69" -> "Intel ($hex)"
        "0x61" -> "Apple ($hex)"
        else -> hex
    }

    private fun decodePart(hex: String): String = when (hex.lowercase()) {
        "0xd03" -> "Cortex-A53 ($hex)"
        "0xd04" -> "Cortex-A35 ($hex)"
        "0xd05" -> "Cortex-A55 ($hex)"
        "0xd07" -> "Cortex-A57 ($hex)"
        "0xd08" -> "Cortex-A72 ($hex)"
        "0xd09" -> "Cortex-A73 ($hex)"
        "0xd0a" -> "Cortex-A75 ($hex)"
        "0xd0b" -> "Cortex-A76 ($hex)"
        "0xd0d" -> "Cortex-A77 ($hex)"
        "0xd41" -> "Cortex-A78 ($hex)"
        "0xd44" -> "Cortex-X1 ($hex)"
        "0xd46" -> "Cortex-A510 ($hex)"
        "0xd47" -> "Cortex-A710 ($hex)"
        "0xd48" -> "Cortex-X2 ($hex)"
        "0xd4d" -> "Cortex-A715 ($hex)"
        "0xd4e" -> "Cortex-X3 ($hex)"
        else -> hex
    }
}
