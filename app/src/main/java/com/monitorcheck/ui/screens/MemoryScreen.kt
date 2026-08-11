package com.monitorcheck.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monitorcheck.core.Fmt
import com.monitorcheck.monitor.Series
import com.monitorcheck.ui.MonitorViewModel
import com.monitorcheck.ui.components.MetricValue
import com.monitorcheck.ui.components.MonoRow
import com.monitorcheck.ui.components.SectionCard
import com.monitorcheck.ui.components.Sparkline
import com.monitorcheck.ui.components.StatusChip
import com.monitorcheck.ui.components.UsageBar
import com.monitorcheck.ui.components.loadColor
import com.monitorcheck.ui.theme.StatusColors

@Composable
fun MemoryScreen(vm: MonitorViewModel, contentPadding: PaddingValues) {
    val sample by vm.sample.collectAsStateWithLifecycle()
    val version by vm.seriesVersion.collectAsStateWithLifecycle()
    // Recomputed each time a new sample arrives so kernel counters stay current.
    val sections = remember(sample?.timestamp) { vm.memoryRepo.infoSections() }

    LazyColumn(contentPadding = contentPadding) {
        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("RAM usage", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    val mem = sample?.memory
                    if (mem?.isAvailable == true) {
                        val m = mem.value!!
                        MetricValue(Fmt.percent(m.usedPercent), color = loadColor(m.usedPercent))
                        Spacer(Modifier.height(8.dp))
                        UsageBar((m.usedPercent / 100.0).toFloat(), color = loadColor(m.usedPercent))
                        Spacer(Modifier.height(10.dp))
                        MonoRow("total", Fmt.bytes(m.totalBytes))
                        MonoRow("used", Fmt.bytes(m.usedBytes))
                        MonoRow("available", Fmt.bytes(m.availableBytes))
                        m.freeBytes?.let { MonoRow("free", Fmt.bytes(it)) }
                        m.cachedBytes?.let { MonoRow("cached", Fmt.bytes(it)) }
                        m.swapUsedBytes?.let { MonoRow("swap used", Fmt.bytes(it)) }
                        m.zramTotalBytes?.let { MonoRow("zram size", Fmt.bytes(it)) }
                        if (m.lowMemory) {
                            Spacer(Modifier.height(6.dp))
                            Text("System is in LOW MEMORY state",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusColors.critical)
                        }
                        Spacer(Modifier.height(10.dp))
                        val v = version
                        Sparkline(
                            values = vm.series.snapshot(Series.RAM),
                            modifier = Modifier.fillMaxWidth().height(80.dp),
                            color = loadColor(m.usedPercent), minValue = 0f, maxValue = 100f
                        )
                        Text("used % over time", style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.muted)
                    } else {
                        StatusChip(mem?.status ?: com.monitorcheck.core.DataStatus.UNAVAILABLE)
                    }
                }
            }
        }

        items(sections.size) { i -> SectionCard(sections[i], showSources = true) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private inline fun androidx.compose.foundation.lazy.LazyListScope.items(
    count: Int,
    crossinline itemContent: @Composable (Int) -> Unit
) = items(count = count) { index -> itemContent(index) }
