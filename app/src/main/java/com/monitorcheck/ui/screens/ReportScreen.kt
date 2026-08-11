package com.monitorcheck.ui.screens

import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.monitorcheck.core.Fmt
import com.monitorcheck.reports.ReportExporter
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.theme.StatusColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ReportScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val exporter = remember { ReportExporter(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var generating by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<String?>(null) }
    var savedFile by remember { mutableStateOf<File?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    LazyColumn(contentPadding = contentPadding) {
        item {
            NoticeCard(
                title = "Full system report",
                body = "Generates a structured plain-text report covering device, Android build, " +
                    "kernel, SELinux, CPU, GPU, memory, storage, battery, thermal, sensors, " +
                    "network, drivers, Binder, applications, permissions and a live monitoring " +
                    "snapshot.\n\nEvery value comes from a real API or kernel interface. Anything " +
                    "the platform withholds is written with its true status — Unavailable, " +
                    "Unsupported, Permission Required or Restricted by Android — never as a " +
                    "plausible-looking number. The report is saved to this app's private storage " +
                    "and is never uploaded."
            )
        }

        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                generating = true; report = null; savedFile = null
                                job = scope.launch {
                                    val text = exporter.buildReport { progress = it }
                                    report = text
                                    savedFile = exporter.writeToFile(text)
                                    generating = false
                                }
                            },
                            enabled = !generating
                        ) { Text("Generate report") }

                        if (generating) {
                            Spacer(Modifier.width(12.dp))
                            CircularProgressIndicator(Modifier.height(18.dp).width(18.dp),
                                strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Collecting $progress…",
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusColors.muted)
                        }
                    }

                    savedFile?.let { file ->
                        Spacer(Modifier.height(12.dp))
                        Text("Saved to app storage", style = MaterialTheme.typography.bodyMedium,
                            color = StatusColors.ok)
                        Text(file.absolutePath, style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.muted)
                        Text("${Fmt.bytes(file.length())} · ${Fmt.timestamp(file.lastModified())}",
                            style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
                        Spacer(Modifier.height(10.dp))
                        Row {
                            Button(onClick = {
                                runCatching {
                                    val uri = exporter.shareUri(file)
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                putExtra(Intent.EXTRA_SUBJECT,
                                                    "Monitored Check system report")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }, "Share report"
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }) { Text("Share") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                runCatching {
                                    val uri = exporter.shareUri(file)
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, "text/plain")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                                Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                }
                            }) { Text("Open") }
                        }
                    }
                }
            }
        }

        report?.let { text ->
            item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Preview", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text("${text.length} characters · ${text.lines().size} lines",
                            style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text.take(20000),
                            style = MaterialTheme.typography.labelSmall
                                .copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (text.length > 20000) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Preview truncated. Open or share the saved file to read the " +
                                    "complete report.",
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusColors.warn
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
