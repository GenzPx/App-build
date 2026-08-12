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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
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
import com.monitorcheck.core.Fmt
import com.monitorcheck.monitor.HistoryAnalyzer
import com.monitorcheck.monitor.HistoryPoint
import com.monitorcheck.ui.MonitorViewModel
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.components.Sparkline
import com.monitorcheck.ui.theme.StatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HistoryScreen(vm: MonitorViewModel, contentPadding: PaddingValues) {
    val ranges = remember { listOf("1 hour" to 3_600_000L, "6 hours" to 6 * 3_600_000L, "24 hours" to 24 * 3_600_000L, "7 days" to 7 * 24 * 3_600_000L, "30 days" to 30 * 24 * 3_600_000L) }
    var selected by remember { mutableStateOf(ranges[2]) }
    var points by remember { mutableStateOf<List<HistoryPoint>>(emptyList()) }
    LaunchedEffect(selected) { points = withContext(Dispatchers.IO) { vm.monitoringHistoryStore.query(System.currentTimeMillis() - selected.second) } }
    LazyColumn(contentPadding = contentPadding) {
        item { NoticeCard("Monitoring history", "Metric disimpan lokal paling banyak satu sample per menit.") }
        item { Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) { ranges.forEach { range -> FilterChip(selected = selected == range, onClick = { selected = range }, label = { Text(range.first) }, modifier = Modifier.padding(end = 5.dp)) } } }
        if (points.isEmpty()) item { NoticeCard("No history yet", "Start monitoring for at least a minute.") }
        else {
            item { HistoryMetricCard("CPU", points.mapNotNull { it.cpuPercent }, "%", 0.0, 100.0) }
            item { HistoryMetricCard("RAM", points.mapNotNull { it.ramPercent }, "%", 0.0, 100.0) }
            item { HistoryMetricCard("Temperature", points.mapNotNull { it.deviceTempC }, "°C", null, null) }
            item { HistoryMetricCard("Battery", points.mapNotNull { it.batteryPercent }, "%", 0.0, 100.0) }
            item {
                val findings = HistoryAnalyzer.findings(points)
                Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f))) {
                    Column(Modifier.padding(14.dp)) { Text("Findings", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); if (findings.isEmpty()) Text("No repeated pattern found.", color = StatusColors.muted) else findings.forEach { Text(it.title, color = StatusColors.warn); Text(it.detail, color = StatusColors.muted, modifier = Modifier.padding(bottom = 6.dp)) } }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable private fun HistoryMetricCard(title: String, values: List<Double>, unit: String, low: Double?, high: Double?) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f))) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            if (values.isEmpty()) Text("Unavailable", color = StatusColors.muted) else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(Fmt.number(values.last(), unit), style = MaterialTheme.typography.headlineSmall); Text("avg ${Fmt.number(values.average(), unit)} · min ${Fmt.number(values.min(), unit)} · max ${Fmt.number(values.max(), unit)}", style = MaterialTheme.typography.labelSmall, color = StatusColors.muted) }
                Sparkline(values.map { it.toFloat() }, Modifier.fillMaxWidth().height(56.dp), StatusColors.accent, low?.toFloat(), high?.toFloat())
            }
        }
    }
}
