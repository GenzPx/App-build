package com.monitorcheck.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.monitorcheck.security.DeviceDiagnosisEngine
import com.monitorcheck.security.DeviceDiagnosisResult
import com.monitorcheck.security.DiagnosisIssue
import com.monitorcheck.security.DiagnosisSeverity
import com.monitorcheck.ui.MonitorViewModel
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.theme.StatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DiagnosisScreen(vm: MonitorViewModel, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<DeviceDiagnosisResult?>(null) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    LazyColumn(contentPadding = contentPadding) {
        item { NoticeCard("Full device diagnosis", "Membaca hardware lalu scan seluruh aplikasi secara lokal.") }
        item {
            Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f))) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            if (!running) {
                                running = true; result = null
                                scope.launch(Dispatchers.IO) {
                                    val diagnosis = DeviceDiagnosisEngine(context.applicationContext).run { name, current, total -> progress = "Scanning $current/$total · $name" }
                                    withContext(Dispatchers.Main) { result = diagnosis; progress = "Complete in ${diagnosis.durationMs} ms"; running = false }
                                }
                            }
                        }, enabled = !running) { Text(if (running) "Scanning…" else "Scan device") }
                        if (running) { CircularProgressIndicator(Modifier.padding(start = 10.dp).height(18.dp), strokeWidth = 2.dp) }
                    }
                    if (progress.isNotBlank()) Text(progress, style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
                }
            }
        }
        result?.let { diagnosis ->
            item { NoticeCard("${diagnosis.scannedApps}/${diagnosis.totalApps} apps scanned", "Critical ${diagnosis.issues.count { it.severity == DiagnosisSeverity.CRITICAL }} · Warning ${diagnosis.issues.count { it.severity == DiagnosisSeverity.WARNING }}") }
            items(diagnosis.issues.size) { index -> IssueCard(diagnosis.issues[index]) }
            if (diagnosis.limitations.isNotEmpty()) item { NoticeCard("Platform limitations", diagnosis.limitations.joinToString("\n"), tone = StatusColors.warn) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable private fun IssueCard(issue: DiagnosisIssue) {
    val color = when (issue.severity) { DiagnosisSeverity.INFO -> StatusColors.ok; DiagnosisSeverity.WARNING -> StatusColors.warn; DiagnosisSeverity.CRITICAL -> StatusColors.critical }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = color.copy(alpha = .08f))) {
        Column(Modifier.padding(12.dp)) { Text("${issue.severity.label}: ${issue.title}", color = color); Text(issue.detail, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
private inline fun androidx.compose.foundation.lazy.LazyListScope.items(count: Int, crossinline itemContent: @Composable (Int) -> Unit) = items(count = count) { index -> itemContent(index) }
