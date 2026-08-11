package com.monitorcheck.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monitorcheck.core.DashboardWidget
import com.monitorcheck.core.NotificationStyle
import com.monitorcheck.core.Permissions
import com.monitorcheck.core.SettingsRepository
import com.monitorcheck.core.ThemeMode
import com.monitorcheck.logs.CrashReporter
import com.monitorcheck.monitor.MonitoringService
import com.monitorcheck.ui.MonitorViewModel
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.theme.StatusColors

@Composable
fun SettingsScreen(vm: MonitorViewModel, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var notificationDenied by remember { mutableStateOf(false) }

    // POST_NOTIFICATIONS is required from Android 13 for the ongoing service notification.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.setBackgroundMonitoring(true)
            MonitoringService.start(context)
        } else {
            notificationDenied = true
        }
    }

    LazyColumn(contentPadding = contentPadding) {
        item {
            SettingsCard("Monitoring engine") {
                Text("Refresh interval", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    SettingsRepository.INTERVAL_OPTIONS.forEach { ms ->
                        FilterChip(
                            selected = settings.refreshIntervalMs == ms,
                            onClick = { vm.setRefreshInterval(ms) },
                            label = { Text(if (ms >= 1000) "${ms / 1000}s" else "${ms}ms") },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Default is 2 seconds. Sampling automatically slows to at least 10 seconds " +
                        "while the app is in the background.",
                    style = MaterialTheme.typography.labelSmall, color = StatusColors.muted
                )

                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    "Low Resource Mode",
                    "Skips the most expensive readings (thermal zones, GPU sysfs) and enforces a " +
                        "minimum 2 second interval. Recommended on low-end devices.",
                    settings.lowResourceMode
                ) { vm.setLowResourceMode(it) }

                SwitchRow(
                    "Auto Monitor on app launch",
                    "Starts the monitoring engine automatically when Monitored Check opens.",
                    settings.autoMonitorOnLaunch
                ) { vm.setAutoMonitor(it) }

                SwitchRow(
                    "Record battery history",
                    "Stores one battery sample per minute in a local database so the battery " +
                        "graphs can cover up to 30 days. Never leaves this device.",
                    settings.batteryHistoryEnabled
                ) { vm.setBatteryHistory(it) }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.clearSeries() }) { Text("Clear graph history") }
            }
        }

        item {
            SettingsCard("Background monitoring") {
                Text(
                    "Runs a foreground service with an ongoing notification showing live metrics. " +
                        "Disabled by default — it is entirely opt-in.",
                    style = MaterialTheme.typography.labelSmall, color = StatusColors.muted
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    "Enable background monitoring",
                    "Keeps sampling and updates a persistent notification.",
                    settings.backgroundMonitoring
                ) { enabled ->
                    if (enabled) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !Permissions.hasNotifications(context)
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            vm.setBackgroundMonitoring(true)
                            MonitoringService.start(context)
                        }
                    } else {
                        vm.setBackgroundMonitoring(false)
                        MonitoringService.stop(context)
                    }
                }

                if (notificationDenied) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Notification permission was denied, so the ongoing notification cannot " +
                            "be shown and background monitoring stays off. You can grant it later " +
                            "in Android settings.",
                        style = MaterialTheme.typography.labelSmall, color = StatusColors.warn
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text("Notification detail", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row {
                    NotificationStyle.entries.forEach { s ->
                        FilterChip(
                            selected = settings.notificationStyle == s,
                            onClick = { vm.setNotificationStyle(s) },
                            label = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Metrics in notification", style = MaterialTheme.typography.bodyMedium)
                SwitchRow("CPU", null, settings.notifyCpu) { vm.setNotifyCpu(it) }
                SwitchRow("RAM", null, settings.notifyRam) { vm.setNotifyRam(it) }
                SwitchRow("Battery", null, settings.notifyBattery) { vm.setNotifyBattery(it) }
                SwitchRow("Network", null, settings.notifyNetwork) { vm.setNotifyNetwork(it) }
                SwitchRow("Temperature", null, settings.notifyTemperature) {
                    vm.setNotifyTemperature(it)
                }
            }
        }

        item {
            SettingsCard("Appearance") {
                Text("Theme", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(
                            selected = settings.themeMode == m,
                            onClick = { vm.setTheme(m) },
                            label = {
                                Text(m.name.lowercase().replaceFirstChar { it.uppercase() })
                            },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                SwitchRow(
                    "Material You dynamic colour",
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        "Derives the palette from your wallpaper."
                    else "Requires Android 12 or later. This device uses the built-in palette.",
                    settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ) { vm.setDynamicColor(it) }
            }
        }

        item {
            SettingsCard("Dashboard widgets") {
                Text(
                    "Toggle cards on or off and reorder them. The dashboard shows only what you " +
                        "enable, which also reduces work for the monitoring engine.",
                    style = MaterialTheme.typography.labelSmall, color = StatusColors.muted
                )
                Spacer(Modifier.height(10.dp))
                val enabledIds = settings.dashboardWidgets
                // Enabled widgets first (in user order), then the disabled ones.
                val ordered = enabledIds.mapNotNull { DashboardWidget.fromId(it) } +
                    DashboardWidget.entries.filter { it.id !in enabledIds }

                ordered.forEach { w ->
                    val isEnabled = w.id in enabledIds
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(w.title, Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall)
                        if (isEnabled) {
                            IconButton(onClick = { vm.moveWidget(w.id, true) }) {
                                Icon(Icons.Default.ArrowUpward, "Move up",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { vm.moveWidget(w.id, false) }) {
                                Icon(Icons.Default.ArrowDownward, "Move down",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Switch(checked = isEnabled, onCheckedChange = { vm.toggleWidget(w.id, it) })
                    }
                }
            }
        }

        item {
            SettingsCard("Special access") {
                Text(
                    "Monitored Check requests nothing at first launch. These optional grants " +
                        "unlock specific features; everything works without them, just with fewer " +
                        "values available.",
                    style = MaterialTheme.typography.labelSmall, color = StatusColors.muted
                )
                Spacer(Modifier.height(10.dp))

                AccessRow(
                    "Usage Access",
                    "Per-app storage sizes, last-used times and app activity",
                    Permissions.hasUsageStats(context)
                ) {
                    runCatching {
                        context.startActivity(Intent(Permissions.usageAccessSettingsAction)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                AccessRow(
                    "All files access",
                    "Full storage analysis of shared storage",
                    Permissions.hasAllFilesAccess()
                ) {
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            context.startActivity(
                                Intent(android.provider.Settings
                                    .ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
                AccessRow(
                    "Location",
                    "Wi-Fi SSID and BSSID (required by Android for these fields)",
                    Permissions.hasLocation(context)
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                AccessRow(
                    "Notifications",
                    "Ongoing notification for background monitoring",
                    Permissions.hasNotifications(context)
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }

        item { OwnPermissionsSection() }

        item {
            NoticeCard(
                title = "Privacy",
                body = "Monitored Check has no analytics, no telemetry, no tracking and no crash " +
                    "upload. It stores monitoring history only in this app's private storage. The " +
                    "only time it touches the network is when you explicitly run a network tool " +
                    "(ping, DNS, HTTP test, speed test or public IP lookup) — and the public IP " +
                    "lookup is the sole feature that contacts a third party.",
                tone = StatusColors.ok
            )
        }

        item {
            SettingsCard("Diagnostics") {
                Text(
                    "Verify that local crash capture works. This intentionally crashes the app; " +
                        "reopen it and the report will be waiting under Logs → Crash reports.",
                    style = MaterialTheme.typography.labelSmall, color = StatusColors.muted
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    CrashReporter(context.applicationContext).triggerTestCrash()
                }) { Text("Trigger test crash") }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            description?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun AccessRow(title: String, description: String, granted: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted)
            Text(
                if (granted) "Granted" else "Not granted",
                style = MaterialTheme.typography.labelSmall,
                color = if (granted) StatusColors.ok else StatusColors.warn
            )
        }
        Spacer(Modifier.width(8.dp))
        if (!granted) {
            Button(onClick = onOpen) { Text("Grant") }
        } else {
            OutlinedButton(onClick = onOpen) { Text("Manage") }
        }
    }
}
