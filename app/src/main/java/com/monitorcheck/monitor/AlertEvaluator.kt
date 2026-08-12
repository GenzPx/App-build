package com.monitorcheck.monitor

import com.monitorcheck.core.AppSettings
import com.monitorcheck.storage.StorageRepository

data class AlertCandidate(val key: String, val title: String, val detail: String, val value: Double)
class AlertEvaluator(private val storageRepository: StorageRepository) {
    fun evaluate(settings: AppSettings, sample: MonitorSample): List<AlertCandidate> {
        if (!settings.alertsEnabled) return emptyList()
        val out = ArrayList<AlertCandidate>()
        sample.cpu?.totalPercent?.value?.let { v -> if (settings.cpuAlertEnabled && v >= settings.cpuAlertThresholdPercent) out.add(AlertCandidate("cpu","High CPU usage","CPU is ${"%.0f".format(java.util.Locale.US,v)}% (threshold ${settings.cpuAlertThresholdPercent.toInt()}%)",v)) }
        sample.memory.value?.let { m -> if (settings.ramAlertEnabled && m.usedPercent >= settings.ramAlertThresholdPercent) out.add(AlertCandidate("ram","High RAM usage","RAM is ${"%.0f".format(java.util.Locale.US,m.usedPercent)}% (threshold ${settings.ramAlertThresholdPercent.toInt()}%)",m.usedPercent)) }
        sample.cpuTemperatureCelsius.value?.let { v -> if (settings.cpuTempAlertEnabled && v >= settings.cpuTempAlertThresholdC) out.add(AlertCandidate("cpu_temp","High CPU temperature","CPU thermal zone is ${"%.1f".format(java.util.Locale.US,v)}°C (threshold ${settings.cpuTempAlertThresholdC}°C)",v)) }
        sample.hottestCelsius.value?.let { v -> if (settings.deviceTempAlertEnabled && v >= settings.deviceTempAlertThresholdC) out.add(AlertCandidate("device_temp","High device temperature","Hottest zone is ${"%.1f".format(java.util.Locale.US,v)}°C (threshold ${settings.deviceTempAlertThresholdC}°C)",v)) }
        sample.batteryCelsius.value?.let { v -> if (settings.batteryTempAlertEnabled && v >= settings.batteryTempAlertThresholdC) out.add(AlertCandidate("battery_temp","High battery temperature","Battery is ${"%.1f".format(java.util.Locale.US,v)}°C (threshold ${settings.batteryTempAlertThresholdC}°C)",v)) }
        sample.battery.value?.let { b -> if (settings.batteryLowAlertEnabled && b.levelPercent <= settings.batteryLowAlertThresholdPercent && !b.isCharging) out.add(AlertCandidate("battery_low","Battery is low","Battery is ${b.levelPercent}% and not charging",b.levelPercent.toDouble())) }
        storageRepository.primaryTotals().value?.let { (total,free) -> if (total > 0) { val pct=free*100.0/total; if (settings.storageAlertEnabled && pct <= settings.storageFreeAlertThresholdPercent) out.add(AlertCandidate("storage","Storage space is low","Only ${"%.1f".format(java.util.Locale.US,pct)}% free",pct)) } }
        return out
    }
}
