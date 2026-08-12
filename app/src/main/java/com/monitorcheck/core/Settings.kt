package com.monitorcheck.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "monitored_check_settings")

enum class ThemeMode { AUTO, LIGHT, DARK }
enum class NotificationStyle { MINIMAL, NORMAL, DETAILED }
enum class MonitoringProfile { NORMAL, GAMING, LOW_RESOURCE }

data class AppSettings(
    val refreshIntervalMs: Long = 2_000L,
    val themeMode: ThemeMode = ThemeMode.AUTO,
    val dynamicColor: Boolean = true,
    val lowResourceMode: Boolean = false,
    val autoMonitorOnLaunch: Boolean = true,
    val backgroundMonitoring: Boolean = false,
    val notificationStyle: NotificationStyle = NotificationStyle.NORMAL,
    val notifyCpu: Boolean = true,
    val notifyRam: Boolean = true,
    val notifyBattery: Boolean = true,
    val notifyNetwork: Boolean = true,
    val notifyTemperature: Boolean = false,
    val batteryHistoryEnabled: Boolean = true,
    val dashboardWidgets: List<String> = DashboardWidget.defaultOrder(),
    val graphPointCount: Int = 60,
    val alertsEnabled: Boolean = true,
    val cpuAlertEnabled: Boolean = true,
    val cpuAlertThresholdPercent: Float = 90f,
    val ramAlertEnabled: Boolean = true,
    val ramAlertThresholdPercent: Float = 80f,
    val deviceTempAlertEnabled: Boolean = true,
    val deviceTempAlertThresholdC: Float = 45f,
    val cpuTempAlertEnabled: Boolean = true,
    val cpuTempAlertThresholdC: Float = 40f,
    val batteryTempAlertEnabled: Boolean = true,
    val batteryTempAlertThresholdC: Float = 42f,
    val storageAlertEnabled: Boolean = true,
    val storageFreeAlertThresholdPercent: Float = 10f,
    val batteryLowAlertEnabled: Boolean = true,
    val batteryLowAlertThresholdPercent: Float = 15f,
    val alertCooldownMs: Long = 15 * 60_000L,
    val monitoringProfile: MonitoringProfile = MonitoringProfile.NORMAL,
    val overlayEnabled: Boolean = false,
    val historyRetentionDays: Int = 30
)

enum class DashboardWidget(val id: String, val title: String) {
    CPU_USAGE("cpu_usage", "CPU usage"), CPU_FREQ("cpu_freq", "CPU frequency"), GPU("gpu", "GPU"),
    RAM("ram", "Memory"), STORAGE("storage", "Storage"), BATTERY("battery", "Battery"),
    BATTERY_TEMP("battery_temp", "Battery temperature"), DEVICE_TEMP("device_temp", "Device temperature"),
    NETWORK("network", "Network speed"), FPS("fps", "Display / FPS"), PROCESSES("processes", "Processes & apps");
    companion object {
        fun defaultOrder(): List<String> = entries.map { it.id }
        fun fromId(id: String): DashboardWidget? = entries.firstOrNull { it.id == id }
    }
}

