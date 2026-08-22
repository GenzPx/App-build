package com.monitorcheck.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.monitorcheck.core.DataStatus
import com.monitorcheck.security.AppPermissionSummary
import com.monitorcheck.security.PermissionGroupKind
import com.monitorcheck.security.PermissionInspector
import com.monitorcheck.ui.components.MonoRow
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.components.StatusChip
import com.monitorcheck.ui.theme.StatusColors

@Composable
fun PermissionScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val inspector = remember { PermissionInspector(context.applicationContext) }

    var summaries by remember { mutableStateOf<List<AppPermissionSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var group by remember { mutableStateOf<PermissionGroupKind?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var showSystem by remember { mutableStateOf(false) }

    val historyAvailability = remember { inspector.usageHistoryAvailability() }

    LaunchedEffect(Unit) {
        summaries = inspector.inspectAll()
        loading = false
    }

    val visible = summaries
        .filter { showSystem || !it.isSystem }
        .filter { s -> group == null || s.granted.any { it.group == group } }

    LazyColumn(contentPadding = contentPadding) {
        item {
            NoticeCard(
                title = "Usage history: ${historyAvailability.status.label}",
                body = historyAvailability.note ?: "",
                tone = StatusColors.warn,
                action = {
                    inspector.privacyDashboardIntent()?.let { intent ->
                        Button(onClick = { runCatching { context.startActivity(intent) } }) {
                            Text("Open system Privacy dashboard")
                        }
                    }
                }
            )
        }

        item {
            Column(Modifier.padding(horizontal = 12.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(
                        selected = group == null, onClick = { group = null },
                        label = { Text("All groups") }, modifier = Modifier.padding(end = 6.dp)
                    )
                    PermissionGroupKind.entries.forEach { g ->
                        FilterChip(
                            selected = group == g, onClick = { group = g },
                            label = { Text(g.label) }, modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    FilterChip(
                        selected = !showSystem, onClick = { showSystem = false },
                        label = { Text("User apps") }, modifier = Modifier.padding(end = 6.dp)
                    )
                    FilterChip(
                        selected = showSystem, onClick = { showSystem = true },
                        label = { Text("Include system") }
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (loading) "Inspecting installed applications…"
                    else "${visible.size} apps · sorted by count of granted dangerous permissions",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.muted
                )
            }
        }

        if (loading) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }
            }
        }

        items(visible.size) { i ->
            val app = visible[i]
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
                    .clickable {
                        expanded = if (expanded == app.packageName) null else app.packageName
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyMedium)
                            Text(app.packageName, style = MaterialTheme.typography.labelSmall,
                                color = StatusColors.muted, maxLines = 1)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${app.granted.size} granted",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (app.sensitiveGrantedCount > 0) StatusColors.warn
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text("${app.sensitiveGrantedCount} dangerous",
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusColors.muted)
                        }
                    }

                    if (expanded == app.packageName) {
                        Spacer(Modifier.height(10.dp))
                        val grouped = app.granted.groupBy { it.group }
                        grouped.forEach { (g, perms) ->
                            Text(g.label, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                            perms.forEach { p ->
                                Row(Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("· ${p.shortName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f))
                                    Text(p.protectionLevel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (p.protectionLevel.contains("dangerous"))
                                            StatusColors.warn else StatusColors.muted)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        if (app.denied.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Requested but not granted (${app.denied.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            app.denied.take(25).forEach {
                                Text("· ${it.shortName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusColors.muted)
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings
                                            .ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.fromParts("package", app.packageName, null)
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }) { Text("Manage permissions") }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun OwnPermissionsSection() {
    val context = LocalContext.current
    val inspector = remember { PermissionInspector(context.applicationContext) }
    val perms = remember { inspector.ownPermissions() }

    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Monitored Check permissions", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            perms.sortedBy { it.shortName }.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(p.shortName, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f))
                    StatusChip(
                        if (p.status == DataStatus.AVAILABLE) DataStatus.AVAILABLE
                        else DataStatus.NOT_REQUESTED
                    )
                }
            }
        }
    }
}

private inline fun androidx.compose.foundation.lazy.LazyListScope.items(
    count: Int,
    crossinline itemContent: @Composable (Int) -> Unit
) = items(count = count) { index -> itemContent(index) }
