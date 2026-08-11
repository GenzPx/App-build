package com.monitorcheck.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.monitorcheck.core.Fmt
import com.monitorcheck.core.Permissions
import com.monitorcheck.storage.AnalysisResult
import com.monitorcheck.storage.DuplicateGroup
import com.monitorcheck.storage.FileEntry
import com.monitorcheck.storage.StorageAnalyzer
import com.monitorcheck.storage.StorageRepository
import com.monitorcheck.ui.components.MonoRow
import com.monitorcheck.ui.components.NoticeCard
import com.monitorcheck.ui.components.SectionCard
import com.monitorcheck.ui.components.UsageBar
import com.monitorcheck.ui.components.loadColor
import com.monitorcheck.ui.theme.StatusColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

private enum class StorageTab(val label: String) {
    OVERVIEW("Overview"), ANALYZER("Analyzer"), BROWSER("Browser"), DUPLICATES("Duplicates")
}

@Composable
fun StorageScreen(contentPadding: PaddingValues) {
    var tab by remember { mutableStateOf(StorageTab.OVERVIEW) }

    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, top = contentPadding.calculateTopPadding(), end = 12.dp)
        ) {
            StorageTab.entries.forEach { t ->
                FilterChip(
                    selected = tab == t, onClick = { tab = t },
                    label = { Text(t.label) }, modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
        val inner = PaddingValues(top = 8.dp, bottom = contentPadding.calculateBottomPadding())
        when (tab) {
            StorageTab.OVERVIEW -> StorageOverviewTab(inner)
            StorageTab.ANALYZER -> StorageAnalyzerTab(inner)
            StorageTab.BROWSER -> StorageBrowserTab(inner)
            StorageTab.DUPLICATES -> DuplicatesTab(inner)
        }
    }
}

@Composable
private fun StorageOverviewTab(padding: PaddingValues) {
    val context = LocalContext.current
    val repo = remember { StorageRepository(context.applicationContext) }
    val volumes = remember { repo.volumes() }
    val sections = remember { repo.infoSections() }

    LazyColumn(contentPadding = padding) {
        volumes.value?.let { list ->
            items(list.size) { i ->
                val v = list[i]
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(v.label, style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        if (v.totalBytes > 0) {
                            UsageBar((v.usedPercent / 100.0).toFloat(),
                                color = loadColor(v.usedPercent), height = 10)
                            Spacer(Modifier.height(8.dp))
                            MonoRow("used", "${Fmt.bytes(v.usedBytes)} (${Fmt.percent(v.usedPercent)})")
                            MonoRow("free", Fmt.bytes(v.freeBytes))
                            MonoRow("total", Fmt.bytes(v.totalBytes))
                            v.filesystem?.let { MonoRow("filesystem", it) }
                            MonoRow("path", v.path)
                        } else {
                            Text("State: ${v.state}", style = MaterialTheme.typography.bodySmall,
                                color = StatusColors.muted)
                        }
                    }
                }
            }
        }
        items(sections.size) { i -> SectionCard(sections[i], showSources = true) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun StorageAnalyzerTab(padding: PaddingValues) {
    val context = LocalContext.current
    val analyzer = remember { StorageAnalyzer(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var roots by remember { mutableStateOf(analyzer.accessibleRoots()) }
    var selectedRoot by remember { mutableStateOf(roots.firstOrNull()?.absolutePath) }
    var result by remember { mutableStateOf<AnalysisResult?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }

    LazyColumn(contentPadding = padding) {
        if (!analyzer.canDeepScan()) {
            item {
                NoticeCard(
                    title = "Limited storage access",
                    body = "Without all-files access Monitored Check can only analyse the folders " +
                        "Android grants it by default. Granting access lets it measure the whole " +
                        "shared storage volume. Nothing is ever deleted without your explicit " +
                        "confirmation.",
                    tone = StatusColors.warn,
                    action = {
                        Button(onClick = {
                            runCatching {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    context.startActivity(
                                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:${context.packageName}"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                } else {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${context.packageName}"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                            roots = analyzer.accessibleRoots()
                        }) { Text("Grant storage access") }
                    }
                )
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Scan location", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    roots.forEach { root ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selectedRoot = root.absolutePath }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = selectedRoot == root.absolutePath,
                                onClick = { selectedRoot = root.absolutePath },
                                label = { Text(root.name.ifBlank { root.absolutePath }) }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(root.absolutePath,
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusColors.muted)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Button(
                            onClick = {
                                val target = selectedRoot ?: return@Button
                                scanning = true; result = null
                                job = scope.launch {
                                    val r = analyzer.analyze(target) { path, count ->
                                        progress = "$count files · $path"
                                    }
                                    result = r; scanning = false
                                }
                            },
                            enabled = !scanning && selectedRoot != null
                        ) { Text("Analyse") }
                        if (scanning) {
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { job?.cancel(); scanning = false }) {
                                Text("Cancel")
                            }
                        }
                    }
                    if (scanning) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.height(18.dp).width(18.dp),
                                strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(progress.take(60), style = MaterialTheme.typography.labelSmall,
                                color = StatusColors.muted)
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
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Summary", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        MonoRow("root", r.rootPath)
                        MonoRow("total size", Fmt.bytes(r.totalBytes))
                        MonoRow("files", r.fileCount.toString())
                        MonoRow("folders", r.dirCount.toString())
                        MonoRow("scan time", "${r.scanTimeMs} ms")
                        if (r.unreadableDirs > 0) {
                            MonoRow("unreadable dirs", r.unreadableDirs.toString())
                        }
                        if (r.truncated) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Scan hit the depth/entry safety limit, so totals cover only the " +
                                    "traversed portion. This cap keeps the app responsive on " +
                                    "low-end devices.",
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusColors.warn
                            )
                        }
                    }
                }
            }

            if (r.typeDistribution.isNotEmpty()) {
                item {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                .copy(alpha = 0.35f))
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("File type distribution",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(10.dp))
                            val max = r.typeDistribution.maxOf { it.totalBytes }.coerceAtLeast(1)
                            r.typeDistribution.forEach { b ->
                                Column(Modifier.padding(vertical = 4.dp)) {
                                    Row(Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${b.label} (${b.fileCount})",
                                            style = MaterialTheme.typography.bodySmall)
                                        Text(Fmt.bytes(b.totalBytes),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    UsageBar((b.totalBytes.toDouble() / max).toFloat(), height = 5)
                                }
                            }
                        }
                    }
                }
            }

            item { FileListCard("Largest files", r.largestFiles.take(20)) }
            item { FileListCard("Largest folders", r.largestFolders.take(20)) }
            if (r.cacheCandidates.isNotEmpty()) {
                item { FileListCard("Cache folders detected", r.cacheCandidates.take(15)) }
            }
            if (r.tempCandidates.isNotEmpty()) {
                item { FileListCard("Temporary files detected", r.tempCandidates.take(15)) }
            }
            if (r.extensionStats.isNotEmpty()) {
                item {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                .copy(alpha = 0.35f))
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Extension statistics",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(8.dp))
                            r.extensionStats.forEach { (ext, size) ->
                                MonoRow(".$ext", Fmt.bytes(size))
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun FileListCard(title: String, files: List<FileEntry>) {
    if (files.isEmpty()) return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            files.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1)
                        Text(f.path, style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.muted, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(Fmt.bytes(f.sizeBytes), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StorageBrowserTab(padding: PaddingValues) {
    val context = LocalContext.current
    val analyzer = remember { StorageAnalyzer(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val roots = remember { analyzer.accessibleRoots() }

    var current by remember { mutableStateOf(roots.firstOrNull()?.absolutePath ?: "/") }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<FileEntry?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(current, reload) { entries = analyzer.listDirectory(current) }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete permanently?") },
            text = {
                Text(
                    "This will permanently delete:\n\n${target.path}\n\n" +
                        (if (target.isDirectory) "This is a folder and all of its contents will be removed. "
                        else "Size: ${Fmt.bytes(target.sizeBytes)}. ") +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = target
                    pendingDelete = null
                    scope.launch { analyzer.deletePath(t.path); reload++ }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(contentPadding = padding) {
        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(current, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Row {
                        val parent = File(current).parent
                        if (parent != null && roots.any { current.startsWith(it.absolutePath) &&
                                current != it.absolutePath }) {
                            OutlinedButton(onClick = { current = parent }) { Text("Up") }
                            Spacer(Modifier.width(8.dp))
                        }
                        roots.forEach { r ->
                            OutlinedButton(
                                onClick = { current = r.absolutePath },
                                modifier = Modifier.padding(end = 6.dp)
                            ) { Text(r.name.ifBlank { "root" }) }
                        }
                    }
                }
            }
        }

        if (entries.isEmpty()) {
            item {
                NoticeCard("Empty or unreadable",
                    "This folder has no entries the app is permitted to list.")
            }
        }

        items(entries.size) { i ->
            val e = entries[i]
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
                    .clickable { if (e.isDirectory) current = e.path },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (e.isDirectory) "${e.name}/" else e.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (e.isDirectory) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            if (e.isDirectory) "${e.childCount} items · ${Fmt.date(e.lastModified)}"
                            else "${Fmt.bytes(e.sizeBytes)} · ${Fmt.date(e.lastModified)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.muted
                        )
                    }
                    TextButton(onClick = { pendingDelete = e }) { Text("Delete") }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DuplicatesTab(padding: PaddingValues) {
    val context = LocalContext.current
    val analyzer = remember { StorageAnalyzer(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val roots = remember { analyzer.accessibleRoots() }

    var groups by remember { mutableStateOf<List<DuplicateGroup>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(0) }
    var done by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    LazyColumn(contentPadding = padding) {
        item {
            NoticeCard(
                title = "How duplicates are detected",
                body = "Files are grouped by exact byte size, then a SHA-256 hash of the first " +
                    "and last 64 KB plus the size is compared. This is fast enough for phone " +
                    "storage and very reliable, but it is a strong heuristic rather than a full " +
                    "byte-for-byte comparison — verify before deleting anything. Only files " +
                    "512 KB and larger are considered. Nothing is deleted automatically."
            )
        }

        item {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Button(
                    onClick = {
                        val root = roots.firstOrNull() ?: return@Button
                        scanning = true; done = false; groups = emptyList()
                        job = scope.launch {
                            groups = analyzer.findDuplicates(root.absolutePath) { scanned = it }
                            scanning = false; done = true
                        }
                    },
                    enabled = !scanning && roots.isNotEmpty()
                ) { Text("Find duplicates") }
                if (scanning) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { job?.cancel(); scanning = false }) { Text("Cancel") }
                }
            }
        }

        if (scanning) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("$scanned files hashed…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (done && groups.isEmpty()) {
            item {
                NoticeCard("No duplicates found",
                    "No files matching the size + hash criteria were found in the scanned area.")
            }
        }

        if (groups.isNotEmpty()) {
            item {
                val wasted = groups.sumOf { it.wastedBytes }
                Text(
                    "${groups.size} duplicate groups · ${Fmt.bytes(wasted)} potentially reclaimable",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.warn,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            items(groups.size) { i ->
                val g = groups[i]
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "${g.files.size} copies · ${Fmt.bytes(g.sizeBytes)} each · " +
                                "${Fmt.bytes(g.wastedBytes)} extra",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(6.dp))
                        g.files.forEach {
                            Text("· $it", style = MaterialTheme.typography.labelSmall,
                                color = StatusColors.muted)
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
