package com.monitorcheck.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monitorcheck.core.AppSettings
import com.monitorcheck.core.Reading
import com.monitorcheck.core.SettingsRepository
import com.monitorcheck.data.battery.BatteryHistoryStore
import com.monitorcheck.data.battery.BatteryRepository
import com.monitorcheck.hardware.cpu.CpuRepository
import com.monitorcheck.hardware.gpu.GpuRepository
import com.monitorcheck.hardware.memory.MemoryRepository
import com.monitorcheck.hardware.thermal.ThermalRepository
import com.monitorcheck.monitor.MonitorSample
import com.monitorcheck.monitor.Series
import com.monitorcheck.monitor.SeriesStore
import com.monitorcheck.network.NetworkRepository
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

/**
 * Central realtime monitoring engine.
 *
 * One coroutine samples every enabled source at the user's interval and publishes an
 * immutable [MonitorSample]. All screens observe this single loop instead of each
 * starting their own timer.
 *
 * Performance design:
 *  - sampling always happens on Dispatchers.IO, never on the main thread
 *  - each graph series has its own flow, so a chart only recomposes when its own
 *    data changes (previously one global counter recomposed every card at once)
 *  - expensive sysfs sources (thermal, GPU) are sampled on a slower sub-interval
 *  - polling throttles automatically in the background and in Low Resource Mode
 */
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

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _sample = MutableStateFlow<MonitorSample?>(null)
    val sample: StateFlow<MonitorSample?> = _sample.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    /** Per-series graph history with bounded memory. */
    val series = SeriesStore(capacity = 120)

    private var loopJob: Job? = null
    private var inForeground = true

    /**
     * Thermal and GPU nodes are the slowest reads. They are refreshed every N ticks
     * rather than every tick, and their last good value is reused in between.
     */
    private var tickCounter = 0L
    private var cachedThermal: Reading<Double> = Reading.unavailable("Collecting first sample")
    private var cachedGpuUtil: Reading<Double> = Reading.unavailable("Collecting first sample")
    private var cachedGpuFreq: Reading<Long> = Reading.unavailable("Collecting first sample")

    init {
        viewModelScope.launch {
            val s = settingsRepo.settings.first()
            if (s.autoMonitorOnLaunch) start()
        }
    }

    fun start() {
        if (loopJob?.isActive == true) return
        _running.value = true
        _paused.value = false
        loopJob = viewModelScope.launch(Dispatchers.Default) { loop() }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        _running.value = false
    }

    fun togglePause() { _paused.value = !_paused.value }

    fun toggleRunning() = if (_running.value) stop() else start()

    /** Called by MainActivity on lifecycle changes to throttle background sampling. */
    fun setForeground(foreground: Boolean) { inForeground = foreground }

    fun clearSeries() = series.clear()

    /** Forces an immediate resample, used by pull-to-refresh. */
    fun refreshNow() {
        viewModelScope.launch(Dispatchers.Default) {
            val s = settings.value
            val sample = withContext(Dispatchers.IO) { collect(s, forceSlowSources = true) }
            _sample.value = sample
            record(sample)
        }
    }

    private suspend fun loop() {
        while (viewModelScope.isActive) {
            val s = settings.value

            if (_paused.value) {
                delay(300)
                continue
            }

            val interval = when {
                !inForeground -> maxOf(10_000L, s.refreshIntervalMs)
                s.lowResourceMode -> maxOf(2_000L, s.refreshIntervalMs)
                else -> s.refreshIntervalMs
            }

            tickCounter++
            val sample = withContext(Dispatchers.IO) { collect(s) }
            _sample.value = sample
            record(sample)

            delay(interval)
        }
    }

    /**
     * Collects one sample. Must be called on an IO dispatcher.
     *
     * @param forceSlowSources re-read thermal/GPU even if it is not their turn
     */
    private fun collect(s: AppSettings, forceSlowSources: Boolean = false): MonitorSample {
        val cpu = runCatching { cpuRepo.sampleUsage() }.getOrNull()
        val memory = runCatching { memoryRepo.snapshot() }
            .getOrElse { Reading.error(it.message) }
        val battery = runCatching { batteryRepo.snapshot() }
            .getOrElse { Reading.error(it.message) }
        val throughput = runCatching { networkRepo.sampleThroughput() }.getOrNull()

        // Refresh the expensive sysfs sources every 3rd tick (or every 5th when the
        // user asked for Low Resource Mode). Values in between are the last real
        // reading, never an interpolation.
        val slowEvery = if (s.lowResourceMode) 5 else 3
        if (forceSlowSources || tickCounter % slowEvery == 1L || tickCounter <= 1) {
            cachedThermal = if (s.lowResourceMode && !forceSlowSources) {
                Reading.unavailable("Skipped in Low Resource Mode")
            } else {
                runCatching { thermalRepo.hottestZone().map { it.celsius } }
                    .getOrElse { Reading.error(it.message) }
            }
            cachedGpuUtil = if (s.lowResourceMode && !forceSlowSources) {
                Reading.unavailable("Skipped in Low Resource Mode")
            } else {
                runCatching { gpuRepo.gpuUtilisation() }.getOrElse { Reading.error(it.message) }
            }
            cachedGpuFreq = if (s.lowResourceMode && !forceSlowSources) {
                Reading.unavailable("Skipped in Low Resource Mode")
            } else {
                runCatching { gpuRepo.gpuFrequency() }.getOrElse { Reading.error(it.message) }
            }
        }

        val batteryTemp = battery.value?.temperatureCelsius
            ?.let { Reading.available(it, "BatteryManager") }
            ?: Reading.unavailable("Not reported by this device")

        return MonitorSample(
            timestamp = System.currentTimeMillis(),
            cpu = cpu,
            memory = memory,
            battery = battery,
            throughput = throughput,
            hottestCelsius = cachedThermal,
            batteryCelsius = batteryTemp,
            gpuUtilPercent = cachedGpuUtil,
            gpuFreqKHz = cachedGpuFreq
        )
    }

    /** Appends real values to the graph series. Missing values are simply not appended. */
    private fun record(sample: MonitorSample) {
        sample.cpu?.totalPercent?.value?.let { series.add(Series.CPU, it.toFloat()) }
        sample.cpu?.cores?.mapNotNull { it.currentKHz }?.maxOrNull()?.let {
            series.add(Series.CPU_FREQ, (it / 1000).toFloat())
        }
        sample.memory.value?.let { series.add(Series.RAM, it.usedPercent.toFloat()) }
        sample.battery.value?.let {
            series.add(Series.BATTERY_LEVEL, it.levelPercent.toFloat())
            it.currentNowUa?.let { c -> series.add(Series.BATTERY_CURRENT, c / 1000f) }
        }
        sample.batteryCelsius.value?.let { series.add(Series.BATTERY_TEMP, it.toFloat()) }
        sample.hottestCelsius.value?.let { series.add(Series.DEVICE_TEMP, it.toFloat()) }
        sample.throughput?.let {
            if (it.elapsedMs > 0) {
                series.add(Series.NET_DOWN, it.rxRateBps.toFloat())
                series.add(Series.NET_UP, it.txRateBps.toFloat())
            }
        }
        sample.gpuUtilPercent.value?.let { series.add(Series.GPU, it.toFloat()) }

        sample.battery.value?.let { snap ->
            if (settings.value.batteryHistoryEnabled) {
                viewModelScope.launch(Dispatchers.IO) { runCatching { historyStore.record(snap) } }
            }
        }
    }

    // ---- Settings mutators ----

    fun setRefreshInterval(ms: Long) = viewModelScope.launch { settingsRepo.setRefreshInterval(ms) }
    fun setTheme(mode: com.monitorcheck.core.ThemeMode) =
        viewModelScope.launch { settingsRepo.setTheme(mode) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { settingsRepo.setDynamicColor(v) }
    fun setLowResourceMode(v: Boolean) = viewModelScope.launch { settingsRepo.setLowResourceMode(v) }
    fun setAutoMonitor(v: Boolean) = viewModelScope.launch { settingsRepo.setAutoMonitor(v) }
    fun setBackgroundMonitoring(v: Boolean) =
        viewModelScope.launch { settingsRepo.setBackgroundMonitoring(v) }
    fun setNotificationStyle(v: com.monitorcheck.core.NotificationStyle) =
        viewModelScope.launch { settingsRepo.setNotificationStyle(v) }
    fun setNotifyCpu(v: Boolean) = viewModelScope.launch { settingsRepo.setNotifyCpu(v) }
    fun setNotifyRam(v: Boolean) = viewModelScope.launch { settingsRepo.setNotifyRam(v) }
    fun setNotifyBattery(v: Boolean) = viewModelScope.launch { settingsRepo.setNotifyBattery(v) }
    fun setNotifyNetwork(v: Boolean) = viewModelScope.launch { settingsRepo.setNotifyNetwork(v) }
    fun setNotifyTemperature(v: Boolean) = viewModelScope.launch { settingsRepo.setNotifyTemperature(v) }
    fun setBatteryHistory(v: Boolean) = viewModelScope.launch { settingsRepo.setBatteryHistory(v) }
    fun setHaptics(v: Boolean) = viewModelScope.launch { settingsRepo.setHaptics(v) }
    fun toggleWidget(id: String, enabled: Boolean) =
        viewModelScope.launch { settingsRepo.toggleWidget(id, enabled) }
    fun moveWidget(id: String, up: Boolean) = viewModelScope.launch { settingsRepo.moveWidget(id, up) }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
