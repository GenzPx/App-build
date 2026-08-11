package com.monitorcheck.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monitorcheck.core.Fmt
import com.monitorcheck.hardware.thermal.ThermalCategory
import com.monitorcheck.monitor.Series
import com.monitorcheck.ui.MonitorViewModel
import com.monitorcheck.ui.components.MetricValue
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.components.Sparkline
import com.monitorcheck.ui.components.StatusChip
import com.monitorcheck.ui.components.UsageBar
import com.monitorcheck.ui.theme.MonoNumberStyle
import com.monitorcheck.ui.theme.StatusColors

@Composable
fun ThermalScreen(vm: MonitorViewModel, contentPadding: PaddingValues) {
    val sample by vm.sample.collectAsStateWithLifecycle()
    val version by vm.seriesVersion.collectAsStateWithLifecycle()
    // Re-read zones on every sample so temperatures stay live.
    val zones = remember(sample?.timestamp) { vm.thermalRepo.readZones() }
    val status = remember(sample?.timestamp) { vm.thermalRepo.thermalStatus() }

    LazyColumn(contentPadding = contentPadding) {
        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Thermal status", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    if (status.isAvailable) {
                        val value = vm.thermalRepo.thermalStatusValue() ?: 0
                        val color = when {
                            value >= 4 -> StatusColors.critical
                            value >= 2 -> StatusColors.warn
                            value >= 1 -> StatusColors.warn
                            else -> StatusColors.ok
                        }
                        Text(status.value!!, style = MaterialTheme.typography.titleLarge, color = color)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Reported by PowerManager.getCurrentThermalStatus() — the only " +
                                "official throttling signal available to apps.",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.muted
                        )
                    } else {
                        StatusChip(status.status)
                        status.note?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = StatusColors.muted)
                        }
                    }

                    val hottest = sample?.hottestCelsius
                    if (hottest?.isAvailable == true) {
                        Spacer(Modifier.height(14.dp))
                        Text("Hottest zone", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val c = hottest.value!!
                        MetricValue(Fmt.temperature(c), color = tempColor(c))
                        Spacer(Modifier.height(8.dp))
                        val v = version
                        Sparkline(
                            values = vm.series.snapshot(Series.DEVICE_TEMP),
                            modifier = Modifier.fillMaxWidth().height(80.dp),
                            color = tempColor(c)
                        )
                    }
                }
            }
        }

        if (!zones.isAvailable) {
            item {
                NoticeCard(
                    title = "Thermal zones ${zones.status.label}",
                    body = zones.note ?: "No readable thermal zones were found on this device.",
                    tone = StatusColors.warn
                )
            }
        } else {
            val grouped = zones.value!!.groupBy { it.category }
            grouped.forEach { (category, list) ->
                item {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(category.label, style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary)
                                Text("${list.size} zones",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusColors.muted)
                            }
                            Spacer(Modifier.height(8.dp))
                            list.sortedByDescending { it.celsius }.forEach { z ->
                                Column(Modifier.padding(vertical = 4.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            z.type,
                                            style = MonoNumberStyle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            Fmt.temperature(z.celsius),
                                            style = MonoNumberStyle,
                                            color = tempColor(z.celsius)
                                        )
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    // Scale bar spans 0-100 °C, the practical SoC range.
                                    UsageBar(
                                        fraction = (z.celsius / 100.0).toFloat().coerceIn(0f, 1f),
                                        color = tempColor(z.celsius),
                                        height = 4
                                    )
                                    Text(
                                        "zone${z.id}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StatusColors.muted
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                NoticeCard(
                    title = "About these readings",
                    body = "Zones come from /sys/class/thermal, which the kernel exports directly. " +
                        "Zone names are vendor-defined, so the grouping above is a best-effort " +
                        "classification of those raw names. Values are shown exactly as the kernel " +
                        "reports them, normalised to °C."
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun tempColor(c: Double) = when {
    c >= 60 -> StatusColors.critical
    c >= 45 -> StatusColors.warn
    else -> StatusColors.ok
}
