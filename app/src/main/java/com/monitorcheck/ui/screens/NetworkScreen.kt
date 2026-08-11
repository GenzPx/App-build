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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monitorcheck.core.Fmt
import com.monitorcheck.monitor.Series
import com.monitorcheck.network.NetworkTools
import com.monitorcheck.network.ToolResult
import com.monitorcheck.ui.MonitorViewModel
import com.monitorcheck.ui.components.MetricValue
import com.monitorcheck.ui.components.MonoRow
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.components.SectionCard
import com.monitorcheck.ui.components.Sparkline
import com.monitorcheck.ui.theme.MonoNumberStyle
import com.monitorcheck.ui.theme.StatusColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class NetTab(val label: String) { STATUS("Status"), TOOLS("Tools"), INTERFACES("Interfaces") }

@Composable
fun NetworkScreen(vm: MonitorViewModel, contentPadding: PaddingValues) {
    var tab by remember { mutableStateOf(NetTab.STATUS) }

    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, top = contentPadding.calculateTopPadding(), end = 12.dp)
        ) {
            NetTab.entries.forEach { t ->
                FilterChip(
                    selected = tab == t,
                    onClick = { tab = t },
                    label = { Text(t.label) },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
        val inner = PaddingValues(top = 8.dp, bottom = contentPadding.calculateBottomPadding())
        when (tab) {
            NetTab.STATUS -> NetworkStatusTab(vm, inner)
            NetTab.TOOLS -> NetworkToolsTab(inner)
            NetTab.INTERFACES -> NetworkInterfacesTab(vm, inner)
        }
    }
}

@Composable
private fun NetworkStatusTab(vm: MonitorViewModel, padding: PaddingValues) {
    val sample by vm.sample.collectAsStateWithLifecycle()
    val version by vm.seriesVersion.collectAsStateWithLifecycle()
    val sections = remember(sample?.timestamp) {
        val r = vm.networkRepo
        listOf(r.capabilitiesSection(), r.linkPropertiesSection(), r.wifiSection(),
            r.mobileSection(), r.trafficSection())
    }

    LazyColumn(contentPadding = padding) {
        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Realtime throughput", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    val t = sample?.throughput
                    if (t != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Download", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                MetricValue(Fmt.bytesPerSecond(t.rxRateBps), color = StatusColors.accent)
                                Text(Fmt.bitsPerSecond(t.rxRateBps * 8),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusColors.muted)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Upload", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                MetricValue(Fmt.bytesPerSecond(t.txRateBps), color = StatusColors.ok)
                                Text(Fmt.bitsPerSecond(t.txRateBps * 8),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusColors.muted)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        val v = version
                        Text("Download", style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.muted)
                        Sparkline(
                            values = vm.series.snapshot(Series.NET_DOWN),
                            modifier = Modifier.fillMaxWidth().height(60.dp),
                            color = StatusColors.accent
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Upload", style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.muted)
                        Sparkline(
                            values = vm.series.snapshot(Series.NET_UP),
                            modifier = Modifier.fillMaxWidth().height(60.dp),
                            color = StatusColors.ok
                        )
                        Spacer(Modifier.height(10.dp))
                        MonoRow("total received", Fmt.bytes(t.rxBytes))
                        MonoRow("total transmitted", Fmt.bytes(t.txBytes))
                    } else {
                        Text("TrafficStats counters are unavailable on this device.",
                            style = MaterialTheme.typography.bodySmall, color = StatusColors.muted)
                    }
                }
            }
        }
        items(sections.size) { i -> SectionCard(sections[i], showSources = true) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun NetworkInterfacesTab(vm: MonitorViewModel, padding: PaddingValues) {
    val sample by vm.sample.collectAsStateWithLifecycle()
    val ifaces = remember(sample?.timestamp) { vm.networkRepo.interfaces() }

    LazyColumn(contentPadding = padding) {
        if (!ifaces.isAvailable) {
            item {
                NoticeCard("Interfaces ${ifaces.status.label}",
                    ifaces.note ?: "No network interfaces could be enumerated.")
            }
        } else {
            items(ifaces.value!!.size) { i ->
                val iface = ifaces.value!![i]
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(iface.name, style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary)
                            Text(
                                if (iface.isUp) "UP" else "DOWN",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (iface.isUp) StatusColors.ok else StatusColors.muted
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        iface.addresses.forEach { MonoRow("addr", it) }
                        MonoRow("mtu", if (iface.mtu > 0) iface.mtu.toString() else "Unavailable")
                        MonoRow("mac", iface.hardwareAddress ?: "Restricted by Android")
                        MonoRow("rx", iface.rxBytes?.let { Fmt.bytes(it) } ?: "Unavailable")
                        MonoRow("tx", iface.txBytes?.let { Fmt.bytes(it) } ?: "Unavailable")
                        if (iface.isLoopback) {
                            Text("loopback interface", style = MaterialTheme.typography.labelSmall,
                                color = StatusColors.muted)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun NetworkToolsTab(padding: PaddingValues) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("1.1.1.1") }
    var port by remember { mutableStateOf("443") }
    var url by remember { mutableStateOf("https://example.com") }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }

    fun run(label: String, block: suspend () -> ToolResult) {
        if (busy) return
        busy = true; busyLabel = label; result = null
        job = scope.launch {
            val r = try { block() } catch (t: Throwable) {
                ToolResult(false, label, "Failed: ${t.message}")
            }
            result = r; busy = false
        }
    }

    LazyColumn(contentPadding = padding) {
        item {
            NoticeCard(
                title = "User-initiated only",
                body = "These tools run only when you press a button and only against the host " +
                    "you enter. Monitored Check performs no automatic scanning and no background " +
                    "network activity. The public IP lookup is the only feature that contacts a " +
                    "third-party service, and it says so before running."
            )
        }

        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Target", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = host, onValueChange = { host = it },
                        label = { Text("Host or IP") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        OutlinedTextField(
                            value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
                            label = { Text("Port") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(120.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = url, onValueChange = { url = it },
                            label = { Text("URL for HTTP test") }, singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
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
                    Text("Diagnostics", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))

                    ToolButtonRow(
                        listOf(
                            "Ping" to { run("Ping") { NetworkTools.ping(host) } },
                            "Latency" to {
                                run("Latency") {
                                    NetworkTools.latencyTest(host, port.toIntOrNull() ?: 443)
                                }
                            }
                        )
                    )
                    ToolButtonRow(
                        listOf(
                            "DNS lookup" to { run("DNS") { NetworkTools.dnsLookup(host) } },
                            "Reverse DNS" to { run("rDNS") { NetworkTools.reverseDns(host) } }
                        )
                    )
                    ToolButtonRow(
                        listOf(
                            "HTTP test" to { run("HTTP") { NetworkTools.httpTest(url) } },
                            "Port check" to {
                                run("Port") {
                                    NetworkTools.portCheck(host, port.toIntOrNull() ?: 80)
                                }
                            }
                        )
                    )
                    ToolButtonRow(
                        listOf(
                            "Traceroute" to { run("Traceroute") { NetworkTools.traceroute(host) } },
                            "Public IP" to { run("Public IP") { NetworkTools.publicIp() } }
                        )
                    )
                    ToolButtonRow(
                        listOf(
                            "Download test" to {
                                run("Download") { NetworkTools.downloadTest() }
                            },
                            "Upload test" to { run("Upload") { NetworkTools.uploadTest() } }
                        )
                    )

                    if (busy) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.height(18.dp).width(18.dp),
                                strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Running $busyLabel…",
                                style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(10.dp))
                            OutlinedButton(onClick = { job?.cancel(); busy = false }) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
        }

        result?.let { r ->
            item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = (if (r.success) StatusColors.ok else StatusColors.critical)
                            .copy(alpha = 0.10f))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(r.title, style = MaterialTheme.typography.titleMedium,
                                color = if (r.success) StatusColors.ok else StatusColors.critical)
                            if (r.durationMs > 0) {
                                Text("${r.durationMs} ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusColors.muted)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            r.output,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ToolButtonRow(actions: List<Pair<String, () -> Unit>>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        actions.forEachIndexed { i, (label, action) ->
            Button(onClick = action, modifier = Modifier.weight(1f)) { Text(label) }
            if (i < actions.lastIndex) Spacer(Modifier.width(8.dp))
        }
    }
}

private inline fun androidx.compose.foundation.lazy.LazyListScope.items(
    count: Int,
    crossinline itemContent: @Composable (Int) -> Unit
) = items(count = count) { index -> itemContent(index) }
