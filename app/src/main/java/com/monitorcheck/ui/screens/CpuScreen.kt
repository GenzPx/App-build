package com.monitorcheck.ui.screens

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
import com.monitorcheck.monitor.Series
import com.monitorcheck.ui.MonitorViewModel
import com.monitorcheck.ui.components.MetricValue
import com.monitorcheck.ui.components.SectionCard
import com.monitorcheck.ui.components.Sparkline
import com.monitorcheck.ui.components.StatusChip
import com.monitorcheck.ui.components.UsageBar
import com.monitorcheck.ui.components.loadColor
import com.monitorcheck.ui.theme.MonoNumberStyle
import com.monitorcheck.ui.theme.StatusColors

@Composable
fun CpuScreen(vm: MonitorViewModel, contentPadding: PaddingValues) {
    val sample by vm.sample.collectAsStateWithLifecycle()
    val version by vm.seriesVersion.collectAsStateWithLifecycle()
    // Static CPU description is read once; it does not change at runtime.
    val staticSections = remember { vm.cpuRepo.staticInfo() }

    LazyColumn(contentPadding = contentPadding) {
        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Total utilisation", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    val usage = sample?.cpu?.totalPercent
                    if (usage?.isAvailable == true) {
                        val pct = usage.value!!
                        MetricValue(Fmt.percent(pct), color = loadColor(pct))
                        Spacer(Modifier.height(8.dp))
                        UsageBar((pct / 100.0).toFloat(), color = loadColor(pct))
                        Spacer(Modifier.height(10.dp))
                        val v = version
                        Sparkline(
                            values = vm.series.snapshot(Series.CPU),
                            modifier = Modifier.fillMaxWidth().height(90.dp),
                            color = loadColor(pct), minValue = 0f, maxValue = 100f
                        )
                    } else {
                        StatusChip(usage?.status ?: com.monitorcheck.core.DataStatus.UNAVAILABLE)
                        usage?.note?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = StatusColors.muted)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    val load = sample?.cpu?.loadAverage
                    Text(
                        "Load average: ${load?.display { "${it.first}  ${it.second}  ${it.third}" } ?: "Unavailable"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val temp = remember(sample?.timestamp) { vm.cpuRepo.cpuTemperature() }
                    Text(
                        "CPU temperature: ${temp.display { Fmt.temperature(it) }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                    Text("Per-core state", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Frequencies from /sys/devices/system/cpu, utilisation from /proc/stat deltas",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.muted
                    )
                    Spacer(Modifier.height(10.dp))

                    val cores = sample?.cpu?.cores.orEmpty()
                    if (cores.isEmpty()) {
                        StatusChip(com.monitorcheck.core.DataStatus.UNAVAILABLE)
                    } else {
                        cores.forEach { core ->
                            Column(Modifier.padding(vertical = 5.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "CPU${core.id}${if (!core.online) " (offline)" else ""}",
                                        style = MonoNumberStyle,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        buildString {
                                            append(core.currentKHz?.let { Fmt.freqKHz(it) } ?: "freq n/a")
                                            append("  ")
                                            append(core.usagePercent?.let { Fmt.percent(it, 0) } ?: "—")
                                        },
                                        style = MonoNumberStyle,
                                        color = core.usagePercent?.let { loadColor(it) }
                                            ?: MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(3.dp))
                                UsageBar(
                                    fraction = ((core.usagePercent ?: 0.0) / 100.0).toFloat(),
                                    color = loadColor(core.usagePercent ?: 0.0),
                                    height = 5
                                )
                                Text(
                                    buildString {
                                        append("min ${core.minKHz?.let { Fmt.freqKHz(it) } ?: "n/a"}")
                                        append(" · max ${core.maxKHz?.let { Fmt.freqKHz(it) } ?: "n/a"}")
                                        core.governor?.let { append(" · $it") }
                                    },
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
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Frequency history", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    val v = version
                    val freqSeries = vm.series.snapshot(Series.CPU_FREQ)
                    if (freqSeries.size >= 2) {
                        Sparkline(
                            values = freqSeries,
                            modifier = Modifier.fillMaxWidth().height(80.dp),
                            color = StatusColors.accent
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "peak core clock in MHz · min ${freqSeries.min().toInt()} / " +
                                "max ${freqSeries.max().toInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.muted
                        )
                    } else {
                        Text("Collecting samples…", style = MaterialTheme.typography.bodySmall,
                            color = StatusColors.muted)
                    }
                }
            }
        }

        items(staticSections.size) { i -> SectionCard(staticSections[i], showSources = true) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Small helper so LazyListScope.items(count) reads naturally above. */
private inline fun androidx.compose.foundation.lazy.LazyListScope.items(
    count: Int,
    crossinline itemContent: @Composable (Int) -> Unit
) = items(count = count) { index -> itemContent(index) }
