package com.monitorcheck.ui.screens

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monitorcheck.core.SettingsRepository
import com.monitorcheck.monitor.AlertEvent
import com.monitorcheck.ui.MonitorViewModel
import com.monitorcheck.ui.components.MonoRow
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.theme.StatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun AlertsScreen(vm: MonitorViewModel, contentPadding: PaddingValues) {
    val settings by vm.settings.collectAsStateWithLifecycle(); var events by remember { mutableStateOf<List<AlertEvent>>(emptyList()) }
    LaunchedEffect(Unit) { events = withContext(Dispatchers.IO) { vm.alertEventStore.recent(30) } }
    LazyColumn(contentPadding = contentPadding) {
        item { NoticeCard("Threshold alerts", "Notifikasi hanya dari reading real. Cooldown mencegah spam.") }
        item { AlertCard("General") { SwitchLine("Enable alerts", settings.alertsEnabled) { vm.setAlertsEnabled(it) }; Text("Cooldown"); Row { SettingsRepository.ALERT_COOLDOWN_OPTIONS.forEach { value -> FilterChip(selected = settings.alertCooldownMs == value, onClick = { vm.setAlertCooldown(value) }, label = { Text(if (value >= 60 * 60_000L) "1h" else "${value / 60_000L}m") }, modifier = Modifier.padding(end = 5.dp)) } } } }
        item { AlertCard("System thresholds") {
            Threshold("CPU usage", settings.cpuAlertEnabled, settings.cpuAlertThresholdPercent, 40f..100f, "%.0f%%", { vm.setCpuAlertEnabled(it) }, { vm.setCpuAlertThreshold(it) })
            Threshold("RAM usage", settings.ramAlertEnabled, settings.ramAlertThresholdPercent, 40f..100f, "%.0f%%", { vm.setRamAlertEnabled(it) }, { vm.setRamAlertThreshold(it) })
            Threshold("CPU temperature", settings.cpuTempAlertEnabled, settings.cpuTempAlertThresholdC, 30f..80f, "%.1f°C", { vm.setCpuTempAlertEnabled(it) }, { vm.setCpuTempAlertThreshold(it) })
            Threshold("Device temperature", settings.deviceTempAlertEnabled, settings.deviceTempAlertThresholdC, 30f..90f, "%.1f°C", { vm.setDeviceTempAlertEnabled(it) }, { vm.setDeviceTempAlertThreshold(it) })
            Threshold("Storage free", settings.storageAlertEnabled, settings.storageFreeAlertThresholdPercent, 1f..50f, "%.0f%%", { vm.setStorageAlertEnabled(it) }, { vm.setStorageAlertThreshold(it) })
        } }
        item { AlertCard("Battery thresholds") {
            Threshold("Battery temperature", settings.batteryTempAlertEnabled, settings.batteryTempAlertThresholdC, 30f..60f, "%.1f°C", { vm.setBatteryTempAlertEnabled(it) }, { vm.setBatteryTempAlertThreshold(it) })
            Threshold("Low battery", settings.batteryLowAlertEnabled, settings.batteryLowAlertThresholdPercent, 1f..50f, "%.0f%%", { vm.setBatteryLowAlertEnabled(it) }, { vm.setBatteryLowAlertThreshold(it) })
        } }
        item { AlertCard("Recent alerts") { if (events.isEmpty()) Text("Belum ada event. Aktifkan background monitoring.", color = StatusColors.muted) else events.forEach { event -> MonoRow(event.title, android.text.format.DateFormat.format("dd MMM HH:mm", event.timestamp).toString()); Text(event.detail, style = MaterialTheme.typography.labelSmall, color = StatusColors.muted) } } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
@Composable private fun AlertCard(title: String, content: @Composable () -> Unit) { Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f))) { Column(Modifier.padding(14.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(8.dp)); content() } } }
@Composable private fun SwitchLine(title: String, checked: Boolean, onChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f)); Switch(checked, onChange) } }
@Composable private fun Threshold(title: String, enabled: Boolean, value: Float, range: ClosedFloatingPointRange<Float>, format: String, onEnabled: (Boolean) -> Unit, onValue: (Float) -> Unit) { Column { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f)); Text(format.format(java.util.Locale.US, value), color = StatusColors.warn); Switch(enabled, onEnabled) }; Slider(value, onValue, valueRange = range, enabled = enabled) } }
