package com.monitorcheck.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monitorcheck.core.Fmt
import com.monitorcheck.core.rememberPolled
import com.monitorcheck.data.battery.BatteryHistoryEntry
import com.monitorcheck.data.battery.HistoryRange
import com.monitorcheck.ui.MonitorViewModel
import com.monitorcheck.ui.components.MetricValue
import com.monitorcheck.ui.components.MonoRow
import com.monitorcheck.ui.components.SectionCard
import com.monitorcheck.ui.components.Sparkline
import com.monitorcheck.ui.components.StatusChip
import com.monitorcheck.ui.components.UsageBar
import com.monitorcheck.ui.theme.StatusColors

@Composable
fun BatteryScreen(vm: MonitorViewModel, contentPadding: PaddingValues) {
    val sample by vm.sample.collectAsStateWithLifecycle()
    // Battery detail sections read sysfs; refresh on a slow timer off the main thread.
    val sectionsState = rememberPolled(5_000L) { vm.batteryRepo.infoSections() }
    val sections = sectionsState.value.valueOrNull.orEmpty()

    var range by remember { mutableStateOf(HistoryRange.H24) }
    var history by remember { mutableStateOf<List<BatteryHistoryEntry>>(emptyList()) }
    var historyCount by remember { mutableStateOf(0) }

    // Reload persisted history whenever the range changes or a new sample lands.
    LaunchedEffect(range, sample?.battery?.value?.levelPercent) {
        history = vm.historyStore.query(range)
        historyCount = vm.historyStore.count()
    }

    LazyColumn(contentPadding = contentPadding) {
        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    val bat = sample?.battery
                    if (bat?.isAvailable == true) {
                        val b = bat.value!!
                        val color = when {
                            b.isCharging -> StatusColors.ok
                            b.levelPercent <= 15 -> StatusColors.critical
                            b.levelPercent <= 30 -> StatusColors.warn
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        MetricValue("${b.levelPercent}", "%", color)
                        Spacer(Modifier.height(8.dp))
                        UsageBar(b.levelPercent / 100f, color = color)
                        Spacer(Modifier.height(12.dp))
                        MonoRow("status", b.status)
                        MonoRow("source", b.plugged)
                        b.voltageMv?.let { MonoRow("voltage", Fmt.voltage(it)) }
                        b.currentNowUa?.let { MonoRow("current", Fmt.currentMa(it)) }
                        b.powerWatts?.let {
                            MonoRow("power", String.format(java.util.Locale.US, "%.2f W", it))
                        }
                        b.temperatureCelsius?.let { MonoRow("temperature", Fmt.temperature(it)) }
                        b.technology?.let { MonoRow("technology", it) }
                        MonoRow("health", b.health)
                    } else {
                        StatusChip(bat?.status ?: com.monitorcheck.core.DataStatus.UNAVAILABLE)
                    }
                }
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("History", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$historyCount samples stored locally on this device",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.muted
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        HistoryRange.entries.forEach { r ->
                            FilterChip(
                                selected = range == r,
                                onClick = { range = r },
                                label = { Text(r.label) },
                                modifier = Modifier.padding(end = 6.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    if (history.size < 2) {
                        Text(
                            "Not enough history yet for this range. Monitored Check records one " +
                                "battery sample per minute while monitoring is running; leave it " +
                                "on (or enable background monitoring) to build up history.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusColors.muted
                        )
                    } else {
                        HistoryGraph("Charge level (%)", history.map { it.levelPercent.toFloat() },
                            StatusColors.accent, 0f, 100f)

                        val temps = history.mapNotNull { it.temperatureCelsius?.toFloat() }
                        if (temps.size >= 2) {
                            HistoryGraph("Temperature (°C)", temps, StatusColors.warn)
                        } else {
                            EmptyGraphNote("Temperature", "not reported by this device")
                        }

                        val volts = history.mapNotNull { it.voltageMv?.div(1000f) }
                        if (volts.size >= 2) {
                            HistoryGraph("Voltage (V)", volts, StatusColors.ok)
                        } else {
                            EmptyGraphNote("Voltage", "not reported by this device")
                        }

                        val currents = history.mapNotNull { it.currentUa?.div(1000f) }
                        if (currents.size >= 2) {
                            HistoryGraph("Current (mA)", currents, MaterialTheme.colorScheme.tertiary)
                        } else {
                            EmptyGraphNote("Current", "device does not expose CURRENT_NOW")
                        }

                        Spacer(Modifier.height(6.dp))
                        val charging = history.count { it.charging }
                        Text(
                            "${history.size} samples in range · $charging charging, " +
                                "${history.size - charging} discharging",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.muted
                        )
                    }
                }
            }
        }

        items(sections.size) { i -> SectionCard(sections[i], showSources = true) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun HistoryGraph(
    title: String,
    values: List<Float>,
    color: androidx.compose.ui.graphics.Color,
    min: Float? = null,
    max: Float? = null
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                "min ${"%.1f".format(values.min())} · max ${"%.1f".format(values.max())} · " +
                    "now ${"%.1f".format(values.last())}",
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted
            )
        }
        Spacer(Modifier.height(4.dp))
        Sparkline(
            values = values,
            modifier = Modifier.fillMaxWidth().height(62.dp),
            color = color, minValue = min, maxValue = max
        )
    }
}

@Composable
private fun EmptyGraphNote(title: String, reason: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Unavailable — $reason", style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted)
    }
}

private inline fun androidx.compose.foundation.lazy.LazyListScope.items(
    count: Int,
    crossinline itemContent: @Composable (Int) -> Unit
) = items(count = count) { index -> itemContent(index) }
