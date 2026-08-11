package com.monitorcheck.data.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.monitorcheck.core.Fmt
import com.monitorcheck.core.InfoItem
import com.monitorcheck.core.InfoSection
import com.monitorcheck.core.Reading
import com.monitorcheck.core.SysFs
import java.util.Locale

data class BatterySnapshot(
    val levelPercent: Int,
    val scale: Int,
    val status: String,
    val isCharging: Boolean,
    val plugged: String,
    val health: String,
    val technology: String?,
    val voltageMv: Int?,
    val temperatureCelsius: Double?,
    val currentNowUa: Int?,
    val currentAverageUa: Int?,
    val chargeCounterUah: Int?,
    val energyCounterNwh: Long?,
    val cycleCount: Int?,
    val capacityDesignUah: Int?,
    val timestamp: Long
) {
    /** Instantaneous power in watts, computed only when both V and I are real readings. */
    val powerWatts: Double?
        get() = if (voltageMv != null && currentNowUa != null)
            (voltageMv / 1000.0) * (currentNowUa / 1_000_000.0) else null
}

/**
 * Battery information.
 *
 * Primary sources are the sticky ACTION_BATTERY_CHANGED broadcast and BatteryManager
 * (both public APIs). Cycle count and design capacity have no public API on most
 * devices/versions, so we try the standard power_supply sysfs nodes and report
 * Unavailable when the kernel does not export them. Nothing is estimated or invented.
 */
class BatteryRepository(private val context: Context) {

    private val batteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    private val powerSupplyBase = "/sys/class/power_supply"

    /** Reads the sticky battery broadcast (no receiver registration required). */
    private fun batteryIntent(): Intent? = try {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    } catch (_: Throwable) {
        null
    }

    fun snapshot(): Reading<BatterySnapshot> {
        val intent = batteryIntent() ?: return Reading.unavailable("Battery broadcast unavailable")

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0) return Reading.unavailable("Battery level not reported")

