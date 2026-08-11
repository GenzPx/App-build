package com.monitorcheck.ui

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
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.monitorcheck.ui.screens.AboutScreen
import com.monitorcheck.ui.screens.AppsScreen
import com.monitorcheck.ui.screens.BenchmarkScreen
import com.monitorcheck.ui.screens.BatteryScreen
import com.monitorcheck.ui.screens.BinderScreen
import com.monitorcheck.ui.screens.CpuScreen
import com.monitorcheck.ui.screens.DashboardScreen
import com.monitorcheck.ui.screens.DeviceInfoScreen
import com.monitorcheck.ui.screens.DisplayScreen
import com.monitorcheck.ui.screens.DriversScreen
import com.monitorcheck.ui.screens.FpsScreen
import com.monitorcheck.ui.screens.GpuScreen
import com.monitorcheck.ui.screens.KernelScreen
import com.monitorcheck.ui.screens.LogScreen
import com.monitorcheck.ui.screens.MemoryScreen
import com.monitorcheck.ui.screens.NetworkScreen
import com.monitorcheck.ui.screens.PermissionScreen
import com.monitorcheck.ui.screens.ReportScreen
import com.monitorcheck.ui.screens.ScannerScreen
import com.monitorcheck.ui.screens.SelinuxScreen
import com.monitorcheck.ui.screens.SensorScreen
import com.monitorcheck.ui.screens.SettingsScreen
import com.monitorcheck.ui.screens.StorageScreen
import com.monitorcheck.ui.screens.TaskScreen
import com.monitorcheck.ui.theme.StatusColors

/** Bottom navigation destinations. */
private enum class TopLevel(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    DASHBOARD("dashboard", "Dashboard", Icons.Default.Dashboard),
    MONITOR("monitor", "Monitor", Icons.Default.Speed),
    APPS("apps", "Apps", Icons.Default.Apps),
    NETWORK("network", "Network", Icons.Default.Wifi),
    MORE("more", "More", Icons.Default.MoreHoriz)
}