class SettingsRepository(private val context: Context) {
    private object Keys {
        val REFRESH = intPreferencesKey("refresh_interval_ms"); val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color"); val LOW_RES = booleanPreferencesKey("low_resource_mode")
        val AUTO_MONITOR = booleanPreferencesKey("auto_monitor"); val BG_MONITOR = booleanPreferencesKey("background_monitoring")
        val NOTIF_STYLE = stringPreferencesKey("notification_style"); val N_CPU = booleanPreferencesKey("notify_cpu")
        val N_RAM = booleanPreferencesKey("notify_ram"); val N_BAT = booleanPreferencesKey("notify_battery")
        val N_NET = booleanPreferencesKey("notify_network"); val N_TEMP = booleanPreferencesKey("notify_temp")
        val BAT_HISTORY = booleanPreferencesKey("battery_history"); val WIDGETS = stringPreferencesKey("dashboard_widgets")
        val GRAPH_POINTS = intPreferencesKey("graph_points")
        val ALERTS = booleanPreferencesKey("alerts_enabled"); val ALERT_CPU = booleanPreferencesKey("alert_cpu_enabled")
        val ALERT_CPU_THRESHOLD = floatPreferencesKey("alert_cpu_threshold"); val ALERT_RAM = booleanPreferencesKey("alert_ram_enabled")
        val ALERT_RAM_THRESHOLD = floatPreferencesKey("alert_ram_threshold"); val ALERT_DEVICE_TEMP = booleanPreferencesKey("alert_device_temp_enabled")
        val ALERT_DEVICE_TEMP_THRESHOLD = floatPreferencesKey("alert_device_temp_threshold"); val ALERT_CPU_TEMP = booleanPreferencesKey("alert_cpu_temp_enabled")
        val ALERT_CPU_TEMP_THRESHOLD = floatPreferencesKey("alert_cpu_temp_threshold"); val ALERT_BAT_TEMP = booleanPreferencesKey("alert_battery_temp_enabled")
        val ALERT_BAT_TEMP_THRESHOLD = floatPreferencesKey("alert_battery_temp_threshold"); val ALERT_STORAGE = booleanPreferencesKey("alert_storage_enabled")
        val ALERT_STORAGE_THRESHOLD = floatPreferencesKey("alert_storage_threshold"); val ALERT_BAT_LOW = booleanPreferencesKey("alert_battery_low_enabled")
        val ALERT_BAT_LOW_THRESHOLD = floatPreferencesKey("alert_battery_low_threshold"); val ALERT_COOLDOWN = longPreferencesKey("alert_cooldown_ms")
        val PROFILE = stringPreferencesKey("monitoring_profile"); val OVERLAY = booleanPreferencesKey("overlay_enabled")
        val RETENTION = intPreferencesKey("history_retention_days")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            refreshIntervalMs = (p[Keys.REFRESH] ?: 2000).toLong(),
            themeMode = runCatching { ThemeMode.valueOf(p[Keys.THEME] ?: "AUTO") }.getOrDefault(ThemeMode.AUTO),
            dynamicColor = p[Keys.DYNAMIC] ?: true, lowResourceMode = p[Keys.LOW_RES] ?: false,
            autoMonitorOnLaunch = p[Keys.AUTO_MONITOR] ?: true, backgroundMonitoring = p[Keys.BG_MONITOR] ?: false,
            notificationStyle = runCatching { NotificationStyle.valueOf(p[Keys.NOTIF_STYLE] ?: "NORMAL") }.getOrDefault(NotificationStyle.NORMAL),
            notifyCpu = p[Keys.N_CPU] ?: true, notifyRam = p[Keys.N_RAM] ?: true, notifyBattery = p[Keys.N_BAT] ?: true,
            notifyNetwork = p[Keys.N_NET] ?: true, notifyTemperature = p[Keys.N_TEMP] ?: false,
            batteryHistoryEnabled = p[Keys.BAT_HISTORY] ?: true,
            dashboardWidgets = p[Keys.WIDGETS]?.split(",")?.filter { it.isNotBlank() } ?: DashboardWidget.defaultOrder(),
            graphPointCount = p[Keys.GRAPH_POINTS] ?: 60,
            alertsEnabled = p[Keys.ALERTS] ?: true, cpuAlertEnabled = p[Keys.ALERT_CPU] ?: true,
            cpuAlertThresholdPercent = p[Keys.ALERT_CPU_THRESHOLD] ?: 90f, ramAlertEnabled = p[Keys.ALERT_RAM] ?: true,
            ramAlertThresholdPercent = p[Keys.ALERT_RAM_THRESHOLD] ?: 80f,
            deviceTempAlertEnabled = p[Keys.ALERT_DEVICE_TEMP] ?: true,
            deviceTempAlertThresholdC = p[Keys.ALERT_DEVICE_TEMP_THRESHOLD] ?: 45f,
            cpuTempAlertEnabled = p[Keys.ALERT_CPU_TEMP] ?: true, cpuTempAlertThresholdC = p[Keys.ALERT_CPU_TEMP_THRESHOLD] ?: 40f,
            batteryTempAlertEnabled = p[Keys.ALERT_BAT_TEMP] ?: true, batteryTempAlertThresholdC = p[Keys.ALERT_BAT_TEMP_THRESHOLD] ?: 42f,
            storageAlertEnabled = p[Keys.ALERT_STORAGE] ?: true, storageFreeAlertThresholdPercent = p[Keys.ALERT_STORAGE_THRESHOLD] ?: 10f,
            batteryLowAlertEnabled = p[Keys.ALERT_BAT_LOW] ?: true, batteryLowAlertThresholdPercent = p[Keys.ALERT_BAT_LOW_THRESHOLD] ?: 15f,
            alertCooldownMs = p[Keys.ALERT_COOLDOWN] ?: 15 * 60_000L,
            monitoringProfile = runCatching { MonitoringProfile.valueOf(p[Keys.PROFILE] ?: MonitoringProfile.NORMAL.name) }.getOrDefault(MonitoringProfile.NORMAL),
            overlayEnabled = p[Keys.OVERLAY] ?: false, historyRetentionDays = p[Keys.RETENTION] ?: 30
        )
    }

    suspend fun setRefreshInterval(ms: Long) = edit { it[Keys.REFRESH] = ms.coerceAtLeast(500L).toInt() }
    suspend fun setTheme(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC] = enabled }
    suspend fun setLowResourceMode(enabled: Boolean) = edit { it[Keys.LOW_RES] = enabled }
    suspend fun setAutoMonitor(enabled: Boolean) = edit { it[Keys.AUTO_MONITOR] = enabled }
    suspend fun setBackgroundMonitoring(enabled: Boolean) = edit { it[Keys.BG_MONITOR] = enabled }
    suspend fun setNotificationStyle(style: NotificationStyle) = edit { it[Keys.NOTIF_STYLE] = style.name }
    suspend fun setNotifyCpu(v: Boolean) = edit { it[Keys.N_CPU] = v }; suspend fun setNotifyRam(v: Boolean) = edit { it[Keys.N_RAM] = v }
    suspend fun setNotifyBattery(v: Boolean) = edit { it[Keys.N_BAT] = v }; suspend fun setNotifyNetwork(v: Boolean) = edit { it[Keys.N_NET] = v }
    suspend fun setNotifyTemperature(v: Boolean) = edit { it[Keys.N_TEMP] = v }; suspend fun setBatteryHistory(v: Boolean) = edit { it[Keys.BAT_HISTORY] = v }
    suspend fun setWidgets(ids: List<String>) = edit { it[Keys.WIDGETS] = ids.joinToString(",") }
    suspend fun setGraphPoints(count: Int) = edit { it[Keys.GRAPH_POINTS] = count.coerceIn(30, 300) }
    suspend fun setAlertsEnabled(v: Boolean) = edit { it[Keys.ALERTS] = v }
    suspend fun setCpuAlertEnabled(v: Boolean) = edit { it[Keys.ALERT_CPU] = v }; suspend fun setCpuAlertThreshold(v: Float) = edit { it[Keys.ALERT_CPU_THRESHOLD] = v.coerceIn(1f, 100f) }
    suspend fun setRamAlertEnabled(v: Boolean) = edit { it[Keys.ALERT_RAM] = v }; suspend fun setRamAlertThreshold(v: Float) = edit { it[Keys.ALERT_RAM_THRESHOLD] = v.coerceIn(1f, 100f) }
    suspend fun setDeviceTempAlertEnabled(v: Boolean) = edit { it[Keys.ALERT_DEVICE_TEMP] = v }; suspend fun setDeviceTempAlertThreshold(v: Float) = edit { it[Keys.ALERT_DEVICE_TEMP_THRESHOLD] = v.coerceIn(20f, 100f) }
    suspend fun setCpuTempAlertEnabled(v: Boolean) = edit { it[Keys.ALERT_CPU_TEMP] = v }; suspend fun setCpuTempAlertThreshold(v: Float) = edit { it[Keys.ALERT_CPU_TEMP_THRESHOLD] = v.coerceIn(20f, 100f) }
    suspend fun setBatteryTempAlertEnabled(v: Boolean) = edit { it[Keys.ALERT_BAT_TEMP] = v }; suspend fun setBatteryTempAlertThreshold(v: Float) = edit { it[Keys.ALERT_BAT_TEMP_THRESHOLD] = v.coerceIn(20f, 100f) }
    suspend fun setStorageAlertEnabled(v: Boolean) = edit { it[Keys.ALERT_STORAGE] = v }; suspend fun setStorageAlertThreshold(v: Float) = edit { it[Keys.ALERT_STORAGE_THRESHOLD] = v.coerceIn(1f, 99f) }
    suspend fun setBatteryLowAlertEnabled(v: Boolean) = edit { it[Keys.ALERT_BAT_LOW] = v }; suspend fun setBatteryLowAlertThreshold(v: Float) = edit { it[Keys.ALERT_BAT_LOW_THRESHOLD] = v.coerceIn(1f, 99f) }
    suspend fun setAlertCooldown(ms: Long) = edit { it[Keys.ALERT_COOLDOWN] = ms.coerceIn(60_000L, 24 * 60 * 60_000L) }
    suspend fun setMonitoringProfile(profile: MonitoringProfile) = edit { it[Keys.PROFILE] = profile.name }
    suspend fun setOverlayEnabled(enabled: Boolean) = edit { it[Keys.OVERLAY] = enabled }
    suspend fun setHistoryRetentionDays(days: Int) = edit { it[Keys.RETENTION] = days.coerceIn(7, 90) }

    suspend fun applyProfile(profile: MonitoringProfile) { context.dataStore.edit { p ->
        p[Keys.PROFILE] = profile.name
        when (profile) {
            MonitoringProfile.NORMAL -> { p[Keys.LOW_RES] = false; p[Keys.REFRESH] = 2_000 }
            MonitoringProfile.GAMING -> { p[Keys.LOW_RES] = false; p[Keys.REFRESH] = 1_000 }
            MonitoringProfile.LOW_RESOURCE -> { p[Keys.LOW_RES] = true; p[Keys.REFRESH] = 5_000 }
        }
    } }

    suspend fun toggleWidget(id: String, enabled: Boolean) { context.dataStore.edit { p ->
        val current = p[Keys.WIDGETS]?.split(",")?.filter { it.isNotBlank() }?.toMutableList() ?: DashboardWidget.defaultOrder().toMutableList()
        if (enabled) { if (!current.contains(id)) current.add(id) } else current.remove(id); p[Keys.WIDGETS] = current.joinToString(",")
    } }
    suspend fun moveWidget(id: String, up: Boolean) { context.dataStore.edit { p ->
        val current = p[Keys.WIDGETS]?.split(",")?.filter { it.isNotBlank() }?.toMutableList() ?: DashboardWidget.defaultOrder().toMutableList()
        val idx = current.indexOf(id); if (idx < 0) return@edit; val target = if (up) idx - 1 else idx + 1
        if (target !in current.indices) return@edit; current[idx] = current[target].also { current[target] = current[idx] }; p[Keys.WIDGETS] = current.joinToString(",")
    } }
    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) { context.dataStore.edit(block) }
    companion object {
        val INTERVAL_OPTIONS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
        val ALERT_COOLDOWN_OPTIONS = listOf(5 * 60_000L, 15 * 60_000L, 30 * 60_000L, 60 * 60_000L)
    }
}