        val statusRaw = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pluggedRaw = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val healthRaw = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 }
        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)

        return Reading.available(
            BatterySnapshot(
                levelPercent = if (scale > 0) level * 100 / scale else level,
                scale = scale,
                status = statusLabel(statusRaw),
                isCharging = statusRaw == BatteryManager.BATTERY_STATUS_CHARGING ||
                    (statusRaw == BatteryManager.BATTERY_STATUS_FULL && pluggedRaw != 0),
                plugged = pluggedLabel(pluggedRaw),
                health = healthLabel(healthRaw),
                technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
                voltageMv = voltage,
                temperatureCelsius = if (tempTenths != Int.MIN_VALUE && tempTenths > -500)
                    tempTenths / 10.0 else null,
                currentNowUa = intProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
                currentAverageUa = intProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
                chargeCounterUah = intProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
                energyCounterNwh = longProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
                cycleCount = readCycleCount(intent),
                capacityDesignUah = readDesignCapacityUah(),
                timestamp = System.currentTimeMillis()
            ),
            source = "BatteryManager + ACTION_BATTERY_CHANGED"
        )
    }

    private fun intProperty(id: Int): Int? = try {
        val v = batteryManager?.getIntProperty(id)
        // BatteryManager returns Integer.MIN_VALUE when a property is unsupported.
        if (v == null || v == Int.MIN_VALUE) null else v
    } catch (_: Throwable) { null }

    private fun longProperty(id: Int): Long? = try {
        val v = batteryManager?.getLongProperty(id)
        if (v == null || v == Long.MIN_VALUE) null else v
    } catch (_: Throwable) { null }

    /**
     * Cycle count: EXTRA_CYCLE_COUNT exists from Android 14. Older devices sometimes
     * export it via sysfs. If neither works we report Unavailable — never a guess.
     */
    private fun readCycleCount(intent: Intent): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val v = intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
            if (v > 0) return v
        }
        for (name in listOf("battery", "bms")) {
            SysFs.readInt("$powerSupplyBase/$name/cycle_count")?.let { if (it > 0) return it }
        }
        return null
    }

    /** Design capacity in µAh from the standard power_supply class, when exported. */
    private fun readDesignCapacityUah(): Int? {
        for (name in listOf("battery", "bms")) {
            SysFs.readLong("$powerSupplyBase/$name/charge_full_design")?.let {
                if (it > 0) return it.toInt()
            }
        }
        return null
    }

    fun readChargeFullUah(): Int? {
        for (name in listOf("battery", "bms")) {
            SysFs.readLong("$powerSupplyBase/$name/charge_full")?.let { if (it > 0) return it.toInt() }
        }
        return null
    }

    fun temperature(): Reading<Double> {
        val t = snapshot().value?.temperatureCelsius
        return if (t != null) Reading.available(t, "ACTION_BATTERY_CHANGED EXTRA_TEMPERATURE")
        else Reading.unavailable("Battery temperature not reported by this device")
    }

    fun infoSections(): List<InfoSection> {
        val snap = snapshot()
        val s = snap.value
        val chargeFull = readChargeFullUah()
        val design = s?.capacityDesignUah

        val state = listOf(
            InfoItem("Level", snap.map { "${it.levelPercent}%" }),
            InfoItem("Status", snap.map { it.status }),
            InfoItem("Charging", snap.map { Fmt.yesNo(it.isCharging) }),
            InfoItem("Power source", snap.map { it.plugged }),
            InfoItem("Health (reported)", snap.map { it.health }),
            InfoItem("Technology", s?.technology?.let { Reading.available(it, "BatteryManager") }
                ?: Reading.unavailable())
        )

        val electrical = listOf(
            InfoItem("Voltage", s?.voltageMv?.let { Reading.available(Fmt.voltage(it), "EXTRA_VOLTAGE") }
                ?: Reading.unavailable("Not reported")),
            InfoItem("Current now", s?.currentNowUa?.let {
                Reading.available(
                    "${Fmt.currentMa(it)} (${if (it > 0) "charging" else "discharging"})",
                    "BATTERY_PROPERTY_CURRENT_NOW"
                )
            } ?: Reading.unavailable("Device does not expose CURRENT_NOW")),
            InfoItem("Current average", s?.currentAverageUa?.let {
                Reading.available(Fmt.currentMa(it), "BATTERY_PROPERTY_CURRENT_AVERAGE")
            } ?: Reading.unavailable()),
            InfoItem("Power", s?.powerWatts?.let {
                Reading.available(String.format(Locale.US, "%.2f W", it), "Computed from measured V x I")
            } ?: Reading.unavailable("Requires both voltage and current readings")),
            InfoItem("Temperature", s?.temperatureCelsius?.let {
                Reading.available(Fmt.temperature(it), "EXTRA_TEMPERATURE")
            } ?: Reading.unavailable())
        )

        val capacity = listOf(
            InfoItem("Charge counter", s?.chargeCounterUah?.let {
                Reading.available("${it / 1000} mAh", "BATTERY_PROPERTY_CHARGE_COUNTER")
            } ?: Reading.unavailable()),
            InfoItem("Design capacity", design?.let {
                Reading.available("${it / 1000} mAh", "$powerSupplyBase/battery/charge_full_design")
            } ?: Reading.unavailable("Not exported by this kernel")),
            InfoItem("Full charge capacity", chargeFull?.let {
                Reading.available("${it / 1000} mAh", "$powerSupplyBase/battery/charge_full")
            } ?: Reading.unavailable("Not exported by this kernel")),
            InfoItem("Capacity vs design", if (design != null && chargeFull != null && design > 0) {
                Reading.available(
                    Fmt.percent(chargeFull * 100.0 / design),
                    "Computed from kernel-reported charge_full / charge_full_design"
                )
            } else Reading.unavailable("Requires both charge_full and charge_full_design")),
            InfoItem("Cycle count", s?.cycleCount?.let {
                Reading.available(it.toString(), "EXTRA_CYCLE_COUNT / sysfs cycle_count")
            } ?: Reading.unavailable("Cycle count is not exposed on most devices")),
            InfoItem("Energy counter", s?.energyCounterNwh?.let {
                Reading.available("${it / 1_000_000_000L} Wh", "BATTERY_PROPERTY_ENERGY_COUNTER")
            } ?: Reading.unavailable())
        )

        return listOf(
            InfoSection("State", state),
            InfoSection("Electrical", electrical),
            InfoSection(
                "Capacity & wear", capacity,
                note = "Design capacity, full-charge capacity and cycle count come from the kernel " +
                    "power_supply class. Android has no public API for these, so many devices report " +
                    "Unavailable. Monitored Check never estimates battery wear."
            )
        )
    }

    private fun statusLabel(v: Int) = when (v) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> "Unknown"
    }

    private fun pluggedLabel(v: Int) = when (v) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
        4 -> "Dock" // BATTERY_PLUGGED_DOCK, API 33+
        0 -> "Not plugged in"
        else -> "Unknown"
    }

    private fun healthLabel(v: Int) = when (v) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Unspecified failure"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        else -> "Unknown"
    }
}