/** Titles for detail routes reached from Monitor/More. */
private val ROUTE_TITLES = mapOf(
    "dashboard" to "Monitored Check",
    "monitor" to "Monitor",
    "apps" to "Applications",
    "network" to "Network",
    "more" to "More",
    "cpu" to "CPU",
    "gpu" to "GPU",
    "memory" to "Memory",
    "storage" to "Storage",
    "battery" to "Battery",
    "thermal" to "Thermal",
    "sensors" to "Sensors",
    "fps" to "Display & FPS",
    "tasks" to "Task manager",
    "device" to "Device information",
    "kernel" to "Kernel",
    "selinux" to "SELinux",
    "binder" to "Binder",
    "drivers" to "Drivers",
    "display" to "Display",
    "permissions" to "Permission inspector",
    "scanner" to "Pattern Scanner",
    "logs" to "Logs & crashes",
    "report" to "Export report",
    "settings" to "Settings",
    "benchmark" to "Benchmark",
    "about" to "About"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitoredCheckApp(vm: MonitorViewModel) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: TopLevel.DASHBOARD.route
    val running by vm.running.collectAsStateWithLifecycle()
    val paused by vm.paused.collectAsStateWithLifecycle()

    val isTopLevel = TopLevel.entries.any { it.route == route }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ROUTE_TITLES[route] ?: "Monitored Check") },
                navigationIcon = {
                    if (!isTopLevel) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    // Pause/resume only makes sense while the engine is running.
                    if (running) {
                        IconButton(onClick = { vm.togglePause() }) {
                            Icon(
                                if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (paused) "Resume" else "Pause",
                                tint = if (paused) StatusColors.warn
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { vm.toggleRunning() }) {
                        Icon(
                            if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (running) "Stop monitoring" else "Start monitoring",
                            tint = if (running) StatusColors.ok
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                TopLevel.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = route == dest.route,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(TopLevel.DASHBOARD.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, dest.label) },
                        label = { Text(dest.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevel.DASHBOARD.route,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(TopLevel.DASHBOARD.route) {
                DashboardScreen(vm, { navController.navigate(it) }, padding)
            }
            composable(TopLevel.MONITOR.route) {
                MenuScreen(MONITOR_ITEMS, padding) { navController.navigate(it) }
            }
            composable(TopLevel.APPS.route) { AppsScreen(padding) }
            composable(TopLevel.NETWORK.route) { NetworkScreen(vm, padding) }
            composable(TopLevel.MORE.route) {
                MenuScreen(MORE_ITEMS, padding) { navController.navigate(it) }
            }

            composable("cpu") { CpuScreen(vm, padding) }
            composable("gpu") { GpuScreen(padding) }
            composable("memory") { MemoryScreen(vm, padding) }
            composable("storage") { StorageScreen(padding) }
            composable("battery") { BatteryScreen(vm, padding) }
            composable("thermal") { com.monitorcheck.ui.screens.ThermalScreen(vm, padding) }
            composable("sensors") { SensorScreen(padding) }
            composable("fps") { FpsScreen(padding) }
            composable("tasks") { TaskScreen(vm, padding) }
            composable("device") { DeviceInfoScreen(padding) }
            composable("kernel") { KernelScreen(padding) }
            composable("selinux") { SelinuxScreen(padding) }
            composable("binder") { BinderScreen(padding) }
            composable("drivers") { DriversScreen(padding) }
            composable("display") { DisplayScreen(padding) }
            composable("permissions") { PermissionScreen(padding) }
            composable("scanner") { ScannerScreen(padding) }
            composable("logs") { LogScreen(padding) }
            composable("report") { ReportScreen(padding) }
            composable("settings") { SettingsScreen(vm, padding) }
            composable("benchmark") { BenchmarkScreen(padding) }
            composable("about") { AboutScreen(padding) }
        }
    }
}

private data class MenuItem(val route: String, val title: String, val subtitle: String)

private val MONITOR_ITEMS = listOf(
    MenuItem("cpu", "CPU", "Cores, clusters, per-core frequency and utilisation"),
    MenuItem("gpu", "GPU", "Renderer, OpenGL ES, Vulkan, extensions"),
    MenuItem("memory", "Memory", "RAM, swap, zRAM and kernel memory detail"),
    MenuItem("battery", "Battery", "Level, current, voltage, health and 30-day history"),
    MenuItem("thermal", "Thermal", "Every readable thermal zone plus throttling status"),
    MenuItem("sensors", "Sensors", "Full sensor inventory with live values"),
    MenuItem("storage", "Storage", "Volumes, analyzer, browser and duplicate finder"),
    MenuItem("fps", "Display & FPS", "Frame rate measurement and display capabilities"),
    MenuItem("tasks", "Task manager", "Processes, services and app activity"),
    MenuItem("benchmark", "Benchmark", "Measured CPU, memory, hashing and storage throughput")
)

private val MORE_ITEMS = listOf(
    MenuItem("device", "Device information", "Model, SoC, build, hardware features"),
    MenuItem("kernel", "Kernel", "Version, cmdline, uptime, boot and verified boot"),
    MenuItem("selinux", "SELinux", "Enforcement state and security context"),
    MenuItem("drivers", "Drivers", "Graphics, audio, camera, wireless, storage, input"),
    MenuItem("binder", "Binder", "IPC diagnostics and platform restrictions"),
    MenuItem("display", "Display", "Resolution, refresh rate, HDR and colour"),
    MenuItem("permissions", "Permission inspector", "Which apps hold which permissions"),
    MenuItem("scanner", "Pattern Scanner", "Local heuristic inspection of apps and files"),
    MenuItem("logs", "Logs & crashes", "Logcat viewer and local crash reports"),
    MenuItem("report", "Export report", "Generate a full TXT diagnostic report"),
    MenuItem("settings", "Settings", "Intervals, theme, widgets, background monitoring"),
    MenuItem("about", "About & support", "Creator, donations, licence, privacy policy")
)

@Composable
private fun MenuScreen(
    items: List<MenuItem>,
    padding: PaddingValues,
    onNavigate: (String) -> Unit
) {
    LazyColumn(contentPadding = padding) {
        items(items.size) { i ->
            val item = items[i]
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .clickable { onNavigate(item.route) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Text(item.subtitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private inline fun androidx.compose.foundation.lazy.LazyListScope.items(
    count: Int,
    crossinline itemContent: @Composable (Int) -> Unit
) = items(count = count) { index -> itemContent(index) }
