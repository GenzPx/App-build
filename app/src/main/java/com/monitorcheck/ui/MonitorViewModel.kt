package com.monitorcheck.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monitorcheck.core.AppSettings
import com.monitorcheck.core.Fmt
import com.monitorcheck.core.Reading
import com.monitorcheck.core.SettingsRepository
import com.monitorcheck.data.battery.BatteryHistoryStore
import com.monitorcheck.data.battery.BatteryRepository
import com.monitorcheck.hardware.cpu.CpuRepository
import com.monitorcheck.hardware.gpu.GpuRepository
import com.monitorcheck.hardware.memory.MemoryRepository
import com.monitorcheck.hardware.thermal.ThermalCategory
import com.monitorcheck.hardware.thermal.ThermalRepository
import com.monitorcheck.hardware.thermal.ThermalZone
import com.monitorcheck.monitor.AlertEventStore
import com.monitorcheck.monitor.HistoryPoint
import com.monitorcheck.monitor.MonitorSample
import com.monitorcheck.monitor.MonitoringHistoryStore
import com.monitorcheck.monitor.Series
import com.monitorcheck.monitor.SeriesStore
import com.monitorcheck.network.NetworkRepository
import com.monitorcheck.storage.StorageRepository
import com.monitorcheck.widget.MonitorWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MonitorViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app.applicationContext
    val settingsRepo = SettingsRepository(context)
    val cpuRepo = CpuRepository()
    val memoryRepo = MemoryRepository(context)
    val batteryRepo = BatteryRepository(context)
    val networkRepo = NetworkRepository(context)
    val thermalRepo = ThermalRepository(context)
    val gpuRepo = GpuRepository(context)
    val historyStore = BatteryHistoryStore(context)
    val monitoringHistoryStore = MonitoringHistoryStore(context)
    val alertEventStore = AlertEventStore(context)
    val storageRepo = StorageRepository(context)

    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val _sample = MutableStateFlow<MonitorSample?>(null)
    val sample: StateFlow<MonitorSample?> = _sample.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()
    val series = SeriesStore(capacity = 120)
    private val _seriesVersion = MutableStateFlow(0)
    val seriesVersion: StateFlow<Int> = _seriesVersion.asStateFlow()
    private var loopJob: Job? = null
    private var inForeground = true

    init { viewModelScope.launch { if (settingsRepo.settings.first().autoMonitorOnLaunch) start() } }
    fun start() { if (loopJob?.isActive == true) return; _running.value = true; _paused.value = false; loopJob = viewModelScope.launch(Dispatchers.Default) { loop() } }
    fun stop() { loopJob?.cancel(); loopJob = null; _running.value = false }
    fun togglePause() { _paused.value = !_paused.value }
    fun toggleRunning() = if (_running.value) stop() else start()
    fun setForeground(foreground: Boolean) { inForeground = foreground }
    fun clearSeries() { series.clear(); _seriesVersion.value++ }

    private suspend fun loop() {
        while (viewModelScope.isActive) {
            val s = settings.value
            if (_paused.value) { delay(300); continue }
            val interval = when { !inForeground -> maxOf(10_000L, s.refreshIntervalMs); s.lowResourceMode -> maxOf(2_000L, s.refreshIntervalMs); else -> s.refreshIntervalMs }
            val current = withContext(Dispatchers.IO) { collect(s) }
            _sample.value = current
            record(current)
            delay(interval)
        }
    }

    private fun collect(s: AppSettings): MonitorSample {
        val cpu = runCatching { cpuRepo.sampleUsage() }.getOrNull()
        val memory = runCatching { memoryRepo.snapshot() }.getOrElse { Reading.error(it.message) }
        val battery = runCatching { batteryRepo.snapshot() }.getOrElse { Reading.error(it.message) }
        val throughput = runCatching { networkRepo.sampleThroughput() }.getOrNull()
        val thermalZones: Reading<List<ThermalZone>> = if (s.lowResourceMode) Reading.unavailable("Skipped in Low Resource Mode")
        else runCatching { thermalRepo.readZones() }.getOrElse { Reading.error(it.message) }
        val hottest = thermalZones.value?.maxByOrNull { it.celsius }?.let { Reading.available(it.celsius, thermalZones.source) }
            ?: Reading(thermalZones.status, null, thermalZones.note, thermalZones.source)
        val cpuTemp = thermalZones.value?.filter { it.category == ThermalCategory.CPU }?.maxByOrNull { it.celsius }?.let { Reading.available(it.celsius, it.path) }
            ?: Reading(thermalZones.status, null, thermalZones.note, thermalZones.source)
        val batteryTemp = battery.value?.temperatureCelsius?.let { Reading.available(it, "BatteryManager") }
            ?: Reading.unavailable("Not reported by this device")
        val gpuUtil = if (s.lowResourceMode) Reading.unavailable<Double>("Skipped in Low Resource Mode") else runCatching { gpuRepo.gpuUtilisation() }.getOrElse { Reading.error(it.message) }
        val gpuFreq = if (s.lowResourceMode) Reading.unavailable<Long>("Skipped in Low Resource Mode") else runCatching { gpuRepo.gpuFrequency() }.getOrElse { Reading.error(it.message) }
        return MonitorSample(System.currentTimeMillis(), cpu, memory, battery, throughput, hottest, batteryTemp, gpuUtil, gpuFreq, cpuTemp)
    }

    private fun record(sample: MonitorSample) {
        var changed = false
        sample.cpu?.totalPercent?.value?.let { series.add(Series.CPU, it.toFloat()); changed = true }
        sample.cpu?.cores?.mapNotNull { it.currentKHz }?.maxOrNull()?.let { series.add(Series.CPU_FREQ, (it / 1000).toFloat()); changed = true }
        sample.memory.value?.let { series.add(Series.RAM, it.usedPercent.toFloat()); changed = true }
        sample.battery.value?.let { series.add(Series.BATTERY_LEVEL, it.levelPercent.toFloat()); it.currentNowUa?.let { c -> series.add(Series.BATTERY_CURRENT, c / 1000f) }; changed = true }
        sample.batteryCelsius.value?.let { series.add(Series.BATTERY_TEMP, it.toFloat()); changed = true }
        sample.hottestCelsius.value?.let { series.add(Series.DEVICE_TEMP, it.toFloat()); changed = true }
        sample.throughput?.takeIf { it.elapsedMs > 0 }?.let { series.add(Series.NET_DOWN, it.rxRateBps.toFloat()); series.add(Series.NET_UP, it.txRateBps.toFloat()); changed = true }
        sample.gpuUtilPercent.value?.let { series.add(Series.GPU, it.toFloat()); changed = true }
        if (changed) _seriesVersion.value++
        monitoringHistoryStore.record(HistoryPoint(sample.timestamp, sample.cpu?.totalPercent?.value, sample.memory.value?.usedPercent, sample.battery.value?.levelPercent?.toDouble(), sample.batteryCelsius.value, sample.hottestCelsius.value, sample.throughput?.rxRateBps, sample.throughput?.txRateBps))
        MonitorWidgetProvider.updateAll(context, MonitorWidgetProvider.WidgetSnapshot(
            cpu = sample.cpu?.totalPercent?.value?.let { Fmt.percent(it, 0) } ?: "Unavailable",
            ram = sample.memory.value?.let { Fmt.percent(it.usedPercent, 0) } ?: "Unavailable",
            battery = sample.battery.value?.let { "${it.levelPercent}%" } ?: "Unavailable",
            temperature = sample.hottestCelsius.value?.let { Fmt.temperature(it) } ?: "Unavailable",
            network = sample.throughput?.takeIf { it.elapsedMs > 0 }?.let { "↓${Fmt.bytesPerSecond(it.rxRateBps)} ↑${Fmt.bytesPerSecond(it.txRateBps)}" } ?: "Unavailable",
            updatedAt = sample.timestamp
        ))
        sample.battery.value?.let { snap -> if (settings.value.batteryHistoryEnabled) viewModelScope.launch { runCatching { historyStore.record(snap) } } }
    }

    fun setRefreshInterval(ms: Long) = viewModelScope.launch { settingsRepo.setRefreshInterval(ms) }
    fun setTheme(mode: com.monitorcheck.core.ThemeMode) = viewModelScope.launch { settingsRepo.setTheme(mode) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { settingsRepo.setDynamicColor(v) }
    fun setLowResourceMode(v: Boolean) = viewModelScope.launch { settingsRepo.setLowResourceMode(v) }
    fun setAutoMonitor(v: Boolean) = viewModelScope.launch { settingsRepo.setAutoMonitor(v) }
    fun setBackgroundMonitoring(v: Boolean) = viewModelScope.launch { settingsRepo.setBackgroundMonitoring(v) }
    fun setNotificationStyle(v: com.monitorcheck.core.NotificationStyle) = viewModelScope.launch { settingsRepo.setNotificationStyle(v) }
    fun setNotifyCpu(v: Boolean) = viewModelScope.launch { settingsRepo.setNotifyCpu(v) }
    fun setNotifyRam(v: Boolean) = viewModelScope.launch { settingsRepo.setNotifyRam(v) }
    fun setNotifyBattery(v: Boolean) = viewModelScope.launch { settingsRepo.setNotifyBattery(v) }
    fun setNotifyNetwork(v: Boolean) = viewModelScope.launch { settingsRepo.setNotifyNetwork(v) }
    fun setNotifyTemperature(v: Boolean) = viewModelScope.launch { settingsRepo.setNotifyTemperature(v) }
    fun setBatteryHistory(v: Boolean) = viewModelScope.launch { settingsRepo.setBatteryHistory(v) }
    fun setAlertsEnabled(v: Boolean) = viewModelScope.launch { settingsRepo.setAlertsEnabled(v) }
    fun setCpuAlertEnabled(v: Boolean) = viewModelScope.launch { settingsRepo.setCpuAlertEnabled(v) }
    fun setCpuAlertThreshold(v: Float) = viewModelScope.launch { settingsRepo.setCpuAlertThreshold(v) }
    fun setRamAlertEnabled(v: Boolean) = viewModelScope.launch { settingsRepo.setRamAlertEnabled(v) }
    fun setRamAlertThreshold(v: Float) = viewModelScope.launch { settingsRepo.setRamAlertThreshold(v) }
    fun setDeviceTempAlertEnabled(v: Boolean) = viewModelScope.launch { settingsRepo.setDeviceTempAlertEnabled(v) }
    fun setDeviceTempAlertThreshold(v: Float) = viewModelScope.launch { settingsRepo.setDeviceTempAlertThreshold(v) }
    fun setCpuTempAlertEnabled(v: Boolean) = viewModelScope.launch { settingsRepo.setCpuTempAlertEnabled(v) }
    fun setCpuTempAlertThreshold(v: Float) = viewModelScope.launch { settingsRepo.setCpuTempAlertThreshold(v) }
    fun setBatteryTempAlertEnabled(v: Boolean) = viewModelScope.launch { settingsRepo.setBatteryTempAlertEnabled(v) }
    fun setBatteryTempAlertThreshold(v: Float) = viewModelScope.launch { settingsRepo.setBatteryTempAlertThreshold(v) }
    fun setStorageAlertEnabled(v: Boolean) = viewModelScope.launch { settingsRepo.setStorageAlertEnabled(v) }
    fun setStorageAlertThreshold(v: Float) = viewModelScope.launch { settingsRepo.setStorageAlertThreshold(v) }
    fun setBatteryLowAlertEnabled(v: Boolean) = viewModelScope.launch { settingsRepo.setBatteryLowAlertEnabled(v) }
    fun setBatteryLowAlertThreshold(v: Float) = viewModelScope.launch { settingsRepo.setBatteryLowAlertThreshold(v) }
    fun setAlertCooldown(ms: Long) = viewModelScope.launch { settingsRepo.setAlertCooldown(ms) }
    fun setMonitoringProfile(v: com.monitorcheck.core.MonitoringProfile) = viewModelScope.launch { settingsRepo.applyProfile(v) }
    fun setOverlayEnabled(v: Boolean) = viewModelScope.launch { settingsRepo.setOverlayEnabled(v) }
    fun setHistoryRetentionDays(v: Int) = viewModelScope.launch { settingsRepo.setHistoryRetentionDays(v) }
    fun clearMonitoringHistory() = viewModelScope.launch(Dispatchers.IO) { monitoringHistoryStore.clear() }
    fun toggleWidget(id: String, enabled: Boolean) = viewModelScope.launch { settingsRepo.toggleWidget(id, enabled) }
    fun moveWidget(id: String, up: Boolean) = viewModelScope.launch { settingsRepo.moveWidget(id, up) }
    override fun onCleared() { stop(); super.onCleared() }
}
