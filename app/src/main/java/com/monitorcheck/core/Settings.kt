package com.monitorcheck.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "monitored_check_settings")

/** Theme options. AUTO follows the system dark-mode setting. */
enum class ThemeMode { AUTO, LIGHT, DARK }

/** Detail level of the optional foreground-service notification. */
enum class NotificationStyle { MINIMAL, NORMAL, DETAILED }

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
    val hapticsEnabled: Boolean = true,
    val dashboardWidgets: List<String> = DashboardWidget.defaultOrder(),
    val graphPointCount: Int = 60
)

/** Dashboard cards the user can toggle and reorder. */
enum class DashboardWidget(val id: String, val title: String) {
    CPU_USAGE("cpu_usage", "CPU usage"),
    CPU_FREQ("cpu_freq", "CPU frequency"),
    GPU("gpu", "GPU"),
    RAM("ram", "Memory"),
    STORAGE("storage", "Storage"),
    BATTERY("battery", "Battery"),
    BATTERY_TEMP("battery_temp", "Battery temperature"),
    DEVICE_TEMP("device_temp", "Device temperature"),
    NETWORK("network", "Network speed"),
    FPS("fps", "Display / FPS"),
    PROCESSES("processes", "Processes & apps");

    companion object {
        fun defaultOrder(): List<String> = entries.map { it.id }
        fun fromId(id: String): DashboardWidget? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Persisted user settings, backed by DataStore Preferences.
 * Everything stays on-device; nothing here is ever transmitted.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val REFRESH = intPreferencesKey("refresh_interval_ms")
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val LOW_RES = booleanPreferencesKey("low_resource_mode")
        val AUTO_MONITOR = booleanPreferencesKey("auto_monitor")
        val BG_MONITOR = booleanPreferencesKey("background_monitoring")
        val NOTIF_STYLE = stringPreferencesKey("notification_style")
        val N_CPU = booleanPreferencesKey("notify_cpu")
        val N_RAM = booleanPreferencesKey("notify_ram")
        val N_BAT = booleanPreferencesKey("notify_battery")
        val N_NET = booleanPreferencesKey("notify_network")
        val N_TEMP = booleanPreferencesKey("notify_temp")
        val BAT_HISTORY = booleanPreferencesKey("battery_history")
        val HAPTICS = booleanPreferencesKey("haptics")
        val WIDGETS = stringPreferencesKey("dashboard_widgets")
        val GRAPH_POINTS = intPreferencesKey("graph_points")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            refreshIntervalMs = (p[Keys.REFRESH] ?: 2000).toLong(),
            themeMode = runCatching { ThemeMode.valueOf(p[Keys.THEME] ?: "AUTO") }.getOrDefault(ThemeMode.AUTO),
            dynamicColor = p[Keys.DYNAMIC] ?: true,
            lowResourceMode = p[Keys.LOW_RES] ?: false,
            autoMonitorOnLaunch = p[Keys.AUTO_MONITOR] ?: true,
            backgroundMonitoring = p[Keys.BG_MONITOR] ?: false,
            notificationStyle = runCatching {
                NotificationStyle.valueOf(p[Keys.NOTIF_STYLE] ?: "NORMAL")
            }.getOrDefault(NotificationStyle.NORMAL),
            notifyCpu = p[Keys.N_CPU] ?: true,
            notifyRam = p[Keys.N_RAM] ?: true,
            notifyBattery = p[Keys.N_BAT] ?: true,
            notifyNetwork = p[Keys.N_NET] ?: true,
            notifyTemperature = p[Keys.N_TEMP] ?: false,
            batteryHistoryEnabled = p[Keys.BAT_HISTORY] ?: true,
            hapticsEnabled = p[Keys.HAPTICS] ?: true,
            dashboardWidgets = p[Keys.WIDGETS]?.split(",")?.filter { it.isNotBlank() }
                ?: DashboardWidget.defaultOrder(),
            graphPointCount = p[Keys.GRAPH_POINTS] ?: 60
        )
    }

    suspend fun setRefreshInterval(ms: Long) = edit { it[Keys.REFRESH] = ms.toInt() }
    suspend fun setTheme(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC] = enabled }
    suspend fun setLowResourceMode(enabled: Boolean) = edit { it[Keys.LOW_RES] = enabled }
    suspend fun setAutoMonitor(enabled: Boolean) = edit { it[Keys.AUTO_MONITOR] = enabled }
    suspend fun setBackgroundMonitoring(enabled: Boolean) = edit { it[Keys.BG_MONITOR] = enabled }
    suspend fun setNotificationStyle(style: NotificationStyle) = edit { it[Keys.NOTIF_STYLE] = style.name }
    suspend fun setNotifyCpu(v: Boolean) = edit { it[Keys.N_CPU] = v }
    suspend fun setNotifyRam(v: Boolean) = edit { it[Keys.N_RAM] = v }
    suspend fun setNotifyBattery(v: Boolean) = edit { it[Keys.N_BAT] = v }
    suspend fun setNotifyNetwork(v: Boolean) = edit { it[Keys.N_NET] = v }
    suspend fun setNotifyTemperature(v: Boolean) = edit { it[Keys.N_TEMP] = v }
    suspend fun setBatteryHistory(v: Boolean) = edit { it[Keys.BAT_HISTORY] = v }
    suspend fun setHaptics(v: Boolean) = edit { it[Keys.HAPTICS] = v }
    suspend fun setWidgets(ids: List<String>) = edit { it[Keys.WIDGETS] = ids.joinToString(",") }
    suspend fun setGraphPoints(count: Int) = edit { it[Keys.GRAPH_POINTS] = count }

    suspend fun toggleWidget(id: String, enabled: Boolean) {
        context.dataStore.edit { p ->
            val current = p[Keys.WIDGETS]?.split(",")?.filter { it.isNotBlank() }?.toMutableList()
                ?: DashboardWidget.defaultOrder().toMutableList()
            if (enabled) { if (!current.contains(id)) current.add(id) } else current.remove(id)
            p[Keys.WIDGETS] = current.joinToString(",")
        }
    }

    suspend fun moveWidget(id: String, up: Boolean) {
        context.dataStore.edit { p ->
            val current = p[Keys.WIDGETS]?.split(",")?.filter { it.isNotBlank() }?.toMutableList()
                ?: DashboardWidget.defaultOrder().toMutableList()
            val idx = current.indexOf(id)
            if (idx < 0) return@edit
            val target = if (up) idx - 1 else idx + 1
            if (target !in current.indices) return@edit
            current[idx] = current[target].also { current[target] = current[idx] }
            p[Keys.WIDGETS] = current.joinToString(",")
        }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    companion object {
        val INTERVAL_OPTIONS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
    }
}
