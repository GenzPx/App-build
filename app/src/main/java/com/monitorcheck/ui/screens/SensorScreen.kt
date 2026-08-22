package com.monitorcheck.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.monitorcheck.hardware.sensor.SensorInfo
import com.monitorcheck.hardware.sensor.SensorReading
import com.monitorcheck.hardware.sensor.SensorRepository
import com.monitorcheck.monitor.RingBuffer
import com.monitorcheck.ui.components.MonoRow
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.components.Sparkline
import com.monitorcheck.ui.components.StatusChip
import com.monitorcheck.ui.theme.MonoNumberStyle
import com.monitorcheck.ui.theme.StatusColors
import java.util.Locale

@Composable
fun SensorScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val repo = remember { SensorRepository(context.applicationContext) }
    val sensors = remember { repo.allSensors() }
    var query by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf<Int?>(null) }

    LazyColumn(contentPadding = contentPadding) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search sensors") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        if (!sensors.isAvailable) {
            item {
                NoticeCard(
                    title = "Sensors ${sensors.status.label}",
                    body = sensors.note ?: "No sensors were reported by this device.",
                    tone = StatusColors.warn
                )
            }
        } else {
            val list = sensors.value!!.filter {
                query.isBlank() || it.name.contains(query, true) ||
                    it.typeName.contains(query, true) || it.vendor.contains(query, true)
            }

            item {
                Text(
                    "${list.size} of ${sensors.value!!.size} sensors",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.muted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            items(list, key = { it.id }) { info ->
                SensorCard(
                    info = info,
                    repo = repo,
                    expanded = expandedId == info.id,
                    onToggle = { expandedId = if (expandedId == info.id) null else info.id }
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SensorCard(
    info: SensorInfo,
    repo: SensorRepository,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(info.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${info.typeName} · ${info.vendor}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.muted
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                LiveSensorValues(info, repo)
                Spacer(Modifier.height(12.dp))

                val unit = SensorRepository.unitFor(info.type)
                MonoRow("type", "${info.typeName} (${info.type})")
                info.stringType?.let { MonoRow("string type", it) }
                MonoRow("vendor", info.vendor)
                MonoRow("version", info.version.toString())
                MonoRow("max range", "${info.maximumRange} $unit")
                MonoRow("resolution", "${info.resolution} $unit")
                MonoRow("power", "${info.power} mA")
                MonoRow("min delay", if (info.minDelayUs > 0) "${info.minDelayUs} µs" else "n/a")
                MonoRow("max rate", info.maxRateHz?.let {
                    String.format(Locale.US, "%.1f Hz", it) } ?: "Unavailable")
                MonoRow("max delay", if (info.maxDelayUs > 0) "${info.maxDelayUs} µs" else "n/a")
                MonoRow("reporting", info.reportingMode)
                MonoRow("wake-up", if (info.isWakeUp) "Yes" else "No")
                MonoRow("dynamic", if (info.isDynamic) "Yes" else "No")
                MonoRow("FIFO events", info.maxEventCount.toString())
            }
        }
    }
}

@Composable
private fun LiveSensorValues(info: SensorInfo, repo: SensorRepository) {
    val sensor = remember(info.id) { repo.sensorById(info.id) }
    var reading by remember(info.id) { mutableStateOf<SensorReading?>(null) }
    val history = remember(info.id) { RingBuffer(80) }
    var version by remember(info.id) { mutableStateOf(0) }

    if (sensor == null) {
        StatusChip(com.monitorcheck.core.DataStatus.UNAVAILABLE)
        return
    }

    LaunchedEffect(info.id) {
        repo.observe(sensor).collect { r ->
            reading = r
            r.values.firstOrNull()?.let { history.add(it) }
            version++
        }
    }

    val r = reading
    if (r == null) {
        Text(
            "Waiting for the first event… One-shot and trigger sensors only report when " +
                "their condition occurs.",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted
        )
        return
    }

    val unit = SensorRepository.unitFor(info.type)
    val axes = listOf("X", "Y", "Z", "W", "5", "6")
    Column {
        r.values.take(6).forEachIndexed { i, v ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (r.values.size == 1) "value" else axes.getOrElse(i) { "$i" },
                    style = MonoNumberStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    String.format(Locale.US, "%.4f %s", v, unit),
                    style = MonoNumberStyle,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "accuracy: ${SensorRepository.accuracyLabel(r.accuracy)} · " +
                "timestamp: ${r.timestampNanos}",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted
        )
        val v = version
        if (history.size >= 2) {
            Spacer(Modifier.height(8.dp))
            Sparkline(
                values = history.toList(),
                modifier = Modifier.fillMaxWidth().height(64.dp),
                color = StatusColors.accent
            )
        }
    }
}
