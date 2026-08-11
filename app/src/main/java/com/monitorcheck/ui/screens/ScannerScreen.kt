package com.monitorcheck.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.monitorcheck.apps.AppEntry
import com.monitorcheck.apps.AppRepository
import com.monitorcheck.core.Fmt
import com.monitorcheck.security.PatternScanner
import com.monitorcheck.security.RiskLevel
import com.monitorcheck.security.ScanResult
import com.monitorcheck.ui.components.MonoRow
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.theme.StatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class ScanTab(val label: String) { APPS("Installed apps"), FILE("File / APK") }

@Composable
fun ScannerScreen(contentPadding: PaddingValues) {
    var tab by remember { mutableStateOf(ScanTab.APPS) }

    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, top = contentPadding.calculateTopPadding(), end = 12.dp)
        ) {
            ScanTab.entries.forEach { t ->
                FilterChip(
                    selected = tab == t, onClick = { tab = t },
                    label = { Text(t.label) }, modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
        val inner = PaddingValues(top = 8.dp, bottom = contentPadding.calculateBottomPadding())
        when (tab) {
            ScanTab.APPS -> AppScanTab(inner)
            ScanTab.FILE -> FileScanTab(inner)
        }
    }
}

@Composable
private fun DisclaimerCard(scanner: PatternScanner) {
    NoticeCard(
        title = "Pattern Scanner is not an antivirus",
        body = scanner.disclaimer,
        tone = StatusColors.warn
    )
}

@Composable
private fun AppScanTab(padding: PaddingValues) {
    val context = LocalContext.current
    val scanner = remember { PatternScanner(context.applicationContext) }
    val appRepo = remember { AppRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var results by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var userOnly by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) { apps = appRepo.loadApps() }

    LazyColumn(contentPadding = padding) {
        item { DisclaimerCard(scanner) }

        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Inspect installed applications",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        FilterChip(selected = userOnly, onClick = { userOnly = true },
                            label = { Text("User apps") }, modifier = Modifier.padding(end = 6.dp))
                        FilterChip(selected = !userOnly, onClick = { userOnly = false },
                            label = { Text("All apps") })
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                scanning = true; results = emptyList(); progress = 0
                                val targets = apps.filter { !userOnly || !it.isSystem }
                                job = scope.launch {
                                    val out = ArrayList<ScanResult>()
                                    withContext(Dispatchers.Default) {
                                        targets.forEach { a ->
                                            out.add(scanner.scanInstalledPackage(a.packageName))
                                            progress = out.size
                                        }
                                    }
                                    results = out.sortedByDescending { it.score }
                                    scanning = false
                                }
                            },
                            enabled = !scanning && apps.isNotEmpty()
                        ) { Text("Scan ${apps.count { !userOnly || !it.isSystem }} apps") }
                        if (scanning) {
                            Spacer(Modifier.width(10.dp))
                            CircularProgressIndicator(Modifier.height(18.dp).width(18.dp),
                                strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("$progress scanned", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        if (results.isNotEmpty()) {
            item {
                val counts = RiskLevel.entries.associateWith { lvl ->
                    results.count { it.riskLevel == lvl }
                }
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Results", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        counts.filter { it.value > 0 }.forEach { (lvl, n) ->
                            MonoRow(lvl.label, n.toString())
                        }
                    }
                }
            }

            items(results.size) { i ->
                val r = results[i]
                ScanResultCard(r, expanded == r.target) {
                    expanded = if (expanded == r.target) null else r.target
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun FileScanTab(padding: PaddingValues) {
    val context = LocalContext.current
    val scanner = remember { PatternScanner(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var result by remember { mutableStateOf<ScanResult?>(null) }
    var folderResults by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var manualPath by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }

    // SAF picker: the supported way to let the user choose any file to scan.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scanning = true; result = null; folderResults = emptyList()
        status = "Copying selected file for analysis…"
        scope.launch {
            val temp = withContext(Dispatchers.IO) {
                try {
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "selected_file"
                    val out = File(context.cacheDir, "scan_$name")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                    out
                } catch (_: Throwable) { null }
            }
            if (temp != null && temp.exists()) {
                status = "Analysing ${temp.name}…"
                result = scanner.scanFile(temp)
                // Clean up the working copy immediately after analysis.
                withContext(Dispatchers.IO) { runCatching { temp.delete() } }
            } else {
                status = "Could not read the selected file."
            }
            scanning = false
        }
    }

    LazyColumn(contentPadding = padding) {
        item { DisclaimerCard(scanner) }

        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Scan a file", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = !scanning
                    ) { Text("Choose file (APK, archive, binary)") }

                    Spacer(Modifier.height(14.dp))
                    Text("Or scan a folder path", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = manualPath,
                        onValueChange = { manualPath = it },
                        label = { Text("Absolute folder path") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val dir = File(manualPath)
                            if (!dir.isDirectory || !dir.canRead()) {
                                status = "Path is not a readable directory."
                                return@Button
                            }
                            scanning = true; result = null; folderResults = emptyList()
                            scope.launch {
                                folderResults = scanner.scanFolder(dir) { name, n ->
                                    status = "$n scanned · $name"
                                }
                                scanning = false
                                status = "Folder scan complete: ${folderResults.size} files analysed."
                            }
                        },
                        enabled = !scanning && manualPath.isNotBlank()
                    ) { Text("Scan folder") }

                    if (scanning || status.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (scanning) {
                                CircularProgressIndicator(Modifier.height(16.dp).width(16.dp),
                                    strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(status, style = MaterialTheme.typography.labelSmall,
                                color = StatusColors.muted)
                        }
                    }
                }
            }
        }

        result?.let { r ->
            item { ScanResultCard(r, true) { } }
        }

        if (folderResults.isNotEmpty()) {
            items(folderResults.size) { i ->
                val r = folderResults[i]
                ScanResultCard(r, expanded == r.target) {
                    expanded = if (expanded == r.target) null else r.target
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun riskColor(level: RiskLevel): Color = when (level) {
    RiskLevel.SAFE -> StatusColors.ok
    RiskLevel.LOW -> StatusColors.ok
    RiskLevel.SUSPICIOUS -> StatusColors.warn
    RiskLevel.HIGH -> StatusColors.critical
    RiskLevel.UNKNOWN -> StatusColors.muted
}

@Composable
private fun ScanResultCard(r: ScanResult, expanded: Boolean, onClick: () -> Unit) {
    val color = riskColor(r.riskLevel)
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.09f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(r.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(r.target, style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.muted, maxLines = 1)
                }
                Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.2f)) {
                    Text(
                        r.riskLevel.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Risk score ${r.score} · ${r.findings.count { it.weight > 0 }} indicators · " +
                    "${r.scanTimeMs} ms",
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted
            )

            if (expanded) {
                Spacer(Modifier.height(12.dp))
                Text("Why this rating", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                r.findings.forEach { f ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(f.title, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f))
                            if (f.weight > 0) {
                                Text("+${f.weight}", style = MaterialTheme.typography.labelSmall,
                                    color = color)
                            }
                        }
                        Text(f.detail, style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.muted)
                    }
                }

                if (r.metadata.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Metadata", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    r.metadata.forEach { (k, v) -> MonoRow(k, v) }
                }

                r.sha256?.let {
                    Spacer(Modifier.height(10.dp))
                    Text("SHA-256", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it, style = MaterialTheme.typography.labelSmall
                        .copy(fontFamily = FontFamily.Monospace), color = StatusColors.muted)
                }
                r.md5?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("MD5", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it, style = MaterialTheme.typography.labelSmall
                        .copy(fontFamily = FontFamily.Monospace), color = StatusColors.muted)
                }
            }
        }
    }
}

private inline fun androidx.compose.foundation.lazy.LazyListScope.items(
    count: Int,
    crossinline itemContent: @Composable (Int) -> Unit
) = items(count = count) { index -> itemContent(index) }
