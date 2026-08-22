package com.monitorcheck.hardware.thermal

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.monitorcheck.core.Reading
import com.monitorcheck.core.SysFs
import java.io.File

data class ThermalZone(
    val id: Int,
    val type: String,
    val category: ThermalCategory,
    val celsius: Double,
    val path: String
)

enum class ThermalCategory(val label: String) {
    CPU("CPU"), GPU("GPU"), BATTERY("Battery"), SKIN("Skin / Device"),
    SOC("SoC"), MODEM("Modem"), CAMERA("Camera"), DISPLAY("Display"),
    CHARGER("Charger"), OTHER("Other")
}

class ThermalRepository(private val context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var cachedZonePaths: List<Pair<Int, String>>? = null

    private fun zonePaths(): List<Pair<Int, String>> {
        cachedZonePaths?.let { return it }
        val zones = SysFs.listDir("/sys/class/thermal") { it.startsWith("thermal_zone") }
            .mapNotNull { dir ->
                val id = dir.name.removePrefix("thermal_zone").toIntOrNull() ?: return@mapNotNull null
                if (!SysFs.isReadable(File(dir, "temp").absolutePath)) return@mapNotNull null
                id to dir.absolutePath
            }
        cachedZonePaths = zones
        return zones
    }

    fun readZones(): Reading<List<ThermalZone>> {
        val paths = zonePaths()
        if (paths.isEmpty()) {
            return Reading.restricted(
                "No readable entries under /sys/class/thermal. Many devices restrict this to system apps."
            )
        }
        val zones = paths.mapNotNull { (id, path) ->
            val raw = SysFs.readLong("$path/temp") ?: return@mapNotNull null
            val celsius = normalise(raw)
            if (celsius < -40.0 || celsius > 200.0) return@mapNotNull null
            val type = SysFs.readFirstLine("$path/type") ?: "thermal_zone$id"
            ThermalZone(id, type, categorise(type), celsius, path)
        }
        return if (zones.isEmpty()) Reading.unavailable("Thermal zones present but not readable")
        else Reading.available(zones, "/sys/class/thermal")
    }

    fun thermalStatus(): Reading<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Reading.unsupported("PowerManager thermal status requires Android 10 (API 29)")
        }
        val pm = powerManager ?: return Reading.unavailable("PowerManager unavailable")
        return try {
            Reading.available(statusLabel(pm.currentThermalStatus), "PowerManager.getCurrentThermalStatus()")
        } catch (t: Throwable) {
            Reading.error(t.message)
        }
    }

    fun thermalStatusValue(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { powerManager?.currentThermalStatus } catch (_: Throwable) { null }
        } else null

    fun statusLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "None — no throttling"
        PowerManager.THERMAL_STATUS_LIGHT -> "Light throttling"
        PowerManager.THERMAL_STATUS_MODERATE -> "Moderate throttling"
        PowerManager.THERMAL_STATUS_SEVERE -> "Severe throttling"
        PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown imminent"
        else -> "Unknown ($status)"
    }

    fun hottestZone(): Reading<ThermalZone> {
        val zones = readZones()
        val hottest = zones.value?.maxByOrNull { it.celsius }
        return if (hottest != null) Reading.available(hottest, zones.source)
        else Reading(zones.status, null, zones.note, zones.source)
    }

    fun skinTemperature(): Reading<ThermalZone> {
        val zones = readZones().value ?: return Reading.unavailable("No thermal zones")
        val skin = zones.firstOrNull { it.category == ThermalCategory.SKIN }
        return skin?.let { Reading.available(it, it.path) }
            ?: Reading.unavailable("No skin/device thermal zone exported")
    }

    private fun normalise(raw: Long): Double = when {
        raw > 10_000 -> raw / 1000.0
        raw > 1_000 -> raw / 100.0
        raw > 200 -> raw / 10.0
        else -> raw.toDouble()
    }

    private fun categorise(type: String): ThermalCategory {
        val t = type.lowercase()
        return when {
            t.contains("batt") -> ThermalCategory.BATTERY
            t.contains("gpu") || t.contains("kgsl") || t.contains("mali") -> ThermalCategory.GPU
            t.contains("cpu") || t.contains("apc") || t.contains("core") || t.contains("tsens") ||
                t.contains("cluster") || t.contains("little") || t.contains("big") -> ThermalCategory.CPU
            t.contains("skin") || t.contains("shell") || t.contains("case") ||
                t.contains("therm") && t.contains("front") -> ThermalCategory.SKIN
            t.contains("soc") || t.contains("aoss") || t.contains("ddr") -> ThermalCategory.SOC
            t.contains("modem") || t.contains("mdm") || t.contains("rf") || t.contains("pa_") ->
                ThermalCategory.MODEM
            t.contains("cam") -> ThermalCategory.CAMERA
            t.contains("disp") || t.contains("lcd") || t.contains("panel") -> ThermalCategory.DISPLAY
            t.contains("chg") || t.contains("charg") || t.contains("usb") || t.contains("pmic") ->
                ThermalCategory.CHARGER
            else -> ThermalCategory.OTHER
        }
    }
}
