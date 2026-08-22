package com.monitorcheck.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.monitorcheck.ui.screens.*
import com.monitorcheck.ui.theme.StatusColors

private enum class TopLevel(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "Dashboard", Icons.Default.Dashboard),
    MONITOR("monitor", "Monitor", Icons.Default.Speed),
    APPS("apps", "Apps", Icons.Default.Apps),
    NETWORK("network", "Network", Icons.Default.Wifi),
    MORE("more", "More", Icons.Default.MoreHoriz)
}

private val titles = mapOf(
    "dashboard" to "Monitored Check", "monitor" to "Monitor", "apps" to "Applications", "network" to "Network", "more" to "More",
    "cpu" to "CPU", "gpu" to "GPU", "memory" to "Memory", "storage" to "Storage", "battery" to "Battery", "thermal" to "Thermal",
    "sensors" to "Sensors", "fps" to "Display & FPS", "tasks" to "Task manager", "device" to "Device information", "kernel" to "Kernel",
    "selinux" to "SELinux", "binder" to "Binder", "drivers" to "Drivers", "display" to "Display", "permissions" to "Permission inspector",
    "scanner" to "Pattern Scanner", "logs" to "Logs & crashes", "report" to "Export report", "alerts" to "Threshold alerts",
    "history" to "History & trends", "diagnosis" to "Device diagnosis", "overlay" to "Floating HUD", "stress" to "Stress test",
    "power" to "Doze & background activity", "guide" to "Guide App", "settings" to "Settings",
    "live" to "Live Monitor", "credits" to "Credits"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitoredCheckApp(vm: MonitorViewModel, initialRoute: String = "dashboard") {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "dashboard"
    val running by vm.running.collectAsStateWithLifecycle()
    val paused by vm.paused.collectAsStateWithLifecycle()
    val topLevel = TopLevel.entries.any { it.route == route }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titles[route] ?: "Monitored Check") },
                navigationIcon = {
                    if (!topLevel) IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (running) IconButton(onClick = { vm.togglePause() }) {
                        Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            if (paused) "Resume" else "Pause",
                            tint = if (paused) StatusColors.warn else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.toggleRunning() }) {
                        Icon(if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
                            if (running) "Stop monitoring" else "Start monitoring",
                            tint = if (running) StatusColors.ok else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            NavigationBar {
                TopLevel.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = route == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo("dashboard") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, destination.label) },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(navController, startDestination = initialRoute, modifier = Modifier.fillMaxSize()) {
            composable("dashboard") { DashboardScreen(vm, { navController.navigate(it) }, padding) }
            composable("monitor") { MenuScreen(MONITOR_ITEMS, padding) { navController.navigate(it) } }
            composable("apps") { AppsScreen(padding) }
            composable("network") { NetworkScreen(vm, padding) }
            composable("more") { MenuScreen(MORE_ITEMS, padding) { navController.navigate(it) } }
            composable("cpu") { CpuScreen(vm, padding) }; composable("gpu") { GpuScreen(padding) }
            composable("memory") { MemoryScreen(vm, padding) }; composable("storage") { StorageScreen(padding) }
            composable("battery") { BatteryScreen(vm, padding) }; composable("thermal") { ThermalScreen(vm, padding) }
            composable("sensors") { SensorScreen(padding) }; composable("fps") { FpsScreen(padding) }
            composable("tasks") { TaskScreen(vm, padding) }; composable("device") { DeviceInfoScreen(padding) }
            composable("kernel") { KernelScreen(padding) }; composable("selinux") { SelinuxScreen(padding) }
            composable("binder") { BinderScreen(padding) }; composable("drivers") { DriversScreen(padding) }
            composable("display") { DisplayScreen(padding) }; composable("permissions") { PermissionScreen(padding) }
            composable("scanner") { ScannerScreen(padding) }; composable("logs") { LogScreen(padding) }
            composable("report") { ReportScreen(padding) }; composable("alerts") { AlertsScreen(vm, padding) }
            composable("history") { HistoryScreen(vm, padding) }; composable("diagnosis") { DiagnosisScreen(vm, padding) }
            composable("overlay") { OverlayScreen(vm, padding) }; composable("stress") { StressTestScreen(vm, padding) }
            composable("power") { PowerActivityScreen(padding) }; composable("guide") { GuideScreen(padding) }
            composable("settings") { SettingsScreen(vm, padding) }
            composable("live") { LiveMonitorScreen(vm, padding) }
            composable("credits") { CreditsScreen(padding) }
        }
    }
}

