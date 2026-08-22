package com.monitorcheck.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monitorcheck.apps.ProcessEntry
import com.monitorcheck.apps.ProcessRepository
import com.monitorcheck.apps.ServiceEntry
import com.monitorcheck.core.Fmt
import com.monitorcheck.core.Permissions
import com.monitorcheck.ui.MonitorViewModel
import com.monitorcheck.ui.components.MonoRow
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.components.StatusChip
import com.monitorcheck.ui.theme.StatusColors

private enum class TaskTab(val label: String) {
    PROCESSES("Processes"), SERVICES("Services"), ACTIVITY("App activity")
}

private enum class ProcSort(val label: String) { IMPORTANCE("Importance"), MEMORY("Memory"), NAME("Name") }

@Composable
fun TaskScreen(vm: MonitorViewModel, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val repo = remember { ProcessRepository(context.applicationContext) }
    val sample by vm.sample.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(TaskTab.PROCESSES) }
    var processes by remember { mutableStateOf<List<ProcessEntry>>(emptyList()) }
    var services by remember { mutableStateOf<com.monitorcheck.core.Reading<List<ServiceEntry>>?>(null) }
    var activity by remember {
        mutableStateOf<com.monitorcheck.core.Reading<List<Pair<String, Long>>>?>(null)
    }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(ProcSort.IMPORTANCE) }
    var selected by remember { mutableStateOf<ProcessEntry?>(null) }

    LaunchedEffect(sample?.timestamp, tab) {
        when (tab) {
            TaskTab.PROCESSES -> processes = repo.runningProcesses()
            TaskTab.SERVICES -> services = repo.runningServices()
            TaskTab.ACTIVITY -> activity = repo.recentAppActivity()
        }
    }

    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, top = contentPadding.calculateTopPadding(), end = 12.dp)
        ) {
            TaskTab.entries.forEach { t ->
                FilterChip(
                    selected = tab == t, onClick = { tab = t },
                    label = { Text(t.label) }, modifier = Modifier.padding(end = 6.dp)
                )
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(
                top = 8.dp, bottom = contentPadding.calculateBottomPadding())
        ) {
            item {
                NoticeCard(
                    title = if (repo.isRestricted) "Limited by Android" else "Full process list",
                    body = repo.restrictionNote,
                    tone = if (repo.isRestricted) StatusColors.warn else StatusColors.ok
                )
            }

            when (tab) {
                TaskTab.PROCESSES -> {
                    item {
                        Column {
                            OutlinedTextField(
                                value = query, onValueChange = { query = it },
                                label = { Text("Search processes") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            Row(Modifier.horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp)) {
                                ProcSort.entries.forEach { s ->
                                    FilterChip(
                                        selected = sort == s, onClick = { sort = s },
                                        label = { Text(s.label) },
                                        modifier = Modifier.padding(end = 6.dp)
                                    )
                                }
                            }
                            Text(
                                "${processes.size} processes visible to this app",
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusColors.muted,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                    }

                    val filtered = processes
                        .filter {
                            query.isBlank() || it.name.contains(query, true) ||
                                it.pid.toString().contains(query)
                        }
                        .let { list ->
                            when (sort) {
                                ProcSort.IMPORTANCE -> list.sortedBy { it.importanceValue }
                                ProcSort.MEMORY -> list.sortedByDescending { it.memoryPssBytes ?: 0 }
                                ProcSort.NAME -> list.sortedBy { it.name }
                            }
                        }

                    items(filtered, key = { it.pid }) { p ->
                        ProcessCard(p, selected?.pid == p.pid) {
                            selected = if (selected?.pid == p.pid) null else p
                        }
                    }

                    if (filtered.isEmpty()) {
                        item {
                            NoticeCard("No processes listed",
                                "Android did not return any process this app is allowed to see.")
                        }
                    }
                }

                TaskTab.SERVICES -> {
                    val s = services
                    if (s == null) {
                        item { Text("Loading…", Modifier.padding(16.dp)) }
                    } else if (s.value.isNullOrEmpty()) {
                        item {
                            NoticeCard(
                                "Services ${s.status.label}",
                                s.note ?: "No running services are visible to this app."
                            )
                        }
                    } else {
                        items(s.value!!) { svc ->
                            Card(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        .copy(alpha = 0.35f))
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(svc.name.substringAfterLast('.'),
                                        style = MaterialTheme.typography.titleMedium)
                                    Text(svc.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StatusColors.muted)
                                    Spacer(Modifier.height(6.dp))
                                    MonoRow("pid", svc.pid.toString())
                                    MonoRow("foreground", if (svc.foreground) "Yes" else "No")
                                    MonoRow("clients", svc.clientCount.toString())
                                    MonoRow("active for",
                                        Fmt.duration(android.os.SystemClock.elapsedRealtime() -
                                            svc.activeSince))
                                }
                            }
                        }
                    }
                }

                TaskTab.ACTIVITY -> {
                    val a = activity
                    if (a == null) {
                        item { Text("Loading…", Modifier.padding(16.dp)) }
                    } else if (!a.isAvailable) {
                        item {
                            NoticeCard(
                                "App activity ${a.status.label}",
                                a.note ?: "Not available.",
                                tone = StatusColors.warn,
                                action = {
                                    Button(onClick = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Permissions.usageAccessSettingsAction)
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        }
                                    }) { Text("Open Usage Access settings") }
                                }
                            )
                        }
                    } else {
                        item {
                            Text(
                                "${a.value!!.size} apps used in the last 24 hours (UsageStatsManager)",
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusColors.muted,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                        items(a.value!!) { (pkg, ts) ->
                            Card(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        .copy(alpha = 0.35f))
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(pkg, style = MaterialTheme.typography.bodySmall)
                                    Text(Fmt.time(ts),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StatusColors.muted)
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ProcessCard(p: ProcessEntry, expanded: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (p.isOwnProcess)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "pid ${p.pid}${p.uid?.let { " · uid $it" } ?: ""} · ${p.importance}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.muted
                    )
                }
                Text(
                    p.memoryPssBytes?.let { Fmt.bytes(it) } ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                MonoRow("pid", p.pid.toString())
                MonoRow("uid", p.uid?.toString() ?: "Unavailable")
                MonoRow("package", p.packageName ?: "Unavailable")
                MonoRow("importance", p.importance)
                MonoRow("threads", p.threadCount?.toString() ?: "Unavailable")
                MonoRow("state", p.state ?: "Unavailable")
                MonoRow("memory (PSS)", p.memoryPssBytes?.let { Fmt.bytes(it) } ?: "Unavailable")

                Spacer(Modifier.height(10.dp))
                if (p.isOwnProcess) {
                    Text(
                        "This is a Monitored Check process.",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.muted
                    )
                } else {
                    StatusChip(com.monitorcheck.core.DataStatus.RESTRICTED_BY_ANDROID)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Force stopping another app requires the system-only FORCE_STOP_PACKAGES " +
                            "permission. Use App Info to stop it through the system UI.",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.muted
                    )
                    p.packageName?.let { pkg ->
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.fromParts("package", pkg, null)
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }) { Text("Open App Info") }
                    }
                }
            }
        }
    }
}
