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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.monitorcheck.core.Fmt
import com.monitorcheck.logs.CrashReport
import com.monitorcheck.logs.CrashReporter
import com.monitorcheck.logs.LogLine
import com.monitorcheck.logs.LogPriority
import com.monitorcheck.logs.LogcatRepository
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.components.StatusChip
import com.monitorcheck.ui.theme.StatusColors
import kotlinx.coroutines.launch

private enum class LogTab(val label: String) { LOGCAT("Logcat"), CRASHES("Crash reports") }

@Composable
fun LogScreen(contentPadding: PaddingValues) {
    var tab by remember { mutableStateOf(LogTab.LOGCAT) }

    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, top = contentPadding.calculateTopPadding(), end = 12.dp)
        ) {
            LogTab.entries.forEach { t ->
                FilterChip(
                    selected = tab == t, onClick = { tab = t },
                    label = { Text(t.label) }, modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
        val inner = PaddingValues(top = 8.dp, bottom = contentPadding.calculateBottomPadding())
        when (tab) {
            LogTab.LOGCAT -> LogcatTab(inner)
            LogTab.CRASHES -> CrashTab(inner)
        }
    }
}

@Composable
private fun LogcatTab(padding: PaddingValues) {
    val context = LocalContext.current
    val repo = remember { LogcatRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var lines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var loadState by remember { mutableStateOf<com.monitorcheck.core.Reading<List<LogLine>>?>(null) }
    var minPriority by remember { mutableStateOf(LogPriority.VERBOSE) }
    var query by remember { mutableStateOf("") }
    var exported by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    val access = remember { repo.accessLevel() }

    LaunchedEffect(reload) {
        val r = repo.dump(500)
        loadState = r
        lines = r.value.orEmpty()
    }

    val filtered = repo.filter(lines, minPriority, query, null)

    LazyColumn(contentPadding = padding) {
        item {
            NoticeCard(
                title = "Log access: ${access.status.label}",
                body = access.note ?: access.value ?: "",
                tone = StatusColors.warn
            )
        }

        item {
            Column(Modifier.padding(horizontal = 12.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Search log") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(
                        LogPriority.VERBOSE, LogPriority.DEBUG, LogPriority.INFO,
                        LogPriority.WARN, LogPriority.ERROR, LogPriority.FATAL
                    ).forEach { p ->
                        FilterChip(
                            selected = minPriority == p,
                            onClick = { minPriority = p },
                            label = { Text(p.label) },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(onClick = { reload++ }) { Text("Refresh") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val r = repo.export(filtered)
                                exported = r.value?.absolutePath
                                    ?: "Export failed: ${r.note ?: r.status.label}"
                            }
                        },
                        enabled = filtered.isNotEmpty()
                    ) { Text("Export") }
                }
                exported?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("Saved: $it", style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.ok)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${filtered.size} of ${lines.size} entries",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.muted
                )
            }
        }

        if (filtered.isEmpty() && loadState != null) {
            item {
                NoticeCard(
                    "No log entries",
                    loadState?.note ?: "The log buffer returned nothing visible to this app."
                )
            }
        }

        items(filtered.size) { i ->
            val l = filtered[i]
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
                Row {
                    Text(
                        l.priority.letter,
                        style = MaterialTheme.typography.labelSmall
                            .copy(fontFamily = FontFamily.Monospace),
                        color = priorityColor(l.priority)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        buildString {
                            l.timestamp?.let { append("$it  ") }
                            l.tag?.let { append("$it") }
                        },
                        style = MaterialTheme.typography.labelSmall
                            .copy(fontFamily = FontFamily.Monospace),
                        color = StatusColors.muted
                    )
                }
                Text(
                    l.message,
                    style = MaterialTheme.typography.labelSmall
                        .copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun priorityColor(p: LogPriority): Color = when (p) {
    LogPriority.ERROR, LogPriority.FATAL -> StatusColors.critical
    LogPriority.WARN -> StatusColors.warn
    LogPriority.INFO -> StatusColors.ok
    else -> StatusColors.muted
}

@Composable
private fun CrashTab(padding: PaddingValues) {
    val context = LocalContext.current
    val reporter = remember { CrashReporter(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var reports by remember { mutableStateOf<List<CrashReport>>(emptyList()) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) { reports = reporter.listReports() }

    LazyColumn(contentPadding = padding) {
        item {
            NoticeCard(
                title = "Local crash reports",
                body = "If Monitored Check ever crashes, the full stack trace and device context " +
                    "are written to this device's private storage. Nothing is uploaded anywhere — " +
                    "there is no network code in the crash reporter at all. You can share a report " +
                    "manually if you want to send it to a developer."
            )
        }

        item {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Button(onClick = { reload++ }) { Text("Refresh") }
                Spacer(Modifier.width(8.dp))
                if (reports.isNotEmpty()) {
                    OutlinedButton(onClick = {
                        scope.launch { reporter.deleteAll(); reload++ }
                    }) { Text("Delete all") }
                }
            }
        }

        if (reports.isEmpty()) {
            item {
                NoticeCard("No crashes recorded",
                    "Monitored Check has not crashed on this device since installation.",
                    tone = StatusColors.ok)
            }
        }

        items(reports.size) { i ->
            val r = reports[i]
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .clickable {
                        expanded = if (expanded == r.fileName) null else r.fileName
                    },
                colors = CardDefaults.cardColors(
                    containerColor = StatusColors.critical.copy(alpha = 0.08f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(r.exceptionType.substringAfterLast('.'),
                        style = MaterialTheme.typography.titleMedium,
                        color = StatusColors.critical)
                    Text(Fmt.timestamp(r.timestamp), style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.muted)
                    Spacer(Modifier.height(6.dp))
                    Text(r.message, style = MaterialTheme.typography.bodySmall)

                    if (expanded == r.fileName) {
                        Spacer(Modifier.height(10.dp))
                        Text("Thread: ${r.threadName}", style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.muted)
                        Text("App: ${r.appVersion}", style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.muted)
                        Text("Android: ${r.androidVersion}",
                            style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
                        Text("Device: ${r.device}", style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.muted)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            r.stackTrace.take(4000),
                            style = MaterialTheme.typography.labelSmall
                                .copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(10.dp))
                        Row {
                            Button(onClick = {
                                runCatching {
                                    val file = reporter.fileFor(r.fileName)
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context, "${context.packageName}.fileprovider", file)
                                    context.startActivity(Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }, "Share crash report"
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }) { Text("Share") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                scope.launch { reporter.delete(r.fileName); reload++ }
                            }) { Text("Delete") }
                        }
                    }
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