private data class MenuItem(val route: String, val title: String, val subtitle: String)
private val MONITOR_ITEMS = listOf(
    MenuItem("live", "Live Monitor", "All realtime graphs on one page — CPU, cores, RAM, network, battery, thermal, FPS"),
    MenuItem("cpu", "CPU", "Cores, frequency and utilisation"), MenuItem("gpu", "GPU", "Renderer, OpenGL ES and Vulkan"),
    MenuItem("memory", "Memory", "RAM, swap and kernel detail"), MenuItem("battery", "Battery", "Level, current, temperature and history"),
    MenuItem("thermal", "Thermal", "Thermal zones and throttling"), MenuItem("sensors", "Sensors", "Inventory and live values"),
    MenuItem("storage", "Storage", "Volumes, analyzer and duplicates"), MenuItem("fps", "Display & FPS", "Refresh modes and own render-loop FPS"),
    MenuItem("tasks", "Task manager", "Processes, services and app activity"), MenuItem("history", "History & trends", "Persistent metric timeline"),
    MenuItem("alerts", "Threshold alerts", "Configure notification thresholds"), MenuItem("overlay", "Floating HUD", "CPU/RAM/temp over other apps"),
    MenuItem("stress", "Stress test", "Real CPU load and thermal log"), MenuItem("power", "Doze & background activity", "Power state and app activity")
)
private val MORE_ITEMS = listOf(
    MenuItem("device", "Device information", "Model, SoC and build"), MenuItem("kernel", "Kernel", "Version, uptime and verified boot"),
    MenuItem("selinux", "SELinux", "Enforcement state"), MenuItem("drivers", "Drivers", "Subsystem information"), MenuItem("binder", "Binder", "IPC diagnostics"),
    MenuItem("display", "Display", "Resolution, HDR and colour"), MenuItem("permissions", "Permission inspector", "App permissions"),
    MenuItem("scanner", "Pattern Scanner", "Local heuristic app/file scan"), MenuItem("logs", "Logs & crashes", "Own logs and crash reports"),
    MenuItem("report", "Export report", "Full TXT report"), MenuItem("diagnosis", "Device diagnosis", "One-tap hardware and app scan"),
    MenuItem("guide", "Guide App", "What each menu means"), MenuItem("credits", "Credits", "About, developer, licences and data sources"),
    MenuItem("settings", "Settings", "Intervals, widgets, alerts and theme")
)

@Composable
private fun MenuScreen(items: List<MenuItem>, padding: PaddingValues, onNavigate: (String) -> Unit) {
    var selected by remember { mutableStateOf<MenuItem?>(null) }
    selected?.let { item ->
        val guide = GuideCatalog.forRoute(item.route)
        AlertDialog(
            onDismissRequest = { selected = null }, title = { Text(guide.title) },
            text = { Column { Text(guide.purpose); Spacer(Modifier.height(8.dp)); Text("Cara kerja", color = MaterialTheme.colorScheme.primary); Text(guide.howItWorks); guide.limitation?.let { Spacer(Modifier.height(8.dp)); Text("Batasan", color = StatusColors.warn); Text(it) } } },
            confirmButton = { TextButton(onClick = { selected = null; onNavigate(item.route) }) { Text("Buka menu") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Tutup") } }
        )
    }
    LazyColumn(contentPadding = padding) {
        items(items.size) { index ->
            val item = items[index]
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable { selected = item }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f))) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text(item.title, style = MaterialTheme.typography.titleMedium); Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
private inline fun androidx.compose.foundation.lazy.LazyListScope.items(count: Int, crossinline itemContent: @Composable (Int) -> Unit) = items(count = count) { index -> itemContent(index) }
