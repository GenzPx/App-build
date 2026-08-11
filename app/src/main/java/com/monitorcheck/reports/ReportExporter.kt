package com.monitorcheck.reports

import android.content.Context
import android.os.Build
import androidx.core.content.FileProvider
import com.monitorcheck.core.Fmt
import com.monitorcheck.core.InfoSection
import com.monitorcheck.core.Permissions
import com.monitorcheck.data.battery.BatteryRepository
import com.monitorcheck.hardware.cpu.CpuRepository
import com.monitorcheck.hardware.display.DisplayRepository
import com.monitorcheck.hardware.gpu.GpuRepository
import com.monitorcheck.hardware.memory.MemoryRepository
import com.monitorcheck.hardware.sensor.SensorRepository
import com.monitorcheck.hardware.thermal.ThermalRepository
import com.monitorcheck.network.NetworkRepository
import com.monitorcheck.security.PermissionInspector
import com.monitorcheck.storage.StorageRepository
import com.monitorcheck.system.DriverRepository
import com.monitorcheck.system.SystemRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Builds the full plain-text diagnostic report.
 *
 * The writer only prints what the repositories actually returned. Any value the
 * platform withheld is written as its real status ("Unavailable", "Restricted by
 * Android", ...), never as a plausible-looking number.
 */
class ReportExporter(private val context: Context) {

    private val width = 78

    suspend fun buildReport(onProgress: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder(64 * 1024)
        val now = System.currentTimeMillis()

        header(sb, now)

        onProgress("Device")
        val system = SystemRepository(context)
        system.deviceSections().forEach { section(sb, it) }

        onProgress("Kernel")
        system.kernelSections().forEach { section(sb, it) }
        section(sb, system.selinuxSection())
        section(sb, system.integritySection())

        onProgress("CPU")
        val cpu = CpuRepository()
        cpu.staticInfo().forEach { section(sb, it) }
        // Two samples are required for a real utilisation delta.
        cpu.sampleUsage()
        delay(1000)
        cpuRuntime(sb, cpu)

        onProgress("GPU")
        section(sb, GpuRepository(context).infoSections().let { it[0] })
        GpuRepository(context).infoSections().drop(1).take(2).forEach { section(sb, it) }

        onProgress("Memory")
        MemoryRepository(context).infoSections().forEach { section(sb, it) }

        onProgress("Storage")
        StorageRepository(context).infoSections().forEach { section(sb, it) }

        onProgress("Battery")
        BatteryRepository(context).infoSections().forEach { section(sb, it) }

        onProgress("Thermal")
        thermal(sb)

        onProgress("Display")
        DisplayRepository(context).infoSections().forEach { section(sb, it) }

        onProgress("Sensors")
        sensors(sb)

        onProgress("Network")
        network(sb)

        onProgress("Drivers")
        DriverRepository(context).sections().forEach { section(sb, it) }

        onProgress("Binder")
        section(sb, system.binderSection())

        onProgress("Applications")
        applications(sb)

        onProgress("Permissions")
        permissions(sb)

        onProgress("Monitoring snapshot")
        monitoringSnapshot(sb)

        footer(sb)
        sb.toString()
    }

    private fun header(sb: StringBuilder, now: Long) {
        sb.appendLine("=".repeat(width))
        sb.appendLine(center("MONITORED CHECK — SYSTEM REPORT"))
        sb.appendLine("=".repeat(width))
        sb.appendLine()
        sb.appendLine("Generated:      ${Fmt.timestamp(now)}")
        sb.appendLine("Device:         ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android:        ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("App version:    ${appVersion()}")
        sb.appendLine()
        sb.appendLine(wrap(
            "This report contains only values that were actually read from Android APIs or " +
                "kernel interfaces on this device. Where the platform does not expose a value " +
                "to third-party applications, the real status is printed instead " +
                "(Unavailable, Unsupported, Permission Required, Restricted by Android, " +
                "Requires Root, Hardware Not Supported). No value in this report is estimated, " +
                "simulated or randomly generated."
        ))
        sb.appendLine()
    }

    private fun footer(sb: StringBuilder) {
        sb.appendLine()
        sb.appendLine("=".repeat(width))
        sb.appendLine(center("END OF REPORT"))
        sb.appendLine("=".repeat(width))
        sb.appendLine()
        sb.appendLine("Legend of availability states:")
        sb.appendLine("  Available            value read successfully from a real source")
        sb.appendLine("  Limited              value is real but scope-restricted by the platform")
        sb.appendLine("  Unavailable          the source exists but returned nothing on this device")
        sb.appendLine("  Unsupported          no API or hardware path exists for this value")
        sb.appendLine("  Permission Required  a runtime or special permission must be granted")
        sb.appendLine("  Restricted by Android  blocked by platform security policy")
        sb.appendLine("  Requires Root        only readable on a rooted device (not used by this app)")
        sb.appendLine("  Hardware Not Supported  the device has no such hardware")
        sb.appendLine()
        sb.appendLine("Monitored Check performs all analysis locally. This report was generated")
        sb.appendLine("on-device and has not been transmitted anywhere.")
    }

    private fun section(sb: StringBuilder, s: InfoSection) {
        sb.appendLine()
        sb.appendLine("-".repeat(width))
        sb.appendLine("  ${s.title.uppercase()}")
        sb.appendLine("-".repeat(width))
        if (s.items.isEmpty()) {
            sb.appendLine("  (no entries)")
        }
        for (item in s.items) {
            val value = item.text
            if (value.contains("\n")) {
                sb.appendLine("  ${item.label}:")
                value.lines().forEach { sb.appendLine("      $it") }
            } else {
                sb.appendLine("  ${pad(item.label, 30)} $value")
            }
        }
        s.note?.let {
            sb.appendLine()
            sb.appendLine(wrap("Note: $it", indent = "  "))
        }
    }

    private fun cpuRuntime(sb: StringBuilder, cpu: CpuRepository) {
        val usage = cpu.sampleUsage()
        sb.appendLine()
        sb.appendLine("-".repeat(width))
        sb.appendLine("  CPU RUNTIME")
        sb.appendLine("-".repeat(width))
        sb.appendLine("  ${pad("Total utilisation", 30)} ${usage.totalPercent.display { Fmt.percent(it) }}")
        sb.appendLine("  ${pad("Load average", 30)} ${
            usage.loadAverage.display { "${it.first} / ${it.second} / ${it.third}" }}")
        sb.appendLine("  ${pad("CPU temperature", 30)} ${
            cpu.cpuTemperature().display { Fmt.temperature(it) }}")
        sb.appendLine()
        sb.appendLine("  Per-core state:")
        usage.cores.forEach { c ->
            sb.appendLine("    CPU${pad(c.id.toString(), 3)} " +
                "${pad(c.currentKHz?.let { Fmt.freqKHz(it) } ?: "Unavailable", 12)} " +
                "${pad(c.usagePercent?.let { Fmt.percent(it) } ?: "Unavailable", 10)} " +
                "min ${pad(c.minKHz?.let { Fmt.freqKHz(it) } ?: "n/a", 10)} " +
                "max ${pad(c.maxKHz?.let { Fmt.freqKHz(it) } ?: "n/a", 10)} " +
                "gov ${c.governor ?: "n/a"}${if (!c.online) "  [offline]" else ""}")
        }
    }

    private fun thermal(sb: StringBuilder) {
        val repo = ThermalRepository(context)
        val zones = repo.readZones()
        sb.appendLine()
        sb.appendLine("-".repeat(width))
        sb.appendLine("  THERMAL")
        sb.appendLine("-".repeat(width))
        sb.appendLine("  ${pad("Thermal status (API)", 30)} ${repo.thermalStatus().display()}")
        sb.appendLine()
        if (zones.isAvailable) {
            zones.value!!.forEach { z ->
                sb.appendLine("  ${pad("zone${z.id} ${z.type}", 40)} " +
                    "${pad(Fmt.temperature(z.celsius), 10)} [${z.category.label}]")
            }
        } else {
            sb.appendLine("  Thermal zones: ${zones.status.label}")
            zones.note?.let { sb.appendLine(wrap(it, indent = "    ")) }
        }
    }

    private fun sensors(sb: StringBuilder) {
        val sensors = SensorRepository(context).allSensors()
        sb.appendLine()
        sb.appendLine("-".repeat(width))
        sb.appendLine("  SENSORS")
        sb.appendLine("-".repeat(width))
        if (!sensors.isAvailable) {
            sb.appendLine("  ${sensors.status.label}${sensors.note?.let { " — $it" } ?: ""}")
            return
        }
        val list = sensors.value!!
        sb.appendLine("  Total sensors: ${list.size}")
        sb.appendLine()
        list.forEach { s ->
            sb.appendLine("  ${s.name}")
            sb.appendLine("      type       ${s.typeName} (${s.type})")
            sb.appendLine("      vendor     ${s.vendor}")
            sb.appendLine("      version    ${s.version}")
            sb.appendLine("      range      ${s.maximumRange} ${SensorRepository.unitFor(s.type)}")
            sb.appendLine("      resolution ${s.resolution}")
            sb.appendLine("      power      ${s.power} mA")
            sb.appendLine("      min delay  ${if (s.minDelayUs > 0) "${s.minDelayUs} µs" else "n/a"}" +
                (s.maxRateHz?.let { " (max ${String.format(java.util.Locale.US, "%.1f", it)} Hz)" } ?: ""))
            sb.appendLine("      reporting  ${s.reportingMode}, wake-up: ${Fmt.yesNo(s.isWakeUp)}")
        }
    }

    private fun network(sb: StringBuilder) {
        val repo = NetworkRepository(context)
        section(sb, repo.capabilitiesSection())
        section(sb, repo.linkPropertiesSection())
        section(sb, repo.wifiSection())
        section(sb, repo.mobileSection())
        section(sb, repo.trafficSection())

        val ifaces = repo.interfaces()
        sb.appendLine()
        sb.appendLine("-".repeat(width))
        sb.appendLine("  NETWORK INTERFACES")
        sb.appendLine("-".repeat(width))
        if (ifaces.isAvailable) {
            ifaces.value!!.forEach { i ->
                sb.appendLine("  ${i.name}${if (i.isUp) " [up]" else " [down]"}" +
                    if (i.isLoopback) " [loopback]" else "")
                i.addresses.forEach { sb.appendLine("      addr  $it") }
                sb.appendLine("      mtu   ${if (i.mtu > 0) i.mtu.toString() else "Unavailable"}")
                sb.appendLine("      mac   ${i.hardwareAddress ?: "Restricted by Android"}")
                sb.appendLine("      rx    ${i.rxBytes?.let { Fmt.bytes(it) } ?: "Unavailable"}")
                sb.appendLine("      tx    ${i.txBytes?.let { Fmt.bytes(it) } ?: "Unavailable"}")
            }
        } else {
            sb.appendLine("  ${ifaces.status.label}")
        }
    }

    private suspend fun applications(sb: StringBuilder) {
        val repo = com.monitorcheck.apps.AppRepository(context)
        val apps = repo.loadApps()
        sb.appendLine()
        sb.appendLine("-".repeat(width))
        sb.appendLine("  APPLICATIONS")
        sb.appendLine("-".repeat(width))
        sb.appendLine("  ${pad("Total installed", 30)} ${apps.size}")
        sb.appendLine("  ${pad("User apps", 30)} ${apps.count { !it.isSystem }}")
        sb.appendLine("  ${pad("System apps", 30)} ${apps.count { it.isSystem }}")
        sb.appendLine("  ${pad("Disabled apps", 30)} ${apps.count { !it.isEnabled }}")
        sb.appendLine("  ${pad("Size data", 30)} ${repo.storageStatsAvailability().display()}")
        sb.appendLine()
        sb.appendLine("  Largest user apps:")
        apps.filter { !it.isSystem }
            .sortedByDescending { it.totalSizeBytes ?: it.apkSizeBytes }
            .take(25)
            .forEach { a ->
                sb.appendLine("    ${pad(a.label.take(28), 30)} " +
                    "${pad(Fmt.bytes(a.totalSizeBytes ?: a.apkSizeBytes), 12)} " +
                    "${a.packageName} (target SDK ${a.targetSdk})")
            }
    }

    private suspend fun permissions(sb: StringBuilder) {
        val inspector = PermissionInspector(context)
        sb.appendLine()
        sb.appendLine("-".repeat(width))
        sb.appendLine("  PERMISSIONS")
        sb.appendLine("-".repeat(width))
        sb.appendLine("  Usage history: ${inspector.usageHistoryAvailability().status.label}")
        inspector.usageHistoryAvailability().note?.let { sb.appendLine(wrap(it, indent = "    ")) }
        sb.appendLine()
        sb.appendLine("  Monitored Check's own permissions:")
        inspector.ownPermissions().sortedBy { it.shortName }.forEach { p ->
            val state = if (p.status == com.monitorcheck.core.DataStatus.AVAILABLE) "Granted" else "Not granted"
            sb.appendLine("    ${pad(p.shortName, 40)} ${pad(state, 14)} [${p.protectionLevel}]")
        }
        sb.appendLine()
        sb.appendLine("  Special access:")
        sb.appendLine("    ${pad("Usage Access", 40)} ${
            if (Permissions.hasUsageStats(context)) "Granted" else "Not granted"}")
        sb.appendLine("    ${pad("All files access", 40)} ${
            if (Permissions.hasAllFilesAccess()) "Granted" else "Not granted"}")
        sb.appendLine("    ${pad("Notifications", 40)} ${
            if (Permissions.hasNotifications(context)) "Granted" else "Not granted"}")
        sb.appendLine("    ${pad("Location (for Wi-Fi SSID)", 40)} ${
            if (Permissions.hasLocation(context)) "Granted" else "Not granted"}")
    }

    private fun monitoringSnapshot(sb: StringBuilder) {
        val mem = MemoryRepository(context).snapshot()
        val bat = BatteryRepository(context).snapshot()
        val net = NetworkRepository(context).sampleThroughput()
        val thermal = ThermalRepository(context).hottestZone()

        sb.appendLine()
        sb.appendLine("-".repeat(width))
        sb.appendLine("  MONITORING SNAPSHOT")
        sb.appendLine("-".repeat(width))
        sb.appendLine("  Captured at ${Fmt.timestamp(System.currentTimeMillis())}")
        sb.appendLine()
        sb.appendLine("  ${pad("RAM used", 30)} ${mem.display {
            "${Fmt.bytes(it.usedBytes)} of ${Fmt.bytes(it.totalBytes)} (${Fmt.percent(it.usedPercent)})" }}")
        sb.appendLine("  ${pad("Battery level", 30)} ${bat.display { "${it.levelPercent}% (${it.status})" }}")
        sb.appendLine("  ${pad("Battery temperature", 30)} ${
            bat.value?.temperatureCelsius?.let { Fmt.temperature(it) } ?: "Unavailable"}")
        sb.appendLine("  ${pad("Hottest thermal zone", 30)} ${
            thermal.display { "${it.type} ${Fmt.temperature(it.celsius)}" }}")
        sb.appendLine("  ${pad("Total received", 30)} ${
            net?.let { Fmt.bytes(it.rxBytes) } ?: "Unavailable"}")
        sb.appendLine("  ${pad("Total transmitted", 30)} ${
            net?.let { Fmt.bytes(it.txBytes) } ?: "Unavailable"}")
        sb.appendLine("  ${pad("Uptime", 30)} ${Fmt.duration(android.os.SystemClock.elapsedRealtime())}")
        sb.appendLine()
        sb.appendLine(wrap(
            "Network transfer rates are omitted from this snapshot because a rate requires two " +
                "samples over time; open the Network monitor for live throughput.", indent = "  "))
    }

    /** Writes the report into app-private storage and returns the file. */
    suspend fun writeToFile(content: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "reports").apply { mkdirs() }
        val name = "MonitoredCheck_report_${
            java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
        }.txt"
        val file = File(dir, name)
        file.writeText(content)
        file
    }

    /** Content URI for sharing, exposed through the app's FileProvider. */
    fun shareUri(file: File) = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )

    private fun appVersion(): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode
        })"
    } catch (_: Throwable) { "unknown" }

    private fun pad(s: String, n: Int): String =
        if (s.length >= n) s.take(n) else s + " ".repeat(n - s.length)

    private fun center(s: String): String {
        val padding = ((width - s.length) / 2).coerceAtLeast(0)
        return " ".repeat(padding) + s
    }

    private fun wrap(text: String, indent: String = ""): String {
        val max = width - indent.length
        val out = StringBuilder()
        var line = StringBuilder()
        for (word in text.split(" ")) {
            if (line.length + word.length + 1 > max) {
                out.appendLine(indent + line.toString().trim())
                line = StringBuilder()
            }
            line.append(word).append(' ')
        }
        if (line.isNotBlank()) out.append(indent).append(line.toString().trim())
        return out.toString()
    }
}
