package com.monitorcheck.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.monitorcheck.core.Fmt
import com.monitorcheck.monitor.StressPoint
import com.monitorcheck.monitor.StressTestEngine
import com.monitorcheck.ui.MonitorViewModel
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.components.Sparkline
import com.monitorcheck.ui.theme.StatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun StressTestScreen(vm: MonitorViewModel, contentPadding: PaddingValues) {
    val scope = rememberCoroutineScope(); var duration by remember { mutableStateOf(60) }; var running by remember { mutableStateOf(false) }; var status by remember { mutableStateOf("") }; val points = remember { mutableStateListOf<StressPoint>() }
    LazyColumn(contentPadding = contentPadding) {
        item { NoticeCard("Real CPU stress test", "CPU akan panas dan baterai berkurang. Test berhenti otomatis di 60°C bila reading tersedia.", tone = StatusColors.warn) }
        item { Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f))) { Column(Modifier.padding(14.dp)) { Text("Duration"); Row { listOf(30, 60, 180, 300).forEach { seconds -> FilterChip(selected = duration == seconds, onClick = { duration = seconds }, label = { Text("${seconds}s") }, modifier = Modifier.padding(end = 4.dp)) } }; Row { Button(onClick = { if (!running) { running = true; points.clear(); scope.launch { val result = withContext(Dispatchers.Default) { StressTestEngine(vm.cpuRepo, vm.thermalRepo).run(duration) { point -> withContext(Dispatchers.Main) { if (points.size < 600) points.add(point); status = "${Fmt.duration(point.elapsedMs)} · CPU ${point.cpuPercent?.let { Fmt.percent(it, 0) } ?: "Unavailable"}" } } }; status = result.stoppedBecause; running = false } } }) { Text(if (running) "Running…" else "Start test") }; if (running) OutlinedButton(onClick = { running = false }) { Text("Stop") } }; Text(status, color = StatusColors.muted) } } }
        if (points.isNotEmpty()) item { Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f))) { Column(Modifier.padding(14.dp)) { val cpu = points.mapNotNull { it.cpuPercent }.map { it.toFloat() }; Text("CPU peak ${cpu.maxOrNull()?.toInt() ?: 0}%"); if (cpu.isNotEmpty()) Sparkline(cpu, Modifier.fillMaxWidth().height(56.dp), StatusColors.ok, 0f, 100f); val temp = points.mapNotNull { it.temperatureC }.map { it.toFloat() }; Text("Temperature peak ${temp.maxOrNull()?.let { Fmt.temperature(it.toDouble()) } ?: "Unavailable"}"); if (temp.isNotEmpty()) Sparkline(temp, Modifier.fillMaxWidth().height(56.dp), StatusColors.warn) } } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
