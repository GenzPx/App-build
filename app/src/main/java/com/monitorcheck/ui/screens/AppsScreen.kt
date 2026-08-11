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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.monitorcheck.apps.AppEntry
import com.monitorcheck.apps.AppFilter
import com.monitorcheck.apps.AppRepository
import com.monitorcheck.apps.AppSort
import com.monitorcheck.core.Fmt
import com.monitorcheck.core.Permissions
import com.monitorcheck.ui.components.MonoRow
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.theme.StatusColors

@Composable
fun AppsScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val repo = remember { AppRepository(context.applicationContext) }

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AppFilter.USER) }
    var sort by remember { mutableStateOf(AppSort.NAME) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        apps = repo.loadApps(includePermissions = true)
        loading = false
    }

    val statsAvailability = remember(reload) { repo.storageStatsAvailability() }
    val visible = repo.filterAndSort(apps, filter, sort, query)

    Column {
        Column(Modifier.padding(top = contentPadding.calculateTopPadding())) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Search apps") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                AppFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f, onClick = { filter = f },
                        label = { Text(f.label) }, modifier = Modifier.padding(end = 6.dp)
                    )
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp)) {
                AppSort.entries.forEach { s ->
                    FilterChip(
                        selected = sort == s, onClick = { sort = s },
                        label = { Text("Sort: ${s.label}") }, modifier = Modifier.padding(end = 6.dp)
                    )
                }
            }
        }

        LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
            if (!statsAvailability.isAvailable) {
                item {
                    NoticeCard(
                        title = "App sizes ${statsAvailability.status.label}",
                        body = (statsAvailability.note ?: "") + " Without it, only the APK file " +
                            "size is shown — user data and cache sizes stay Unavailable rather " +
                            "than being estimated.",
                        tone = StatusColors.warn,
                        action = {
                            Button(onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Permissions.usageAccessSettingsAction)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }) { Text("Grant Usage Access") }
                        }
                    )
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (loading) "Loading applications…"
                        else "${visible.size} of ${apps.size} apps",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.muted
                    )
                    OutlinedButton(onClick = { reload++ }, enabled = !loading) { Text("Refresh") }
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

            items(visible, key = { it.packageName }) { app ->
                AppCard(app, expanded == app.packageName) {
                    expanded = if (expanded == app.packageName) null else app.packageName
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AppCard(app: AppEntry, expanded: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.muted
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Fmt.bytes(app.totalSizeBytes ?: app.apkSizeBytes),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        if (app.isSystem) "system" else "user",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.muted
                    )
                }
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                MonoRow("version", "${app.versionName ?: "Unavailable"} (${app.versionCode})")
                MonoRow("target SDK", app.targetSdk.toString())
                MonoRow("min SDK", app.minSdk?.toString() ?: "Unavailable (needs Android 7+)")
                MonoRow("uid", app.uid.toString())
                MonoRow("installed", Fmt.timestamp(app.firstInstallTime))
                MonoRow("updated", Fmt.timestamp(app.lastUpdateTime))
                MonoRow("enabled", if (app.isEnabled) "Yes" else "No")
                MonoRow("last used", app.lastUsedTime?.let { Fmt.timestamp(it) }
                    ?: "Unavailable (needs Usage Access)")
                MonoRow("APK size", Fmt.bytes(app.apkSizeBytes))
                MonoRow("app size", app.appSizeBytes?.let { Fmt.bytes(it) }
                    ?: "Unavailable (needs Usage Access)")
                MonoRow("data size", app.dataSizeBytes?.let { Fmt.bytes(it) }
                    ?: "Unavailable (needs Usage Access)")
                MonoRow("cache size", app.cacheSizeBytes?.let { Fmt.bytes(it) }
                    ?: "Unavailable (needs Usage Access)")
                MonoRow("permissions", app.permissions.size.toString())

                if (app.permissions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Requested permissions",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    app.permissions.sorted().take(40).forEach {
                        Text(
                            "· ${it.substringAfterLast('.')}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.muted
                        )
                    }
                    if (app.permissions.size > 40) {
                        Text("… and ${app.permissions.size - 40} more",
                            style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row {
                    Button(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", app.packageName, null)
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) { Text("App Info") }
                    Spacer(Modifier.width(8.dp))
                    val launch = context.packageManager.getLaunchIntentForPackage(app.packageName)
                    if (launch != null) {
                        OutlinedButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) { Text("Launch") }
                    }
                }
            }
        }
    }
}
