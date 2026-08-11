package com.monitorcheck.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monitorcheck.core.DashboardWidget
import com.monitorcheck.core.Fmt
import com.monitorcheck.monitor.Series
import com.monitorcheck.ui.MonitorViewModel
import com.monitorcheck.ui.components.MetricValue
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.components.Sparkline
import com.monitorcheck.ui.components.StatusChip
import com.monitorcheck.ui.components.UsageBar
import com.monitorcheck.ui.components.loadColor
import com.monitorcheck.ui.theme.StatusColors

/**
 * Material You dashboard.
 *
 * Cards are rendered in the user's configured order and only for enabled widgets.
 * Every card reads from the shared [MonitorViewModel] sample — no card starts its own
 * polling — and tapping one opens the matching detail screen.
 */
@Composable
fun DashboardScreen(
    vm: MonitorViewModel,
    onNavigate: (String) -> Unit,
    contentPadding: PaddingValues
) {
    val sample by vm.sample.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val paused by vm.paused.collectAsStateWithLifecycle()
    val version by vm.seriesVersion.collectAsStateWithLifecycle()

    val widgets = settings.dashboardWidgets.mapNotNull { DashboardWidget.fromId(it) }

    LazyColumn(
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (!running) {
            item {
                NoticeCard(
                    title = "Monitoring stopped",
                    body = "Live sampling is not running, so the cards below show no new values. " +
                        "Use the play button in the top bar to start the monitoring engine.",
                    tone = StatusColors.warn
                )
            }
        } else if (paused) {
            item {
                NoticeCard(
                    title = "Paused",
                    body = "Graphs are frozen at the last collected sample. Resume from the top bar.",
                    tone = StatusColors.warn
                )
            }
        }

        items(widgets, key = { it.id }) { widget ->
            when (widget) {
                DashboardWidget.CPU_USAGE -> CpuUsageCard(vm, sample, version) { onNavigate("cpu") }
                DashboardWidget.CPU_FREQ -> CpuFreqCard(vm, sample, version) { onNavigate("cpu") }
                DashboardWidget.GPU -> GpuCard(sample) { onNavigate("gpu") }
                DashboardWidget.RAM -> RamCard(vm, sample, version) { onNavigate("memory") }
                DashboardWidget.STORAGE -> StorageCard(vm) { onNavigate("storage") }
                DashboardWidget.BATTERY -> BatteryCard(vm, sample, version) { onNavigate("battery") }
                DashboardWidget.BATTERY_TEMP -> BatteryTempCard(vm, sample, version) { onNavigate("battery") }
                DashboardWidget.DEVICE_TEMP -> DeviceTempCard(vm, sample, version) { onNavigate("thermal") }
                DashboardWidget.NETWORK -> NetworkCard(vm, sample, version) { onNavigate("network") }
                DashboardWidget.FPS -> FpsCard { onNavigate("fps") }
                DashboardWidget.PROCESSES -> ProcessesCard(vm) { onNavigate("tasks") }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DashCard(
    title: String,
    onClick: () -> Unit,
    content: ColumnContent
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Open $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

private typealias ColumnContent = @Composable () -> Unit

@Composable
private fun CpuUsageCard(
    vm: MonitorViewModel,
    sample: com.monitorcheck.monitor.MonitorSample?,
    version: Int,
    onClick: () -> Unit
) {
    DashCard("CPU usage", onClick) {
        val usage = sample?.cpu?.totalPercent
        if (usage?.isAvailable == true) {
            val pct = usage.value!!
            MetricValue(Fmt.percent(pct), color = loadColor(pct))
            Spacer(Modifier.height(6.dp))
            UsageBar(fraction = (pct / 100.0).toFloat(), color = loadColor(pct))
            Spacer(Modifier.height(8.dp))
            // Reading seriesVersion here makes the graph recompose when new samples land.
            val ignoredVersion = version
            Sparkline(
                values = vm.series.snapshot(Series.CPU),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                color = loadColor(pct),
                minValue = 0f,
                maxValue = 100f
            )
            val cores = sample.cpu.cores
            if (cores.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${cores.size} cores · " + cores.mapNotNull { it.usagePercent }
                        .let { list ->
                            if (list.isEmpty()) "per-core usage unavailable"
                            else "peak core ${Fmt.percent(list.max())}"
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            StatusChip(usage?.status ?: com.monitorcheck.core.DataStatus.UNAVAILABLE)
            usage?.note?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
            }
        }
    }
}

@Composable
private fun CpuFreqCard(
    vm: MonitorViewModel,
    sample: com.monitorcheck.monitor.MonitorSample?,
    version: Int,
    onClick: () -> Unit
) {
    DashCard("CPU frequency", onClick) {
        val cores = sample?.cpu?.cores.orEmpty()
        val freqs = cores.mapNotNull { it.currentKHz }
        if (freqs.isNotEmpty()) {
            MetricValue(Fmt.freqKHz(freqs.max()), unit = "peak")
            Spacer(Modifier.height(6.dp))
            Text(
                "avg ${Fmt.freqKHz(freqs.average().toLong())} · " +
                    "min ${Fmt.freqKHz(freqs.min())} · ${freqs.size} of ${cores.size} cores reporting",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            // Reading seriesVersion here makes the graph recompose when new samples land.
            val ignoredVersion = version
            Sparkline(
                values = vm.series.snapshot(Series.CPU_FREQ),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                color = StatusColors.accent
            )
        } else {
            StatusChip(com.monitorcheck.core.DataStatus.UNAVAILABLE)
            Spacer(Modifier.height(4.dp))
            Text(
                "cpufreq nodes are not readable on this device",
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted
            )
        }
    }
}

@Composable
private fun GpuCard(sample: com.monitorcheck.monitor.MonitorSample?, onClick: () -> Unit) {
    DashCard("GPU", onClick) {
        val util = sample?.gpuUtilPercent
        val freq = sample?.gpuFreqKHz
        if (util?.isAvailable == true) {
            MetricValue(Fmt.percent(util.value!!), color = loadColor(util.value!!))
            Spacer(Modifier.height(6.dp))
            UsageBar((util.value!! / 100.0).toFloat(), color = loadColor(util.value!!))
        } else {
            StatusChip(util?.status ?: com.monitorcheck.core.DataStatus.UNSUPPORTED)
            Spacer(Modifier.height(4.dp))
            Text(
                util?.note ?: "Android exposes no GPU utilisation API to apps.",
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted
            )
        }
        if (freq?.isAvailable == true) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Clock ${Fmt.freqKHz(freq.value!!)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RamCard(
    vm: MonitorViewModel,
    sample: com.monitorcheck.monitor.MonitorSample?,
    version: Int,
    onClick: () -> Unit
) {
    DashCard("Memory", onClick) {
        val mem = sample?.memory
        if (mem?.isAvailable == true) {
            val m = mem.value!!
            MetricValue(Fmt.percent(m.usedPercent), color = loadColor(m.usedPercent))
            Spacer(Modifier.height(6.dp))
            UsageBar((m.usedPercent / 100.0).toFloat(), color = loadColor(m.usedPercent))
            Spacer(Modifier.height(6.dp))
            Text(
                "${Fmt.bytes(m.usedBytes)} used · ${Fmt.bytes(m.availableBytes)} available " +
                    "of ${Fmt.bytes(m.totalBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (m.lowMemory) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "System reports LOW MEMORY state",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.critical
                )
            }
            Spacer(Modifier.height(8.dp))
            // Reading seriesVersion here makes the graph recompose when new samples land.
            val ignoredVersion = version
            Sparkline(
                values = vm.series.snapshot(Series.RAM),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                color = loadColor(m.usedPercent),
                minValue = 0f, maxValue = 100f
            )
        } else {
            StatusChip(mem?.status ?: com.monitorcheck.core.DataStatus.UNAVAILABLE)
        }
    }
}

@Composable
private fun StorageCard(vm: MonitorViewModel, onClick: () -> Unit) {
    val repo = rememberStorageRepo(vm)
    val totals = repo.primaryTotals()
    DashCard("Storage", onClick) {
        if (totals.isAvailable) {
            val (total, free) = totals.value!!
            val used = total - free
            val pct = if (total > 0) used * 100.0 / total else 0.0
            MetricValue(Fmt.percent(pct), color = loadColor(pct))
            Spacer(Modifier.height(6.dp))
            UsageBar((pct / 100.0).toFloat(), color = loadColor(pct))
            Spacer(Modifier.height(6.dp))
            Text(
                "${Fmt.bytes(used)} used · ${Fmt.bytes(free)} free of ${Fmt.bytes(total)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            StatusChip(totals.status)
        }
    }
}

@Composable
private fun BatteryCard(
    vm: MonitorViewModel,
    sample: com.monitorcheck.monitor.MonitorSample?,
    version: Int,
    onClick: () -> Unit
) {
    DashCard("Battery", onClick) {
        val bat = sample?.battery
        if (bat?.isAvailable == true) {
            val b = bat.value!!
            val color = when {
                b.isCharging -> StatusColors.ok
                b.levelPercent <= 15 -> StatusColors.critical
                b.levelPercent <= 30 -> StatusColors.warn
                else -> MaterialTheme.colorScheme.onSurface
            }
            MetricValue("${b.levelPercent}", unit = "%", color = color)
            Spacer(Modifier.height(6.dp))
            UsageBar(b.levelPercent / 100f, color = color)
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append(b.status)
                    if (b.plugged != "Not plugged in") append(" · ${b.plugged}")
                    b.currentNowUa?.let { append(" · ${Fmt.currentMa(it)}") }
                    b.powerWatts?.let {
                        append(" · ${String.format(java.util.Locale.US, "%.2f W", it)}")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            // Reading seriesVersion here makes the graph recompose when new samples land.
            val ignoredVersion = version
            Sparkline(
                values = vm.series.snapshot(Series.BATTERY_LEVEL),
                modifier = Modifier.fillMaxWidth().height(40.dp),
                color = color, minValue = 0f, maxValue = 100f
            )
        } else {
            StatusChip(bat?.status ?: com.monitorcheck.core.DataStatus.UNAVAILABLE)
        }
    }
}

@Composable
private fun BatteryTempCard(
    vm: MonitorViewModel,
    sample: com.monitorcheck.monitor.MonitorSample?,
    version: Int,
    onClick: () -> Unit
) {
    DashCard("Battery temperature", onClick) {
        val t = sample?.batteryCelsius
        if (t?.isAvailable == true) {
            val c = t.value!!
            val color = when {
                c >= 45 -> StatusColors.critical
                c >= 38 -> StatusColors.warn
                else -> StatusColors.ok
            }
            MetricValue(Fmt.temperature(c), color = color)
            Spacer(Modifier.height(8.dp))
            // Reading seriesVersion here makes the graph recompose when new samples land.
            val ignoredVersion = version
            Sparkline(
                values = vm.series.snapshot(Series.BATTERY_TEMP),
                modifier = Modifier.fillMaxWidth().height(40.dp),
                color = color
            )
        } else {
            StatusChip(t?.status ?: com.monitorcheck.core.DataStatus.UNAVAILABLE)
            t?.note?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
            }
        }
    }
}

@Composable
private fun DeviceTempCard(
    vm: MonitorViewModel,
    sample: com.monitorcheck.monitor.MonitorSample?,
    version: Int,
    onClick: () -> Unit
) {
    DashCard("Device temperature", onClick) {
        val t = sample?.hottestCelsius
        if (t?.isAvailable == true) {
            val c = t.value!!
            val color = when {
                c >= 60 -> StatusColors.critical
                c >= 45 -> StatusColors.warn
                else -> StatusColors.ok
            }
            MetricValue(Fmt.temperature(c), unit = "hottest zone", color = color)
            Spacer(Modifier.height(8.dp))
            // Reading seriesVersion here makes the graph recompose when new samples land.
            val ignoredVersion = version
            Sparkline(
                values = vm.series.snapshot(Series.DEVICE_TEMP),
                modifier = Modifier.fillMaxWidth().height(40.dp),
                color = color
            )
        } else {
            StatusChip(t?.status ?: com.monitorcheck.core.DataStatus.UNAVAILABLE)
            t?.note?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
            }
        }
    }
}

@Composable
private fun NetworkCard(
    vm: MonitorViewModel,
    sample: com.monitorcheck.monitor.MonitorSample?,
    version: Int,
    onClick: () -> Unit
) {
    DashCard("Network", onClick) {
        val t = sample?.throughput
        if (t != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Download", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MetricValue(Fmt.bytesPerSecond(t.rxRateBps), color = StatusColors.accent)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Upload", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MetricValue(Fmt.bytesPerSecond(t.txRateBps), color = StatusColors.ok)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "since boot: ↓${Fmt.bytes(t.rxBytes)} ↑${Fmt.bytes(t.txBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            // Reading seriesVersion here makes the graph recompose when new samples land.
            val ignoredVersion = version
            Sparkline(
                values = vm.series.snapshot(Series.NET_DOWN),
                modifier = Modifier.fillMaxWidth().height(44.dp),
                color = StatusColors.accent
            )
        } else {
            StatusChip(com.monitorcheck.core.DataStatus.UNAVAILABLE)
            Spacer(Modifier.height(4.dp))
            Text(
                "TrafficStats counters are not supported on this device",
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted
            )
        }
    }
}

@Composable
private fun FpsCard(onClick: () -> Unit) {
    DashCard("Display & FPS", onClick) {
        Text(
            "Open to start a Choreographer-based frame rate measurement of this app's " +
                "render loop, plus full display capabilities.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProcessesCard(vm: MonitorViewModel, onClick: () -> Unit) {
    DashCard("Processes & apps", onClick) {
        Text(
            "Open the task manager for running processes, services and app activity that " +
                "Android makes visible to this app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun rememberStorageRepo(vm: MonitorViewModel): com.monitorcheck.storage.StorageRepository {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember {
        com.monitorcheck.storage.StorageRepository(context.applicationContext)
    }
}
