package com.monitorcheck.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monitorcheck.core.Fmt
import com.monitorcheck.hardware.display.DisplayRepository
import com.monitorcheck.hardware.display.FpsMonitor
import com.monitorcheck.monitor.RingBuffer
import com.monitorcheck.monitor.Series
import com.monitorcheck.ui.MonitorViewModel
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.components.Sparkline
import com.monitorcheck.ui.theme.StatusColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

private data class TimeWindow(val label: String, val seconds: Int)

private val WINDOWS = listOf(
    TimeWindow("1 min", 60),
    TimeWindow("2 min", 120),
    TimeWindow("5 min", 300),
    TimeWindow("Max", 0)
)

@Composable
fun LiveMonitorScreen(vm: MonitorViewModel, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val running by vm.running.collectAsStateWithLifecycle()
    val paused by vm.paused.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val seriesVersion by vm.seriesVersion.collectAsStateWithLifecycle()
    val sample by vm.sample.collectAsStateWithLifecycle()
    var windowIndex by remember { mutableIntStateOf(1) }

    val displayRepo = remember { DisplayRepository(context) }
    val refreshHz = remember { displayRepo.refreshRate().value ?: 60f }
    val fpsMonitor = remember { FpsMonitor(refreshHz) }
    val fpsStats by fpsMonitor.stats.collectAsStateWithLifecycle()
    val fpsHistory = remember { RingBuffer(300) }
    var fpsVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        fpsMonitor.start()
        onDispose { fpsMonitor.stop() }
    }
    LaunchedEffect(paused) {
        while (isActive) {
            if (!paused) {
                val s = fpsMonitor.stats.value
                if (s.measuring && s.currentFps > 0) {
                    fpsHistory.add(s.currentFps.toFloat())
                    fpsVersion++
                }
            }
            delay(1_000)
        }
    }

    val intervalMs = settings.refreshIntervalMs.coerceAtLeast(500L)
    val window = WINDOWS[windowIndex]
    fun windowed(values: List<Float>): List<Float> {
        if (window.seconds == 0) return values
        val points = ((window.seconds * 1000L) / intervalMs).toInt().coerceAtLeast(2)
        return if (values.size > points) values.takeLast(points) else values
    }

    @Suppress("UNUSED_EXPRESSION") seriesVersion

    LazyColumn(contentPadding = contentPadding) {
        if (!running) {
            item {
                NoticeCard(
                    title = "Monitoring is stopped",
                    body = "Graphs only move while the central monitoring engine is running. " +
                        "Start it to collect live samples — nothing is ever simulated.",
                    tone = StatusColors.warn,
                    action = { Button(onClick = { vm.start() }) { Text("Start monitoring") } }
                )
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Time window", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        WINDOWS.forEachIndexed { i, w ->
                            FilterChip(
                                selected = windowIndex == i,
                                onClick = { windowIndex = i },
                                label = { Text(w.label) },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedButton(onClick = { vm.togglePause() }, enabled = running) {
                            Text(if (paused) "Resume" else "Pause")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { vm.clearSeries() }) { Text("Clear graphs") }
                        Spacer(Modifier.width(12.dp))
                        val secLabel = if (intervalMs % 1000L == 0L) "${intervalMs / 1000} s"
                            else String.format(Locale.US, "%.1f s", intervalMs / 1000.0)
                        Text(
                            "Sampling every $secLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            LiveGraphCard(
                title = "CPU usage",
                current = sample?.cpu?.totalPercent?.value?.let { Fmt.percent(it, 0) },
                values = windowed(vm.series.snapshot(Series.CPU)),
                color = MaterialTheme.colorScheme.primary,
                minValue = 0f, maxValue = 100f,
                source = "/proc/stat jiffy delta (same method as top)"
            )
        }

        item {
            val perCore = vm.perCoreSnapshot()
            if (perCore.isNotEmpty()) {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("CPU per core", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        val cores = perCore.entries.sortedBy { it.key }
                        cores.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth()) {
                                pair.forEach { (id, values) ->
                                    Column(Modifier.weight(1f).padding(4.dp)) {
                                        Row(horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()) {
                                            Text("Core $id", style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(values.lastOrNull()?.let {
                                                String.format(Locale.US, "%.0f%%", it) } ?: "—",
                                                style = MaterialTheme.typography.labelSmall)
                                        }
                                        Sparkline(
                                            values = windowed(values),
                                            modifier = Modifier.fillMaxWidth().height(36.dp),
                                            color = MaterialTheme.colorScheme.tertiary,
                                            minValue = 0f, maxValue = 100f
                                        )
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Source: /proc/stat per-core jiffy delta",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                NoticeCard(
                    title = "Per-core usage unavailable",
                    body = "Per-core /proc/stat rows are not readable on this device " +
                        "(many Android builds restrict them). Only real readings are shown."
                )
            }
        }

        item {
            LiveGraphCard(
                title = "RAM used",
                current = sample?.memory?.value?.let { Fmt.percent(it.usedPercent, 0) },
                values = windowed(vm.series.snapshot(Series.RAM)),
                color = StatusColors.accent,
                minValue = 0f, maxValue = 100f,
                source = "ActivityManager.MemoryInfo + /proc/meminfo"
            )
        }

        item {
            val down = windowed(vm.series.snapshot(Series.NET_DOWN))
            val up = windowed(vm.series.snapshot(Series.NET_UP))
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Network", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text(
                            sample?.throughput?.takeIf { it.elapsedMs > 0 }?.let {
                                "↓${Fmt.bytesPerSecond(it.rxRateBps)}  ↑${Fmt.bytesPerSecond(it.txRateBps)}"
                            } ?: "—",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Download", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GraphOrWaiting(down, StatusColors.ok, minValue = 0f)
                    Spacer(Modifier.height(8.dp))
                    Text("Upload", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GraphOrWaiting(up, StatusColors.warn, minValue = 0f)
                    Spacer(Modifier.height(6.dp))
                    Text("Source: TrafficStats / /proc/net/dev byte-counter delta",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            LiveGraphCard(
                title = "Battery level",
                current = sample?.battery?.value?.let { "${it.levelPercent}%" },
                values = windowed(vm.series.snapshot(Series.BATTERY_LEVEL)),
                color = StatusColors.ok,
                minValue = 0f, maxValue = 100f,
                source = "BatteryManager (ACTION_BATTERY_CHANGED)"
            )
        }

        item {
            LiveGraphCard(
                title = "Device temperature (hottest zone)",
                current = sample?.hottestCelsius?.value?.let { Fmt.temperature(it) },
                values = windowed(vm.series.snapshot(Series.DEVICE_TEMP)),
                color = StatusColors.critical,
                minValue = null, maxValue = null,
                source = "/sys/class/thermal thermal zones",
                emptyNote = if (settings.lowResourceMode)
                    "Thermal zones are skipped in Low Resource Mode." else null
            )
        }

        item {
            @Suppress("UNUSED_EXPRESSION") fpsVersion
            LiveGraphCard(
                title = "FPS (this app's own frames)",
                current = fpsStats.takeIf { it.measuring && it.currentFps > 0 }
                    ?.let { String.format(Locale.US, "%.0f fps", it.currentFps) },
                values = windowed(fpsHistory.toList()),
                color = MaterialTheme.colorScheme.tertiary,
                minValue = 0f,
                maxValue = maxOf(refreshHz, fpsHistory.max()),
                source = "Choreographer vsync callbacks on this app's render loop — " +
                    "Android does not expose other apps' FPS"
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun LiveGraphCard(
    title: String,
    current: String?,
    values: List<Float>,
    color: Color,
    minValue: Float?,
    maxValue: Float?,
    source: String,
    emptyNote: String? = null
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(current ?: "—", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(8.dp))
            if (values.size >= 2) {
                Sparkline(
                    values = values,
                    modifier = Modifier.fillMaxWidth().height(90.dp),
                    color = color,
                    minValue = minValue,
                    maxValue = maxValue
                )
            } else {
                Text(
                    emptyNote ?: "Waiting for samples…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Text("Source: $source", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GraphOrWaiting(values: List<Float>, color: Color, minValue: Float?) {
    if (values.size >= 2) {
        Sparkline(
            values = values,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            color = color,
            minValue = minValue
        )
    } else {
        Text("Waiting for samples…", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
